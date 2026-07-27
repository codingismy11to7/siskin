# Drop the phone launcher entry and declare Siskin an automotive app

**Date:** 2026-07-27
**Status:** Approved

## Context

Siskin exists to run natively on Android Automotive OS. Phone-specific surface is
inherited from upstream, not something the fork invests in.

Two things still contradict that. The app advertises `MainActivity` with
`android.intent.category.LAUNCHER`, so a head unit shows a Siskin icon in the App
Grid *alongside* its media source entry — two entries for one app, one of which
opens a phone UI. And the manifest never declares
`android.hardware.type.automotive`, so nothing in the package identifies it as an
automotive app.

This lands after the AAOS sign-in flow, and could not have landed before it. Until
that flow existed, the launcher icon was the only way to configure a server on a
head unit. Removing it first would have left the app unconfigurable in a car. That
sequencing was recorded in `2026-07-27-aaos-sign-in-design.md` and is now
satisfied.

### No conditional treatment

An earlier draft of this design proposed hiding the launcher entry only on
automotive — a `values-car` boolean, or `setComponentEnabledSetting` gated on
`FEATURE_AUTOMOTIVE`. That was wrong. Siskin has no phone audience to preserve;
the F-Droid/IzzyOnDroid path was dropped during the rebrand precisely because AAOS
distributes through Play Automotive. Conditionality would buy nothing and cost a
detection mechanism, a resource qualifier or a persisted component state.

The launcher entry is simply removed.

## Decisions

### Remove the `MAIN`/`LAUNCHER` intent-filter, keep the activity

`MainActivity` carries two intent-filters. Only the launcher one goes; the
`VIEW`/`BROWSABLE` `tempo://asset` deep link stays, and so does
`android:exported="true"`, which that deep link requires.

`MainActivity` itself is **not** deleted or disabled. It remains launchable by
explicit component intent, which matters for the next decision.

### Declare `android.hardware.type.automotive`

Added as `required="true"`. The emulator reports the feature
(`pm list features | grep automotive`), so it does not interfere with the existing
test loop.

### Point the crash handler at `MainActivity` explicitly

`App.java:44` reads:

```java
.restartActivity(null) //default: null (your app's launch activity)
```

CustomActivityOnCrash resolves that fallback through
`PackageManager.getLaunchIntentForPackage()`, which returns null once no `LAUNCHER`
activity exists. The **Restart app** button on the crash screen would silently stop
restarting anything.

This is a real coupling to the launcher entry, not incidental cleanup, so it
belongs in this change rather than a follow-up. Fixed by naming the class:
`.restartActivity(MainActivity.class)`.

### Why the session-activity route is expected to survive

A head unit may offer to open an app's own UI while parked. If that affordance
depends on the `LAUNCHER` category, this change removes it.

`BaseMediaService.kt:674-680` builds the media session's activity from an
**explicit** intent, not a launcher lookup:

```kotlin
TaskStackBuilder.create(this).run {
    addNextIntent(Intent(baseContext, MainActivity::class.java))
    getPendingIntent(0, FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)
}
```

That `PendingIntent` is handed to `MediaLibrarySession.Builder.setSessionActivity`,
which is the standard route by which a car media UI opens the owning app. It does
not consult `android.intent.category.LAUNCHER`, so removing the launcher entry
leaves it intact.

**This is a hypothesis, not a finding.** It is offered as the reason to expect the
parked-UI affordance to survive, and as the first thing to check if it does not.

## Scope

**In:** the launcher intent-filter removal, the `uses-feature` declaration, the
crash-handler fix.

**Out:** deleting `MainActivity` or the phone UI; removing the Google Cast
dependency (a known candidate, but casting from a car is a separate argument);
anything about Play tracks or store listings.

## Verification

On the emulator:

- `adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LAUNCHER` returns nothing for Siskin.
- Siskin no longer appears in the App Grid, but still appears in the media source chooser.
- Sign-in and browse still work end to end.
- The `tempo://asset` deep link still resolves to `MainActivity`.
- Install succeeds with `required="true"`.

Not verifiable here, and the thing to watch when this reaches a real head unit:

- Whether the parked "open app UI" affordance survives. The emulator does not
  present one, so its behaviour is unobservable in this environment.
- Whether the crash screen's **Restart app** button works — reachable only by
  provoking a crash on-device.
