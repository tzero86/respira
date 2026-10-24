# Ambience audio: quality and spatialisation options

Date: 2026-10-24
Status: assessment only — no code or assets changed
Scope: the ten soundscape layers in the Android app (`AmbienceCatalog` →
`DefaultAudioHub`, one `MediaPlayer` per layer) and the mixer demo on the
landing page (`docs/assets/js/site.js`, Web Audio).

## TL;DR

1. The "TV left on" impression is structural, not a mastering problem. Every
   layer is stationary band-shaped noise. Measured on the bundled files, rain
   puts **73 % of its energy above 2 kHz with a spectral flatness of 0.44** — that
   is hiss by definition. Fire has **0 % above 2 kHz** (it is a 159 Hz rumble),
   ocean has almost no foam, and there is no reverb, no distance and no movement
   anywhere.
2. The biggest jump per day of work is **replacing the five texture layers with
   real field recordings** (CC0), keeping the synth for wind and drone, and
   adding **per-layer pan** — which the current playback path already supports
   through `MediaPlayer.setVolume(left, right)`.
3. **Spatial audio is worth a small amount, not a rebuild.** Per-layer pan plus
   a baked room is cheap and works everywhere; Android's `Spatializer` (API 32+)
   is route-dependent and spatialises the mix, not the layers. A full HRTF mixer
   is weeks of work for a bedside app that mostly plays on a phone speaker.

## 1. What ships today, measured

Files: `app/src/main/res/raw/amb_*.ogg`, Vorbis, 44.1 kHz stereo, 40.0 s,
~128 kbps, 265–607 KB each (4.6 MB total). Peak-normalised to 0.8–0.85,
`MediaPlayer` looping per layer, gain applied as `volume × master × duck ×
timer` (linear, no master bus, no limiter).

| layer | RMS dBFS | crest dB | centroid Hz | flatness | <200 Hz | 200–2k | >2k | L/R corr |
|---|---|---|---|---|---|---|---|---|
| rain | −19.4 | 13.4 | 4643 | 0.439 | 0 % | 27 % | 73 % | 0.12 |
| rain_heavy | −18.8 | 13.3 | 3242 | 0.377 | 3 % | 40 % | 57 % | 0.05 |
| thunder | −24.5 | 20.0 | 103 | 0.000 | 95 % | 5 % | 0 % | 0.47 |
| wind | −20.5 | 15.9 | 280 | 0.000 | 50 % | 50 % | 0 % | 0.00 |
| stream | −19.0 | 13.7 | 2772 | 0.222 | 0 % | 42 % | 58 % | 0.01 |
| ocean | −22.0 | 16.7 | 443 | 0.015 | 50 % | 47 % | 3 % | 0.00 |
| fire | −18.9 | 13.3 | 159 | 0.000 | 70 % | 30 % | 0 % | 0.00 |
| forest | −20.8 | 15.8 | 842 | 0.000 | 47 % | 38 % | 15 % | 0.07 |
| birds | −22.3 | 17.6 | 1924 | 0.000 | 17 % | 24 % | 59 % | 0.38 |
| drone | −17.9 | 12.0 | 88 | 0.000 | 95 % | 5 % | 0 % | −0.02 |
| storm (unused) | −20.3 | 17.9 | 1157 | 0.174 | 65 % | 15 % | 20 % | 0.53 |

Flatness: 1.0 = white noise, 0.0 = tonal. Loudness spread at equal slider
value: **6.7 dB RMS** (thunder −24.5 vs drone −17.9), because files are
peak-normalised instead of loudness-normalised.

Method: 22.05 kHz mono decodes (Nyquist 11 kHz), 2048-sample Hann frames with
50 % overlap, energy-weighted centroid and band shares, flatness per frame then
averaged. Shares are relative to that 0–11 kHz band, so the high-frequency
figures are conservative.

What the synthesis does (`tools/generate_audio.py`):

- A bed of FFT-shaped Gaussian noise per layer (`shaped_noise`), plus sparse
  events: 150 droplets/40 s for rain (3.75 per second, amplitude 0.02–0.10
  under a bed at 0.46), 520 crackles/40 s for fire, 110 bubbles/40 s for stream,
  three thunder events at fixed positions (5.5 s, 17 s, 29.5 s), cricket trains
  on fixed 0.5/0.625/0.8 s periods.
