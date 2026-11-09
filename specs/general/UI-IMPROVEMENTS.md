# UI/UX Improvements

## Summary

Respira is a Jetpack Compose Android app (dark-only) with three bottom tabs (Inicio, Respirar, Biblioteca), a floating mini player, and a full-bleed animated night backdrop. This review is based on reading the source for the theme, shared components, the backdrop, the bottom bar, the mini player, the Home, Breathe, Ambience and Meditate screens. The app was **not run on a device or emulator**, so nothing here is based on screenshots, real device sizes or measured contrast; treat spacing and size recommendations as starting points to check on a phone.

The atmosphere is good: the palette, the Fraunces + Manrope pairing and the slow aurora drift give the app a calm identity. The weaknesses are about **hierarchy and monotony**. Almost every surface is the same translucent card, so nothing stands out. Home repeats the navigation, the primary action on Breathe sits below a large orb, and several controls are below comfortable touch size. Fixing those would make the app feel more deliberate without changing its character.

## Critical Issues

### Issue: Breathe's main Start/Pause control is pushed below the fold
**Current State**: `BreatheScreen` stacks the header, a 300 dp orb, a summary block (min 124 dp), the pattern chips, the routine pill, then the Start/Pause button and the haptics/sound toggles, all inside one `verticalScroll` column (`feature/breathe/BreatheScreen.kt`, ~L221–411).
**Problem**: On a typical 6.1–6.7" phone the orb alone takes most of the visible height. The one action the screen exists for sits roughly 700–900 dp down, so the user has to scroll mid-session to pause. The ring control is also the smallest visual weight in the layout: 80 dp inside a 118 dp halo.
**Recommendation**: Pin the primary control to the bottom of the screen (outside the scroll container) and let the orb scale with the available height. Use `BoxWithConstraints` and size the orb to `min(300.dp, maxHeight * 0.45f)`. Move pattern chips and toggles below the orb or into a sheet opened from the header pill, so the first screen shows only: title, orb, phase text, and the big control.
**Impact**: The core action is always reachable during a session. Users stop accidentally scrolling the orb away, and the screen reads as one clear task.
**Implementation Notes**: Replace the outer `Column(verticalScroll)` with a `Column` that has a weighted middle `Box` for the orb and a fixed bottom `Box` for the control. Keep a scroll container only around the secondary options. Check the Complete state too, since `CompletionSummary` currently sits in the same flow.

### Issue: Touch targets below 48 dp in several places
**Current State**: `SavedMixChip` delete affordance is a 20 dp box with a 12 dp icon (`feature/ambience/AmbienceScreen.kt`, ~L361–375). The "Buscar actualización / Check for updates" control on Home is 8×4 dp padding around 11 sp text (`feature/home/HomeScreen.kt`, ~L306–316). `SoftIconButton` defaults to 46 dp, and the mini-player close button is 38 dp (`ui/components/MiniPlayer.kt`, ~L142–148). The bottom tab labels are 11 sp.
**Problem**: The saved-mix delete control is about 20 dp, far below the 48 dp Material minimum, and it sits right next to the mix name, so it is easy to hit by accident and hard to hit on purpose. The update check is tiny text with almost no hit area. Several icon buttons are at or below the minimum.
**Recommendation**: Give every interactive element a 48 dp minimum hit area (`Modifier.minimumInteractiveComponentSize()` or an explicit `heightIn(min = 48.dp)`), keeping the visual size smaller where needed. For the saved-mix chip, move delete into a long-press menu or a separate 48 dp hit area with a clear gap from the name. Make "Buscar actualización" a `labelLarge` text button with `heightIn(min = 48.dp)`.
**Impact**: Fewer mis-taps, especially for users with larger fingers, reduced dexterity, or who use the app half-asleep in the dark, which is this app's main use case.
**Implementation Notes**: `SoftIconButton` and `GhostPill` already take a `modifier`, so the fix can be applied at the call sites. Add a `minimumInteractiveComponentSize()` to `SoftIconButton` once rather than per call site.

