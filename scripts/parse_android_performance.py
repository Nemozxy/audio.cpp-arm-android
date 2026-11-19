#!/usr/bin/env python3
"""Convert audio.cpp Android TXT performance reports into a repeatable CSV table."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
from pathlib import Path

TIMING = re.compile(r"^\[TIMING[^\]]*\]\s+(\S+)\s+(.+?)\s*$")
ASR = re.compile(
    r"ASR timing:.*?inference=([0-9.]+) ms total=([0-9.]+) ms threads=(\d+)"
)
TTS = re.compile(
    r"TTS timing:.*?inference=([0-9.]+) ms total=([0-9.]+) ms "
    r"output=([0-9.]+) s rtf=([0-9.]+) threads=(\d+)"
)
RUN_STARTED = re.compile(r"Run started: task=(\S+) threads=(\d+) profiling=([01])")


def number(value: str) -> float | int | str:
    try:
        return int(value)
    except ValueError:
        try:
            return float(value)
        except ValueError:
            return value


def parse(path: Path) -> dict[str, object]:
    text = path.read_text(encoding="utf-8")
    result: dict[str, object] = {"report": str(path), "variant": path.parent.name}
    timings: dict[str, list[float | int | str]] = {}
    kernels: dict[str, int] = {}
    op_profiles = []
    graphs = []
    cpus = []
    frequencies = []
    for line in text.splitlines():
        if "=" in line and not line.startswith("[") and " " not in line.split("=", 1)[0]:
            key, value = line.split("=", 1)
            if key.startswith(("device.", "model.", "run.", "native.")):
                result[key] = number(value)
        # The native start line describes the actual run. The UI header can
        # reflect a checkbox changed after inference but before log export.
        match = RUN_STARTED.search(line)
        if match:
            result["run.task"] = match.group(1)
            result["run.threads"] = int(match.group(2))
            result["run.profiling"] = "true" if match.group(3) == "1" else "false"
        match = TIMING.match(line)
        if match:
            timings.setdefault(match.group(1), []).append(number(match.group(2)))
        match = ASR.search(line)
        if match:
            result["inference_ms"] = float(match.group(1))
            result["total_ms"] = float(match.group(2))
        match = TTS.search(line)
        if match:
            result["inference_ms"] = float(match.group(1))
            result["total_ms"] = float(match.group(2))
            result["output_s"] = float(match.group(3))
            result["rtf"] = float(match.group(4))
        fields = {key: number(value) for item in line.split() if "=" in item
                  for key, value in [item.split("=", 1)]}
        if line.startswith("ggml.cpu.build "):
            result["cpu_build"] = fields
        elif line.startswith("ggml.cpu.hwcap "):
            result["cpu_hwcap"] = fields
        elif line.startswith("ggml.cpu.extra_buffers="):
            result["cpu_extra_buffers"] = fields.get("ggml.cpu.extra_buffers")
        elif line.startswith("ggml.kernel.hit "):
            key = f"{fields.get('buffer')}:{fields.get('op')}:{fields.get('src0')}:{fields.get('src1')}"
            kernels[key] = kernels.get(key, 0) + int(fields.get("calls", 0))
        elif line.startswith("ggml.profile.op "):
            op_profiles.append(fields)
        elif line.startswith("ggml.profile.graph "):
            graphs.append(fields)
        elif line.startswith("ggml.profile.cpu"):
            cpus.append(fields)
        elif line.startswith("cpu.freq.sample "):
            frequencies.append({"elapsed_ms": fields.get("elapsed_ms"),
                                "unit": fields.get("unit"),
                                "values": line.split(" unit=kHz ", 1)[-1]})

    transcript = re.search(r"(?ms)^\[transcript\]\n(.*?)\n\n\[native timing and diagnostics\]", text)
    if transcript:
        result["transcript_sha256"] = hashlib.sha256(
            transcript.group(1).encode("utf-8")).hexdigest()
    encoder = timings.pop("moss_transcribe_diarize.encoder.graph.compute_ms", [])
    for index, value in enumerate(encoder, 1):
        result[f"encoder_graph_{index}_ms"] = value
    selected = {
        "encoder_ms": "moss_transcribe_diarize.whisper_encoder_ms",
        "prefill_ms": "moss_transcribe_diarize.prefill_ms",
        "decode_ms": "moss_transcribe_diarize.decode_ms",
        "decode_tokens": "moss_transcribe_diarize.decode_step_count",
        "decode_mean_ms": "moss_transcribe_diarize.decode_step_mean_ms",
        "decode_p50_ms": "moss_transcribe_diarize.decode_step_p50_ms",
        "decode_p95_ms": "moss_transcribe_diarize.decode_step_p95_ms",
        "tts_generate_ms": "moss_tts_nano.session.generate_ms",
        "codec_decode_ms": "moss.audio_tokenizer.decode.graph_compute_ms",
    }
    for output, key in selected.items():
        values = timings.get(key)
        if values:
            result[output] = values[-1]
    for output, key in {
        "backend_name": "backend.name",
        "backend_registry": "backend.registry",
        "backend_device": "backend.device",
        "backend_device_type": "backend.device_type",
    }.items():
        values = timings.get(key)
        if values:
            result[output] = values[-1]
    if kernels:
        result["kernel_hits"] = kernels
    if op_profiles:
        result["op_profiles"] = op_profiles
    if graphs:
        result["graph_profiles"] = graphs
    if cpus:
        result["cpu_profiles"] = cpus
    if frequencies:
        result["cpu_frequency_samples"] = frequencies
    result["all_timings"] = timings
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("reports", nargs="+", type=Path)
    parser.add_argument("--csv", type=Path, help="Write the stage comparison CSV here")
    parser.add_argument("--json", type=Path, help="Write complete parsed diagnostics here")
    args = parser.parse_args()
    parsed = [parse(path) for path in args.reports]
    for row in parsed:
        build = row.get("cpu_build", {})
        hwcap = row.get("cpu_hwcap", {})
        if isinstance(build, dict):
            for key in ("arm_arch", "dotprod", "fp16_va", "i8mm", "repack", "kleidiai"):
                row[f"cpu_{key}"] = build.get(key)
        if isinstance(hwcap, dict):
            for key in ("AT_HWCAP", "AT_HWCAP2", "fphp", "asimdhp", "asimddp", "i8mm"):
                row[f"hwcap_{key}"] = hwcap.get(key)
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(parsed, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.csv:
        columns = [
            "variant", "report", "run.task", "device.model", "device.soc_model", "run.threads",
            "run.backend", "run.profiling", "backend_name", "backend_registry", "backend_device",
            "backend_device_type", "cpu_arm_arch", "cpu_dotprod", "cpu_fp16_va", "cpu_i8mm",
            "cpu_repack", "cpu_kleidiai", "hwcap_AT_HWCAP", "hwcap_AT_HWCAP2", "hwcap_fphp",
            "hwcap_asimdhp", "hwcap_asimddp", "hwcap_i8mm", "total_ms", "inference_ms",
            "encoder_ms", "prefill_ms", "decode_ms", "decode_tokens", "decode_mean_ms",
            "decode_p50_ms", "decode_p95_ms", "tts_generate_ms", "codec_decode_ms", "output_s",
            "rtf", "transcript_sha256",
        ]
        columns += sorted({key for row in parsed for key in row if key.startswith("encoder_graph_")})
        args.csv.parent.mkdir(parents=True, exist_ok=True)
        with args.csv.open("w", encoding="utf-8-sig", newline="") as output:
            writer = csv.DictWriter(output, fieldnames=columns, extrasaction="ignore")
            writer.writeheader()
            writer.writerows(parsed)
    if not args.csv and not args.json:
        print(json.dumps(parsed, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
