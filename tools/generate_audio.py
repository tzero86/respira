#!/usr/bin/env python3
"""Generate and encode every bundled sound for Respira.

Ambience layers are 40 s seamless loops built from event-driven models:
Poisson-distributed impacts with distance-dependent filtering (rain, fire,
stream, ocean), a synthesised convolution room as a wet send, and a
mid/side stereo field with a frequency dependent interaural delay. Every
part is loop-periodic -- the beds are shaped in the frequency domain, the
transients are wrapped modulo the loop and the reverb tail is convolved
circularly -- so the join is continuous.

Meditation tracks are long-form pad pieces rendered at 32 kHz. Everything is
deterministic for a given seed, so reruns produce identical assets.

Usage:
    python tools/generate_audio.py            # everything
    python tools/generate_audio.py ambience
    python tools/generate_audio.py meditation

Verification helpers:
    python tools/measure_ambience.py          # RMS, crest, bands, loudness
    python tools/check_seam.py                # loop seam null test
"""

from __future__ import annotations

import math
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


def rms_dbfs(x: np.ndarray) -> float:
    """RMS level of a signal in dBFS."""
    v = float(np.sqrt(np.mean(np.asarray(x, dtype=np.float64) ** 2)))
    return 20.0 * math.log10(v) if v > 0.0 else -120.0


def peak_dbfs(x: np.ndarray) -> float:
    m = float(np.max(np.abs(x))) or 1.0
    return 20.0 * math.log10(m)


def mix_to_rms(x: np.ndarray, target_db: float) -> np.ndarray:
    """Scale a block of audio to a given RMS level in dBFS."""
    cur = rms_dbfs(x)
    if cur <= -119.0:
        return x
    return (x * (10.0 ** ((target_db - cur) / 20.0))).astype(np.float32)


def limit_peak(x: np.ndarray, ceiling_db: float = -3.0, knee: float = 0.15) -> np.ndarray:
    """Soft limiter: transparent below the knee, never exceeds the ceiling."""
    t = 10.0 ** (ceiling_db / 20.0)
    k = knee * t
    lo = t - k
    a = np.abs(x)
    y = a.copy()
    over = a > lo
    if np.any(over):
        y[over] = lo + k * np.tanh((a[over] - lo) / k)
    return (np.sign(x) * y).astype(np.float32)


# --------------------------------------------------------------------------- #
# ambience machinery
# --------------------------------------------------------------------------- #

AMBIENCE_LUFS = -23.0
# Vorbis is lossy and the decoder overshoots the input peak by up to ~2 dB on
# transient material, so the mix is baked to -5 dBFS: the decoded file then
# stays at or below -3 dBFS, which is the headroom the ten summed layers need.
AMBIENCE_CEILING = -5.0
# The loop is rendered 0.5 s longer and folded back with an equal-power
# crossfade, which is what the assessment asks for and what removes any
# residual discontinuity at the junction.
CROSSFADE_S = 0.5


def lfo(n: int, sr: int, cycles: float, phase: float = 0.0) -> np.ndarray:
    """Loop-periodic LFO: `cycles` whole cycles across the loop."""
    t = np.arange(n) / sr
    return np.sin(TAU * cycles * t / LOOP + phase).astype(np.float32)


def crossfade_loop(x: np.ndarray, sr: int, fade_s: float = 0.5) -> np.ndarray:
    """Turn a longer render into a seamless loop with an equal-power crossfade.

    `x` must be `fade_s` seconds longer than the loop.  The loop is
    `x[:N]`; its first `fade` samples are replaced by an equal-power blend of
    the render's tail (fading out) and the render's head (fading in), where
    `N = len(x) - fade`.

    Both ends of the result are continuous by construction:

      * out[N - 1] = x[N - 1] and out[0] = x[N], which are adjacent samples
        of the original render, so the junction carries no step;
      * out[fade - 1] = x[fade - 1] and out[fade] = x[fade], the same again
        where the blend hands back to the untouched body.

    This is the belt-and-braces version of the seam: every part of the
    generator is already loop-periodic, but dense overlapping transients can
    leave the limiter engaged across the join, and the crossfade removes
    that by construction.
    """
    fade = int(fade_s * sr)
    total = x.shape[0]
    loop = total - fade
    if fade < 8 or loop < fade:
        return x[:loop] if loop > 0 else x
    ramp = np.linspace(0.0, np.pi / 2.0, fade, dtype=np.float32)
    out = x[:loop].copy()
    out[:fade] = (x[loop:] * np.cos(ramp)[:, None]
                  + x[:fade] * np.sin(ramp)[:, None])
    return out


def random_pan(rng, count: int, spread: float = 1.0) -> np.ndarray:
    """Pan positions in [-1, 1] with a centre bias."""
    u = rng.beta(1.7, 1.7, size=count)
    return np.clip((u * 2.0 - 1.0) * spread, -1.0, 1.0)


def pad_bank(bank: list[np.ndarray]) -> list[np.ndarray]:
    """Pad a bank of one-shots to a common length so they can be scattered."""
    length = max(len(b) for b in bank)
    return [np.pad(b, (0, length - len(b))) for b in bank]