- No early reflections, no reverb, no nonlinearity, no spectral motion beyond
  slow gain LFOs. `normalize(peak=0.8)` at the end.
- 40 s loops, circular by construction (so the seam is clean) but short enough
  that fixed events repeat recognisably.

### Why it reads as static

1. **Stationary spectrum.** Real rain is thousands of droplet impacts per second
   with distance-dependent filtering; here the impacts are inaudible under the
   bed, so only the bed remains. A flat spectrum between 2 and 8 kHz is what the
   ear labels "TV hiss".
2. **No space.** Nothing has early reflections or a decay tail, so every layer
   sits at distance zero, in the same place.
3. **No movement.** Gain LFOs at 0.05–0.17 Hz change level, not position or
   spectrum.
4. **Masking.** Rain, stream and birds all live in 1–8 kHz; with linear summing
   the loudest bed wins and the rest turns to mush.
5. **Repetition.** Three thunderclaps every 40 s, crickets at a metronomic
   period.
6. **No headroom.** Ten `MediaPlayer`s sum at the OS mixer with no limiter, so
   dense mixes clip or force the user to keep the volume low.

Two footnotes: `amb_storm.ogg` (593 KB) is not referenced by any Kotlin file —
the storm preset uses `amb_rain_heavy`. The web mixer is worse than the app:
six raw noise sources with gain LFOs, no events and no reverb, which is the
literal "TV static" version.

## 2. Option A — source real recordings

This is the largest perceived improvement per hour spent. The ear recognises
real rain, fire and water immediately, and real recordings carry the
micro-events and room tone that synthesis here lacks.

### Licensing (checked 2026-10-24)

