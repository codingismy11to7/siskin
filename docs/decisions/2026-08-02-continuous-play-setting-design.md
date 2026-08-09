# Continuous play becomes a setting

**Date:** 2026-08-02
**Status:** Approved

## Context

Closes [#72](https://github.com/codingismy11to7/siskin/issues/72). Touches the
overflow row last settled by
[the car star rating design](2026-08-02-car-star-rating-design.md), and adds the
first row to the Settings screen that
[sign-in behind the car's settings gear](2026-08-01-car-sign-in-entry-point-design.md)
built.

## Why

Continuous play extends a running queue on its own and nobody can have asked for
it. `Preferences.isContinuousPlayEnabled()` reads
`getBoolean(CONTINUOUS_PLAY, true)`, and the key has exactly two references in
`app/src/main/java` — the constant and that read. Nothing writes it. The fork
deleted tempo's settings screen, so the default is not a default; it is the
value, permanently.

Measured on the API 33 AAOS emulator, stepping to the end of an eight-track album
queue:

```
D MediaManager: Continuous Play: adding 23 similar tracks
W MediaManager: Continuous Play: no new similar tracks, falling back to random songs
D MediaManager: Continuous Play: adding 25 random tracks
```

An eight-track album became a ~56-track queue. It also contaminated an unrelated
shuffle-ordering measurement, which is how it was noticed.

The obvious objection is that the overflow already has an instant-mix pair, so
there is an off switch. There is not.
`buildMediaButtonPreferences` selects between the two on
`MediaManager.continuousPlayIsRunning` — an in-flight flag, true only between
`MediaManager.java:89` and `:171`, measured at ~0.7s on the emulator
(`23:06:27.627` → `23:06:28.331`). So ⊖ is on screen for well under a second, and
`CUSTOM_COMMAND_INSTANT_MIX_OFF` has no branch in `onCustomCommand` anyway: the
`when` handles instant-mix-on, both shuffle commands and the three repeat
commands, then falls to `else` and returns `ERROR_NOT_SUPPORTED`. Even a working
⊖ would cancel an in-flight mix rather than disable the feature.

## The rule

**Whether the queue extends itself is configuration, not transport.**

Repeat and shuffle belong in the overflow because they are changed mid-drive, in
response to what is playing. "Should my queue silently grow forever" is decided
once and then left alone. Putting it beside repeat and shuffle miscategorises it,
and the overflow is the wrong place to spend a slot on a decision nobody revisits.

Two consequences fall out of that, and both are improvements rather than costs.

**The setting is parked-only, and that is correct.** `CarSignInActivity` carries
an `APPLICATION_PREFERENCES` intent-filter, which is what makes the gear the car
draws in its top bar a live button rather than an inert outline. It deliberately
declares no `distractionOptimized` meta-data — a sign-in form cannot be compliant
with keyboard input restricted while driving — so the platform blocks the screen
while the car is moving. For a sign-in form that is a constraint worked around.
For this it is the right behaviour: a configuration decision should not be
reachable at speed.

**Deleting ⚡ removes a destructive one-tap action from a menu used at speed.**
`BaseMediaService.onInstantMix` removes every track after the current one before
appending the mix. No confirmation, no undo, and the truncation is not something
the button's icon suggests. Nothing else in the app performs it.

## Decision: the preference gets a writer and an off default

`isContinuousPlayEnabled()` defaults to `false`, and a
`setContinuousPlayEnabled(Boolean)` writes the same key.

The comment above it records why the default is off — nothing should extend a
queue the user did not ask to extend. That is *not* the shape of the comment on
`isFallbackToRandomTracksEnabled()` directly below, which explains that there is
no writer so the default is the effective value. That note stays true of the
fallback preference and stops being true of this one, and the two comments
sitting adjacent should not read as though they say the same thing.

Nothing in the manifest declares `android:process`, so the activity's write and
the service's read share one `SharedPreferences` instance. The service reads at
the moment the queue runs out (`BaseMediaService.kt:175`), not at startup, so a
flip takes effect on the next exhaustion — no restart, no invalidation, no
listener.

## Decision: the overflow loses instant mix entirely

Removed:

- `customCommandInstantMixOn` and `customCommandInstantMixOff`, and their entries
  in `customLayoutCommandButtons`
- the `continuousPlayIsRunning` selector in `buildMediaButtonPreferences`; the
  row becomes repeat and shuffle
- the `CUSTOM_COMMAND_INSTANT_MIX_ON` branch in `onCustomCommand`. `_OFF` never
  had one, so the dead button is deleted rather than fixed
- `Constants.CUSTOM_COMMAND_INSTANT_MIX_ON` and `_OFF`
- `BaseMediaService.onInstantMix` — one caller, no overrides — and with it the
  queue truncation described above
- `res/drawable/ic_instantmix_on.xml`
- the `"instantmix on"` and `"instantmix off"` display names, which were
  hardcoded English literals in a five-locale app. They are deleted rather than
  translated, which is the cheaper of the two ways to stop them being a
  `MissingTranslation` argument waiting to happen

Kept, and worth stating because each looks orphaned at a glance:

- `MediaManager.continuousPlayIsRunning` — still the re-entrancy guard at
  `MediaManager.java:81`. It stops driving button state and nothing else.
- `isInstantMixUsable()` / `setLastInstantMix()` — the ten-second throttle inside
  `continuousPlay`.
- `MediaManager.removeRange` — the queue purge that `continuousPlay` performs on
  its own behalf, keeping `getNumberOfTracksKeepInQueue()` items behind the
  current index. Unrelated to `onInstantMix`'s truncation.

## Decision: Settings gains its first toggle row

`applyArrangement`'s comment already commits this screen to growing — it chose
the open-ended list arrangement for a screen holding one button precisely so that
"adding a row is only adding a row", naming transcoding and ReplayGain as what is
coming. This is the first of those rows, so it sets the pattern they follow, and
is worth building as a pattern rather than as a one-off.

`addToggle(label, initial, onChange)`, a sibling to `addChoice`: a full-width row
at `plex_sign_in_choice_min_height` with the label at the start and a
`MaterialSwitch` at the end (Material 1.10.0 carries it). **The whole row is the
tap target, not the thumb** — the same arm's-length reasoning that zeroes
`MaterialButton`'s insets in `addChoice`. It goes into `choice_container` *above*
Sign out: a destructive terminal action belongs last.

`render()` clears `choice_container` on every pass, so the row reads the
preference each time it is built and holds no state of its own. It writes on
change; there is no Save button, which is ordinary Android settings behaviour and
also the only thing that works on a screen with no commit affordance.

## Decision: one string, self-describing

`car_settings_continuous_play` = **"Keep the music playing"**, plus its four
translations in `values-{de,es,fr,it}`.

Not "Continuous play", which names the mechanism rather than the effect. Not
"Keep playing similar music", which becomes a lie the moment
`isFallbackToRandomTracksEnabled` — default true, no writer — drops the mix to
random tracks. The screen has no summary-line pattern beneath a row, and a label
that describes its own effect does not need one.

## Not in scope

**Whether continuous play belongs in Siskin at all.** This makes the question
answerable rather than answering it: with the automatic trigger off by default
and a switch that turns it on deliberately, the feature can be driven with and
judged. Deleting it now would be answering by fiat, and re-adding it later is a
revert rather than a flag flip.

`PlexMixRepository`, the similar-tracks and random tiers, and
`isFallbackToRandomTracksEnabled` are untouched. The fallback preference keeps its
`true` default and its "no writer" comment.

## Tests

- `Preferences`: the default is `false`, and the setter round-trips. Robolectric
  caches `SharedPreferences` statically across test methods, so `@Before` resets
  the key rather than assuming its absence.
- `BaseSessionCallbackOverflowTest`:
  `theOverflowIsRepeatThenShuffleThenInstantMix` becomes repeat-then-shuffle at
  two entries, and gains an every-state assertion that no instant-mix command is
  offered — the shape `noRatingButtonIsOfferedAtAll` already uses for the heart.
- Settings row: a Robolectric check that the `Connected` state renders the row and
  that toggling it writes the preference. This is the one worth insisting on,
  because "cannot be turned off" is the defect. `CarSignInActivityTest` notes that
  `render()` never runs in its existing tests, so whether `Connected` is cheaply
  reachable is an implementation-time finding; if it is not, the implementation
  says so rather than dropping the test quietly.
- On the emulator: gear → Settings → toggle. Then play an album to its end with
  the switch off and confirm no `MediaManager` line appears in the buffer at all,
  and again with it on and confirm the mix appends.