def band_energy(x: np.ndarray, sr: int, lo: float, hi: float) -> float:
    """Energy of a mono signal inside a band."""
    mono = x.mean(axis=1) if x.ndim == 2 else x
    spec = np.abs(np.fft.rfft(mono)) ** 2
    freqs = np.fft.rfftfreq(mono.shape[0], 1.0 / sr)
    mask = (freqs >= lo) & (freqs < hi)
    return float(spec[mask].sum())


def rebalance(x: np.ndarray, sr: int, groups: list[tuple[float, float, float]],
              band_limit: float = 11000.0) -> np.ndarray:
    """Scale bands of a mix so each lands on its target energy share.

    `groups` is a list of (target_share, lo, hi) whose bands partition
    [0, band_limit) -- the band the acceptance measurements use.  The audio
    inside each band is scaled so that the band ends up with the requested
    share of the energy in the whole band.

    The scales are solved in one pass: because the bands partition the range,
    scaling band `i` by `sqrt(target_i * total / energy_i)` gives every band
    its target share at once and leaves the total unchanged.  The overall
    level of the mix is therefore preserved, which matters because the layer
    is loudness-normalised afterwards.
    """
    out = x.astype(np.float64).copy()
    freqs = np.fft.rfftfreq(out.shape[0], 1.0 / sr)
    energies = [band_energy(out, sr, lo, hi) for _, lo, hi in groups]
    total = float(sum(energies))
    if total <= 0.0:
        return out.astype(np.float32)

    spec = np.fft.rfft(out, axis=0)
    for (target, lo, hi), energy in zip(groups, energies):
        if energy <= 1e-20:
            continue
        scale = math.sqrt(max(target * total, 1e-20) / energy)
        band = ((freqs >= lo) & (freqs < hi)).astype(np.float64)
        spec *= (1.0 + (scale - 1.0) * band)[:, None]
    out = np.fft.irfft(spec, out.shape[0], axis=0)
    return out.astype(np.float32)


def stereo_field(mono: np.ndarray, sr: int, rng, itd_ms: float = 0.55,
                 width: float = 1.0, corner: float = 250.0, nbands: int = 32
                 ) -> np.ndarray:
    """Coherent stereo from a mono bed.

    The bed is split into a mid signal (kept as it is) and a side signal (the
    same bed with a random phase per frequency band, high-passed above
    `corner`).  The side signal is decorrelated from the mid, so adding and
    subtracting it to the two channels widens the field without the comb
    filtering a plain delay produces and without touching the bass, which
    stays centred so a phone speaker keeps its weight in the middle.

    A frequency dependent interaural time difference (largest at low
    frequencies, capped at `itd_ms`) is applied to the right channel on top,
    which gives the field a front-to-back dimension.
    """
    n = mono.shape[0]
    base = mono.astype(np.float64)
    if width <= 0.0:
        return np.stack([base, base], axis=1).astype(np.float32)

    freqs = np.fft.rfftfreq(n, 1.0 / sr)
    spec = np.fft.rfft(base)
    edges = np.geomspace(corner, sr / 2.0, nbands + 1)
    phase = np.zeros(freqs.shape[0])
    for i in range(nbands):
        sel = (freqs >= edges[i]) & (freqs < edges[i + 1])
        phase[sel] = rng.uniform(-np.pi, np.pi, int(sel.sum()))
    side_spec = spec * np.exp(1j * phase)
    side_spec *= (freqs >= corner).astype(np.float64)
    side = np.fft.irfft(side_spec, n) - base

    k = 0.85 * width
    left = base - k * side
    right = base + k * side

    if itd_ms > 0.0:
        tau = (itd_ms / 1000.0) / (1.0 + (freqs / 1500.0) ** 2)
        right = np.fft.irfft(np.fft.rfft(right) * np.exp(-1j * TAU * freqs * tau), n)
    return np.stack([left, right], axis=1).astype(np.float32)


def burst_bank(sr: int, rng, count: int, lo: float, hi: float, dur: float,
               attack: float, resonance: float = 0.0, decay: float = 0.35,
               tilt: float = 0.0) -> list[np.ndarray]:
    """Bank of short band-passed noise bursts: droplets, crackles, foam."""
    length = max(8, int(dur * sr))
    t = np.arange(length) / sr
    band = gain_band(lo, hi, tilt)(np.fft.rfftfreq(length, 1.0 / sr))
    bank = []
    for _ in range(count):
        noise = rng.normal(0.0, 1.0, length)
        sig = np.fft.irfft(np.fft.rfft(noise) * band, length)
        if resonance > 0.0:
            f0 = float(np.clip(np.sqrt(lo * hi) * rng.uniform(0.7, 1.5), lo, hi))
            sig = sig + resonance * np.sin(TAU * f0 * t + rng.uniform(0.0, TAU)) \
                * np.exp(-t / (dur * 0.30))
        env = (1.0 - np.exp(-t / attack)) * np.exp(-t / (dur * decay))
        bank.append((sig * env).astype(np.float32))
    return bank


def poisson_events(rate: float, duration: float, rng) -> np.ndarray:
    """Arrival times (seconds) of a Poisson process, wrapped into the loop."""
    count = int(rng.poisson(rate * duration))
    if count <= 0:
        return np.zeros(0)
    return np.cumsum(rng.exponential(1.0 / rate, size=count)) % duration


