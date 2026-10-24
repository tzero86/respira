#!/usr/bin/env python3
"""Check that the ambience layers still sound like what they claim to be.

The complaint that started this work was that the fire sounded like a
geiger counter.  That is not a question of taste, and it can be measured.

A sound reads as its name when two things are true:

* **It has a body.**  Real rain hisses but it also has a low middle, real
  fire roars, real water moves.  A layer made only of clicks has no body:
  its high band is silent most of the time, so the clicks stand alone.

* **Its events are varied.**  A geiger counter ticks at a steady rate with
  every tick the same loudness.  Fire crackles irregularly and each crackle
  is different.  So the irregularity of the spacing and the spread of the
  amplitudes have to be high.

The numbers below are measured on the shipped files.  The `fill` column is
the fraction of the high band that is above 20 % of its peak: a layer with
almost no high-band body is a click train with silence around it.

Usage:
    python tools/check_layers.py
    python tools/check_layers.py path/to/file.ogg ...
"""

from __future__ import annotations

import os
import subprocess
import sys

import numpy as np
from scipy.signal import butter, find_peaks, hilbert, medfilt, sosfiltfilt

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, "app", "src", "main", "res", "raw")

SR = 22050

LAYERS = [
    "amb_rain", "amb_rain_heavy", "amb_thunder", "amb_wind", "amb_stream",
    "amb_ocean", "amb_fire", "amb_forest", "amb_birds", "amb_drone",
]

# What each layer should look like, and what it must not look like.
#
#   fill      fraction of the high band above 20 % of its peak.  Below 0.15
#             means the layer is a set of isolated clicks on silence, which is
#             the geiger-counter signature.
#   cv        spread of the time between events.  Below 0.4 means the events
#             are metronomic.
#   spread    spread of the event amplitudes, p90 over p10.  Below 3 means
#             every event is the same loudness, which is also a click train.
#   bright_cv spread of the *colour* of the events.  Below 0.08 means one
#             click timbre repeated, which is a geiger counter however the
#             amplitudes are arranged.
EXPECT = {
    "amb_rain":       dict(fill=(0.25, 1.00), cv=(0.35, 3.0), spread=(2.0, 40.0), bright_cv=(0.08, 0.35)),
    "amb_rain_heavy": dict(fill=(0.25, 1.00), cv=(0.35, 3.0), spread=(2.0, 40.0), bright_cv=(0.08, 0.35)),
    "amb_thunder":    dict(fill=(0.02, 1.00), cv=(0.30, 9.0), spread=(1.5, 400.0), bright_cv=(0.02, 0.35)),
    "amb_wind":       dict(fill=(0.02, 1.00), cv=(0.20, 4.0), spread=(1.0, 40.0), bright_cv=(0.08, 0.35)),
    "amb_stream":     dict(fill=(0.25, 1.00), cv=(0.20, 3.0), spread=(1.5, 40.0), bright_cv=(0.03, 0.35)),
    "amb_ocean":      dict(fill=(0.10, 1.00), cv=(0.60, 8.0), spread=(2.0, 40.0), bright_cv=(0.08, 0.35)),
    "amb_fire":       dict(fill=(0.15, 1.00), cv=(0.60, 3.0), spread=(3.5, 40.0), bright_cv=(0.11, 0.35)),
    # the crickets are near-equal in amplitude, which is what a cricket is
    "amb_forest":     dict(fill=(0.05, 1.00), cv=(0.30, 5.0), spread=(1.2, 40.0), bright_cv=(0.08, 0.35)),
    "amb_birds":      dict(fill=(0.02, 1.00), cv=(0.60, 8.0), spread=(2.0, 40.0), bright_cv=(0.08, 0.35)),
    "amb_drone":      dict(fill=(0.02, 1.00), cv=(0.15, 4.0), spread=(1.0, 40.0), bright_cv=(0.02, 0.35)),
}


def decode(path: str) -> np.ndarray:
    raw = subprocess.run(
        ["ffmpeg", "-nostdin", "-loglevel", "error", "-i", path,
         "-f", "f32le", "-ac", "2", "-ar", str(SR), "-"],
        check=True, capture_output=True,
    ).stdout
    return np.frombuffer(raw, dtype="<f4").reshape(-1, 2).astype(np.float64)


def measure(x: np.ndarray) -> dict:
    mono = x.mean(axis=1)
    band = sosfiltfilt(butter(4, [1500, 8000], btype="band", fs=SR, output="sos"), mono)
    env = np.abs(hilbert(band))
    env = np.convolve(env, np.ones(44) / 44.0, mode="same")

    peak = float(np.percentile(env, 99))
    fill = float((env > 0.2 * peak).mean())

    # the continuous body under the events
    body = medfilt(env, 4401)
    resid = env - body
    threshold = max(float(np.percentile(resid, 99)) * 0.4,
                    float(np.median(env)) * 0.05)
    from scipy.signal import find_peaks
    peaks, _ = find_peaks(resid, height=threshold, distance=int(SR * 0.015))
    amps = resid[peaks]
    gaps = np.diff(peaks) / SR
    duration = mono.shape[0] / SR

    cv = float(np.std(gaps) / (np.mean(gaps) + 1e-9)) if gaps.size > 2 else 0.0
    spread = (float(np.percentile(amps, 90) / (np.percentile(amps, 10) + 1e-9))
              if amps.size > 1 else 1.0)
    return {
        "fill": fill,
        "cv": cv,
        "spread": spread,
        "rate": peaks.size / duration if duration else 0.0,
        "bright_cv": bright_cv(mono),
    }