### Issue: Home duplicates the bottom navigation
**Current State**: The Home screen ends with two "Explore" cards, "Meditar" and "Sonidos", that open the same Meditate and Ambience screens that the Biblioteca tab already reaches (`feature/home/HomeScreen.kt`, ~L251–281). The sleep-routine entry sits between them.
**Problem**: Home has seven sections in the morning (hero, resume, tonight, favorites, routine, explore label, two explore cards) and the navigation bar is always visible. The explore cards are a second route to the same places, which adds length and makes the screen feel like a menu instead of a starting point.
**Recommendation**: Remove the two explore cards. Keep the sleep-routine entry, since it has no other home. If discovery of the Sonidos screen is a concern, add a single "Sonidos" chip in the Biblioteca tab header or as the first item of the Favorites row.
**Impact**: Home gets shorter and more focused on the three things a user needs (breathe, resume, favorite). Less repeated copy in the Spanish-first UI.
**Implementation Notes**: Remove `ExploreCard` usages and the `SectionLabel(home_explore_label)` if no longer used. Check the `home_card_*` strings for unused entries.

## High Priority Improvements

### 1. Glass cards are nearly indistinguishable from the background
**Current State**: `GlassCard` (`ui/components/Ui.kt`, L38–70) paints a vertical gradient of `topTint` at alpha 0.08 → 0.025 over the backdrop, with a 1 dp border at about 10–15% white. The Night/Deep backdrop is already very dark. Almost every surface (resume, tonight, favorites, explore, mixer footer, the bottom bar, the mini player) uses this one treatment.
**Problem**: The cards read as faint outlines rather than surfaces, so the page has no depth and no obvious primary element. The name "glass" promises a translucent blur that is not there (no `Modifier.blur` or backdrop effect is used). The "tonight" and "resume" cards, which should be the most prominent, look the same as the secondary explore card.
**Recommendation**: Define three elevation levels and use them consistently:
- **Hero** (Breathe hero, "Para esta noche"): a more opaque gradient, e.g. `Brush.verticalGradient(Surface.copy(0.9f), Deep.copy(0.95f))` plus a soft accent glow behind it.
- **Standard** (resume, tonight, footer): the current `GlassCard` with alpha raised to about 0.12 at the top.
- **Quiet** (explore rows, footer in the bottom bar): no gradient, just `RespiraColors.Surface` at 60% and the `Line` border.
Rename `GlassCard` to `SurfaceCard` if the blur is not planned, so the name matches what is drawn.
**Impact**: Clear hierarchy on Home and in the mixer; the page is scannable at a glance, which matters at 22:00 in a dark room.
**Implementation Notes**: Add a `level` parameter (or a `CardElevation` enum) to `GlassCard`. Keep the existing signature working with a default. Tokens are already in `RespiraColors.Surface`, `SurfaceHigh` and `Line`.