def scatter_bank(buf: np.ndarray, bank, times: np.ndarray, gains: np.ndarray,
                 pans: np.ndarray, rng) -> None:
    """Scatter bank one-shots into a stereo buffer with constant-power pans."""
    if len(times) == 0:
        return
    n = buf.shape[0]
    length = len(bank[0])
    theta = (pans + 1.0) / 2.0 * (np.pi / 2.0)
    gl = np.cos(theta)
    gr = np.sin(theta)
    for start in range(0, len(times), 4096):
        stop = min(start + 4096, len(times))
        pick = rng.integers(0, len(bank), stop - start)
        contrib = np.stack([bank[i] for i in pick]) * gains[start:stop, None]
        idx = (times[start:stop, None] + np.arange(length)[None, :]) % n
        flat = idx.ravel()
        buf[:, 0] += np.bincount(flat, weights=(contrib * gl[start:stop, None]).ravel(),
                                 minlength=n)
        buf[:, 1] += np.bincount(flat, weights=(contrib * gr[start:stop, None]).ravel(),
                                 minlength=n)


def room_ir(sr: int, rng, dur: float = 1.8, predelay: float = 0.035,
            damp: float = 0.6) -> np.ndarray:
    """Synthesised stereo room: early reflections plus a darkening diffuse tail."""
    length = int((dur + predelay) * sr)
    ir = np.zeros((length, 2), dtype=np.float32)
    tail_len = length - int(predelay * sr)
    segs = 10
    seg = tail_len // segs
    for ch in range(2):
        tail = np.zeros(tail_len, dtype=np.float32)
        for s in range(segs):
            a, b = s * seg, (s + 1) * seg
            noise = rng.normal(0.0, 1.0, b - a).astype(np.float32)
            cutoff = 11000.0 * (1.0 - damp) ** (s / segs) + 700.0
            bq, aq = signal.butter(2, min(cutoff, sr * 0.45) / (sr * 0.5), btype="low")
            noise = signal.lfilter(bq, aq, noise).astype(np.float32)
            tt = np.arange(a, b) / sr
            tail[a:b] += noise * np.exp(-tt / (dur * 0.30))
        tail /= np.sqrt(np.sum(tail ** 2)) + 1e-9
        ir[int(predelay * sr):, ch] = tail
    taps = ((0.013, 0.55), (0.021, -0.42), (0.029, 0.34), (0.043, -0.27), (0.062, 0.21))
    for delay_s, amp in taps:
        i = int((predelay + delay_s) * sr)
        j = i + int(0.0004 * sr)
        if i < length:
            ir[i, 0] += amp
        if j < length:
            ir[j, 1] += amp * rng.uniform(0.55, 1.0)
    norm = float(np.sqrt(np.sum(ir ** 2)))
    if norm > 0.0:
        ir /= norm
    return ir


def room_reverb(x: np.ndarray, ir: np.ndarray, wet: float = 0.32) -> np.ndarray:
    """Circular convolution reverb: the tail wraps round, so the loop seam survives."""
    n = x.shape[0]
    out = np.zeros((n, 2), dtype=np.float64)

    def wrap(conv: np.ndarray, target: np.ndarray, scale: float = 1.0) -> None:
        """Add a convolution to `target`, wrapping the overflow to the front."""
        target += conv[:n] * scale
        if conv.shape[0] > n:
            target[: conv.shape[0] - n] += conv[n:] * scale

    for ch in range(2):
        wrap(signal.fftconvolve(x[:, ch].astype(np.float64),
                                 ir[:, ch].astype(np.float64)), out[:, ch])
    # a little cross feed from the right channel into the left early path
    wrap(signal.fftconvolve(x[:, 1].astype(np.float64),
                             ir[:, 0].astype(np.float64)), out[:, 0], 0.2)
    return (x * (1.0 - wet) + out * wet).astype(np.float32)


def _k_weight(x: np.ndarray, sr: int) -> np.ndarray:
    """ITU-R BS.1770-4 pre-filter (high shelf) and RLB high-pass."""
    f0, Q = 1681.974450955533, 0.7071752369554196
    K = math.tan(math.pi * f0 / sr)
    Vh = 10.0 ** (3.999843853973347 / 20.0)
    Vb = Vh ** 0.4996667741545416
    a0 = 1.0 + K / Q + K * K
    b1 = [(Vh + Vb * K / Q + K * K) / a0, 2.0 * (K * K - Vh) / a0,
          (Vh - Vb * K / Q + K * K) / a0]
    a1 = [1.0, 2.0 * (K * K - 1.0) / a0, (1.0 - K / Q + K * K) / a0]
    f0, Q = 38.13547087602444, 0.5003270373238773
    K = math.tan(math.pi * f0 / sr)
    a0 = 1.0 + K / Q + K * K
    b2 = [1.0, -2.0, 1.0]
    a2 = [1.0, 2.0 * (K * K - 1.0) / a0, (1.0 - K / Q + K * K) / a0]
    return signal.lfilter(b2, a2, signal.lfilter(b1, a1, x))


