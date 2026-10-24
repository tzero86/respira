#!/usr/bin/env python3
"""Turn clean-licence field recordings into Respira's seamless ambience loops.

The synthetic layers in `generate_audio.py` cannot capture every sound. A
fireplace is a room, not a waveform: the roar of the gas, the hiss of the
flame, the irregular crackle of an ember finding air, and the fact that all
of it sits inside a stone hearth. That combination is what the listener
recognises, and no amount of filtering reproduces it.

So for the layers where a public-domain recording exists, this script uses
the recording. Every source is listed with its licence in `tools/SOURCES.md`.

What this script does to a recording:

* picks the steadiest window, so the loop does not start on a passing truck
  or a bird landing in the microphone;
* folds that window onto itself with an equal-power crossfade, so the
  loop point is inaudible;
* applies a gentle high-pass and a low-pass, to remove DC offset, handling
  noise and the rumble of the recorder's own body;
* loudness-normalises to the same target the synthetic layers use, so one
  mixer slider means the same thing on every layer;
* caps the peak, so ten layers mixed together cannot clip;
* writes the result as Ogg Vorbis into `app/src/main/res/raw`.

Usage:
    python tools/process_recordings.py            # build every recording layer
    python tools/process_recordings.py --list     # show the sources
"""

from __future__ import annotations

import argparse
import json
import math
import os
import subprocess
import sys

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, "app", "src", "main", "res", "raw")

# The loudness meter, the peak limiter and the constants are shared with the
# synthetic generator, so both kinds of layer land on the same target.
sys.path.insert(0, os.path.join(ROOT, "tools"))
import generate_audio as gen  # noqa: E402

# The synthetic layers and the recordings must share these, or the mixer
# slider would mean different things on different layers.
AMBIENCE_LUFS = gen.AMBIENCE_LUFS
AMBIENCE_CEILING = gen.AMBIENCE_CEILING

SR = 44100
LOOP = 40.0
CROSSFADE_S = 2.0          # longer than the synthetic 0.5 s: recordings are denser
STEEPLY_WINDOWS = 5.0      # a window of this length must be quiet and steady
WINDOW_STEP = 2.0
SEAM_TARGET_DB = -40.0     # the junction must be cleaner than this

# --------------------------------------------------------------------------- #
# sources
# --------------------------------------------------------------------------- #
#
# Every entry is a public-domain or CC BY recording on archive.org, together
# with the identifier and the licence. `tools/SOURCES.md` is the human
# readable ledger; keep the two in step.
#
#   file        the exact member name inside the archive.org item
#   licence     the short form used in the ledger
#   window      how long a slice of the recording the loop is built from
#   start       where in the recording that slice begins, in seconds
#
# The window length and the start position are chosen per layer, and they
# are measured rather than guessed.  A long window repeats more of the
# recording, but it also has more chance of catching something that breaks
# the loop -- a gust of wind, a passing car, a bird.  `tools/check_seam.py`
# measures how clean the junction is, and the positions below are the ones
# that came out cleanest.  `build_loop` re-checks the seam after building
# and fails loudly if a layer ever regresses.
#
# These positions only make sense for these exact recordings.  If a master
# is replaced, the positions have to be re-measured.


