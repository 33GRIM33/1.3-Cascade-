# Local SOS to JSON to peer transfer

The caller starts this flow by pressing SOS. The app must save the raw message
before running any model. Model output is an optional suggestion, never the
only copy of the report and never authorization to dispatch or close a report.

## Working reference

From WSL in this directory:

```bash
python -m unittest -v test_local_sos.py
python local_sos.py \
  "Water is rising at my house. Two people are trapped and need a boat." \
  --origin-id demo-device-1 \
  --outbox checkpoints/sos_outbox.sqlite3
python evaluate_local_sos.py --show-errors 5
```

The command prints one compact JSON envelope and stores the same bytes in a
SQLite outbox. The parser should produce `needs: ["rescue"]`,
`hazards: ["flood"]`, `requested_items: ["boat"]`,
`affected_people_count: 2`, and `location_text: "my house"`. It retains the
original text and marks the report `unverified`. Missing details stay unknown;
an SOS still enters the outbox.

The Python module exposes `build_envelope()`, `wire_bytes()`, `enqueue()`,
`pending_payloads()`, and `mark_acked()`. The outbox is an integration point for
an Android transport adapter. `mark_acked()` should run only after an
application-level acknowledgement from a peer, not merely after a send call.

The evaluator uses only the 20 emergency reports in `gold_test.jsonl`. These
records were inspected while improving the parser, so the score is a
development benchmark, not an independent estimate of field accuracy. It
reports exact fields, need/hazard recall, unsupported labels, literal quote
grounding, and whether every raw message remains in a valid envelope. The
gold annotations sometimes infer a count or need from context, whereas this
parser deliberately leaves unsupported facts unknown; review mismatches
before treating an exact-match difference as a safety failure.

## Android integration path

1. The SOS screen collects raw text and optional device location obtained with
   the user's permission. It calls the local parser and saves the envelope in
   an outbox transaction immediately.
2. A background worker may run a small on-device model with one short
   extraction task: return source spans for location, people, requested help,
   and hazards. Validate each proposed span against the raw text. A literal
   quote proves where a suggestion came from, but does not prove that its
   category is correct. Keep model category suggestions unverified until a
   user or responder confirms them. Never let the model replace the raw
   report, decide that no SOS exists, guess coordinates, or mark it verified.
3. The transport worker takes the UTF-8 bytes from the outbox and sends them as
   a Nearby Connections `BYTES` payload to an authenticated peer. On receipt,
   validate the envelope, deduplicate by `message_id`, persist it, and return
   an application-level acknowledgement. Retry pending records while they are
   unexpired; relay only under the app's hop and trust policy.
4. Any later enrichment is a **new update event** linked to the original
   `message_id`, so peers that already received the SOS do not lose the source
   report or silently receive a changed record under the same ID.

This repository has no Android app or Nearby transport implementation yet.
`local_sos.py` is a Python reference implementation of parsing, JSON framing,
and persistent queuing. The Android app must implement the same envelope and
validation rules in its own runtime, plus authenticated peer connections,
encryption, receipt acknowledgements, retries, and relay limits.

## Why the existing Needle fine-tune is excluded

The current `cascade.cact` achieved 6/16 correct tool selections and 0/14
exact argument matches on the smoke set. Both it and the base model struggled
with the large emergency tool schema. A local model can still help with narrow
span extraction after the SOS is queued, but the app must operate correctly
when model inference fails, stalls, or returns an ungrounded value.

## Generalization gate

Freeze `gold_test.jsonl` as a development set. Its 20 emergency cases have
already influenced the rules; improving its score by adding case-specific
phrases is not evidence that new messages will work. Build a separate,
human-annotated test set from real user wording. Include paraphrases, spelling
errors, local place names, mixed languages, negation, missing details, and
messages with several people or incidents. Keep entire incident families out
of training and prompt examples, not merely individual sentences.

Compare the rule baseline, a local model alone, and the combined flow on that
untouched set. Report recall and false labels for every need and hazard,
location and count accuracy, unsupported facts, SOS delivery success, and
inference time on the target phone. For emergency use, review every false
negative, especially missed rescue or medical requests. A model must improve
held-out results and fit the phone's memory and latency budget before it can
become an automatic extractor. Until then, transmit the raw message and show
uncertain fields as unverified suggestions.

## Compact encoder experiment

The optional `tiny_encoder.py` path tests a hybrid classifier while keeping
Needle-sized local deployment in view. It uses the Apache-2.0
`paraphrase-MiniLM-L3-v2` 17.5 MB INT8 ONNX encoder, followed by a trained
384-input linear label head. The encoder is frozen; training fits only that
small head on `datasets/tiny/train.jsonl`. `validation.jsonl` selects its
regularization and cutoff. The gold and challenge files are excluded from
training. The encoder returns category suggestions; existing rules continue
to extract slots. Hybrid mode applies explicit negation and resolution gates.
Every prediction remains unverified, and the SOS envelope retains its original
message.

From WSL, the single setup command creates an isolated CPU environment, saves
each stage to `checkpoints/tiny_encoder_eval/setup.log`, downloads and checks
the pinned 17.5 MB model, saves the head before running evaluation, and writes
predictions and a summary under `checkpoints/tiny_encoder_eval/`:

```bash
python3 setup_tiny_encoder.py
```

The model setup needs network access once. After the files are cached, inference
and evaluation run offline. A failed download or fit leaves the log, verified
downloads, and any saved head in place. The Python reference is a CPU
measurement; its timings do not predict phone performance. The repository has
no Android runtime yet, so porting the same encoder and head to ONNX Runtime
Mobile remains a separate integration task.

The standalone linear model attempted before this experiment achieved only
22/66 label true positives on its synthetic validation set and is not used by
the SOS pipeline. Its result and the development split check are in
`checkpoints/tiny_linear_experiment.json`. The original 2,400-example Needle
training split also contains only ten distinct emergency sentence templates;
all ten recur in validation with changed locations and numbers. Treat its low
loss as template fitting, not evidence of generalization.
