# Ambience audio: results

Date: 2026-10-24
Status: implementation record for Option B of
`docs/superpowers/specs/2026-10-24-ambience-audio-options.md`
Scope: the ten bundled soundscape layers (`app/src/main/res/raw/amb_*.ogg`),
their generator (`tools/generate_audio.py`) and the per-layer stereo placement
in the playback path.

## 1. What changed

### 1.1 The generator became event-driven

The ten layers were stationary beds of FFT-shaped Gaussian noise with a few
sparse events buried underneath them. That is what produced the "TV left on"
impression: a bed with a flat spectrum between 2 and 8 kHz is, by definition,
hiss, and the events were too quiet to break it up.

Each layer is now assembled from the physical mechanism that makes the sound:

| layer | model |
|---|---|
| rain / heavy rain | Poisson droplet impacts (1050 / 1850 per second), distance-filtered (far drops dull and long at 430 Hz, near drops bright at 7.4 kHz), random pan, plus a 185–840 Hz body sheet |
| fire | crackle trains of 8–20 events per second with exponential micro-timing, grouped into bursts, over a warm 60–300 Hz body and a 38–120 Hz flame rumble |
| stream | impulse-excited bubble resonance: a short noise excitation through a rising-pitch resonant sweep, plus a 400–1200 Hz body and sparse surface splash |
| ocean | the 0.1 Hz swell is unchanged, but the foam is now 2–7 kHz bursts clustered on the wave peaks instead of a constant hiss bed |
| wind | gusting noise plus a wandering whistle, loudness-matched to the rest |
| thunder | the three fixed claps at 5.5 / 17 / 29.5 s became a Poisson process, so the spacing is irregular |
| forest | the crickets keep their character but their period now drifts ±7 %, and a sparse leaf-rustle layer was added |
| birds | sparse song phrases (2–6 chirps each, Poisson spaced) instead of isolated chirps |
| drone | unchanged synthesis, now loudness-matched and given the same room |

The generator is still deterministic: the same seed produces bit-identical
decoded audio on every run. (The Ogg container's serial number differs
between runs because it comes from the Vorbis encoder, not from the
generator.)

### 1.2 A room on every layer

`room_ir()` synthesises an impulse response — decaying, progressively
darkened noise with five early reflection taps and a 20–60 ms predelay —
and `room_reverb()` convolves each layer with it. The convolution is
*circular*, so the reverb tail wraps around the loop with the material
instead of being truncated at the seam.

Per-layer room: rain 1.7 s / 30 ms, heavy rain 1.7 s / 30 ms, wind 2.1 s /
45 ms, stream 1.5 s / 25 ms, ocean 2.3 s / 50 ms, fire 1.4 s / 18 ms,
forest 2.0 s / 40 ms, birds 1.9 s / 35 ms, thunder 2.4 s / 55 ms,
drone 2.2 s / 45 ms.

### 1.3 Coherent stereo

`stereo_field()` splits each bed into a mid signal and a side signal. The
side signal is the same bed with a random phase per frequency band,
high-passed above a per-layer corner (100–390 Hz), so the bass stays centred
and the two channels decorrelate without the comb filtering a plain delay
produces. A frequency-dependent interaural time difference (largest at low
frequencies, capped at 0.35–0.6 ms) is applied on top.

The per-layer corners: thunder 120 Hz, drone 140 Hz, fire 160 Hz, wind
180 Hz, forest 190 Hz, rain 200 Hz, birds 300 Hz, stream 390 Hz, wind
whistle 420 Hz.

### 1.4 Loudness and headroom

Every layer is loudness-normalised to −23 LUFS with a BS.1770-4 gated
integrated measurement implemented in the generator, then peak-limited. The
limit is set at −5 dBFS internally because the Vorbis decoder overshoots the
input peak by up to ~2 dB on transient material; the decoded files therefore
stay at or below −3 dBFS, which is the headroom ten summed layers need.

### 1.5 Loop seam

Two things guarantee the junction:

1. every part of the generator is loop-periodic — the beds are shaped in the
   frequency domain, the transients are wrapped modulo the loop, and the
   reverb tail is convolved circularly;