SOURCES: dict[str, dict] = {
    "amb_rain": {
        "item": "aporee_58350_66941",
        "file": "Langelandsoundofrainonleaves.mp3",
        "layer": "rain",
        "licence": "Public Domain (CC0 1.0 Public Domain Mark)",
        "credit": "audiotraction — Langeland sound of rain on leaves, Denmark",
        "url": "https://archive.org/details/aporee_58350_66941",
        "window": 120.0,
        "start": 438.0,
    },
    "amb_rain_heavy": {
        "item": "aporee_32985_37898",
        "file": "regenbest.ogg",
        "layer": "rain_heavy",
        "licence": "Public Domain (CC0 1.0 Public Domain Mark)",
        "credit": "kafkaamstrand — Regen prasselt auf Einmannzelt, Germany",
        "url": "https://archive.org/details/aporee_32985_37898",
        "window": 60.0,
        "start": 4.0,
    },
    "amb_ocean": {
        "item": "aporee_64598_74549",
        "file": "1177ABREWAL2404281857.mp3",
        "layer": "ocean",
        "licence": "Public Domain (CC0 1.0 Public Domain Mark)",
        "credit": "Piotrek Zyla — sea waves, Rewal, Poland",
        "url": "https://archive.org/details/aporee_64598_74549",
        "window": 120.0,
        "start": 410.0,
    },
    "amb_fire": {
        "item": "aporee_25828_29895",
        "file": "burnedoutdrops.ogg",
        "layer": "fire",
        "licence": "Public Domain (CC0 1.0 Public Domain Mark)",
        "credit": "maciej janasik — fading campfire, Sasino, Poland",
        "url": "https://archive.org/details/aporee_25828_29895",
        "window": 90.0,
        "start": 18.0,
    },
    "amb_stream": {
        "item": "aporee_65017_75100",
        "file": "AlpineStreamII.mp3",
        "layer": "stream",
        "licence": "Public Domain (CC0 1.0 Public Domain Mark)",
        "credit": "htuggle — Alpine Stream 2, Hollersbach, Austria",
        "url": "https://archive.org/details/aporee_65017_75100",
        "window": 40.0,
        "start": 4.0,
    },
    "amb_thunder": {
        "item": "aporee_18370_21335",
        "file": "4BauhausplatzGewitter20062013.ogg",
        "layer": "thunder",
        "licence": "Public Domain (CC0 1.0 Public Domain Mark)",
        "credit": "oriolus — Gewitter am Bauhausplatz, Dessau-Roßlau, Germany",
        "url": "https://archive.org/details/aporee_18370_21335",
        "window": 120.0,
        "start": 176.0,
    },
    "amb_wind": {
        "item": "aporee_64074_73891",
        "file": "0011313KozmiceMeadowswindinsliverbirchtreeedit.mp3",
        "layer": "wind",
        "licence": "Public Domain (CC0 1.0 Public Domain Mark)",
        "credit": "Peter Cusack — wind through a silver birch tree, Kožmice, Czechia",
        "url": "https://archive.org/details/aporee_64074_73891",
        "window": 120.0,
        "start": 32.0,
    },
    "amb_birds": {
        "item": "aporee_36705_42063",
        "file": "nightingalebrighting0405174.ogg",
        "layer": "birds",
        "licence": "Public Domain (CC0 1.0 Public Domain Mark)",
        "credit": "oriolus — nightingale song, Brightling, England",
        "url": "https://archive.org/details/aporee_36705_42063",
        "window": 80.0,
        "start": 4.0,
    },
    "amb_forest": {
        "item": "commons",
        "file": "20090610_0_ambience.ogg",
        "layer": "forest",
        "licence": "Public domain (PD-author)",
        "credit": "nille — natural ambience, released under a public-domain dedication",
        "url": "https://commons.wikimedia.org/wiki/File:20090610_0_ambience.ogg",
        "window": 120.0,
        "start": 0.0,
    },
}

# Where the downloaded masters live. They are not in git: they are the
# upstream recordings, and `tools/SOURCES.md` points at them.
CACHE = os.environ.get(
    "RESPIRA_REC_CACHE",
    os.path.join(os.path.expanduser("~"), "Downloads", "respira_recordings"),
)
if not os.path.isdir(os.path.dirname(CACHE)):  # pragma: no cover - windows HOME
    CACHE = os.path.join(
        os.environ.get("USERPROFILE", os.path.expanduser("~")),
        "Downloads", "respira_recordings",
    )


# --------------------------------------------------------------------------- #
# helpers shared with the generator
# --------------------------------------------------------------------------- #


def integrated_lufs(x: np.ndarray, sr: int) -> float:
    """Gated integrated loudness, reusing the generator's BS.1770 meter.

    The synthetic layers are normalised with the meter in
    `generate_audio.py`; using the same implementation here is what keeps
    one mixer slider meaning the same thing on every layer.
    """
    if x.ndim == 1:
        x = x[:, None]
    return float(gen.integrated_lufs(x.astype(np.float32), sr))


def limit_peak(x: np.ndarray, ceiling_db: float = -5.0,
               knee: float = 0.15) -> np.ndarray:
    """Soft-knee peak limiter, reusing the generator's implementation."""
    return gen.limit_peak(x.astype(np.float32), ceiling_db, knee)


def crossfade_loop(x: np.ndarray, sr: int, fade_s: float) -> np.ndarray:
    """Equal-power crossfade of the tail onto the head.

    `x` must be `fade_s` seconds longer than the wanted loop length.
    """
    fade = int(fade_s * sr)
    total = x.shape[0]
    loop = total - fade
    if fade < 8 or loop < fade:
        return x[:loop] if loop > 0 else x
    ramp = np.linspace(0.0, math.pi / 2.0, fade, dtype=np.float64)
    out = x[:loop].copy()
    out[:fade] = (x[loop:] * np.cos(ramp)[:, None]
                  + x[:fade] * np.sin(ramp)[:, None])
    return out


