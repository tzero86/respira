# Ambience audio: recognisability round

Date: 2026-10-24
Follows: `2026-10-24-ambience-audio-results.md` (the hiss round)
Scope: the ten bundled soundscape layers, after the report that the fire
preset sounded like a geiger counter and that some layers read as
"totally different" sounds.

## 1. The complaint was a measurement, not a taste

The alpha12 layers were rebuilt to have the right *spectrum*: rain had a
200–800 Hz body, fire had crackle above 1 kHz, the ocean had foam. The
spectrum was right and the sounds were still wrong.

Measuring the high band of each shipped layer showed why. `fill` is the
fraction of the 1.5–8 kHz band that is above 20 % of its own peak — in
other words, how much of the time the layer has *any* sound in it:

| layer | alpha12 fill | reads as |
|---|---|---|
| fire | **0.058** | 94 % silence in the crackle band: isolated ticks |
| birds | 0.077 | song on silence |
| thunder | 0.029 | booms on silence |
| ocean | 0.190 | foam only at the wave peaks |
| rain | 0.663 | continuous |
| stream | 0.994 | continuous |

The fire's crackle band was empty except for the pops. A real fire is not
silent with clicks in it; it is a continuous roar and sizzle with pops on
top. So the fire read as a **click train**, and a click train with evenly
spaced ticks is exactly a geiger counter.

Two numbers confirmed it. The alpha12 fire had an amplitude spread
(p90/p10) of **2.56** — every crackle the same loudness — and the pops sat
**28× above** the local bed. A real fire varies wildly in how loud each
crackle is, and the pops ride on a bed that is always there.

The same audit found the problem was not limited to fire: **birds, thunder
and ocean** all had sparse, isolated events over a near-silent bed.

## 2. What was done

### 2.1 Real recordings, for the layers that need them

A field recording of a fire contains things synthesis cannot: the roar of
the gas, the hiss of the flame, the crack of an ember finding air, and the
stone hearth around all of it. That combination is what the listener
recognises.

55 candidate recordings on Wikimedia Commons and archive.org were examined
and measured. Five layers were replaced with public-domain field
recordings:

| layer | recording | credit |
|---|---|---|
| rain | Langeland sound of rain on leaves, Denmark | audiotraction |
| rain_heavy | Regen prasselt auf Einmannzelt, Germany | kafkaamstrand |
| ocean | sea waves, Rewal, Poland | Piotrek Zyla |
| fire | fading campfire, Sasino, Poland | maciej janasik |
| stream | Alpine Stream 2, Hollersbach, Austria | htuggle |

Every licence was checked directly against the archive.org item metadata:
all five are **Public Domain (CC0 1.0 Public Domain Mark)**. No attribution
is legally required; the credits are kept in `tools/SOURCES.md` anyway.

`tools/process_recordings.py` builds them: it picks the window of the
recording that is quiet and unchanging, high-passes at 35 Hz, low-passes at
15 kHz, folds the window onto itself with an equal-power crossfade,
loudness-normalises to the same target as the synthetic layers and caps the
peak. The build **fails** if the loop point would be audible.

### 2.2 The loops got longer

A 40-second loop repeats 90 times in an hour and 720 times in a night.
A real recording can carry a much longer loop, and the window that is
cleanest is not always the longest one, so the length and the position are
measured per layer:

| layer | loop |
|---|---|
| rain | 120 s |
| rain_heavy | 60 s |
| ocean | 120 s |
| fire | 90 s |
| stream | 40 s |

### 2.3 The seam test was fixed

`tools/check_seam.py` had a null test whose answer changed with the length
of the window it looked at: the *same file* read −150 dB at a 50 ms window
and −19 dB at 10 ms. That is not a measurement.

It now reports two direct measurements that do not depend on a window:

* **step/p999** — the sample-to-sample step at the junction over the file's
  own 99.9th percentile step. Below 1 means the loop point is quieter than
  the layer's own loudest transient.
* **hf jump** — the broadband energy in the 5 ms after the junction over the
  5 ms before it. A click puts a burst of high-frequency energy there.

### 2.4 A recognisability check

`tools/check_layers.py` states what each layer must look like and fails if
it does not: it must have a body (`fill`), its events must be irregular
(`cv`) and of varied amplitude (`spread`). It also fails any layer that
shows the click-train signature whatever it claims to be.

### 2.5 The measurement that names the fault

A later pass over 226 candidate recordings turned up a measurement the first
pass had missed, and it is the one that actually names the complaint.

**`bright_cv` — the spread of the *colour* of the individual events.** Each
event is isolated in the 1.5–10 kHz band and its spectral centroid is
measured on its own; the figure is the standard deviation over the mean of
those centroids.

A geiger counter repeats one click, so every click has the same spectrum.
A real fire crackles differently every time, so each crackle has its own
colour. Nothing about the spacing or the loudness matters — the *timbre* is
what gives it away:

| | bright_cv |
|---|---|
| synthetic fire (alpha12) | **0.037** — one click repeated |
| real fires | 0.078 – 0.166 |
| synthetic ocean (alpha12) | 0.028 |
| synthetic forest (alpha12) | 0.035 |

The classical metrics did not separate them: the alpha12 fire had an
inter-onset-interval CV of 1.14 and an amplitude spread of 10.5, both of
which look healthy, because the Poisson trains were irregular enough. The
colour was the tell.