def integrated_lufs(x: np.ndarray, sr: int) -> float:
    """Gated integrated loudness in LUFS (ITU-R BS.1770-4)."""
    block = int(0.4 * sr)
    step = int(0.1 * sr)
    if x.shape[0] < block:
        return -70.0
    weighted = [_k_weight(x[:, ch].astype(np.float64), sr) for ch in range(x.shape[1])]
    count = 1 + (x.shape[0] - block) // step
    energies = np.empty(count)
    for i in range(count):
        s = i * step
        e = 0.0
        for y in weighted:
            seg = y[s:s + block]
            e += float(np.mean(seg ** 2))
        energies[i] = e
    with np.errstate(divide="ignore"):
        blocks = -0.691 + 10.0 * np.log10(energies)
    keep = blocks > -70.0
    if not np.any(keep):
        return -70.0
    relative = -0.691 + 10.0 * np.log10(float(np.mean(energies[keep]))) - 10.0
    keep &= blocks > relative
    if not np.any(keep):
        return -70.0
    return float(-0.691 + 10.0 * np.log10(float(np.mean(energies[keep]))))


def finalise(x: np.ndarray, sr: int, target_lufs: float = AMBIENCE_LUFS,
             ceiling_db: float = AMBIENCE_CEILING) -> np.ndarray:
    """Loudness-normalise to the shared target, then cap the peak."""
    y = x.astype(np.float32)
    for _ in range(3):
        loud = integrated_lufs(y, sr)
        if loud <= -70.0:
            break
        y = (y * (10.0 ** ((target_lufs - loud) / 20.0))).astype(np.float32)
        y = limit_peak(y, ceiling_db)
    return y.astype(np.float32)


def seam_null_db(x: np.ndarray, sr: int, window_ms: float = 100.0,
                 fit_ms: float = 20.0) -> tuple[float, float]:
    """Loop-seam null test.

    A two-tap linear predictor is fitted on the material just before the
    junction and pushed across it; the residual over the first `window_ms` of
    the repeat is the artefact energy.  The same test is run at interior
    positions to get the material's own baseline, so the first number says
    whether the seam is rougher than the rest of the file and the second says
    how loud the artefact is relative to the signal.
    """
    n = x.shape[0]
    W = max(16, int(window_ms * sr / 1000.0))
    fit = max(64, int(fit_ms * sr / 1000.0))
    d = np.concatenate([x, x], axis=0).astype(np.float64)

    def residual(pos: int, ch: int) -> np.ndarray:
        sig = d[:, ch]
        seg = sig[pos - fit:pos]
        A = np.stack([seg[1:-1], seg[:-2], np.ones(fit - 2)], axis=1)
        coef, *_ = np.linalg.lstsq(A, seg[2:], rcond=None)
        nxt = np.arange(pos, pos + W)
        A2 = np.stack([sig[nxt - 1], sig[nxt - 2], np.ones(W)], axis=1)
        return sig[nxt] - A2 @ coef

    seam = np.concatenate([residual(n, ch) for ch in range(x.shape[1])])
    positions = np.linspace(n + W, 2 * n - W, 257).astype(int)
    base = np.concatenate([residual(int(p), ch) for p in positions
                           for ch in range(x.shape[1])])
    p_seam = float(np.mean(seam ** 2))
    p_base = float(np.mean(base ** 2))
    p_file = float(np.mean(d[:n] ** 2))
    floor = 1e-18 * max(p_file, 1e-30)
    excess = max(0.0, p_seam - p_base)
    null_db = 10.0 * math.log10((excess + floor) / max(p_file, 1e-30))
    ratio_db = 20.0 * math.log10(max(p_seam, floor) / max(p_base, floor))
    return null_db, ratio_db


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
#
# Every layer is assembled from loop-periodic parts:
#   * beds are shaped in the frequency domain, so they are exactly periodic;
#   * transient events are wrapped modulo the loop length;
#   * the reverb tail is convolved circularly, so it wraps with the loop.
# The join is therefore continuous; `seam_null_db` measures how clean it is.


def _room(stereo: np.ndarray, sr: int, rng, wet: float, dur: float,
          predelay: float, damp: float) -> np.ndarray:
    ir = room_ir(sr, rng, dur=dur, predelay=predelay, damp=damp)
    return room_reverb(stereo, ir, wet=wet)


