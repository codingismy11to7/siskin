# Restore the car's rating control, delete the heart

**Date:** 2026-08-02
**Status:** Approved

## Context

Reverses the rating half of
[the car UI design sweep](2026-07-29-car-ui-design-sweep-design.md), and corrects
a claim in [the browse/playback spec](2026-07-28-plex-browse-playback-design.md)
that was load-bearing for both.

## Why

The sweep gave the transport-row slot to our own heart button by publishing no
rating at all, on the reasoning that two affordances for one action is one too
many and the app should own the surviving one. That worked, and the result is
visibly wrong in the car: the heart is our drawable rendered among AOSP's, and it
does not match the icons either side of it.

The car's own control matches, because it is the car's. Reversing the sweep costs
the heart, and the heart was only ever a stand-in for the control we were
suppressing.

## What was measured

Both halves were probed on the API 33 AAOS emulator against a live Plex account
before any of this was designed, because the question is entirely about what
`com.android.car.media` does with what we publish, and nothing in the app can
answer it.

**Five stars are not available, and the previously recorded reason is wrong.**
The browse/playback spec said:

> media3 has no successor to `MediaSessionCompat.setRatingType(RATING_5_STARS)`,
> and `androidx.media3.session.MediaConstants` carries no rating constant at all.

Publishing `StarRating(5, …)` disproves it. `dumpsys media_session` reported
`rating type=5` on the legacy session, so media3 does derive and forward the
rating type — from the `Rating` subtype in metadata, which is why no constant is
needed. What actually blocks five stars is one layer further out: the car
declines to draw a widget for `RATING_5_STARS`, leaving the row unchanged and our
heart still in the slot. The conclusion the old spec reached survives; its stated
reason does not, and a future reader following that reasoning would conclude the
API is missing rather than that the car is selective.

The spec's *other* argument against five stars stands on its own and is the real
one: five tap targets is not a driving input.

**`HeartRating` restores the control, and it clears.** Published, the car draws
its rating widget left of transport and our heart drops to the overflow. Tapping
it arrives at `onSetRating` as a `HeartRating` with no custom command in between:

| tap | request | result |
|---|---|---|
| unfilled star | `/:/rate?key=346475&rating=10` | 200, filled |
| filled star | `/:/rate?key=346475&rating=-1` | 200, unfilled |

The sweep had only ever observed the setting direction, which left "the car's
widget may be one-way" as an open risk against this change. It is not one-way.
Both directions drive the code this change keeps.

## Decision: publish `HeartRating` again

`PlexMediaMapper.buildTrackMediaItem` sets `userRating` from `isHearted`. That
one line is the whole mechanism — the car reads the rating type off the `Rating`
subtype, so publishing the field *is* asking for the widget.

`EXTRA_HEARTED` and `readHearted` go with it. They exist only because the field
was unpublished: `readHearted` prefers `userRating` and falls back to the extra,
and once every mapped item carries a `userRating` the fallback can never fire.
`readTrackFields` reads the `HeartRating` directly. The Room entities are
untouched — they persist `isHearted` as a boolean and rebuild through
`buildTrackMediaItem`, so the round trip still lands in the published field.

## Decision: delete the heart button and its state plumbing

Not merely demote it. With the star present the heart sits in the overflow doing
what the row above it already does, which is the redundancy the sweep set out to
remove — the sweep just resolved it in the other direction.

Removed: the three heart `CommandButton`s, their `Constants`, the per-item
`setSupportedCommands`, the `isRatingPending` parameter threaded through
`updateMediaNotificationCustomLayout` / `buildMediaButtonPreferences` /
`buildCustomLayout`, the `HEART_ON` / `HEART_OFF` arm of `onCustomCommand`, the
`ic_favorite`, `ic_favorites_outlined` and `ic_bookmark_sync` drawables, and the
three `exo_controls_heart_*` strings. All are heart-only.

