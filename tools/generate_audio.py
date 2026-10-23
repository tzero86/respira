#!/usr/bin/env python3
"""Generate and encode every bundled sound for Respira.

Ambience layers are 40 s seamless loops (spectral synthesis is periodic by
construction; transient events are wrapped modulo the loop). Meditation tracks
are long-form pad pieces rendered at 32 kHz. Everything is deterministic for a
given seed, so reruns produce identical assets.

Usage:
    python tools/generate_audio.py            # everything
    python tools/generate_audio.py ambience
    python tools/generate_audio.py meditation
"""

from __future__ import annotations

import os
import subprocess
import sys

import numpy as np
from scipy import signal
from scipy.io import wavfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, "app", "src", "main", "res", "raw")
WORK = os.path.join(ROOT, "tools", "out")
SR_A = 44100
SR_M = 32000
LOOP = 40.0
TAU = 2.0 * np.pi

rng = np.random.default_rng(20261023)


# --------------------------------------------------------------------------- #
# primitives
# --------------------------------------------------------------------------- #

def gain_band(lo: float, hi: float, tilt: float = 0.0):
    def g(freqs):
        f = np.maximum(freqs, 1e-3)
        low = 1.0 / (1.0 + (lo / f) ** 4)
        high = 1.0 / (1.0 + (f / hi) ** 4)
        return low * high * (f / 1000.0) ** tilt

    return g


def shaped_noise(n: int, sr: int, gain, rng, dtype=np.float32):
    """Periodic noise: shaping happens in the frequency domain."""
    freqs = np.fft.rfftfreq(n, 1.0 / sr)
    mag = gain(freqs)
    phase = rng.uniform(0.0, TAU, freqs.shape)
    spec = mag * np.exp(1j * phase)
    spec[0] = 0.0
    out = np.fft.irfft(spec, n)
    out /= np.std(out) + 1e-9
    return out.astype(dtype)


def normalize(x: np.ndarray, peak: float = 0.85):
    m = float(np.max(np.abs(x))) or 1.0
    return x * (peak / m)


def add_circular(buf: np.ndarray, start: int, sig: np.ndarray, pan: float, amp: float = 1.0):
    n = buf.shape[0]
    idx = (np.arange(sig.shape[0]) + int(start)) % n
    buf[idx, 0] += sig * amp * np.sqrt(1.0 - pan)
    buf[idx, 1] += sig * amp * np.sqrt(pan)


def add_linear(buf: np.ndarray, start: int, sig: np.ndarray, pan: float, amp: float = 1.0):
    n = buf.shape[0]
    start = max(0, int(start))
    if start >= n:
        return
    end = min(n, start + sig.shape[0])
    chunk = sig[: end - start]
    buf[start:end, 0] += chunk * amp * np.sqrt(1.0 - pan)
    buf[start:end, 1] += chunk * amp * np.sqrt(pan)


def raised_cos(count: int) -> np.ndarray:
    return (0.5 - 0.5 * np.cos(np.linspace(0.0, np.pi, max(2, count)))).astype(np.float32)