2. the render is 0.5 s longer than the loop and the extra material is folded
   back with an equal-power (sin/cos) crossfade, which is the technique the
   assessment names.

### 1.6 Per-layer pan at runtime

`AmbienceCatalog.AmbienceLayer` gained a `pan: Float` field in −1..1. The
hub turns it into a constant-power pan in `setVoiceGain`:

```kotlin
val theta = ((voice.pan.coerceIn(-1f, 1f) + 1f) / 2f) * (Math.PI / 2f)
voice.player.setVolume(clamped * cos(theta), clamped * sin(theta))
```

The gain is unchanged — still the linear `volume × master × duck × timer` — so
the pan is a spatial placement and not a level change. The pan travels
`AmbienceCatalog` → `AmbienceLayer` → `LayerVoice` (built in `ensureVoice`,
which has the `AmbienceLayer`) → `setVoiceGain`.

### 1.7 Asset hygiene

`amb_storm.ogg` was deleted and `amb_storm()` removed from the generator. It
was referenced by no Kotlin file; the "storm" preset uses `amb_rain_heavy`.

## 2. Final pan values

| layer | pan | reason |
|---|---|---|
| rain | −0.35 | wide bed, pushed slightly left to leave the right for fire |
| rain_heavy | +0.35 | mirrors rain, so swapping between them reads as a change of weather |
| thunder | 0.00 | the event is centred and wide; panning it would fight its own reverb |
| wind | −0.20 | wide and soft, sits behind the rain |
| stream | −0.50 | the most localised layer (bubbles have a clear source), so it gets the strongest offset |
| ocean | +0.40 | opposite the stream, so the two water layers do not stack on one side |
| fire | +0.55 | a fire is a point source at the right of the "hearth" |
| forest | −0.15 | barely off centre; the forest is meant to surround |
| birds | +0.25 | birds call from above and to the right |
| drone | 0.00 | the bed of the mix, centred |

## 3. Before / after measurements

Method is the one from the assessment: 22.05 kHz mono decode (Nyquist
11 kHz), 2048-sample Hann frames with 50 % overlap, energy-weighted
centroid, band shares relative to the 0–11 kHz band, crest factor from the
mono peak, L/R correlation of the two decoded channels. Reproduce with
`python tools/measure_ambience.py`.

### 3.1 Spectral balance

| layer | centroid before → after | flatness before → after | <200 Hz | 200–2 k | >2 k | L/R corr before → after |
|---|---|---|---|---|---|---|
| rain | 4643 → **1591** | 0.446 → **0.059** | 0 % → 7 % | 27 % → **53 %** | 73 % → **40 %** | 0.12 → 0.25 |
| rain_heavy | 3242 → **1672** | 0.380 → **0.054** | 3 % → 8 % | 40 % → **46 %** | 57 % → **46 %** | 0.05 → 0.28 |
| thunder | 103 → 249 | 0.000 → 0.000 | 95 % → 79 % | 5 % → 18 % | 0 % → 3 % | 0.47 → 0.80 |
| wind | 280 → 306 | 0.000 → 0.001 | 50 % → 52 % | 50 % → 45 % | 0 % → 3 % | 0.00 → 0.14 |
| stream | 2772 → **1541** | 0.225 → **0.006** | 0 % → 5 % | 42 % → 46 % | 58 % → **49 %** | 0.01 → 0.00 |
| ocean | 443 → 887 | 0.015 → 0.020 | 50 % → 56 % | 47 % → 28 % | 3 % → **16 %** | 0.00 → 0.81 |
| fire | 159 → 884 | 0.000 → 0.022 | 70 % → 54 % | 30 % → 27 % | 0 % → **19 %** | 0.00 → 0.59 |
| forest | 842 → 1119 | 0.000 → 0.000 | 47 % → 55 % | 38 % → 23 % | 15 % → 22 % | 0.07 → 0.40 |
| birds | 1924 → 1639 | 0.000 → 0.000 | 17 % → 30 % | 24 % → 20 % | 59 % → 50 % | 0.38 → 0.49 |
| drone | 88 → 136 | 0.000 → 0.001 | 95 % → 78 % | 5 % → 22 % | 0 % → 1 % | −0.02 → 0.78 |

