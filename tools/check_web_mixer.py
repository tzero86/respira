#!/usr/bin/env python3
"""Measure the landing page's web mixer layers with the app's own metrics.

The demo on the landing page synthesises its soundscapes in the browser.
The app measures its layers with `tools/check_layers.py`, so the demo is
measured the same way: the numbers are directly comparable.

Usage:
    python tools/check_web_mixer.py
"""

from __future__ import annotations

import os
import subprocess
import sys

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
from check_layers import bright_cv  # noqa: E402

OUT = os.path.join(ROOT, "tools", "out", "webmixer")
LAYERS = ["rain", "thunder", "wind", "stream", "ocean", "fire"]

# What each layer must look like.  The numbers come from the app's own
# layers, which are recordings:
#
#   high     share of the energy above 2 kHz.  A layer above 55 % is a hiss.
#   crest    peak minus RMS.  Below 15 dB means there is nothing under the
#            events, which is what makes a layer sound like a click train.
#   low      share below 200 Hz.  The wind and thunder beds need some.
TARGETS = {
    "rain":    dict(high=(0.15, 0.55), crest=(16.0, 40.0), low=(0.03, 0.40)),
    "thunder": dict(high=(0.00, 0.20), crest=(15.0, 40.0), low=(0.25, 0.90)),
    "wind":    dict(high=(0.05, 0.45), crest=(15.0, 40.0), low=(0.05, 0.45)),
    # A stream is water moving over stones: it is a continuous layer with
    # bubbles in it, so its crest is naturally lower than a fire's.
    "stream":  dict(high=(0.03, 0.45), crest=(14.0, 40.0), low=(0.00, 0.25)),
    "ocean":   dict(high=(0.05, 0.40), crest=(17.0, 40.0), low=(0.08, 0.60)),
    "fire":    dict(high=(0.03, 0.35), crest=(18.0, 40.0), low=(0.15, 0.75)),
}


def decode(path: str) -> np.ndarray:
    raw = subprocess.run(
        ["ffmpeg", "-nostdin", "-loglevel", "error", "-i", path,
         "-f", "f32le", "-ac", "1", "-ar", "22050", "-"],
        check=True, capture_output=True,
    ).stdout
    return np.frombuffer(raw, dtype="<f4").astype(np.float64)


def measure(x: np.ndarray) -> dict:
    sr = 22050
    spectrum = np.abs(np.fft.rfft(x))
    freqs = np.fft.rfftfreq(len(x), 1.0 / sr)
    power = spectrum ** 2 + 1e-20
    centroid = float((freqs * spectrum).sum() / (spectrum.sum() + 1e-20))
    total = power.sum()
    high = float(power[freqs >= 2000].sum() / total)
    low = float(power[(freqs >= 1) & (freqs < 200)].sum() / total)
    rms = float(np.sqrt((x ** 2).mean()))
    crest = 20 * np.log10(float(np.abs(x).max()) / (rms + 1e-12))
    psd = power
    flat = float(np.exp(np.mean(np.log(psd))) / (np.mean(psd) + 1e-20))
    # the crest of the layer tells us whether it has a body under its events
    return {
        "centroid": centroid,
        "high": high,
        "low": low,
        "crest": crest,
        "flat": flat,
        "bcv": bright_cv(x),
    }


def main() -> int:
    print("The landing page's web mixer, measured with the app's metrics")
    print()
    print(f"{'layer':9s} {'centroid Hz':>12s} {'>2k':>6s} {'<200Hz':>7s} "
          f"{'crest dB':>9s} {'flat':>8s} {'bcv':>6s}  verdict")
    print("-" * 74)
    failures = 0
    for layer in LAYERS:
        path = os.path.join(OUT, f"{layer}.wav")
        if not os.path.exists(path):
            print(f"{layer:9s} (not rendered -- run tools/render_web_mixer.js)")
            continue
        m = measure(decode(path))
        want = TARGETS[layer]
        problems = []
        for key, (lo, hi) in want.items():
            value = m[key]
            if value < lo or value > hi:
                problems.append(f"{key}={value:.3f} not in [{lo}, {hi}]")
        ok = not problems
        failures += 0 if ok else 1
        print(f"{layer:9s} {m['centroid']:12.0f} {m['high'] * 100:5.0f}% "
              f"{m['low'] * 100:6.0f}% {m['crest']:9.1f} {m['flat']:8.4f} "
              f"{m['bcv']:6.3f}  {'OK' if ok else 'FAIL: ' + '; '.join(problems)}")
    print()
    print("For comparison, the app's own layers are recordings:")
    print("  rain  2867 Hz   51% >2k   25.4 dB crest   0.083 bcv")
    print("  fire   607 Hz    7% >2k   24.4 dB crest   0.143 bcv")
    print()
    print("A crest under 15 dB means the layer has nothing under its events,")
    print("which is what makes a layer sound like a click train.")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