def amb_rain(n: int, sr: int, heavy: bool) -> np.ndarray:
    """Rain: a dense field of droplet impacts over a low-mid bed.

    The bed is a 130-830 Hz sheet that gives the rain weight; the character
    comes from the impacts, which are distance filtered (near drops are bright
    and short, far ones dull and long) and scattered across the stereo field.
    """
    if heavy:
        rate, body_lo, body_hi = 1850.0, 135.0, 720.0
        cut_far, cut_near = 520.0, 6200.0
        body_gain, hero_rate, hero_gain = 0.62, 1.5, 2.2
    else:
        rate, body_lo, body_hi = 1050.0, 185.0, 840.0
        cut_far, cut_near = 430.0, 7400.0
        body_gain, hero_rate, hero_gain = 0.50, 1.0, 2.6
    dur = n / sr

    body = shaped_noise(n, sr, gain_band(body_lo, body_hi, -0.22), rng)
    body *= (0.80 + 0.20 * lfo(n, sr, 3, 1.1)).astype(np.float32)

    stereo = np.zeros((n, 2), dtype=np.float32)

    times = poisson_events(rate, dur, rng) * sr
    count = len(times)
    cut = cut_far * (cut_near / cut_far) ** rng.beta(1.6, 2.6, size=count)
    edges = np.geomspace(cut_far, cut_near, 6)
    bucket = np.clip(np.searchsorted(edges, cut, side="right") - 1, 0, 4)
    for k in range(5):
        sel = np.nonzero(bucket == k)[0]
        if sel.size == 0:
            continue
        frac = k / 4.0
        fc = float(edges[k])
        drop = 0.042 - 0.032 * frac
        bank = burst_bank(sr, rng, 160, fc * 0.55, fc * 1.7, drop,
                          attack=0.0009, resonance=0.10 + 0.45 * frac,
                          decay=0.28, tilt=-0.15)
        gains = (0.10 + 0.42 * frac) * rng.uniform(0.40, 1.0, sel.size)
        pans = random_pan(rng, sel.size, 0.95)
        scatter_bank(stereo, bank, times[sel].astype(np.int64),
                     gains.astype(np.float32), pans.astype(np.float32), rng)

    hero_t = poisson_events(hero_rate, dur, rng) * sr
    bank = burst_bank(sr, rng, 96, 800.0, 6800.0, 0.022, attack=0.0012,
                      resonance=0.60, decay=0.20, tilt=-0.30)
    gains = hero_gain * rng.uniform(0.6, 1.4, len(hero_t)).astype(np.float32)
    pans = random_pan(rng, len(hero_t), 0.75)
    scatter_bank(stereo, bank, hero_t.astype(np.int64), gains, pans, rng)

    bed = stereo_field(body, sr, rng, itd_ms=0.45, width=0.85, corner=200.0)
    out = bed * body_gain + stereo
    out = rebalance(out, sr, [
        (0.08 if heavy else 0.07, 0.0, 200.0),      # a little sub weight
        (0.44 if heavy else 0.50, 200.0, 2000.0),   # the 200-800 Hz body
        (0.48 if heavy else 0.43, 2000.0, 11000.0),  # the impact field
    ])
    return _room(out, sr, rng, wet=0.24, dur=1.7, predelay=0.030, damp=0.55)



def amb_wind(n: int, sr: int) -> np.ndarray:
    """Wind: gusting filtered noise plus a wandering whistle.

    Kept synthetic but given the same room and loudness target as the rest, so
    it sits in the mix instead of towering over it.
    """
    t = np.arange(n) / sr
    low = shaped_noise(n, sr, gain_band(20, 420, -0.30), rng)
    mid = shaped_noise(n, sr, gain_band(160, 1000, -0.15), rng)
    gust = (
        0.34
        + 0.40 * (0.5 + 0.5 * np.sin(TAU * 0.075 * t + 0.7)) ** 2
        + 0.26 * (0.5 + 0.5 * np.sin(TAU * 0.125 * t + 2.1)) ** 2
    ).astype(np.float32)
    body = low * 0.72 + mid * 0.34 * gust
    out = stereo_field(body * gust, sr, rng, itd_ms=0.5, width=0.9, corner=180.0)

    whistle = shaped_noise(n, sr, gain_band(430, 1150, 0.4), rng)
    whistle *= (0.15 + 0.85 * (0.5 + 0.5 * np.sin(TAU * 0.175 * t + 4.0)) ** 3).astype(np.float32)
    wstereo = stereo_field(whistle, sr, rng, itd_ms=0.6, width=1.0, corner=420.0)
    out += wstereo * 0.13

    out = rebalance(out, sr, [
        (0.52, 0.0, 200.0),
        (0.45, 200.0, 2000.0),
        (0.03, 2000.0, 11000.0),
    ])
    out = _room(out, sr, rng, wet=0.26, dur=2.1, predelay=0.045, damp=0.62)
    return out


def thunder_event(sr: int, dur: float, intensity: float, rng) -> np.ndarray:
    n = int(dur * sr)
    t = np.arange(n) / sr
    env = (1.0 - np.exp(-t / 0.18)) * np.exp(-t / 2.4)
    crack_env = np.exp(-t / 0.3)
    rumble = shaped_noise(n, sr, gain_band(18, 130), rng) * env
    crack = shaped_noise(n, sr, gain_band(140, 1800, -0.2), rng) * crack_env
    return (rumble * 1.0 + crack * 0.75) * intensity


def amb_thunder(n: int, sr: int) -> np.ndarray:
    """Thunder: a low rumble bed with a few randomly spaced rolls.

    The events used to sit at fixed 5.5 / 17 / 29.5 s positions, which read as
    a metronome; they are now drawn from a Poisson process so the spacing is
    irregular but still wraps cleanly.
    """
    dur = n / sr
    bed = shaped_noise(n, sr, gain_band(22, 90, -0.3), rng) * 0.12
    out = stereo_field(bed, sr, rng, itd_ms=0.4, width=0.5, corner=120.0)

    times = poisson_events(0.075, dur, rng) * sr
    for t0 in times:
        intensity = float(rng.uniform(0.45, 1.0))
        ev = thunder_event(sr, 8.0, intensity, rng)
        pan = float(rng.uniform(0.2, 0.8))
        add_circular(out, int(t0), ev, pan, 0.75)
    out = rebalance(out, sr, [
        (0.72, 0.0, 200.0),
        (0.24, 200.0, 2000.0),
        (0.04, 2000.0, 11000.0),
    ])
    out = _room(out, sr, rng, wet=0.30, dur=2.4, predelay=0.055, damp=0.68)
    return out


