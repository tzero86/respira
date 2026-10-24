# Audio sources

Respira ships two kinds of ambience audio, and this file records where every
one of them came from.

## Synthetic layers

`tools/generate_audio.py` generates these from scratch: no recording, no
sample, no third-party material. The generator is deterministic (it is seeded
with a fixed seed), so the same source always produces the same audio.

| layer | what it is made of |
|---|---|
| `amb_drone` | a 40–300 Hz harmonic bed |

## Recorded layers

`tools/process_recordings.py` builds these from field recordings. Every source
below was checked directly against the archive.org item metadata, and every
one of them is in the **public domain** (CC0 1.0 Public Domain Mark, or a US
Government work). The one Commons file was checked on its own file page: it
carries the `PD-author` category and a public-domain licence template. No
attribution is legally required. The credits are kept here anyway, because
the recorders deserve to be named.

| layer | recording | credit | licence | source |
|---|---|---|---|---|
| `amb_rain` | Langeland sound of rain on leaves, Denmark | audiotraction | Public Domain (CC0 1.0 PDM) | https://archive.org/details/aporee_58350_66941 |
| `amb_rain_heavy` | Regen prasselt auf Einmannzelt, Germany | kafkaamstrand | Public Domain (CC0 1.0 PDM) | https://archive.org/details/aporee_32985_37898 |
| `amb_ocean` | sea waves, Rewal, Poland | Piotrek Zyla | Public Domain (CC0 1.0 PDM) | https://archive.org/details/aporee_64598_74549 |
| `amb_fire` | fading campfire, Sasino, Poland | maciej janasik | Public Domain (CC0 1.0 PDM) | https://archive.org/details/aporee_25828_29895 |
| `amb_stream` | Alpine Stream 2, Hollersbach, Austria | htuggle | Public Domain (CC0 1.0 PDM) | https://archive.org/details/aporee_65017_75100 |
| `amb_thunder` | Gewitter am Bauhausplatz, Dessau-Roßlau, Germany | oriolus | Public Domain (CC0 1.0 PDM) | https://archive.org/details/aporee_18370_21335 |
| `amb_wind` | wind through a silver birch tree, Kožmice, Czechia | Peter Cusack | Public Domain (CC0 1.0 PDM) | https://archive.org/details/aporee_64074_73891 |
| `amb_birds` | nightingale song, Brightling, England | oriolus | Public Domain (CC0 1.0 PDM) | https://archive.org/details/aporee_36705_42063 |
| `amb_forest` | natural ambience | nille | Public domain (PD-author) | https://commons.wikimedia.org/wiki/File:20090610_0_ambience.ogg |

### What was done to the recordings

Each recording is cut to a window that is quiet and unchanging, high-passed at
35 Hz and low-passed at 15 kHz, folded onto itself with an equal-power
crossfade so the loop point is inaudible, loudness-normalised to the same
target as the synthetic layers (−23 LUFS) and peak-capped.

The masters are not in git. To rebuild the recorded layers:

```sh
# download the masters into ~/Downloads/respira_recordings
python tools/process_recordings.py
```

`python tools/process_recordings.py --list` prints the same table.

## Rejected sources

Several recordings were examined and rejected, and the reasons are worth
keeping:

* **CC BY-SA** recordings — the share-alike obligation would apply to the
  app itself, which is not acceptable here.
* **CC BY-NC** and **CC BY-ND** recordings — the licence does not permit this
  use.
* Recordings whose crackle rate and amplitude spread matched a geiger
  counter rather than a fire, and recordings with speech or music in them.
* Recordings that were mono but wide, or stereo but nearly correlated: the
  first wastes the stereo field, the second sounds like one source in the
  middle of the head.