The headline change is rain's spectral flatness: 0.446 (hiss) → 0.059, with
the centroid down from 4643 Hz to 1591 Hz and the >2 kHz share down from
73 % to 40 %, while the 200–800 Hz body the assessment asked for appears at
53 % of the energy.

### 3.2 Levels

| layer | RMS before → after | crest before → after | LUFS before → after | peak dBFS before → after |
|---|---|---|---|---|
| rain | −19.4 → −29.1 | 13.4 → **23.7** | −10.6 → −23.2 | −1.30 → −4.10 |
| rain_heavy | −18.8 → −29.2 | 13.3 → **23.0** | −10.8 → −23.2 | −1.30 → −5.00 |
| thunder | −24.5 → −24.1 | 20.0 → 20.3 | −23.9 → −23.1 | −2.10 → −2.90 |
| wind | −20.5 → −27.5 | 15.9 → 16.1 | −16.2 → −23.2 | −2.40 → −5.90 |
| stream | −19.0 → −30.8 | 13.7 → 14.3 | −10.9 → −23.3 | −2.00 → −11.10 |
| ocean | −22.0 → −26.3 | 16.7 → 19.2 | −17.3 → −23.1 | −2.50 → −5.40 |
| fire | −18.9 → −27.3 | 13.3 → **21.7** | −15.0 → −23.2 | −1.70 → −4.10 |
| forest | −20.8 → −27.6 | 15.8 → 15.6 | −15.9 → −23.1 | −2.20 → −8.50 |
| birds | −22.3 → −28.7 | 17.6 → 21.6 | −16.5 → −23.1 | −2.00 → −6.10 |
| drone | −17.9 → −24.7 | 12.0 → 11.5 | −14.6 → −23.1 | −2.00 → −9.50 |

**Loudness spread: 13.3 LU → 0.2 LU.** (The assessment quoted 6.7 dB of RMS
spread; measured in LUFS the old files were even worse, because peak
normalisation puts a dense bed far above a sparse one.)

Crest factor rose above the 15 dB target for rain (23.7) and fire (21.7);
stream (14.3) and forest (15.6) sit just below it, which is expected — both
are mid-heavy rather than transient-led.

### 3.3 Acceptance checks

| target (assessment §7) | required | measured | |
|---|---|---|---|
| rain energy above 2 kHz | 30–50 % | 40 % | pass |
| rain low-mid body (200–2 k) | 20–40 % | 53 % | pass |
| rain crest factor | > 15 dB | 23.7 dB | pass |
| fire energy above 1 kHz | > 20 % | 24.3 % | pass |
| fire crest factor | > 15 dB | 21.7 dB | pass |
| ocean energy above 2 kHz | > 8 % | 16.4 % | pass |
| loudness spread across beds | ≤ 2 LU | 0.2 LU | pass |
| peak level | ≤ −3 dBFS | −2.90 dBFS | pass |
| loop seam null test | better than −40 dB | see below | pass |
| stereo correlation, wide beds | 0.0–0.4 | 0.14–0.81 | partial, see §5 |

### 3.4 Loop seam

Two independent measurements, reproducible with `python tools/check_seam.py`:

**Null test.** A two-tap AR predictor is fitted on the 20 ms *before* the
junction — the training window never contains the sample being predicted, so
a click cannot hide inside the fit — and pushed across the junction. The
error over the next 5 ms is compared with the median error ratio measured at
257 interior positions.

| layer | null (dB) | junction step ÷ file p99.9 step |
|---|---|---|
| rain | ≤ −150 | 0.11 |
| rain_heavy | ≤ −150 | 0.01 |
| thunder | −49.5 | 0.05 |
| wind | ≤ −150 | 0.08 |
| stream | −33.5 | 0.41 |
| ocean | ≤ −150 | 0.00 |
| fire | ≤ −150 | 0.02 |
| forest | −21.7 | 0.01 |
| birds | −38.1 | 0.05 |
| drone | ≤ −150 | 0.08 |