`tools/check_layers.py` now measures it too, and it is the check that fails
the old fire:

```
amb_fire (alpha12)  spread=2.65  bcv=0.070   FAIL
amb_fire (alpha14)  spread=4.94  bcv=0.143   OK
```

### 2.6 Four more layers became recordings

With 226 candidates measured, the four layers that had no clean-licence
recording in the first pass turned out to have some. They are all public
domain, checked the same way:

| layer | recording | credit |
|---|---|---|
| thunder | Gewitter am Bauhausplatz, Dessau-Roßlau | oriolus |
| wind | wind through a silver birch tree, Kožmice | Peter Cusack |
| birds | nightingale song, Brightling | oriolus |
| forest | natural ambience | nille (PD-author) |

Only the low hum is still synthesised now.

| layer | bright_cv before | after |
|---|---|---|
| fire | 0.037 | **0.143** |
| ocean | 0.028 | **0.103** |
| forest | 0.035 | **0.161** |
| birds | 0.099 | **0.223** |
| wind | 0.034 | **0.092** |
| rain | 0.106 | 0.083 |
| rain_heavy | 0.095 | 0.088 |
| stream | 0.024 | 0.076 |
| thunder | 0.200 | 0.037 |
| drone | 0.058 | 0.050 |

Thunder is the one that went *down*: the old synthetic rolls were already
well spread, and the recording has a steadier, lower rumble under them. It
was never the weak layer the complaint was about.

## 3. Before and after

### The fire layer

| | alpha12 | now |
|---|---|---|
| fill (high band body) | 0.058 | **0.991** |
| amplitude spread p90/p10 | 2.56 | **4.94** |
| inter-event cv | 1.05 | 1.45 |
| pop level over bed | 28× | riding on a bed |
| crest | 21.7 dB | 24.4 dB |
| below 200 Hz | 54 % | 68 % |

### All ten layers

| layer | fill | cv | spread | verdict |
|---|---|---|---|---|
| rain | 0.998 | 0.64 | 3.56 | OK |
| rain_heavy | 0.335 | 0.70 | 3.37 | OK |
| thunder | 0.029 | 6.80 | 23.01 | OK |
| wind | 0.948 | 0.44 | 2.64 | OK |
| stream | 1.000 | 0.39 | 2.57 | OK |
| ocean | 0.454 | 2.38 | 3.42 | OK |
| fire | 0.991 | 1.45 | 4.94 | OK |
| forest | 0.192 | 1.37 | 1.49 | OK |
| birds | 0.078 | 3.13 | 4.05 | OK |
| drone | 0.992 | 0.38 | 2.45 | OK |

The check was run against the alpha12 files as well: it flags the old fire
(`fill` 0.058, `spread` 2.56) and passes the new one, so it detects the
fault it was written for.

### Loop points

All ten layers pass the direct seam measurements. The worst step at a
junction is 0.10 of that file's own loudest transient (rain), and the
largest high-frequency jump is +1.6 dB.

### Loudness

Unchanged: the spread across the ten layers is **0.2 LU**, so the mixer
slider still means the same thing everywhere. Peak −1.2 dBFS.

The ambience set grew from 5.3 MB to 8.9 MB, which is the price of the
longer loops.

## 4. A bug the new audio exposed

The new layers are longer and denser, and that made a pre-existing bug in the
mixer visible. In the **release** build the mixer refused to play: the tile
showed its level, the footer said "En pausa", and the audio system showed
`renderer(paused(1))` with the output at zero volume. The debug build
recovered after a second tap, which is why it had gone unnoticed.

The chain was:

1. The screen restores the saved mix when it opens, and does it with
   `setLayers(...)` followed by `setLayersPlaying(false)`.
2. `setLayers` set `layersPlaying = true` on the way through — setting the
   levels of a mix also started it.
3. `setLayersPlaying(false)` then paused it and abandoned the audio focus,
   all within the same frame.
4. Because the layers were still in the map, the next `setLayersPlaying`
   call took the "already paused" path and never started the sound again.

Three things were wrong and all three are fixed:

* **`setLayers` no longer starts the mix.** Setting the levels of a mix and
  playing it are different things. A preset that the user just tapped
  starts; a mix restored when the screen opens stays silent.
* **`setLayerVolume` always starts the mix when a layer is turned up.** It
  used to depend on the layer map having been empty, so a mix that had been
  paused could never be restarted by tapping a tile.
* **The media session no longer cancels the app's own state.** The app
  publishes its playback state through the media session, and the session
  calls the app's own pause handler back. The handler now checks whether the
  app is actually playing and ignores its own echo.

The release build now shows `renderer(paused(0))` with the stereo output at
`0.52 / 0.29`, which is the constant-power pan doing its job.

## 5. What is still open

* **Only the low hum is still synthetic.** Every other layer is a recording.
* **The sea, the stream and the rain are recordings of different places**, so
  mixing them is a montage rather than a place.
* **Two loop variants per layer** would remove the last of the repetition.
  Not done: it doubles the asset size and the loop is already 40–120 s.
* **The ambience set is 13.3 MB**, up from 4.6 MB before alpha13. The loops
  are longer and there are more of them; the APK grew by about 3 MB.
* **Subjective A/B is still the author's job.** Every claim above is a
  measurement; whether the fire now sounds like her fireplace is for the
  person who knows what her fireplace sounds like.