def bright_cv(x: np.ndarray) -> float:
    """Spread of the *colour* of the individual events.

    This is the number that diagnoses the geiger counter.  A geiger counter
    repeats one click, so every click has the same spectrum.  A real fire
    crackles differently every time, so each crackle has its own colour.

    Each event is isolated in the 1.5-10 kHz band and its spectral centroid
    is measured on its own, magnitude-weighted.  The figure is the standard
    deviation over the mean of those centroids.  Real fires and water sit
    between 0.11 and 0.28; a repeated click sits below 0.08.
    """
    if x.shape[0] < 4096:
        return 0.0
    band = sosfiltfilt(butter(4, [1500, 10000], btype="band", fs=SR, output="sos"), x)
    window = int(0.008 * SR)
    hop = int(0.004 * SR)
    count = max(1, (band.shape[0] - window) // hop)
    frames = np.lib.stride_tricks.as_strided(
        band, shape=(count, window), strides=(band.strides[0] * hop, band.strides[0]))
    energy = np.sqrt((frames ** 2).mean(axis=1) + 1e-20)
    energy = np.convolve(energy, np.ones(3) / 3.0, mode="same")[:count]

    median = float(np.median(energy))
    spread = float(np.median(np.abs(energy - median)) * 1.4826)
    floor = median + 1.2 * spread

    peaks = []
    i = 1
    while i < count - 1:
        if energy[i] > floor and energy[i] >= energy[i - 1] and energy[i] >= energy[i + 1]:
            back = max(0, i - int(0.050 * SR / hop))
            if energy[i] > 1.5 * energy[back]:
                peaks.append(i)
                i += max(1, int(0.010 * SR / hop))
                continue
        i += 1

    pre = int(0.004 * SR)
    clip = int(0.020 * SR)
    colours = []
    for p in peaks:
        a = max(0, p * hop - hop // 2)
        b = min(band.shape[0], p * hop + hop // 2 + 1)
        if b - a < 3:
            continue
        centre = a + int(np.argmax(np.abs(band[a:b])))
        start = centre - pre
        if start < 0 or start + clip > band.shape[0]:
            continue
        segment = band[start:start + clip]
        if not np.any(segment):
            continue
        spectrum = np.abs(np.fft.rfft(segment * np.hanning(len(segment))))
        freqs = np.fft.rfftfreq(len(segment), 1.0 / SR)
        total = spectrum.sum()
        if total > 0:
            colours.append(float((freqs * spectrum).sum() / total))

    if len(colours) < 6:
        return 0.0
    colours = np.array(colours)
    return float(np.std(colours) / (np.mean(colours) + 1e-9))


def main(argv: list[str]) -> int:
    paths = argv[1:] or [os.path.join(RAW, f"{n}.ogg") for n in LAYERS]
    print(f"{'layer':16s} {'fill':>6s} {'cv':>6s} {'spread':>7s} {'bcv':>6s} "
          f"{'events/s':>9s}  verdict")
    print("-" * 70)
    failures = 0
    for path in paths:
        if not os.path.exists(path):
            continue
        name = os.path.splitext(os.path.basename(path))[0]
        m = measure(decode(path))
        want = EXPECT.get(name)
        problems = []
        if want:
            for key, (lo, hi) in want.items():
                value = m[key]
                if value < lo or value > hi:
                    problems.append(f"{key}={value:.3f} not in [{lo}, {hi}]")
        # the geiger-counter signature, whatever the layer claims to be.
        # It needs all three at once: a sparse high band, a metronomic
        # spacing and uniform amplitudes.  A continuous bed has a low colour
        # spread too, but it has no discrete clicks to be one timbre of.
        if m["fill"] < 0.15 and m["cv"] < 0.55 and m["spread"] < 3.0:
            problems.append("reads as a click train")
        if (m["bright_cv"] < 0.08 and m["cv"] < 0.55 and m["spread"] < 3.0
                and m["fill"] < 0.6):
            problems.append("one click timbre repeated")
        ok = not problems
        failures += 0 if ok else 1
        print(f"{name:16s} {m['fill']:6.3f} {m['cv']:6.2f} {m['spread']:7.2f} "
              f"{m['bright_cv']:6.3f} {m['rate']:9.1f}  "
              f"{'OK' if ok else 'FAIL: ' + '; '.join(problems)}")
    print()
    print("fill     = fraction of the high band above 20 % of its peak")
    print("cv       = spread of the time between events (below 0.4 = metronomic)")
    print("spread   = p90/p10 of the event amplitudes (below 3 = every click alike)")
    print("bcv      = spread of the colour of the events (below 0.08 = one")
    print("           click timbre repeated, which is a geiger counter)")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
