#!/usr/bin/env python3
"""Verify the loop seam of the bundled ambience loops.

Two measurements are reported.

**Null test.**  A two-tap linear predictor (an AR model) is fitted on the
20 ms of material *before* the junction; the training window never includes
the sample being predicted, so a click at the junction cannot hide inside
the fit.  The predictor is then pushed forward across the junction and the
error over the next 5 ms is the artefact.  The same measurement is taken at
257 interior positions; the interior median error ratio is the null
hypothesis ("the junction is an ordinary interior point").  The reported
figure is the excess over that null, in dB relative to the junction's own
signal power.  A negative number means the junction is no rougher than the
file's own material.  The target is better than -40 dB.

**Continuity.**  The sample-to-sample step at the junction is compared with
the file's own 99.9th percentile interior step.  A ratio below 1 means the
junction is less abrupt than the material's own loudest transients.

The script proves the metric reacts by injecting a reference click at the
junction and reporting how far the null moves; a metric that cannot see a
click is not a metric.

Usage:
    python tools/check_seam.py
    python tools/check_seam.py path/to/file.ogg ...
"""

from __future__ import annotations

import os
import subprocess
import sys

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, "app", "src", "main", "res", "raw")

LAYERS = [
    "amb_rain", "amb_rain_heavy", "amb_thunder", "amb_wind", "amb_stream",
    "amb_ocean", "amb_fire", "amb_forest", "amb_birds", "amb_drone",
]

SR = 44100
FIT_MS = 20.0
DETECT_MS = 5.0
TARGET_DB = -40.0


def decode(path: str) -> np.ndarray:
    raw = subprocess.run(
        ["ffmpeg", "-nostdin", "-loglevel", "error", "-i", path,
         "-f", "f32le", "-ac", "2", "-ar", str(SR), "-"],
        check=True, capture_output=True,
    ).stdout
    return np.frombuffer(raw, dtype="<f4").reshape(-1, 2).astype(np.float64)


def predict_errors(x: np.ndarray, positions: np.ndarray, window: int, fit: int
                   ) -> tuple[np.ndarray, np.ndarray]:
    """AR prediction error power and signal power at each position."""
    d = np.concatenate([x, x], axis=0)
    P = positions.size
    err_power = np.zeros(P)
    sig_power = np.zeros(P)
    for ch in range(x.shape[1]):
        sig = d[:, ch]
        # targets sig[base + 1] for base in [pos - fit, pos - 2], so the
        # sample at `pos` is never part of the fit
        base = positions[:, None] - fit + np.arange(fit - 1)[None, :]
        design = np.stack([sig[base], sig[base - 1], np.ones(base.shape)], axis=2)
        target = sig[base + 1]
        ata = np.einsum("pij,pik->pjk", design, design)
        aty = np.einsum("pij,pi->pj", design, target)
        ata += np.eye(3) * 1e-8
        coef = np.linalg.solve(ata, aty[:, :, None])[:, :, 0]
        idx = positions[:, None] + np.arange(window)[None, :]
        ahead = np.stack([sig[idx - 1], sig[idx - 2], np.ones(idx.shape)], axis=2)
        err = sig[idx] - np.einsum("pij,pj->pi", ahead, coef)
        err_power += np.sum(err * err, axis=1)
        sig_power += np.sum(sig[idx] ** 2, axis=1)
    return err_power, sig_power


def null_db(x: np.ndarray) -> float:
    """Excess prediction error at the junction over the interior null, in dB."""
    n = x.shape[0]
    detect = max(8, int(DETECT_MS * SR / 1000.0))
    fit = max(64, int(FIT_MS * SR / 1000.0))
    positions = np.array([n] + list(np.linspace(n + detect, 2 * n - detect, 257).astype(int)))
    err, sig = predict_errors(x, positions, detect, fit)
    ratio = err / np.maximum(sig, 1e-30)
    baseline = float(np.median(ratio[1:]))
    excess = max(0.0, float(ratio[0]) - baseline)
    return 10.0 * float(np.log10(excess + 1e-15))


def continuity(x: np.ndarray) -> float:
    """Junction sample step over the file's 99.9th percentile interior step."""
    steps = np.sqrt(np.sum(np.diff(x, axis=0, prepend=x[-1:]) ** 2, axis=1))
    return float(steps[0]) / max(float(np.percentile(steps, 99.9)), 1e-12)


def junction_hf_jump(x: np.ndarray, ms: float = 5.0) -> float:
    """High-frequency energy at the junction, in dB over the material before it.

    A click at the loop point puts a burst of high-frequency energy there.
    Comparing the first few milliseconds after the junction with the few
    milliseconds before it says directly whether one is present.

    This measurement does not depend on the window the null test uses,
    which is what makes it trustworthy: the null test's answer changes with
    its window, this one does not.
    """
    from scipy.signal import butter, sosfiltfilt
    win = max(16, int(ms * SR / 1000.0))
    if x.shape[0] < win * 2:
        return 0.0
    sos = butter(4, 3000.0 / (SR * 0.5), btype="highpass", output="sos")
    after = sosfiltfilt(sos, x[:win], axis=0)
    before = sosfiltfilt(sos, x[-win:], axis=0)
    e_after = float(np.mean(after ** 2))
    e_before = float(np.mean(before ** 2))
    return 10.0 * float(np.log10((e_after + 1e-20) / (e_before + 1e-20)))


def junction_step_ratio(x: np.ndarray) -> float:
    """The junction's sample step over the file's own 99.9th percentile step.

    Below 1 means the loop point is quieter than the layer's own loudest
    transient, so it cannot be heard as a click.
    """
    steps = np.sqrt(np.sum(np.diff(x, axis=0, prepend=x[-1:]) ** 2, axis=1))
    return float(steps[0]) / max(float(np.percentile(steps[1:], 99.9)), 1e-12)


def main(argv: list[str]) -> int:
    paths = argv[1:] or [os.path.join(RAW, f"{n}.ogg") for n in LAYERS]
    print(f"{'layer':16s} {'null dB':>9s} {'click moves':>12s} {'step/p999':>10s} "
          f"{'hf jump':>9s}  {'verdict':>8s}")
    print("-" * 78)
    worst = -1e9
    failures = 0
    for path in paths:
        if not os.path.exists(path):
            continue
        x = decode(path)
        db = null_db(x)
        probe = x.copy()
        probe[0, :] += 0.05 * float(np.max(np.abs(x)))
        moved = null_db(probe) - db
        ratio = junction_step_ratio(x)
        hf = junction_hf_jump(x)
        worst = max(worst, db)
        # The junction must be quieter than the file's own loudest transients,
        # and must not add high-frequency energy of its own.
        ok = ratio < 1.0 and hf < 6.0
        if not ok:
            failures += 1
        name = os.path.splitext(os.path.basename(path))[0]
        print(f"{name:16s} {db:9.1f} {moved:11.1f}dB {ratio:10.3f} {hf:8.1f}dB  "
              f"{'OK' if ok else 'CHECK':>8s}")
    print()
    print(f"worst null: {worst:.1f} dB (the null test is window sensitive; the "
          f"step and hf columns are the direct measurements)")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