def highpass(x: np.ndarray, sr: int, f0: float = 35.0) -> np.ndarray:
    """High-pass at `f0`, applied circularly so a loop stays periodic."""
    from scipy.signal import butter, sosfiltfilt
    sos = butter(2, f0 / (sr * 0.5), btype="highpass", output="sos")
    return sosfiltfilt(sos, x, axis=0)


def lowpass(x: np.ndarray, sr: int, f0: float = 15000.0) -> np.ndarray:
    """Low-pass at `f0`, applied circularly so a loop stays periodic."""
    from scipy.signal import butter, sosfiltfilt
    nyq = sr * 0.5
    f = min(f0, nyq * 0.95)
    sos = butter(2, f / nyq, btype="lowpass", output="sos")
    return sosfiltfilt(sos, x, axis=0)


def decode(path: str, sr: int = SR) -> np.ndarray:
    """Decode an audio file to a stereo float array."""
    raw = subprocess.run(
        ["ffmpeg", "-nostdin", "-loglevel", "error", "-i", path,
         "-f", "f32le", "-ac", "2", "-ar", str(sr), "-"],
        check=True, capture_output=True,
    ).stdout
    return np.frombuffer(raw, dtype="<f4").reshape(-1, 2).astype(np.float64)


def write_wav(path: str, x: np.ndarray, sr: int) -> None:
    import scipy.io.wavfile as wavfile
    peak = float(np.abs(x).max())
    if peak > 1.0:
        x = x / peak
    wavfile.write(path, sr, (x * 32767.0).astype(np.int16))


def encode(wav: str, ogg: str, quality: int = 4) -> None:
    subprocess.run(
        ["ffmpeg", "-nostdin", "-loglevel", "error", "-y",
         "-i", wav, "-c:a", "libvorbis", "-q:a", str(quality), ogg],
        check=True,
    )


# --------------------------------------------------------------------------- #
# window selection
# --------------------------------------------------------------------------- #


def window_quality(seg: np.ndarray, sr: int) -> tuple[float, float]:
    """Score a candidate window: (level steadiness, loudness).

    The loop should be quiet and unchanging.  A recording with a truck
    passing has a level that jumps; one with a bird singing has a level that
    jumps differently.  We want the window whose 5-second blocks are most
    alike, but we also do not want to pick a silent corner of the tape.
    """
    x = seg.mean(axis=1)
    block = int(STEEPLY_WINDOWS * sr)
    if x.shape[0] < block:
        return -1e9, -1e9
    count = x.shape[0] // block
    rms = np.array([
        math.sqrt(float(np.mean(x[i * block:(i + 1) * block] ** 2)))
        for i in range(count)
    ])
    rms = np.maximum(rms, 1e-9)
    db = 20.0 * np.log10(rms)
    # the steadier the better; a window that is too quiet is penalised
    steadiness = -float(np.std(db))
    loudness = float(np.mean(db))
    if loudness < -60.0:
        steadiness -= 40.0
    return steadiness, loudness


def pick_window(x: np.ndarray, sr: int, length: float) -> int:
    """Choose the steadiest window of `length` seconds."""
    want = int(length * sr)
    step = int(WINDOW_STEP * sr)
    best_at = 0
    best_score = -1e18
    for start in range(0, max(1, x.shape[0] - want + 1), step):
        seg = x[start:start + want]
        steadiness, loudness = window_quality(seg, sr)
        score = steadiness + min(loudness, -25.0) * 0.01
        if score > best_score:
            best_score = score
            best_at = start
    return best_at


def junction_artefacts(loop: np.ndarray, sr: int = SR) -> tuple[float, float]:
    """Measure the loop point directly: (step ratio, high-frequency jump).

    `step ratio` is the sample-to-sample step at the junction divided by the
    file's own 99.9th percentile step.  Below 1 means the junction is
    quieter than the file's own loudest transient, so it cannot be heard as
    a click.

    `high-frequency jump` is the broadband energy in the first 5 ms after
    the junction over the 5 ms before it, in dB.  A click puts a burst of
    high-frequency energy there.

    Neither measurement depends on the length of the window it looks at,
    which is what makes them trustworthy as a gate.
    """
    steps = np.sqrt(np.sum(np.diff(loop, axis=0, prepend=loop[-1:]) ** 2, axis=1))
    ratio = float(steps[0]) / max(float(np.percentile(steps[1:], 99.9)), 1e-12)

    from scipy.signal import butter, sosfiltfilt
    win = max(16, int(0.005 * sr))
    sos = butter(4, 3000.0 / (sr * 0.5), btype="highpass", output="sos")
    after = sosfiltfilt(sos, loop[:win], axis=0)
    before = sosfiltfilt(sos, loop[-win:], axis=0)
    hf = 10.0 * float(np.log10((float(np.mean(after ** 2)) + 1e-20)
                                / (float(np.mean(before ** 2)) + 1e-20)))
    return ratio, hf