`isRatingPending` deserves its own note, because the code and its comments
describe a feature that does not exist. `customCommandToggleHeartLoading` is
built, given `ic_bookmark_sync`, and registered as an available session command —
but `getCommandButton` never returns it. The `"[heartID]"` branch returns `null`
while a rating is pending, so the button is *dropped* for the duration of the
request rather than swapped for a loading icon, and `ic_bookmark_sync` has never
rendered. It is a static vector regardless; there is no spinner anywhere in this.

What that produces in the car is a defect, and it is what prompted this pass as
much as the mismatched icon did: the layout is one button shorter while the
request is in flight, so repeat slides up into the vacated slot. A tap on the
heart makes a dimmed repeat glyph appear where the heart was, for as long as the
GET takes, and then the heart returns. Nothing in the code says this is what
happens — the comment on `onSetRating`'s unconditional layout rebuild explains it
as leaving the button "spinning forever" if skipped, which describes an icon that
is never shown.

So the pending state has no behaviour to preserve, and deleting it removes a
visual artifact rather than a feature. The comment goes with it.

Kept: `onSetRating`, `applyRatingToQueue`, `sessionResultFor`, and the main-thread
scope with the reasoning attached to it. The star taps into all of them unchanged.
`applyRatingToQueue` becomes more load-bearing, not less — it is now the only
thing that keeps the widget's filled state correct after a tap.

Two paths are hardened because the car is now their only caller: `rating as
HeartRating` becomes a checked cast returning `ERROR_NOT_SUPPORTED`, and the
no-mediaId overload's `currentMediaItem!!` gets the same treatment. Neither is
reachable today — the car sends what we publish, and it sends it about the
current item — but a `ClassCastException` or NPE on a car-driven path fails as an
uncaught crash rather than as a session error.

## Decision: delete the overflow-ordering preferences

`buildCustomLayout` ordered the overflow from `CUSTOM_COMMAND_FIRST_BUTTON` and
`CUSTOM_COMMAND_SECOND_BUTTON`, inherited from tempo, where a settings screen
wrote them. This fork deleted that screen in the three-tab rip-out and nothing has
written either key since, so both always returned their defaults and the
"configurable" order was a fixed one spelled indirectly — through two preference
reads and a string-id dispatch in `getCommandButton`.

The heart's removal makes that visible, since the first default *was*
`"[heartID]"`. `buildCustomLayout` returns repeat, shuffle and instant mix
directly; the accessors, their keys and the string dispatch go.

This is the opposite call from the browse/playback spec's `isFallbackToRandom
TracksEnabled`, which kept a preference the UI no longer set so a later settings
surface would have something to bind to. The difference is what the preference
holds: that one carries a decision worth re-exposing, this one carries the order
of three overflow buttons.

## Not in scope

Rating anything other than the current track — the widget is a Now Playing
control and browse rows carry no rating affordance either way. Restyling the
car's widget, which is not ours. Any settings surface for the overflow.

## Tests

`BaseSessionCallbackRatingTest` is untouched by design: it drives `onSetRating`
directly, which is exactly the path that survives, so it goes on pinning the
`SessionError` mechanism and the rating directions without knowing the button is
gone. Its continued passing is the evidence that deleting the button did not
disturb rating.

The heart-state assertions invert rather than disappear.
`PlexMediaMapperAssemblyTest.heartStateRidesInTheExtrasBundleAndNotInUserRating`
becomes its opposite, and its `assertNull` on `userRating` — which guarded the
sweep's reason for the extras bundle — becomes the assertion that the field is
published, guarding this one. The `readHearted` call sites there and in
`RoomEntityRoundTripTest` read `userRating` instead. The "a tap outranks the
mapped value" test is deleted rather than adapted: it pinned the precedence rule
between two sources, and there is one source now.

Two cases are added: that a checked cast turns a non-heart `Rating` into
`ERROR_NOT_SUPPORTED` rather than a `ClassCastException`, and that the overflow
is the three buttons in order with no heart among them.