def amb_stream(n: int, sr: int) -> np.ndarray:
    """Stream: impulse-excited bubble resonance over a 400-1200 Hz body.

    The bubbles are damped resonators, not filtered noise: each one is a
    short noise excitation fed through a high-Q bandpass whose centre
    frequency rises as the bubble grows, which is what makes it read as water.
    """
    dur = n / sr
    body = shaped_noise(n, sr, gain_band(400, 1200, -0.10), rng)
    body *= (0.82 + 0.18 * lfo(n, sr, 4, 0.4)).astype(np.float32)
    out = stereo_field(body, sr, rng, itd_ms=0.5, width=0.8, corner=390.0)

    count = int(rng.poisson(4.2 * dur))
    times = np.cumsum(rng.exponential(1.0 / 4.2, size=count)) % dur if count else np.zeros(0)
    times = times * sr
    stereo = np.zeros((n, 2), dtype=np.float32)
    for i, t0 in enumerate(times):
        length = max(16, int(rng.uniform(0.035, 0.10) * sr))
        tt = np.arange(length) / sr
        f0 = float(rng.uniform(320, 780))
        f1 = f0 * float(rng.uniform(1.6, 3.2))
        # rising-pitch bubble: an exponential sweep excites a resonant peak
        k = (f1 / f0 - 1.0) / (length / sr)
        sweep = f0 * (np.exp(k * tt) - 1.0) / k
        excite = rng.normal(0.0, 1.0, length) * np.exp(-tt / 0.004)
        sig = signal.hilbert(excite)
        phase = TAU * np.cumsum(sweep) / sr
        sig = np.imag(sig * np.exp(1j * phase))
        q = float(rng.uniform(6.0, 16.0))
        dec = float(rng.uniform(0.010, 0.030))
        sig *= np.exp(-tt / dec) * (1.0 - np.exp(-tt / 0.0015))
        # band limit the bubble to its own band
        band = gain_band(f0 * 0.55, f1 * 1.5, -0.1)(np.fft.rfftfreq(length, 1.0 / sr))
        sig = np.fft.irfft(np.fft.rfft(sig) * band, length)
        pan = float(rng.uniform(0.05, 0.95))
        g = float(rng.uniform(0.6, 1.5))
        theta = pan * (np.pi / 2.0)
        idx = (np.arange(length) + int(t0)) % n
        stereo[idx, 0] += (sig * np.cos(theta) * g).astype(np.float32)
        stereo[idx, 1] += (sig * np.sin(theta) * g).astype(np.float32)
    out += stereo * 0.5

    # sparse surface splash: high, very short, wide
    splash_t = poisson_events(0.9, dur, rng) * sr
    bank = burst_bank(sr, rng, 48, 3200.0, 8000.0, 0.030, attack=0.0015,
                      resonance=0.25, decay=0.22, tilt=-0.25)
    gains = rng.uniform(0.08, 0.24, len(splash_t)).astype(np.float32)
    pans = random_pan(rng, len(splash_t), 0.9)
    scatter_bank(out, bank, splash_t.astype(np.int64), gains, pans, rng)

    out = rebalance(out, sr, [
        (0.05, 0.0, 200.0),
        (0.45, 200.0, 2000.0),     # the 400-1200 Hz body
        (0.50, 2000.0, 11000.0),   # the bubbles and splash
    ])
    out = _room(out, sr, rng, wet=0.28, dur=1.5, predelay=0.025, damp=0.5)
    return out


