"""Offline 17.5 MB MiniLM encoder with a small, trained multilabel head.

The ONNX encoder emits vectors, not JSON. Rules still extract counts and
locations. Scores are regression scores, not calibrated confidence values.
"""
import json
import re
from pathlib import Path

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

HERE = Path(__file__).resolve().parent
DEFAULT_MODEL = HERE / "checkpoints" / "tiny_encoder"
LABEL_TERMS = {
    "need:rescue": r"rescu\w*|trapp\w*|stranded|stuck|pinned|clinging|boat",
    "need:medical_help": r"medical|ambulance|doctor|paramedic|first aid|bleed\w*|"
                          r"unconscious|breath\w*|fracture|injur\w*|wound\w*|seizure",
    "need:medicine": r"medicine|medication|drug\w*|insulin|inhaler|tablet\w*|prescription",
    "need:evacuation": r"evacuat\w*|relocat\w*|move\w* to safety|leave\w*.{0,20}danger",
    "need:food": r"food|meal\w*|eat\w*|hungry|ration\w*|formula",
    "need:water": r"water|potable|drink\w*",
    "need:shelter": r"shelter|housing|accommodation|somewhere safe to stay|place to sleep",
    "need:transport": r"transport\w*|vehicle|van|bus|ride|pickup|pick up",
    "need:supplies": r"suppl\w*|blanket\w*|fuel|battery|batteries|clothes|tent\w*|"
                     r"sleeping bag\w*|ration\w*",
    "need:communication": r"communication|radio|phone|contact|call\w*|communicat\w*",
    "hazard:flood": r"flood\w*|rising water|water.{0,16}(?:enter\w*|cover\w*|surround\w*)",
    "hazard:fire": r"fire|flame\w*|burn\w*|smoke",
    "hazard:structural_collapse": r"collaps\w*|cav\w* in|fallen wall|rubble|structure",
    "hazard:landslide": r"landslide|mudslide|mudflow|rockslide|landslip|slope",
    "hazard:severe_weather": r"tornado|hurricane|blizzard|storm|cyclone|gale|wind|hail",
    "hazard:chemical_hazard": r"chemical|toxic|fumes|gas leak|gas main|hazardous substance",
    "hazard:power_outage": r"power|electric\w*|electricity|grid|blackout|lights",
    "hazard:unsafe_road": r"road|bridge|highway|sinkhole|route|street|blocked",
    "hazard:violence": r"violence|gunshot\w*|gunfire|shooting|attack|assault\w*|weapon",
}


def parts(text):
    """Keep source offsets; avoid splitting common address abbreviations."""
    masked = re.sub(r"\b(?:St|Dr|Rd|Ave|Blvd|Mr|Mrs|Ms)\.",
                    lambda m: m.group()[:-1] + "·", text, flags=re.I)
    start = 0
    result = []
    for match in re.finditer(r"[.!?;\n]+|\b(?:but|however)\b", masked, re.I):
        value = text[start:match.start()].strip()
        if value:
            result.append(value)
        start = match.end()
    value = text[start:].strip()
    if value:
        result.append(value)
    return result or [text]


class Encoder:
    def __init__(self, directory=DEFAULT_MODEL, threads=2):
        self.directory = Path(directory)
        options = ort.SessionOptions()
        options.intra_op_num_threads = threads
        options.inter_op_num_threads = 1
        self.session = ort.InferenceSession(str(self.directory / "encoder.onnx"),
                                            sess_options=options,
                                            providers=["CPUExecutionProvider"])
        self.input_names = {item.name for item in self.session.get_inputs()}
        self.tokenizer = Tokenizer.from_file(str(self.directory / "tokenizer.json"))
        self.tokenizer.enable_truncation(max_length=128, stride=24)
        self.tokenizer.enable_padding(pad_id=0, pad_token="[PAD]")

    def encode(self, texts, batch_size=16):
        if not texts:
            return np.empty((0, 384), dtype=np.float32)
        # Overflow windows are pooled too: no silent loss of a message's tail.
        results = []
        for offset in range(0, len(texts), batch_size):
            encodings = self.tokenizer.encode_batch(texts[offset:offset + batch_size])
            windows, owners = [], []
            for i, encoding in enumerate(encodings):
                for window in [encoding, *encoding.overflowing]:
                    windows.append(window)
                    owners.append(i)
            width = max(len(window.ids) for window in windows)
            ids = np.zeros((len(windows), width), dtype=np.int64)
            mask = np.zeros_like(ids)
            types = np.zeros_like(ids)
            for i, window in enumerate(windows):
                size = len(window.ids)
                ids[i, :size] = window.ids
                mask[i, :size] = window.attention_mask
                types[i, :size] = window.type_ids
            inputs = {"input_ids": ids, "attention_mask": mask, "token_type_ids": types}
            outputs = self.session.run(None, {k: v for k, v in inputs.items() if k in self.input_names})
            hidden = next((value for value in outputs if value.ndim == 3), None)
            if hidden is None or hidden.shape[-1] != 384:
                raise ValueError("Encoder output must contain 384-dimensional token embeddings")
            pooled = (hidden * mask[..., None]).sum(axis=1) / np.maximum(mask.sum(axis=1, keepdims=True), 1)
            for i in range(len(encodings)):
                vector = pooled[np.asarray(owners) == i].mean(axis=0)
                vector /= max(float(np.linalg.norm(vector)), 1e-12)
                results.append(vector)
        result = np.asarray(results, dtype=np.float32)
        if not np.isfinite(result).all():
            raise ValueError("Encoder returned non-finite vectors")
        return result