### 2. Hardcoded colors and alphas bypass the palette
**Current State**: `RespiraColors` says it is the "single source of truth", but many screens use `Color.White.copy(alpha = …)` directly (`Ui.kt`, `HomeScreen.kt` `FavoriteCard`, `AmbienceScreen.kt` `SavedMixChip`, `MiniPlayer.kt`, the sheet's drag handle). `HomeScreen.BreatheHero` hardcodes `Color(0xFF14303F)`, `0xFF161A38`, `0xFF1B1830`. `MaterialTheme.colorScheme` is set but used in only a few places.
**Problem**: Borders and surfaces drift: the same "hairline" is `0x14FFFFFF` (`Line`), `White.copy(0.10f)`, `White.copy(0.09f)` and `White.copy(0.12f)` in different files. Changing the look means editing many files, and the hero gradient will not follow future palette changes.
**Recommendation**: Add three tokens to `RespiraColors`: `Hairline` (0.08), `Border` (0.12), `BorderStrong` (0.22), and replace the `Color.White.copy(...)` calls with them. Add `HeroStart`, `HeroMid`, `HeroEnd` for the hero gradient or derive them from `Aqua`/`Indigo`/`Violet` at fixed alphas. Map `colorScheme.surfaceContainer*` to the same tokens so new M3 components pick them up.
**Impact**: One place to tune the whole look, and consistent borders across screens.
**Implementation Notes**: A grep for `Color.White.copy` and `Color(0xFF` in `app/src/main/java` gives the full list.

### 3. Two different breathing orbs
**Current State**: `HomeScreen.kt` has its own private `BreathOrb` (L738–789), a faint gradient circle with a 5.6 s swell and two rings. `feature/breathe/BreathOrb.kt` is the real, phase-driven orb with a pulse ring and a white highlight.
**Problem**: The home hero and the Breathe screen show two different shapes for the same concept, so the first impression does not match what the user gets after tapping. The home version also has no phase, so it feels like a decoration.
**Recommendation**: Use one orb composable with two modes: `Idle` (the hero, slow swell, no phase text) and `Session` (the live engine). The hero can use the same drawing at a smaller size with `engine = null`.
**Impact**: The app has one recognisable visual signature, and the transition from Home to Breathe feels continuous.
**Implementation Notes**: `BreathOrb` currently takes a `BreathEngine`. Add an overload that takes a `Float scale` and `Color accent` so Home can call the same drawing code.

### 4. The mini player and bottom bar stack into two floating bars
**Current State**: `RespiraApp` puts `MiniPlayer` directly above `RespiraBottomBar` inside the same column (`ui/RespiraApp.kt`, L82–91). Both are `GlassCard`s with 14 dp side padding. Together they take about 130–150 dp of the bottom of the screen whenever something is playing.
**Problem**: Two stacked rounded bars compete with the content, and the mini player's controls (play/pause and close, each 38 dp) sit very close to the tab row. On Ambience, where the footer is already tall, the grid loses even more space.
**Recommendation**: Merge the mini player into the bottom bar's top edge as a slim 56 dp strip with a 2 dp progress line, a tap target for the full player, and a single play/pause button. Drop the close (X) button from the strip; stopping is available in the full player. This keeps the info and the control, and removes one bar.
**Impact**: About 60–80 dp of content space back on every tab while playing, and a calmer bottom edge.
**Implementation Notes**: `MiniPlayer` gets its progress from `Audio.hub.state` (`positionMs`, `track.durationMs`). Use `LinearProgressIndicator` with `track = Line`, `progress = Aqua`.

### 5. Breathe controls are three pill styles that look alike
**Current State**: Pattern choice (Calm / Box / 4-7-8 / Coherent), the session length pills, the routine action, Haptics and Sound toggles, and the Again/Done buttons all use `GhostPill` or `PillButton` with the same size and corner radius (`BreatheScreen.kt`, L342–409). Selected pattern and enabled toggle use the same `selected` state.
**Problem**: The user cannot tell a *choose one of* group (pattern, length) from *on/off switches* (haptics, sound) or from *an action* (routine). Four pattern pills in a horizontal scroll row also hide options past the edge with no affordance beyond the cut-off.
**Recommendation**: Use a segmented control (single row, equal widths, shared container) for the pattern choice, with the pattern's detail text beneath it. Render haptics and sound as two compact switches with icons and labels. Keep the routine as a clearly different full-width tonal button. Put "Again/Done" in a `Row` with `weight(1f)` for equal widths.
**Impact**: Each group reads as what it is, and nothing needs a horizontal scroll.
**Implementation Notes**: Build one `SegmentedChoice` composable in `ui/components/Ui.kt`. Use the same for the Ambience session-length pills if you want consistency.

### 6. Numbers in Fraunces make the countdown harder to read
**Current State**: The phase countdown (`headlineLarge`, Fraunces 30 sp) and the mini timer use the serif display face. The remaining-time label in the header uses `headlineMedium`, also Fraunces.
**Problem**: Serif numerals of varying width make a ticking seconds counter jitter and are less legible in dim light. The phase word above is correct in Fraunces, but the number is data, not display text.
**Recommendation**: Set numeric readouts (seconds, remaining time, percentages, volume) in Manrope with `FontFeatureSettings("tnum")` so digits have equal width. Keep Fraunces for words and titles.
**Impact**: A steady, readable counter during a session.
**Implementation Notes**: Add a `numeric` text style (Manrope, SemiBold, tabular figures) to `Type.kt` and use it in `ActiveSummary` and `formatDuration` callers.

## Medium Priority Enhancements

### 7. Section labels are small and low in weight
`SectionLabel` (labelSmall, 11 sp, 2 sp tracking, TertiaryText) introduces Home's sections. It is readable, but it is the only signal between sections, so the page reads as one list. Consider `titleSmall` in `SecondaryText` with a 1 px accent rule, or a larger gap (28 dp instead of 22 dp) before each section.

### 8. The Ambience layer tiles and the grid look uniform
The 2-column grid reorders active layers first, which is a good behaviour. The tiles themselves share the same surface, so a playing layer is only distinguished by its volume. Give active tiles a faint accent wash in their layer colour and a small animated level bar, so the mix reads at a glance.

### 9. Empty and error states are text-only
`LibraryEmpty`, `FavoritesEmpty` and the import error card use a text row with an icon badge. Add a short, calm illustration (the existing `ic_*` drawables work) or a single clear primary action per state so the next step is obvious.

### 10. Bedtime switch is abrupt at 22:00
`currentMoment` flips the whole top half of Home at the hour (`HomeScreen.kt`, L151–165). The hero is replaced by the routine card with no transition. Wrap the swap in `AnimatedContent` (fade + slight vertical slide, ~400 ms) so the change feels intentional.

### 11. Greeting and wordmark hierarchy
The wordmark ("RESPIRA", labelSmall with 6 sp tracking) and the greeting (labelSmall, 2.4 sp tracking) are both tiny and both uppercase, so the top of Home has three uppercase labels in a row. Keep the wordmark, make the greeting sentence case in `bodyLarge` `SecondaryText`, and let the headline carry the emphasis.

### 12. Accessibility: hardcoded and unlocalised strings
`ScreenHeader` uses `contentDescription = "Back"` (`ui/components/Ui.kt`, L183) in English on a Spanish-default app, and the version label `v$version` is not announced usefully. Move the back description into `strings.xml` (both locales). Check `contentDescription` on all `SoftIconButton` uses.

### 13. Light and reduced-motion support
The app is dark-only, which fits a sleep app, but the aurora drift runs continuously. Honour the system "remove animations" setting (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`) by freezing the backdrop, and expose a "Dim the screen" option for the breathing screen.

## Low Priority Suggestions

- **Motion on tab switch**: the `enterTransition` slide (`it / 28`) is subtle and good. Consider a shared-axis horizontal transition only between tabs; keep the vertical one for drill-down screens.
- **Icon set**: the drawables are consistent 24 dp line icons. Confirm stroke width matches the 1.2 dp orb rings so they look like one family.
- **Favorites row**: `FavoriteCard` is 132 dp wide with a 96 dp thumbnail. A 2-line title with `labelLarge` at 14 sp could be truncated on large-font settings; test with the system font scale at 1.3×.
- **Haptic/sound state persistence**: `hapticsOn` and `soundOn` use `rememberSaveable`, so they reset when the app process is killed. Store them in `DataStore` if they should survive.
- **Sleep timer sheet**: `ModalBottomSheet` uses `Deep` with a 0.6 scrim. Consider a `Surface` tint and a short "Ends at 23:40" line to match the rest of the app's time copy.

## Positive Observations

- **Palette and mood**: teal (Aqua) on deep navy with indigo and violet accents suits a night-time app. The amber used for the hold-out phase and the paused state is a good warm contrast.
- **Typography pairing**: Fraunces for display and Manrope for body and labels gives the app a distinct, calm voice, and the full type scale in `Type.kt` is complete and consistent.
- **Adaptive home**: switching to a bedtime layout after 22:00, hiding the browsing cards, and keeping "Seguir durmiendo" first is a thoughtful use of time context.
- **Breathing engine feedback**: the `BreathOrb` phase colours (aqua inhale, violet hold, indigo exhale, amber hold-out) and the pulse ring give clear, non-verbal cues. Keep them.
- **Semantic state for screen readers**: `selected`, `stateDescription` and `Role.RadioButton` are already set on pattern chips, toggles, timer options and the mini player. This is ahead of most apps.
- **Keep-awake and resume**: `FLAG_KEEP_SCREEN_ON` during sessions, a 30 s resume threshold, and the silent restore of a saved mix all avoid surprising the user.
- **Ambient backdrop**: the slow aurora drift (26 s / 12 s) is subtle enough to stay in the background. Keep it.
- **Quiet audio start**: restoring a saved mix without auto-playing is the right call for a screen that opens at night.
