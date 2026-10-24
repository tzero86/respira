#!/usr/bin/env python3
"""Measure the bundled ambience loops.

The numbers this prints are the ones quoted in
`docs/superpowers/specs/2026-10-24-ambience-audio-results.md`, so the
before/after table can be regenerated at any time.

Method (matches the assessment in
`docs/superpowers/specs/2026-10-24-ambience-audio-options.md`):

* the file is decoded to a 22.05 kHz mono signal (Nyquist 11 kHz);
* 2048-sample Hann frames with 50 % overlap give a spectrum per frame;
* the centroid is energy weighted, and the band shares are the fraction of
  the 0-11 kHz energy that falls in each band;
* the crest factor is peak dBFS minus RMS dBFS;
* L/R correlation is the Pearson correlation of the two decoded channels.

Usage:
    python tools/measure_ambience.py                     # the shipped ogg files
    python tools/measure_ambience.py path/to/file.ogg ... # specific files
"""

from __future__ import annotations

import os
import subprocess
import sys

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, "app", "src", "main", "res", "raw")

ANALYSIS_SR = 22050
FRAME = 2048
HOP = FRAME // 2

LAYERS = [
    "amb_rain", "amb_rain_heavy", "amb_thunder", "amb_wind", "amb_stream",
    "amb_ocean", "amb_fire", "amb_forest", "amb_birds", "amb_drone",
]


def decode(path: str) -> tuple[np.ndarray, np.ndarray, int]:
    """Decode an audio file to (stereo, mono, sample rate)."""
    raw = subprocess.run(
        ["ffmpeg", "-nostdin", "-loglevel", "error", "-i", path,
         "-f", "f32le", "-ac", "2", "-ar", str(ANALYSIS_SR), "-"],
        check=True, capture_output=True,
    ).stdout
    stereo = np.frombuffer(raw, dtype="<f4").reshape(-1, 2).astype(np.float64)
    mono = stereo.mean(axis=1)
    return stereo, mono, ANALYSIS_SR


def band_shares(mono: np.ndarray, sr: int) -> dict[str, float]:
    """Energy share per band, plus the spectral centroid."""
    if mono.size < FRAME:
        return {"lo": 0.0, "mid": 0.0, "hi": 0.0, "centroid": 0.0, "flatness": 0.0}
    window = np.hanning(FRAME)
    frames = 1 + (mono.size - FRAME) // HOP
    freqs = np.fft.rfftfreq(FRAME, 1.0 / sr)
    lo_mask = freqs < 200.0
    mid_mask = (freqs >= 200.0) & (freqs < 2000.0)
    hi_mask = freqs >= 2000.0

    totals = np.zeros(3)
    centroid_num = 0.0
    centroid_den = 0.0
    flat_sum = 0.0
    for i in range(frames):
        seg = mono[i * HOP:i * HOP + FRAME] * window
        mag = np.abs(np.fft.rfft(seg)) ** 2
        e_lo = float(mag[lo_mask].sum())
        e_mid = float(mag[mid_mask].sum())
        e_hi = float(mag[hi_mask].sum())
        totals += (e_lo, e_mid, e_hi)
        total = e_lo + e_mid + e_hi
        centroid_num += float((mag * freqs).sum())
        centroid_den += total
        p = mag[1:] / max(mag[1:].sum(), 1e-30)
        p = p[p > 0]
        if p.size > 1:
            flat_sum += float(np.exp(np.mean(np.log(p))) / np.mean(p))
    total = float(totals.sum()) or 1.0
    return {
        "lo": totals[0] / total,
        "mid": totals[1] / total,
        "hi": totals[2] / total,
        "centroid": centroid_num / max(centroid_den, 1e-30),
        "flatness": flat_sum / max(frames, 1),
    }