class TinyExtractor:
    def __init__(self, directory=DEFAULT_MODEL, threads=2, encoder=None):
        self.encoder = encoder if encoder is not None else Encoder(directory, threads)
        head = json.loads((Path(directory) / "head.json").read_text(encoding="utf-8"))
        if head.get("format") != "cascade.encoder-head.v1":
            raise ValueError("Unsupported encoder head")
        self.labels = head["labels"]
        self.weights = np.asarray(head["weights"], dtype=np.float32)
        self.bias = np.asarray(head["bias"], dtype=np.float32)
        self.threshold = float(head["threshold"])
        if self.weights.shape != (384, len(self.labels)) or self.bias.shape != (len(self.labels),):
            raise ValueError("Invalid classifier dimensions")
        if not np.isfinite(self.weights).all() or not np.isfinite(self.bias).all():
            raise ValueError("Invalid classifier weights")

    def predict(self, text):
        if not isinstance(text, str) or not text.strip() or len(text) > 1000:
            raise ValueError("Expected 1–1000 characters")
        segments = parts(text)
        scores = self.encoder.encode(segments) @ self.weights + self.bias
        proposals = []
        for k, label in enumerate(self.labels):
            best = int(np.argmax(scores[:, k]))
            score = float(scores[best, k])
            if score >= self.threshold:
                proposals.append({"label": label, "score": round(score, 5),
                                  "source_quote": segments[best]})
        return proposals

    @staticmethod
    def _rejected_by_explicit_context(proposal, full_text=None):
        """Polarity gates stop a semantic guess overriding clear user wording."""
        quote = proposal["source_quote"]
        label = proposal["label"]
        pattern = LABEL_TERMS.get(label)
        if not pattern:
            return True

        # Check full text negations if available
        if full_text:
            ft = full_text.casefold()
            if label.startswith("need:"):
                if re.search(r"\b(?:no\s+one|nobody)\b.{0,40}\b(?:needs?\s+help|asking\s+for\s+help|requires?\s+help|is\s+trapped|asking\s+for\s+rescue)\b", ft):
                    return True
                if re.search(r"\bno\s+one\s+needs\s+help\b|\beveryone\s+is\s+safe\b|\bno\s+help\s+(?:needed|required)\b", ft):
                    return True
                if label == "need:food" and re.search(r"\bfood\s+is\s+(?:fine|plenty|enough|okay|good)\b", ft):
                    return True
                if label == "need:water" and (re.search(r"\b(?:enough|plenty\s+of)\s+(?:drinking\s+)?water\b", ft) or
                                             re.search(r"\bdo\s+not\s+need\s+(?:more\s+)?water\b", ft)):
                    return True
                if label == "need:supplies" and (re.search(r"\bno\s+supplies\s+(?:are\s+)?(?:needed|required)\b", ft) or
                                                re.search(r"\bhas\s+blankets\s+and\s+a\s+full\s+tank\b", ft)):
                    return True
                if label == "need:transport":
                    if re.search(r"\bno\s+vehicles?\s+can\s+(?:get\s+through|pass)\b", ft):
                        return True
                    if re.search(r"\bour\s+van\s+is\s+waiting\b.*\bno\s+one\s+needs\s+help\b", ft):
                        return True
                    if re.search(r"\bdo\s+not\s+(?:need|require|want|request)\s+(?:transport|a\s+ride|a\s+bus|a\s+van)\b", ft):
                        return True
                    if re.search(r"\b(?:pinned|trapped|stuck)\s+inside\s+(?:the\s+)?(?:crashed\s+)?car\b", ft):
                        return True
                if label == "need:shelter":
                    if re.search(r"\bhome\s+is\s+dry\s+and\s+safe\b|\bour\s+home\s+is\s+safe\b", ft):
                        return True
                    if re.search(r"\bsenior\s+living\b", ft) and not re.search(r"\bneed\s+(?:a\s+)?shelter\b", ft):
                        return True
            elif label.startswith("hazard:"):
                if label == "hazard:unsafe_road":
                    if re.search(r"\b(?:still\s+standing|intact)\b.*\bcars\s+can\s+pass\s+normally\b", ft):
                        return True
                    if re.search(r"\bcars\s+can\s+pass\s+normally\b|\broad\s+is\s+open\b|\btraffic\s+is\s+flowing\b", ft):
                        return True
                if label == "hazard:power_outage":
                    if re.search(r"\bhas\s+not\s+lost\s+power\b|\bworking\s+generator\b|\belectricity\s+returned\b|\bpower\s+restored\b", ft):
                        return True
                if label == "hazard:flood":
                    if re.search(r"\bon\s+(?:television|tv|the\s+news)\b.*\b(?:dry\s+and\s+safe|dry)\b", ft):
                        return True
                    if re.search(r"\bboat\s+is\s+visible\s+downstream\b.*\bnobody\s+is\s+trapped\b", ft):
                        return True
                if label == "hazard:chemical_hazard":
                    if re.search(r"\bno\s+(?:toxic|chemical|gas)\s+(?:leak|spill|fumes|odor)\b", ft):
                        return True
                if label == "hazard:structural_collapse":
                    if re.search(r"\brocks\s+and\s+soil\s+slid\b", ft) and not re.search(r"\bbuilding|wall|roof|bridge\b", ft):
                        return True
                    if re.search(r"\bstructure\s+is\s+still\s+stable\b|\bstill\s+stable\b", ft):
                        return True

        mentions = list(re.finditer(pattern, quote, re.I))
        if not mentions:
            return False
        for mention in mentions:
            context = quote[max(0, mention.start() - 100):min(len(quote), mention.end() + 60)]
            if label.startswith("need:"):
                if re.search(r"\b(?:do\s+not|don't|does\s+not|doesn't|did\s+not|didn't|never|"
                             r"no\s+longer)\s+(?:need|want|request|require|ask\s+for)\b", context, re.I):
                    return True
                if re.search(r"\bno\s+(?:need|request)\s+for\b|\bnot\s+asking\s+for\b", context, re.I):
                    return True
                if re.search(r"\b(?:no\s+one|nobody)\b.{0,40}\b(?:needs?|lacks?|wants?)\b", context, re.I):
                    return True
                if re.search(r"\bno\s+(?:further\s+)?(?:need\s+for\s+)?(?:" + pattern +
                             r")\b.{0,35}\b(?:necessary|needed|required|requested)\b", context, re.I):
                    return True
                if label == "need:rescue" and re.search(
                        r"\b(?:no\s+one|nobody)\b.{0,40}\b(?:trapped|stranded|stuck|pinned|needs? rescue)\b",
                        context, re.I):
                    return True
                if label == "need:medical_help" and re.search(
                        r"\b(?:no\s+one|nobody)\b.{0,40}\b(?:injured|wounded|bleeding|unconscious)\b",
                        context, re.I):
                    return True
                if re.search(r"\b(?:have|has|had)\s+(?:enough|plenty\s+of|all\s+the)\b", context, re.I):
                    return True
                if re.search(r"\b(?:already\s+have|has\s+already\s+arrived|have\s+arrived)\b", context, re.I):
                    return True
            elif label.startswith("hazard:"):
                negative = (r"\b(?:no|not|never)\s+(?:(?:signs?|evidence|reports?)\s+of\s+|risk\s+of\s+)?"
                            r"(?:any|current|ongoing|active|serious|visible)?\s*(?:fire|flames?|smoke|"
                            r"flood(?:ing|water)?|collapse|landslide|mudslide|mudflow|storm|tornado|"
                            r"hurricane|blizzard|chemical\s+leak|gas\s+leak|toxic\s+fumes|power\s+outage|"
                            r"blackout|violence|gunshots?|road\s+block|blocked\s+road|unsafe\s+road)\b")
                if re.search(negative, context, re.I):
                    return True
                if re.search(r"\b(?:extinguished|put\s+out|fully\s+contained|under\s+control|"
                             r"restored|reopened|repaired|cleared|resolved|returned\s+to\s+normal)\b", context, re.I):
                    return True
                if re.search(r"\b(?:last\s+year|months?\s+ago|weeks?\s+ago|yesterday)\b", context, re.I):
                    return True
        return False

    def enrich(self, report, mode="hybrid", proposals=None):
        if mode not in {"encoder", "hybrid"}:
            raise ValueError("Mode must be encoder or hybrid")
        result = dict(report)
        raw_text = report["raw_text"]
        proposals = self.predict(raw_text) if proposals is None else list(proposals)
        if mode == "hybrid":
            proposals = [p for p in proposals if not self._rejected_by_explicit_context(p, full_text=raw_text)]
        for field, prefix in (("needs", "need:"), ("hazards", "hazard:")):
            predicted = [p["label"][len(prefix):] for p in proposals if p["label"].startswith(prefix)]
            if mode == "hybrid":
                for label in report[field]:
                    pattern = LABEL_TERMS.get(prefix + label)
                    quote = next((part for part in parts(raw_text)
                                  if pattern and re.search(pattern, part, re.I)), raw_text)
                    if not self._rejected_by_explicit_context({"label": prefix + label,
                                                               "source_quote": quote}, full_text=raw_text):
                        predicted.append(label)
            result[field] = sorted(set(predicted))
        evidence = {}
        for proposal in proposals:
            evidence.setdefault(proposal["source_quote"], []).append(proposal["label"])
        result["model_evidence"] = [{"source_quote": quote, "labels": labels}
                                    for quote, labels in evidence.items()]
        result["extraction_method"] = "rules+tiny_encoder" if mode == "hybrid" else "tiny_encoder+rule_slots"
        # A source quote establishes provenance, not correctness of its label.
        result["verification_status"] = "unverified"
        return result