def amb_ocean(n: int, sr: int) -> np.ndarray:
    """Ocean: a slow swell with foam riding on top of it.

    The body (40-500 Hz) still carries the swell, but the foam is now built
    from short 2-7 kHz bursts that arrive with each wave instead of being a
    constant hiss bed, which is what makes a wave break audible.
    """
    dur = n / sr
    t = np.arange(n) / sr
    swell_l = (0.5 + 0.5 * np.sin(TAU * 0.1 * t)) ** 2
    swell_r = (0.5 + 0.5 * np.sin(TAU * 0.1 * t + 0.55)) ** 2

    body = shaped_noise(n, sr, gain_band(40, 500, -0.40), rng)
    out = np.empty((n, 2), dtype=np.float32)
    for ch, swell in ((0, swell_l), (1, swell_r)):
        out[:, ch] = body * (0.30 + 0.70 * swell) * 0.9

    # Foam: bursts clustered around the wave peaks.  The peaks of a 0.1 Hz
    # swell cover a large part of the loop, so the picks are thinned out and
    # spread over the peak region rather than landing on every sample of it.
    stereo = np.zeros((n, 2), dtype=np.float32)
    peak_l = np.nonzero(swell_l > 0.62)[0]
    peak_r = np.nonzero(swell_r > 0.62)[0]
    for swell, peaks in ((swell_l, peak_l), (swell_r, peak_r)):
        if peaks.size == 0:
            continue
        # one foam burst every ~90 ms of peak, spread evenly inside the peak
        spacing = int(0.09 * sr)
        count = max(1, peaks.size // spacing)
        picks = peaks[np.linspace(0, peaks.size - 1, count).astype(int)]
        times = np.unique(picks.astype(np.int64))
        bank = burst_bank(sr, rng, 64, 2000.0, 7000.0, 0.055, attack=0.004,
                          resonance=0.20, decay=0.30, tilt=-0.15)
        gains = (rng.uniform(0.3, 1.0, times.size) * 0.45).astype(np.float32)
        pans = random_pan(rng, times.size, 0.8)
        scatter_bank(stereo, bank, times, gains, pans, rng)
    out += stereo

    out = rebalance(out, sr, [
        (0.55, 0.0, 200.0),        # the swell body
        (0.28, 200.0, 2000.0),     # the mid transition
        (0.17, 2000.0, 11000.0),   # the foam
    ])
    out = _room(out, sr, rng, wet=0.30, dur=2.3, predelay=0.050, damp=0.66)
    return out


def amb_fire(n: int, sr: int) -> np.ndarray:
    """Fire: crackle trains over a warm 60-300 Hz body.

    A crackle is a short bright transient with a snapping high-frequency edge;
    the trains group into bursts so the fire crackles rather than hisses.
    """
    dur = n / sr
    body = shaped_noise(n, sr, gain_band(60, 300, -0.30), rng)
    body *= (0.85 + 0.15 * lfo(n, sr, 5, 2.0)).astype(np.float32)
    out = stereo_field(body, sr, rng, itd_ms=0.4, width=0.6, corner=160.0)

    # crackle trains: 8-20 events per second, grouped into bursts
    stereo = np.zeros((n, 2), dtype=np.float32)
    t = 0.0
    while t < dur:
        train_len = float(rng.uniform(0.08, 0.45))
        rate = float(rng.uniform(8.0, 20.0))
        count = max(1, int(rng.poisson(rate * train_len)))
        arrivals = np.cumsum(rng.exponential(1.0 / rate, size=count))
        times = (t + np.minimum(arrivals, train_len)) % dur
        bank = burst_bank(sr, rng, 48, 1200.0, 6000.0, 0.024, attack=0.0006,
                          resonance=0.35, decay=0.22, tilt=-0.25)
        gains = (rng.uniform(0.5, 1.5, times.size) * 0.75).astype(np.float32)
        pans = random_pan(rng, times.size, 0.85)
        scatter_bank(stereo, bank, (times * sr).astype(np.int64), gains, pans, rng)
        t += train_len + float(rng.uniform(0.05, 0.60))
    out += stereo

    # low body of the flame: gentle 40-120 Hz movement
    low = shaped_noise(n, sr, gain_band(38, 120, -0.4), rng) * 0.45
    out += stereo_field(low, sr, rng, itd_ms=0.3, width=0.3, corner=100.0)

    out = rebalance(out, sr, [
        (0.52, 0.0, 200.0),        # the warm 60-300 Hz body
        (0.22, 200.0, 1000.0),     # the transition band
        (0.26, 1000.0, 11000.0),   # the crackle field
    ])
    out = _room(out, sr, rng, wet=0.22, dur=1.4, predelay=0.018, damp=0.45)
    return out


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
    """Forest: a low leaf-and-air bed with 2-8 kHz insect events."""
    dur = n / sr
    t = np.arange(n) / sr
    bed = shaped_noise(n, sr, gain_band(30, 700, -0.4), rng) * 0.12
    bed *= (0.55 + 0.45 * (0.5 + 0.5 * np.sin(TAU * 0.05 * t))).astype(np.float32)
    out = stereo_field(bed, sr, rng, itd_ms=0.5, width=0.85, corner=190.0)

    # insect events: the crickets used to sit on a fixed 0.5 / 0.625 / 0.8 s
    # period, which sounded like a machine; the period now drifts.
    for freq, period, pan, amp in ((4300, 0.5, 0.25, 0.20), (4700, 0.625, 0.7, 0.16),
                                   (3900, 0.8, 0.5, 0.14)):
        train, pan_i = cricket_train(sr, freq, 4, 0.012, 0.016, pan, amp)
        start = 0
        while start < n:
            add_circular(out, start, train, pan_i / 1000.0, 1.0)
            start += int(period * sr * float(rng.uniform(0.93, 1.07)))

    for pos, freq in ((6.5, 380.0), (21.0, 330.0), (33.0, 410.0)):
        length = int(0.9 * sr)
        tt = np.arange(length) / sr
        env = np.sin(np.linspace(0, np.pi, length)) ** 2
        hoot = np.sin(TAU * freq * tt + 0.6 * np.sin(TAU * 5.0 * tt)) * env
        add_circular(out, int(pos * sr), hoot.astype(np.float32), 0.35, 0.06)

    # sparse leaf rustle high in the canopy
    rustle_t = poisson_events(1.4, dur, rng) * sr
    bank = burst_bank(sr, rng, 64, 2000.0, 8000.0, 0.040, attack=0.006,
                      resonance=0.12, decay=0.28, tilt=-0.2)
    gains = rng.uniform(0.05, 0.16, len(rustle_t)).astype(np.float32)
    pans = random_pan(rng, len(rustle_t), 0.9)
    scatter_bank(out, bank, rustle_t.astype(np.int64), gains, pans, rng)

    out = rebalance(out, sr, [
        (0.55, 0.0, 200.0),
        (0.22, 200.0, 2000.0),
        (0.23, 2000.0, 11000.0),   # the insect events
    ])
    out = _room(out, sr, rng, wet=0.27, dur=2.0, predelay=0.040, damp=0.6)
    return out


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
    """Birds: sparse 2-6 kHz song phrases over a faint air bed."""
    dur = n / sr
    air = shaped_noise(n, sr, gain_band(40, 900, -0.35), rng) * 0.05
    out = stereo_field(air, sr, rng, itd_ms=0.4, width=0.7, corner=300.0)

    # a phrase is a few chirps close together; phrases are Poisson spaced
    phrases = poisson_events(0.55, dur, rng) * sr
    for p0 in phrases:
        count = int(rng.integers(2, 7))
        for _ in range(count):
            chirp = bird_chirp(sr, rng)
            pan = float(rng.uniform(0.1, 0.9))
            theta = pan * (np.pi / 2.0)
            g = float(rng.uniform(0.16, 0.34))
            idx = (np.arange(chirp.shape[0]) + int(p0) + int(rng.integers(0, int(0.2 * sr)))) % n
            out[idx, 0] += (chirp * np.cos(theta) * g).astype(np.float32)
            out[idx, 1] += (chirp * np.sin(theta) * g).astype(np.float32)

    out = rebalance(out, sr, [
        (0.30, 0.0, 200.0),
        (0.20, 200.0, 2000.0),
        (0.50, 2000.0, 11000.0),   # the 2-6 kHz song
    ])
    out = _room(out, sr, rng, wet=0.25, dur=1.9, predelay=0.035, damp=0.55)
    return out


def amb_drone(n: int, sr: int) -> np.ndarray:
    """Drone: a 40-300 Hz harmonic bed, loudness-matched to the other layers."""
    t = np.arange(n) / sr
    mono = np.zeros(n, dtype=np.float32)
    for freq, amp, cyc in ((55.0, 0.30, 1), (82.5, 0.18, 3), (110.0, 0.16, 2),
                           (165.0, 0.10, 5), (220.0, 0.06, 4), (330.0, 0.03, 7)):
        vib = 0.035 * np.sin(TAU * (cyc / LOOP) * t + amp * 10)
        wave = np.sin(TAU * freq * t + vib)
        trem = 0.65 + 0.35 * np.sin(TAU * (2 * cyc / LOOP) * t + amp * 20) ** 2
        mono += (wave * trem * amp).astype(np.float32)
    bed = shaped_noise(n, sr, gain_band(20, 500, -0.5), rng) * 0.08
    mono += bed
    out = stereo_field(mono, sr, rng, itd_ms=0.35, width=0.45, corner=140.0)
    out = rebalance(out, sr, [
        (0.74, 0.0, 200.0),
        (0.24, 200.0, 2000.0),
        (0.02, 2000.0, 11000.0),
    ])
    out = _room(out, sr, rng, wet=0.24, dur=2.2, predelay=0.045, damp=0.66)
    return out


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
    "amb_thunder": amb_thunder,
    "amb_wind": amb_wind,
    "amb_stream": amb_stream,
    "amb_ocean": amb_ocean,
    "amb_fire": amb_fire,
    "amb_forest": amb_forest,
    "amb_birds": amb_birds,
    "amb_drone": amb_drone,
}