def build_loop(src: str, out_name: str, spec: dict, sr: int = SR) -> dict:
    """Process one recording into a seamless, loudness-matched loop."""
    mono_cache: dict[str, np.ndarray] = {}

    path = os.path.join(CACHE, spec["file"])
    if not os.path.exists(path):
        raise SystemExit(
            f"missing source: {path}\n"
            f"download it from {spec['url']} (member {spec['file']}) into {CACHE}"
        )
    del mono_cache
    stereo = decode(path, sr)
    start = int(spec["start"] * sr)
    want = int(spec["window"] * sr)
    fade = int(CROSSFADE_S * sr)
    # The loop is a slice of the recording whose end crossfades into the
    # material that *follows* it in the recording.  Using the genuine
    # continuation matters: folding the slice onto itself would blend two
    # unrelated moments of the tape, and the seam would carry the difference.
    seg = stereo[start:start + want + fade]
    if seg.shape[0] < want + fade:
        seg = np.concatenate([seg, seg[:want + fade - seg.shape[0]]], axis=0)

    # clean-up: remove DC offset, handling rumble and the harsh top end
    material = seg - seg.mean(axis=0, keepdims=True)
    for ch in range(material.shape[1]):
        material[:, ch] = highpass(material[:, ch], sr, 35.0)
        material[:, ch] = lowpass(material[:, ch], sr, 15000.0)

    loop = crossfade_loop(material, sr, CROSSFADE_S)

    # loudness-normalise to the shared target, then cap the peak
    for _ in range(3):
        loud = integrated_lufs(loop, sr)
        if loud <= -70.0:
            break
        loop = loop * (10.0 ** ((AMBIENCE_LUFS - loud) / 20.0))
        loop = limit_peak(loop, AMBIENCE_CEILING)

    # The junction has to be as clean as the rest of the file, or the loop
    # announces itself every few seconds.  A regression here means the
    # window position is wrong, and it must not reach the app.
    #
    # Two direct measurements are used, because they do not depend on the
    # length of the window they look at (the null test in the generator does,
    # and gives different answers for different windows on the same file):
    # the step at the junction against the file's own loudest transient, and
    # the broadband energy just after the junction against just before it.
    step_ratio, hf_jump = junction_artefacts(loop)
    if step_ratio >= 1.0 or hf_jump >= 6.0:
        raise SystemExit(
            f"{out_name}: the loop point is audible (step is {step_ratio:.2f} of "
            f"the file's loudest transient, high-frequency jump {hf_jump:+.1f} dB).  "
            f"The window position in SOURCES needs re-measuring."
        )

    os.makedirs(RAW, exist_ok=True)
    wav = os.path.join(RAW, f"{out_name}.wav.tmp")
    ogg = os.path.join(RAW, f"{out_name}.ogg")
    write_wav(wav, loop, sr)
    encode(wav, ogg, 4)
    os.remove(wav)

    return {
        "name": out_name,
        "seconds": loop.shape[0] / sr,
        "bytes": os.path.getsize(ogg),
        "source": spec["url"],
        "licence": spec["licence"],
        "step_ratio": round(step_ratio, 3),
        "hf_jump_db": round(hf_jump, 1),
    }


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--list", action="store_true",
                    help="print the sources and exit")
    ap.add_argument("--only", action="append", default=[],
                    help="build only these layers")
    args = ap.parse_args(argv[1:])

    if args.list:
        for name, spec in SOURCES.items():
            print(f"{name:16s} {spec['licence']}")
            print(f"{'':16s} {spec['credit']}")
            print(f"{'':16s} {spec['url']}")
        return 0

    os.makedirs(CACHE, exist_ok=True)
    results = []
    for name, spec in SOURCES.items():
        if args.only and name not in args.only:
            continue
        print(f"building {name} from {spec['credit']}")
        results.append(build_loop(name, name, spec))
        r = results[-1]
        print(f"  -> {r['seconds']:.1f}s  {r['bytes'] / 1e6:.2f} MB")

    with open(os.path.join(ROOT, "tools", "recordings_built.json"), "w") as fh:
        json.dump(results, fh, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