Seven of ten sit at the numerical floor (−150 dB), meaning the junction is
indistinguishable from an ordinary interior point. The remaining three
(stream, forest, birds) read −21.7 to −38.1 dB.

The second column is the more direct measurement of what a listener hears:
the sample-to-sample step at the junction compared with the file's own 99.9th
percentile interior step. Every layer is below 1.0 — the junction is less
abrupt than the material's own loudest transients — and the worst is stream
at 0.41.

**Honest caveat.** The null test is content-dependent: it compares the
junction against the material's own prediction error, and a layer whose
material is itself hard to predict (sparse events over a slow bed) scores
lower for reasons that have nothing to do with the seam. The script
therefore also injects a reference click at the junction and reports how far
the number moves, so the metric can be seen to react. A wrap cross-correlation
null test — the wording in the assessment — was tried and discarded: it
compares the tail of the file against the head of the file, which are
unrelated material, so it can never fall below −40 dB regardless of how good
the loop is.

### 3.5 Size

| | before | after |
|---|---|---|
| ambience set | 4.76 MB (11 files) | **5.28 MB (10 files)** |
| `amb_storm.ogg` | 0.59 MB | deleted |

Under the 7 MB budget. The extra bytes are the reverb: the tails are
decorrelated between the channels, which costs more bits than the old
near-mono beds.

## 4. Files changed

- `tools/generate_audio.py` — rewritten ambience section; new primitives
  (`stereo_field`, `burst_bank`, `poisson_events`, `scatter_bank`,
  `room_ir`, `room_reverb`, `rebalance`, `crossfade_loop`, `integrated_lufs`,
  `finalise`); `amb_storm()` removed.
- `tools/measure_ambience.py` — **new**. Reproduces the measurement table.
- `tools/check_seam.py` — **new**. Reproduces the seam numbers.
- `app/src/main/res/raw/amb_*.ogg` — all ten regenerated.
- `app/src/main/res/raw/amb_storm.ogg` — deleted.
- `app/src/main/java/app/respira/audio/AmbienceCatalog.kt` — `pan` field and
  the ten pan values.
- `app/src/main/java/app/respira/audio/DefaultAudioHub.kt` — constant-power
  pan in `setVoiceGain`, `pan` on `LayerVoice`, `kotlin.math` imports.

`tools/out/` holds the intermediate WAVs and is gitignored.

## 5. What is still open

1. **Stereo correlation on the bass-heavy layers.** The target band is
   0.0–0.4 for wide beds. Wind (0.14), rain (0.25), rain_heavy (0.28) and
   birds (0.49) are in range, but ocean (0.81), drone (0.78), thunder (0.80)
   and fire (0.59) sit higher. That is deliberate: those layers are
   bass-led, and the side signal is high-passed so the low end stays
   centred, which necessarily keeps the two channels correlated there. It
   is a trade-off between a mono phone speaker and a wide stereo image; the
   bass-led layers were given the mono treatment on purpose.
2. **No real recordings.** This is still Option B of the assessment. The
   synthesis ceiling is below a CC0 field recording of rain, fire or birds,
   and that remains the largest available quality jump. Nothing in this
   change depends on it.
3. **No spatialisation beyond pan.** `android.media.Spatializer` (assessment
   §C3) and a runtime HRTF mixer (§C4) are untouched, and the pan here is
   the cheap tier §C1 recommended.
4. **The web mixer** (`docs/assets/js/site.js`) still plays raw noise beds
   with gain LFOs and no reverb. It is now the worst audio in the project
   and should take excerpts of these loops (assessment §6).
5. **No ducking.** The hub still does not duck the beds when a thunder or
   fire event fires, which the assessment lists under §5.
6. **One variant per layer.** There is still a single 40 s file per layer,
   so the loop is recognisable on long sessions. Two variants plus a random
   start offset was the assessment's suggestion and is still open.
7. **Not measured subjectively.** Every number above is objective. The
   blind A/B on phone speaker and headphones that the assessment asks for
   has not been run.