# Checkpoint of the shared generator as it stood when the ambience loops were
# last rendered. Restored before the meditation tracks are built, so those
# assets stay byte-identical no matter what happens to the ambience code.
MEDIA_RNG_STATE = {
    "bit_generator": "PCG64",
    "has_uint32": 1,
    "state": {
        "inc": 162461770569406593203092421552682192259,
        "state": 119795251561713918416770145793633311323,
    },
    "uinteger": 1977067747,
}


def main(argv):
    os.makedirs(RAW, exist_ok=True)
    os.makedirs(WORK, exist_ok=True)
    which = argv[1] if len(argv) > 1 else "all"

    if which in ("all", "ambience"):
        print("ambience loops")
        n = int(LOOP * SR_A)
        for name, fn in AMBIENCE.items():
            # render a little extra so the equal-power crossfade can fold it
            # back onto the head, then normalise and cap the peak
            extra = int(CROSSFADE_S * SR_A)
            audio = fn(n + extra, SR_A)
            audio = crossfade_loop(audio, SR_A, CROSSFADE_S)
            audio = finalise(audio, SR_A)
            publish(name, audio, SR_A, 4)
        sfx()

    if which in ("all", "meditation"):
        print("meditation tracks")
        rng.bit_generator.state = MEDIA_RNG_STATE
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