| Source | Terms | Fit |
|---|---|---|
| [Freesound](https://freesound.org) | CC0 (public domain) filter available; CC-BY needs attribution; API free for light use | **Best.** Use CC0 only, keep a ledger |
| [Sonniss GDC bundles](https://sonniss.com/gameaudiogdc/) | Royalty-free, commercial, no attribution. Apps count as finished projects, but the licence forbids supplying the sounds as sound files (asset packs, standalone downloads) and forbids AI training | **Good** for one-shots inside the APK (thunder, fire crackle); not for anything that serves the audio itself |
| [ZapSplat](https://www.zapsplat.com/) | Free tier requires attribution; paid removes it | Good, attribution screen needed |
| [Pixabay audio](https://pixabay.com/sound-effects/) | Free licence, no attribution required; verify current terms per file | Convenient, shallower catalogue |
| [Internet Archive](https://archive.org) | Item-by-item; many public-domain field recordings | Good for long ambiences, check each item |
| BBC Sound Effects | RemArc: personal/educational/research only | **Excluded** — cannot ship in an app |
| AI-generated audio | Licence and provenance unclear | **Avoid** for now |

Attribution is compatible with the app (a credits screen), but CC0 keeps the
product story ("no third-party licences") intact.

### What to look for

- Stereo, 48 kHz, at least 30 s without a recognisable event; 60–90 s ideal.
- No music, no voices, no birdsong in a "rain" file unless intended.
- Rain: on leaves, on a roof, distant; **plus** the low-mid body (200–800 Hz)
  that the current file lacks.
- Fire: close crackle with a 60–300 Hz body; avoid recordings dominated by a
  stove fan.
- Ocean: body 40–300 Hz plus foam 2–7 kHz; the foam is what sells it.
- Stream: bubbling, not a jet of white noise.
- Thunder: distant rolls (1 per 60–90 s on average), not a single sharp crack.

### Editing spec

- Crossfade loops with an equal-power 500 ms join (or an 8 s crossfade for
  sparse layers), verify the wrap with a null test.
- Loudness-normalise to −20 LUFS for beds, −26 LUFS for sparse event layers,
  peaks ≤ −3 dBFS.
- Two variants per dense layer (rain, fire, stream) so the app can crossfade
  between them instead of repeating one 40 s file.
- Keep 48 kHz; encode Vorbis 96–128 kbps or Opus ~96 kbps (test Opus decoding
  on API 26 first — Vorbis remains the safe default).

### Cost

| | current | real, 60 s × 2 variants |
|---|---|---|
| rain + heavy + stream + fire + ocean (10 files) | 2.9 MB | ~7.2 MB |
| thunder, wind, forest, birds, drone (5 files) | 2.0 MB | ~3.6 MB (wind/drone can stay synthetic) |
| **total** | 4.6 MB (11 files) | **~10–12 MB** |

Effort: **3–5 days** including licensing and loop editing. APK grows ~6 MB,
still far below the 24 MB it ships at today.

## 3. Option B — keep generating, make it event-driven

If sourcing is off the table, the generator can be pushed much further without
licensing:

- **Droplet/impact models instead of beds.** Poisson-distributed impacts with
  distance-dependent lowpass (2–8 kHz for near, 400 Hz for far), random pan and
  a short room tail. Rain becomes recognisable from the impact statistics.
- **Crackle trains** for fire: 8–20 events/s with micro-timing jitter and a warm
  body; currently 0 % of the energy is above 2 kHz.
- **Bubble resonance** for stream (impulse-excited bandpass), not filtered noise.
- **Convolution reverb** with a synthesized impulse response (decaying filtered
  noise, 1.2–2.5 s, 20–60 ms predelay), applied as a wet send. This is the single
  biggest "space" improvement available offline.
- **Coherent stereo**: decorrelate with allpass + frequency-dependent ITD
  (≤0.7 ms) instead of independent noise per channel.
- **No fixed loop length**: render 2–4 minutes, or several variants with
  crossfades.

Effort: **1–2 weeks**. No licensing, small APK, deterministic builds. Ceiling is
still below real recordings for rain, fire and birds; good enough for wind,
ocean body and drone.

## 4. Option C — spatial audio

Four tiers, from cheap to architectural.

### C1. Per-layer stereo placement (recommended, ~1 day)

`DefaultAudioHub.setVoiceGain` already calls `player.setVolume(l, r)` with the
same value twice. Passing different values gives a constant-power pan:

```text
θ = (pan + 1) / 2 · π/2      // pan ∈ [-1, 1]
gL = cos θ, gR = sin θ
```

Place layers on a stage (stream left, fire right, wind wide, drone center) and
store the position in `AmbienceCatalog`. This costs nothing at runtime, works
with speakers and headphones, needs no new assets, and immediately separates the
mix. It does **not** add distance or vertical cues.

### C2. Baked binaural / room (best quality per effort)

Render each loop offline through a head-related transfer function and a small
room, then ship ordinary stereo files. Headphones then hear layers *placed*
(azimuth, distance, a little elevation); speakers still hear a wider, nicer bed.

- Tooling: `pyroomacoustics`, `spaudiopy`, or a small numpy ITD/ILD/pinna model.
- HRIR datasets (CIPIC, MIT KEMAR) often carry **research-only** terms — check
  before redistributing, or synthesize the filters yourself.
- Check mono fold-down (phones on a nightstand are mono in practice) and keep a
  non-spatial asset set or a mild setting.
- Cost: 2–4 days offline rendering, zero runtime CPU. Doubling assets adds
  ~10 MB; a single mild "wide + early reflections" set avoids that.

### C3. Platform spatialisation (bonus only)

Android 12L (API 32) added `android.media.Spatializer`
(`AudioManager.getSpatializer()`), with `AudioAttributes.setSpatializationBehavior(
SPATIALIZATION_BEHAVIOR_AUTO)`. Android 13 standardized head tracking for OEMs
that support it. Constraints that matter here:

- Available only on spatial-capable routes (usually headphones/BT, device
  dependent); `isAvailable()` must be checked at runtime.
- It spatialises the **mix**, not individual layers — it will not place rain
  left and fire right.
- `MediaPlayer` and ExoPlayer both feed the platform mix, so integration is a
  few lines, but behaviour varies by OEM.
- Head tracking is a gimmick for a stationary bedside app and costs battery.

Verdict: expose a "Espacial" toggle only when `isAvailable()`, default off.
Never make it the product promise.

### C4. Runtime HRTF mixer (not recommended)

Replace `MediaPlayer` per layer with an `AudioTrack` float mixer: decode with
`MediaCodec`, per-layer gain/pan/HRTF convolution, master limiter, sample-accurate
crossfades and positions that can move. It is the only way to get per-layer
runtime spatialisation, but it is weeks of work, adds battery/CPU risk, and
rewrites the `AudioHub` and its service. Wrong trade for this product.

## 5. Mixing fixes worth doing regardless

- **Loudness, not peak.** Normalise layers to a target LUFS; today two layers at
  the same slider position differ by 6.7 dB.
- **Spectral slots.** Decide per layer and bake it: rain 1.5–10 kHz + 200–800
  body; stream 400–6 kHz + bubbles; fire 60–300 body + 1–6 kHz crackle; wind
  100–900; ocean 40–500 body + 2–7 foam; thunder 30–120; forest 2–8 k events;
  birds 2–6 k; drone 40–300.
- **Headroom.** Ten unsynchronised `MediaPlayer`s have no master bus. Either
  bake peaks at −6 dBFS and cap the master around 0.55, or add a limiter — which
  requires the custom mixer above.
- **Ducking.** The hub already ducks the meditation track under chimes
  (`duckGain`); the same idea should lower dense beds when thunder or fire
  events are audible.
- **Fades.** Equal-power fades of 1.5–3 s on layer add/remove instead of the
  stepped linear ramp.
- **Variants and offsets.** Two variants per dense layer, crossfaded, plus a
  random start offset per session, kills the 40 s déjà vu.
- **Presets.** Keep at most three dense beds at once; the current six presets
  are close to that already.

## 6. The web mixer

The page's mixer is a placeholder by design; it is the most "TV static" audio in
the project.

- **Cheap fix (preferred):** ship 15 s excerpts of the real loops as Opus/WebM
  (≈60–120 KB each, ~700 KB for six), start them at random offsets, crossfade
  between two `AudioBufferSourceNode`s. It also makes the demo honest.
- **Keep-synthesis fix:** add a convolution reverb with a generated IR, split
  each layer into 2–3 octave bands with independent slow LFOs, schedule Poisson
  crackle/droplet events, and give each layer a `StereoPannerNode` (or
  `PannerNode` HRTF for headphones).

Note the licensing carry-over: if the app ships CC-BY audio, the web demo needs
the same attribution. And the demo serves each loop as a separate file, which is
"supplying the sounds as files" — so use CC0 or your own recordings there, not
the Sonniss bundle.

## 7. How to verify the improvement

Objective, per file:

- Loop seam: wrap cross-correlation null test, target better than −40 dB.
- Crest factor: rain and fire should rise above ~15 dB once events dominate.
- Band balance: fire >20 % above 1 kHz; ocean >8 % above 2 kHz (foam); rain
  20–40 % low-mid body.
- Loudness: `ffmpeg -af ebur128` per file, spread ≤ 2 LU across beds.
- Temporal interest: modulation energy in 0.5–8 Hz (event rate), and spectral
  flux above the current near-constant value.
- Stereo: correlation 0.0–0.4 for wide beds; mono fold-down check for binaural.

Subjective: blind A/B with five people, "does this sound like rain / a fire /
the sea?" on phone speaker and headphones. Test the four routes that matter:
phone speaker, wired headphones, Bluetooth speaker, Bluetooth headset with
spatialisation available or not.

## 8. Recommendation and sequencing

| Phase | Work | Effort | Impact |
|---|---|---|---|
| 0 | Drop unused `amb_storm.ogg`, retarget presets, lower master headroom | hours | small, frees 600 KB |
| 1 | Five CC0 recordings (rain, heavy rain, stream, fire, ocean), 60 s, two variants, −20 LUFS; per-layer pan | 3–5 days | **largest** |
| 2 | Baked early reflections / mild room; event-driven thunder, forest, birds; ducking; crossfaded variants | 3–5 days | high |
| 3 | Optional `Spatializer` toggle when `isAvailable()` | 1 day | low, nice demo |
| 4 | Web demo uses real 15 s excerpts | 1 day | high for the landing page |

Do Phase 1 and 4 first; they are independent and each fixes the "hiss"
complaint. Phase 2 makes the mix feel like a place. Phase 3 is polish.

## 9. Risks and open questions

Risks: licence drift (keep `SOURCES.md` next to the assets with URL, author,
licence per file), HRIR dataset terms, APK growth, Opus support on older devices,
mono fold-down of binaural assets, and the temptation to over-spatialise a
sleep app.

Questions to settle before implementation:

1. Primary listening: phone speaker on a nightstand, or headphones? Binaural only
   pays off for the latter.
2. Is there budget for a paid library (ZapSplat premium, A Sound Effect), or CC0
   only?
3. May the landing-page demo use the same recordings (licence must cover web
   delivery)?