def measure(path: str) -> dict:
    stereo, mono, sr = decode(path)
    rms = float(np.sqrt(np.mean(mono ** 2)))
    # the crest factor uses the mono peak, which is what the assessment used
    peak = float(np.max(np.abs(mono)))
    bands = band_shares(mono, sr)
    left = stereo[:, 0]
    right = stereo[:, 1]
    if left.std() > 0 and right.std() > 0:
        corr = float(np.corrcoef(left, right)[0, 1])
    else:
        corr = 0.0
    return {
        "file": os.path.basename(path),
        "rms": 20.0 * np.log10(max(rms, 1e-12)),
        "peak": 20.0 * np.log10(max(peak, 1e-12)),
        "crest": 20.0 * np.log10(max(peak, 1e-12)) - 20.0 * np.log10(max(rms, 1e-12)),
        "centroid": bands["centroid"],
        "flatness": bands["flatness"],
        "lo": bands["lo"],
        "mid": bands["mid"],
        "hi": bands["hi"],
        "corr": corr,
    }


def measure_lufs(path: str) -> float:
    """Integrated loudness straight from ffmpeg's EBU R128 implementation."""
    out = subprocess.run(
        ["ffmpeg", "-nostdin", "-loglevel", "info", "-i", path,
         "-af", "ebur128=peak=true", "-f", "null", "-"],
        check=True, capture_output=True, text=True,
    ).stderr
    loud = -70.0
    peak = -70.0
    for line in out.splitlines():
        if "I:" in line and "LUFS" in line:
            try:
                loud = float(line.split("I:")[1].split("LUFS")[0].strip())
            except (IndexError, ValueError):
                pass
        if "Peak:" in line:
            try:
                peak = float(line.split("Peak:")[1].split("dBFS")[0].strip())
            except (IndexError, ValueError):
                pass
    return loud, peak


def format_table(rows: list[dict]) -> str:
    header = (f"{'layer':16s} {'RMS':>7s} {'crest':>6s} {'centroid':>9s} "
              f"{'flat':>6s} {'<200':>6s} {'200-2k':>7s} {'>2k':>6s} {'L/R':>6s}")
    lines = [header, "-" * len(header)]
    for r in rows:
        lines.append(
            f"{r['name']:16s} {r['rms']:7.1f} {r['crest']:6.1f} {r['centroid']:9.0f} "
            f"{r['flatness']:6.3f} {r['lo'] * 100:5.0f}% {r['mid'] * 100:6.0f}% "
            f"{r['hi'] * 100:5.0f}% {r['corr']:6.2f}"
        )
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    if len(argv) > 1:
        paths = argv[1:]
    else:
        paths = [os.path.join(RAW, f"{name}.ogg") for name in LAYERS]
    rows = []
    lufs_rows = []
    for path in paths:
        if not os.path.exists(path):
            print(f"missing: {path}")
            continue
        m = measure(path)
        loud, peak = measure_lufs(path)
        m["name"] = os.path.splitext(os.path.basename(path))[0].replace("amb_", "")
        m["lufs"] = loud
        m["ebur_peak"] = peak
        m["size"] = os.path.getsize(path)
        rows.append(m)
        lufs_rows.append(m)
    print(format_table(rows))
    print()
    loud_values = [r["lufs"] for r in lufs_rows]
    if loud_values:
        print(f"LUFS spread : {max(loud_values):.1f} .. {min(loud_values):.1f} "
              f"= {max(loud_values) - min(loud_values):.1f} LU")
    print(f"peak max    : {max(r['ebur_peak'] for r in lufs_rows):.2f} dBFS")
    print(f"total bytes : {sum(r['size'] for r in lufs_rows) / 1e6:.2f} MB")
    print()
    print(f"{'layer':16s} {'LUFS':>8s} {'peak dBFS':>10s} {'MB':>7s}")
    for r in lufs_rows:
        print(f"{r['name']:16s} {r['lufs']:8.1f} {r['ebur_peak']:10.2f} "
              f"{r['size'] / 1e6:7.2f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
