#!/usr/bin/env python3
"""One command: isolated CPU environment, model setup, tests, and evaluation."""
import os
from pathlib import Path
import subprocess
import sys
import venv

HERE = Path(__file__).resolve().parent


def main():
    environment = HERE / ".venv-tiny-encoder"
    interpreter = environment / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    logs = HERE / "checkpoints" / "tiny_encoder_eval"
    logs.mkdir(parents=True, exist_ok=True)
    if not interpreter.exists():
        print("Creating isolated CPU environment:", environment, flush=True)
        venv.EnvBuilder(with_pip=True).create(environment)
    commands = [
        [str(interpreter), "-m", "pip", "install", "-r", str(HERE / "requirements-tiny-encoder.txt")],
        [str(interpreter), "-m", "unittest", "-v", "test_local_sos.py", "test_tiny_encoder.py", "test_cascade_pipeline.py"],
        [str(interpreter), "-u", str(HERE / "prepare_tiny_encoder.py")],
        [str(interpreter), "-u", str(HERE / "train_pipeline.py")],
        [str(interpreter), "-u", str(HERE / "evaluate_pipeline.py"), "--show-errors", "5"],
    ]
    with (logs / "setup.log").open("a", encoding="utf-8") as log:
        for i, command in enumerate(commands, 1):
            title = f"\nStage {i}/{len(commands)}: {' '.join(command)}\n"
            print(title, flush=True)
            log.write(title)
            log.flush()
            process = subprocess.Popen(command, cwd=HERE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       text=True, encoding="utf-8", errors="replace")
            for line in process.stdout:
                print(line, end="", flush=True)
                log.write(line)
                log.flush()
            code = process.wait()
            if code:
                raise SystemExit(f"Stage {i} failed (exit {code}). Log: {logs / 'setup.log'}. "
                                 "Previously saved downloads and model files are retained.")
    print(f"\nDone. Results: {logs / 'summary.json'}", flush=True)
    print(f'Next: "{interpreter}" run_tiny_encoder.py --query "Two people are trapped and need a boat."', flush=True)


if __name__ == "__main__":
    main()