def fade_edges(x: np.ndarray, sr: int, fade_in: float, fade_out: float):
    n = x.shape[0]
    fi = min(n // 2, max(2, int(fade_in * sr)))
    fo = min(n // 2, max(2, int(fade_out * sr)))
    if x.ndim == 1:
        x[:fi] *= raised_cos(fi)
        x[-fo:] *= raised_cos(fo)[::-1]
    else:
        x[:fi] *= raised_cos(fi)[:, None]
        x[-fo:] *= raised_cos(fo)[::-1, None]
    return x


def write_wav(path: str, x: np.ndarray, sr: int):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    data = np.clip(x, -1.0, 1.0)
    wavfile.write(path, sr, (data * 32767.0).astype(np.int16))


def encode(wav: str, ogg: str, quality: int):
    subprocess.run(
        [
            "ffmpeg", "-nostdin", "-loglevel", "error", "-y",
            "-i", wav, "-c:a", "libvorbis", "-q:a", str(quality), ogg,
        ],
        check=True,
    )


def publish(name: str, x: np.ndarray, sr: int, quality: int):
    wav = os.path.join(WORK, f"{name}.wav")
    ogg = os.path.join(RAW, f"{name}.ogg")
    write_wav(wav, x, sr)
    encode(wav, ogg, quality)
    print(f"  {name:18s} {x.shape[0] / sr:7.1f}s  {os.path.getsize(ogg) / 1e6:5.2f} MB")


# --------------------------------------------------------------------------- #
# ambience layers (40 s seamless loops)
# --------------------------------------------------------------------------- #

def amb_rain(n: int, sr: int, heavy: bool) -> np.ndarray:
    lo = 120.0 if heavy else 350.0
    hi = 9000.0 if heavy else 14000.0
    out = np.stack(
        [shaped_noise(n, sr, gain_band(lo, hi, -0.25), rng) for _ in range(2)], axis=1
    ).astype(np.float32)
    out *= 0.55 if heavy else 0.46

    drops = 380 if heavy else 150
    for _ in range(drops):
        length = int(rng.uniform(0.008, 0.05) * sr)
        env = np.exp(-np.linspace(0.0, 7.0, length))
        f = rng.uniform(900, 4200) if heavy else rng.uniform(1800, 7200)
        tone = np.sin(TAU * f * np.arange(length) / sr + rng.uniform(0, TAU)) * env
        add_circular(out, rng.integers(0, n), tone.astype(np.float32),
                     rng.uniform(0.1, 0.9), rng.uniform(0.02, 0.10))
    return normalize(out, 0.8)


def amb_wind(n: int, sr: int) -> np.ndarray:
    t = np.arange(n) / sr
    out = np.stack(
        [shaped_noise(n, sr, gain_band(18, 900, -0.35), rng) for _ in range(2)], axis=1
    ).astype(np.float32)
    gust = (
        0.42
        + 0.34 * (0.5 + 0.5 * np.sin(TAU * 0.075 * t + 0.7)) ** 2
        + 0.24 * (0.5 + 0.5 * np.sin(TAU * 0.125 * t + 2.1)) ** 2
    ).astype(np.float32)
    out *= gust[:, None]

    whistle = shaped_noise(n, sr, gain_band(420, 1150, 0.4), rng)
    whistle *= (0.2 + 0.8 * (0.5 + 0.5 * np.sin(TAU * 0.175 * t + 4.0)) ** 3).astype(np.float32)
    out[:, 0] += whistle * 0.10
    out[:, 1] += np.roll(whistle, int(0.013 * sr)) * 0.10
    return normalize(out, 0.8)


def thunder_event(sr: int, dur: float, intensity: float) -> np.ndarray:
    n = int(dur * sr)
    t = np.arange(n) / sr
    env = (1.0 - np.exp(-t / 0.18)) * np.exp(-t / 2.4)
    crack_env = np.exp(-t / 0.3)
    rumble = shaped_noise(n, sr, gain_band(18, 130), rng) * env
    crack = shaped_noise(n, sr, gain_band(140, 1800, -0.2), rng) * crack_env
    return (rumble * 1.0 + crack * 0.75) * intensity


def amb_storm(n: int, sr: int) -> np.ndarray:
    out = amb_rain(n, sr, heavy=True) * 0.8
    for pos, intensity in ((5.0, 0.55), (16.5, 0.9), (28.0, 0.7)):
        ev = thunder_event(sr, 8.0, intensity)
        add_circular(out, int(pos * sr), ev, 0.5, 0.55)
    low = shaped_noise(n, sr, gain_band(20, 90), rng)
    out[:, 0] += low * 0.10
    out[:, 1] += low * 0.10
    return normalize(out, 0.85)



def amb_thunder(n: int, sr: int) -> np.ndarray:
    out = np.stack(
        [shaped_noise(n, sr, gain_band(22, 90, -0.3), rng) for _ in range(2)], axis=1
    ).astype(np.float32) * 0.12
    for pos, intensity, spread in ((5.5, 0.85, 0.22), (17.0, 0.6, 0.55), (29.5, 0.95, 0.4)):
        ev = thunder_event(sr, 8.0, intensity)
        add_circular(out, int(pos * sr), ev, spread, 0.75)
    return normalize(out, 0.82)


def amb_stream(n: int, sr: int) -> np.ndarray:
    out = np.stack(
        [shaped_noise(n, sr, gain_band(260, 6200, -0.15), rng) for _ in range(2)], axis=1
    ).astype(np.float32)
    out *= 0.42
    for _ in range(110):
        length = int(rng.uniform(0.015, 0.07) * sr)
        env = np.exp(-np.linspace(0.0, 5.0, length))
        f0 = rng.uniform(380, 900)
        f1 = f0 * rng.uniform(1.3, 2.4)
        tt = np.arange(length) / sr
        chirp = np.sin(TAU * (f0 * tt + 0.5 * (f1 - f0) / (length / sr) * tt * tt)) * env
        add_circular(out, rng.integers(0, n), chirp.astype(np.float32),
                     rng.uniform(0.15, 0.85), rng.uniform(0.03, 0.12))
    return normalize(out, 0.8)


def amb_ocean(n: int, sr: int) -> np.ndarray:
    t = np.arange(n) / sr
    swell_l = (0.5 + 0.5 * np.sin(TAU * 0.1 * t)) ** 2
    swell_r = (0.5 + 0.5 * np.sin(TAU * 0.1 * t + 0.55)) ** 2
    out = np.empty((n, 2), dtype=np.float32)
    for ch, swell in ((0, swell_l), (1, swell_r)):
        body = shaped_noise(n, sr, gain_band(25, 900, -0.4), rng)
        foam = shaped_noise(n, sr, gain_band(1800, 11000, -0.1), rng)
        out[:, ch] = body * (0.35 + 0.65 * swell) + foam * (swell ** 3) * 0.28
    return normalize(out, 0.82)


def amb_fire(n: int, sr: int) -> np.ndarray:
    out = np.stack(
        [shaped_noise(n, sr, gain_band(18, 420, -0.3), rng) for _ in range(2)], axis=1
    ).astype(np.float32) * 0.24
    for _ in range(520):
        length = int(rng.uniform(0.006, 0.03) * sr)
        env = np.exp(-np.linspace(0.0, 4.0, length))
        burst = rng.normal(0.0, 1.0, length).astype(np.float32) * env
        burst = signal.lfilter([1.0, -0.9], [1.0], burst).astype(np.float32)
        burst *= np.linspace(0.0, 1.0, length).astype(np.float32)
        add_circular(out, rng.integers(0, n), burst,
                     rng.uniform(0.05, 0.95), rng.uniform(0.12, 0.40))
    return normalize(out, 0.8)


def cricket_train(sr: int, freq: float, pulses: int, pulse: float, gap: float,
                  pan: float, amp: float) -> tuple[np.ndarray, int]:
    total = int((pulse + gap) * pulses * sr)
    sig = np.zeros(total, dtype=np.float32)
    plen = int(pulse * sr)
    win = np.sin(np.linspace(0.0, np.pi, max(2, plen))).astype(np.float32)
    for p in range(pulses):
        start = int(p * (pulse + gap) * sr)
        sig[start:start + plen] += np.sin(
            TAU * freq * np.arange(plen) / sr + p * 0.7
        ).astype(np.float32) * win
    return sig * amp, int(pan * 1000)


def amb_forest(n: int, sr: int) -> np.ndarray:
    t = np.arange(n) / sr
    out = np.stack(
        [shaped_noise(n, sr, gain_band(30, 700, -0.4), rng) for _ in range(2)], axis=1
    ).astype(np.float32) * 0.12
    out *= (0.55 + 0.45 * (0.5 + 0.5 * np.sin(TAU * 0.05 * t))).astype(np.float32)[:, None]

    for freq, period, pan, amp in ((4300, 0.5, 0.25, 0.20), (4700, 0.625, 0.7, 0.16),
                                   (3900, 0.8, 0.5, 0.14)):
        train, pan_i = cricket_train(sr, freq, 4, 0.012, 0.016, pan, amp)
        start = 0
        while start < n:
            add_circular(out, start, train, pan_i / 1000.0, 1.0)
            start += int(period * sr)

    for pos, freq in ((6.5, 380.0), (21.0, 330.0), (33.0, 410.0)):
        length = int(0.9 * sr)
        tt = np.arange(length) / sr
        env = np.sin(np.linspace(0, np.pi, length)) ** 2
        hoot = np.sin(TAU * freq * tt + 0.6 * np.sin(TAU * 5.0 * tt)) * env
        add_circular(out, int(pos * sr), hoot.astype(np.float32), 0.35, 0.06)

    return normalize(out, 0.8)


def bird_chirp(sr: int, rng):
    duration = rng.uniform(0.08, 0.22)
    length = max(16, int(duration * sr))
    tt = np.arange(length) / sr
    f0 = rng.uniform(2200, 3800)
    f1 = f0 * rng.uniform(0.65, 1.45)
    phase = TAU * (f0 * tt + 0.5 * (f1 - f0) / duration * tt * tt)
    env = np.sin(np.linspace(0.0, np.pi, length)) ** 2
    return (np.sin(phase) * env).astype(np.float32)


def amb_birds(n: int, sr: int) -> np.ndarray:
    out = np.stack(
        [shaped_noise(n, sr, gain_band(40, 900, -0.35), rng) for _ in range(2)], axis=1
    ).astype(np.float32) * 0.05
    for _ in range(90):
        chirp = bird_chirp(sr, rng)
        add_circular(out, rng.integers(0, n), chirp,
                     rng.uniform(0.1, 0.9), rng.uniform(0.16, 0.34))
    return normalize(out, 0.8)


def amb_drone(n: int, sr: int) -> np.ndarray:
    t = np.arange(n) / sr
    out = np.zeros((n, 2), dtype=np.float32)
    for freq, amp, cyc in ((55.0, 0.30, 1), (82.5, 0.18, 3), (110.0, 0.16, 2),
                           (165.0, 0.10, 5), (220.0, 0.06, 4), (330.0, 0.03, 7)):
        vib = 0.035 * np.sin(TAU * (cyc / LOOP) * t + amp * 10)
        wave = np.sin(TAU * freq * t + vib)
        trem = 0.65 + 0.35 * np.sin(TAU * (2 * cyc / LOOP) * t + amp * 20) ** 2
        out[:, 0] += (wave * trem * amp).astype(np.float32)
        out[:, 1] += (np.sin(TAU * (freq + 0.025) * t + vib + 0.3) * trem * amp).astype(np.float32)
    bed = shaped_noise(n, sr, gain_band(20, 500, -0.5), rng) * 0.08
    out[:, 0] += bed
    out[:, 1] += np.roll(bed, int(0.011 * sr))
    return normalize(out, 0.78)


# --------------------------------------------------------------------------- #
# meditation tracks
# --------------------------------------------------------------------------- #

def hz(note: str, octave: int) -> float:
    names = {"C": 0, "C#": 1, "D": 2, "D#": 3, "E": 4, "F": 5, "F#": 6,
             "G": 7, "G#": 8, "A": 9, "A#": 10, "B": 11}
    midi = 12 * (octave + 1) + names[note]
    return 440.0 * 2 ** ((midi - 69) / 12.0)


def chord(*notes) -> list[float]:
    return [hz(n, o) for n, o in notes]


def render_pad(dry: np.ndarray, sr: int, chords, rng, cross: float = 7.0):
    n = dry.shape[0]
    for start_s, end_s, notes in chords:
        start = int(start_s * sr)
        seg_len = int((end_s - start_s) * sr) + int(cross * sr)
        end = min(n, start + seg_len)
        length = end - start
        if length <= 8:
            continue
        tt = (np.arange(length) + start) / sr
        env = np.ones(length, dtype=np.float32)
        fi = min(length // 2, int(cross * sr))
        env[:fi] *= raised_cos(fi)
        env[-fi:] *= raised_cos(fi)[::-1]
        amp = 0.45 / np.sqrt(max(1, len(notes)))
        for ch in range(2):
            acc = np.zeros(length, dtype=np.float32)
            for i, freq in enumerate(notes):
                phase0 = rng.uniform(0, TAU)
                detunes = (0.9988, 1.0012) if ch == 0 else (0.9982, 1.0018)
                for det in detunes:
                    vib = 0.0012 * np.sin(TAU * 0.07 * tt + phase0 + i)
                    wave = np.sin(TAU * freq * det * tt * (1.0 + vib) + phase0)
                    wave += 0.22 * np.sin(TAU * freq * det * 2.0 * tt + phase0 * 1.7)
                    acc += (wave * amp).astype(np.float32)
                trem = 0.82 + 0.18 * np.sin(TAU * 0.03 * tt + phase0 * 0.5)
                acc *= trem.astype(np.float32)
            dry[start:end, ch] += acc * env

        # sub an octave below the root, always mono-ish
        root = notes[0] / 2.0
        sub = np.sin(TAU * root * tt + 0.2) * amp * 0.9
        dry[start:end, 0] += (sub * env * 0.5).astype(np.float32)
        dry[start:end, 1] += (sub * env * 0.5).astype(np.float32)


def bell(freq: float, sr: int, duration: float = 6.0) -> np.ndarray:
    n = int(duration * sr)
    t = np.arange(n) / sr
    index = 2.6 * np.exp(-t / 1.4)
    sig = np.sin(TAU * freq * t + index * np.sin(TAU * freq * 2.01 * t))
    sig += 0.35 * np.sin(TAU * freq * 2.99 * t + 0.4) * np.exp(-t / 1.8)
    env = (1.0 - np.exp(-t / 0.008)) * np.exp(-t / 2.0)
    return (sig * env).astype(np.float32)


def add_bells(dry: np.ndarray, sr: int, notes: list[float], rng, count: int, level: float):
    n = dry.shape[0]
    times = np.linspace(8.0, n / sr - 10.0, count)
    for t in times:
        f = float(rng.choice(notes))
        sig = bell(f, sr)
        add_linear(dry, int((t + rng.uniform(-3.0, 3.0)) * sr), sig,
                   rng.uniform(0.25, 0.75), level)


def add_waves(dry: np.ndarray, sr: int, rng, level: float = 0.35):
    n = dry.shape[0]
    tt = np.arange(n) / sr
    swell = ((0.5 + 0.5 * np.sin(TAU * 0.085 * tt + 0.4)) ** 2).astype(np.float32)
    for ch in range(2):
        body = shaped_noise(n, sr, gain_band(25, 1000, -0.4), rng)
        foam = shaped_noise(n, sr, gain_band(1600, 9000, -0.1), rng)
        dry[:, ch] += (body * (0.3 + 0.7 * swell) + foam * swell ** 3 * 0.22) * level


def add_crickets(dry: np.ndarray, sr: int, rng, level: float = 0.3):
    n = dry.shape[0]
    for freq, period, pan in ((4300, 0.5, 0.25), (4650, 0.625, 0.7), (4000, 0.55, 0.45)):
        train, _ = cricket_train(sr, freq, 4, 0.012, 0.016, pan, level * 0.16)
        start = int(rng.uniform(0, period) * sr)
        while start < n:
            add_linear(dry, start, train, pan, 1.0)
            start += int(period * sr)


def add_birds(dry: np.ndarray, sr: int, rng, level: float = 0.5, count: int = 55):
    n = dry.shape[0]
    for _ in range(count):
        chirp = bird_chirp(sr, rng)
        add_linear(dry, rng.integers(0, n), chirp, rng.uniform(0.1, 0.9),
                   rng.uniform(0.05, 0.16) * level)


def reverb(x: np.ndarray, sr: int, rng, wet: float = 0.34) -> np.ndarray:
    n = x.shape[0]
    length = int(3.2 * sr)
    t = np.arange(length) / sr
    out = np.zeros_like(x)
    block = 10 * sr
    for ch in range(2):
        ir = rng.normal(0.0, 1.0, length) * np.exp(-t / 0.8)
        ir = signal.lfilter([0.08], [1.0, -0.86], ir)
        ir /= np.sqrt(np.sum(ir ** 2)) + 1e-9
        for start in range(0, n, block):
            segment = x[start:start + block, ch]
            conv = signal.fftconvolve(segment, ir.astype(np.float32))
            end = min(n, start + conv.shape[0])
            out[start:end, ch] += conv[: end - start]
    return (x * (1.0 - wet) + out * wet * 1.6).astype(np.float32)


def master(x: np.ndarray, sr: int) -> np.ndarray:
    x = np.tanh(x.astype(np.float32) * 1.1) / np.tanh(1.1)
    x = fade_edges(x, sr, 2.0, 5.0)
    rms = float(np.sqrt(np.mean(x.astype(np.float64) ** 2))) or 1.0
    x = x * (0.16 / rms)
    peak = float(np.max(np.abs(x))) or 1.0
    if peak > 0.85:
        x = x * (0.85 / peak)
    return x.astype(np.float32)


def render_track(duration: float, chords, bells_notes, rng, beds=()):
    sr = SR_M
    n = int(duration * sr)
    dry = np.zeros((n, 2), dtype=np.float32)
    render_pad(dry, sr, chords, rng)
    for bed in beds:
        bed(dry, sr, rng)
    add_bells(dry, sr, bells_notes, rng, count=max(6, int(duration / 40)), level=0.22)
    wet = reverb(dry, sr, rng)
    return master(wet, sr)


def track_still_water():
    dm9 = chord(("D", 2), ("A", 2), ("F", 3), ("C", 4), ("E", 4))
    bbmaj7 = chord(("A#", 2), ("D", 3), ("F", 3), ("A", 3), ("C", 4))
    fmaj7 = chord(("F", 2), ("C", 3), ("E", 3), ("A", 3), ("G", 4))
    cadd9 = chord(("C", 3), ("E", 3), ("G", 3), ("D", 4), ("E", 4))
    prog = [dm9, bbmaj7, fmaj7, cadd9, dm9, bbmaj7, fmaj7, cadd9]
    chords = [(i * 60.0, (i + 1) * 60.0, notes) for i, notes in enumerate(prog)]
    return render_track(480.0, chords,
                        [hz("D", 5), hz("F", 5), hz("A", 5), hz("C", 6), hz("E", 6)],
                        rng)


def track_night_sky():
    am9 = chord(("A", 2), ("E", 3), ("G", 3), ("B", 3), ("C", 4))
    fmaj7 = chord(("F", 2), ("C", 3), ("E", 3), ("A", 3), ("G", 4))
    cmaj7 = chord(("C", 3), ("G", 3), ("B", 3), ("E", 4), ("D", 4))
    g6 = chord(("G", 2), ("D", 3), ("B", 3), ("E", 4), ("A", 4))
    prog = [am9, fmaj7, cmaj7, g6, am9, fmaj7, cmaj7, g6]
    chords = [(i * 60.0, (i + 1) * 60.0, notes) for i, notes in enumerate(prog)]
    return render_track(480.0, chords,
                        [hz("A", 5), hz("C", 6), hz("E", 6), hz("G", 6), hz("B", 6)],
                        rng, beds=(add_crickets,))


def track_sunrise():
    cmaj9 = chord(("C", 3), ("E", 3), ("G", 3), ("B", 3), ("D", 4))
    g = chord(("G", 2), ("D", 3), ("G", 3), ("B", 3), ("D", 4))
    am7 = chord(("A", 2), ("E", 3), ("G", 3), ("C", 4), ("E", 4))
    fmaj7 = chord(("F", 2), ("C", 3), ("E", 3), ("A", 3), ("G", 4))
    prog = [cmaj9, g, am7, fmaj7, cmaj9, g, am7, fmaj7]
    chords = [(i * 52.5, (i + 1) * 52.5, notes) for i, notes in enumerate(prog)]
    return render_track(420.0, chords,
                        [hz("C", 6), hz("D", 6), hz("E", 6), hz("G", 6), hz("A", 6)],
                        rng, beds=(add_birds,))


def track_deep_rest():
    d = chord(("D", 2), ("A", 2), ("D", 3), ("F", 3), ("A", 3))
    bb = chord(("A#", 1), ("F", 2), ("A#", 2), ("D", 3), ("F", 3))
    f = chord(("F", 2), ("C", 3), ("F", 3), ("A", 3), ("E", 4))
    g = chord(("G", 2), ("D", 3), ("G", 3), ("A#", 3), ("D", 4))
    prog = [d, bb, f, g, d, bb, f, g]
    chords = [(i * 60.0, (i + 1) * 60.0, notes) for i, notes in enumerate(prog)]
    return render_track(480.0, chords,
                        [hz("D", 4), hz("F", 4), hz("A", 4), hz("D", 5)],
                        rng)


def track_ocean_breath():
    dm9 = chord(("D", 2), ("A", 2), ("F", 3), ("C", 4), ("E", 4))
    fmaj7 = chord(("F", 2), ("C", 3), ("E", 3), ("A", 3), ("G", 4))
    prog = [dm9, fmaj7, dm9, fmaj7, dm9, fmaj7, dm9, fmaj7]
    chords = [(i * 60.0, (i + 1) * 60.0, notes) for i, notes in enumerate(prog)]
    return render_track(480.0, chords,
                        [hz("D", 5), hz("E", 5), hz("F", 5), hz("A", 5), hz("C", 6)],
                        rng, beds=(add_waves,))


# --------------------------------------------------------------------------- #
# event sounds
# --------------------------------------------------------------------------- #

def sfx():
    sr = SR_A
    n = int(3.5 * sr)
    t = np.arange(n) / sr
    env = (1.0 - np.exp(-t / 0.008)) * np.exp(-t / 1.9)
    sig = np.sin(TAU * 587.33 * t + 2.2 * np.exp(-t / 1.3) * np.sin(TAU * 587.33 * 2.01 * t))
    sig += 0.3 * np.sin(TAU * 880.0 * t) * np.exp(-t / 1.1)
    sig *= env
    sig = fade_edges(sig, sr, 0.004, 0.06)
    stereo = np.stack([sig, np.roll(sig, int(0.002 * sr))], axis=1) * 0.55
    publish("sfx_bell", stereo, sr, 5)

    n = int(0.09 * sr)
    t = np.arange(n) / sr
    tick = np.sin(TAU * 1180.0 * t) * np.exp(-t / 0.018)
    tick *= (1.0 - np.exp(-t / 0.002))
    stereo = np.stack([tick, tick], axis=1).astype(np.float32) * 0.35
    publish("sfx_tick", stereo, sr, 5)


# --------------------------------------------------------------------------- #
# entry point
# --------------------------------------------------------------------------- #

AMBIENCE = {
    "amb_rain": lambda n, sr: amb_rain(n, sr, heavy=False),
    "amb_rain_heavy": lambda n, sr: amb_rain(n, sr, heavy=True),
    "amb_storm": amb_storm,
    "amb_thunder": amb_thunder,
    "amb_wind": amb_wind,
    "amb_stream": amb_stream,
    "amb_ocean": amb_ocean,
    "amb_fire": amb_fire,
    "amb_forest": amb_forest,
    "amb_birds": amb_birds,
    "amb_drone": amb_drone,
}


def main(argv):
    os.makedirs(RAW, exist_ok=True)
    os.makedirs(WORK, exist_ok=True)
    which = argv[1] if len(argv) > 1 else "all"

    if which in ("all", "ambience"):
        print("ambience loops")
        n = int(LOOP * SR_A)
        for name, fn in AMBIENCE.items():
            publish(name, fn(n, SR_A), SR_A, 4)
        sfx()

    if which in ("all", "meditation"):
        print("meditation tracks")
        for name, fn in (
            ("med_still_water", track_still_water),
            ("med_night_sky", track_night_sky),
            ("med_sunrise", track_sunrise),
            ("med_deep_rest", track_deep_rest),
            ("med_ocean_breath", track_ocean_breath),
        ):
            publish(name, fn(), SR_M, 2)


if __name__ == "__main__":
    main(sys.argv)
