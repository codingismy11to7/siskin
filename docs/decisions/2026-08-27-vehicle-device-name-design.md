# The Plex device row names the car

Every Siskin install is the same row in the Plex account's device list —
`Automotive` / `automotive`, with no device name at all — because
`PlexIdentity` sends those two words as constants. Two cars are two
indistinguishable rows, which is a problem at exactly one moment: revoking the
one you sold.

The vehicle knows what it is. `CarPropertyManager` answers `INFO_MAKE`,
`INFO_MODEL` and `INFO_MODEL_YEAR`, and the row becomes `2024 Cadillac LYRIQ`.
See #153.

## Three things the issue was unsure about, settled on the emulator

The issue listed these as "to verify before building", and two of the three
came back the opposite of what it feared.

**`android.car.permission.CAR_INFO` is `protectionLevel: normal`.** Granted at
install, no runtime dialog. The issue's stated reason to abandon the whole
approach — "a permission dialog in a car for the sake of a nicer row in a
device list is a bad trade" — does not arise.

**The emulator reports real values.** `sdk_gcar_x86_64` answers `Toy Vehicle`,
`Speedy Model` and `2020`. The issue guessed the fallback path would be the
one exercised in development; it is the other way round, and the fallback is
what needs deliberate testing.

**Plex's display semantics remain unverified**, and the design routes around
the question rather than answering it — see below.

## All three headers carry it

`X-Plex-Device-Name`, `X-Plex-Device` and `X-Plex-Model` are all sent:

```
X-Plex-Device-Name: 2024 Cadillac LYRIQ
X-Plex-Device:      Cadillac
X-Plex-Model:       LYRIQ
```

Which of the three plex.tv's device list actually renders was the issue's third
unknown, and it needs a real account to settle. Sending all three makes it moot:
whichever field the list picks, the row reads as the car. The alternative —
determine the field, then populate only that one — buys a tidier request in
exchange for a dependency on an undocumented UI detail that Plex can change
without telling anyone.

The cost is that `Automotive` stops being sent as a device *class*. Nothing is
known to consume it — Plex has no automotive device icon — and a row that names
the car is worth more than a class signal with no observed reader.

The model year goes in `X-Plex-Device-Name` only. It is how people name a car,
and it separates two of the same model; `X-Plex-Device` and `X-Plex-Model` stay
clean make and model so they read like every other Plex client's. A missing
year drops out of the name and changes nothing else.

## Three tiers, applied whole

| Tier | Source | Condition |
|---|---|---|
| `VEHICLE` | `INFO_MAKE`, `INFO_MODEL`, `INFO_MODEL_YEAR` | make and model both non-blank; year optional |
| `BUILD` | `Build.MANUFACTURER`, `Build.MODEL` | both non-blank; no year |
| `UNKNOWN` | today's constants | anything else |

A tier is chosen as a unit rather than per field, so a row never mixes a real
make with a placeholder model. Blank and whitespace count as absent, the way
`PlexIdentity` already treats a blank token and a blank language.

`Build.*` sits in the middle despite the issue rejecting it as the *primary*
source, and the rejection was right: it names whatever the OEM put in the
build, which on this emulator is `Google` / `Android SDK built for x86_64`.
As a fallback the calculus inverts. On real OEM hardware it may well name the
car, and nothing is lost when it does not — a head unit that answers neither
`CarPropertyManager` nor a useful `Build.MODEL` was going to show a useless row
either way.

At `UNKNOWN` no `X-Plex-Device-Name` is sent at all. There is nothing to name,
and `Automotive` as a device *name* would be the same undifferentiated row this
change exists to fix. `X-Plex-Device` and `X-Plex-Model` keep today's constants,
so a car that tells us nothing looks exactly as it does now.

## Where the code goes, and why the seam is there

Three pieces, split on the line `PlexIdentity` already draws between a pure
core and a framework edge.

**`car/VehicleIdentity.kt`** is a value and the tier logic, in vehicle terms
only — `make`, `model`, `year`, `source`. It knows nothing about Plex and is
where the ladder above is unit-tested.

**`car/VehicleInfoReader.kt`** is the framework edge. `start(context)` from
`App.onCreate` hands a background executor `Car.createCar` → `getCarManager` →
read three properties → `disconnect`, caching the result in a `@Volatile`
field. `identity()` returns it, or `UNKNOWN` until it lands.

**`plex/PlexIdentity.kt`** maps a `VehicleIdentity` to the three headers. The
Plex vocabulary stays here, next to the constants it already owns, so the
dependency runs Plex → car and never back.

`PlexApi` grows `val vehicle` beside `appVersion` and `language`, and the four
`PlexIdentity.headers(...)` call sites pass it.

The seam is not decoration. `android.car.jar` is not on the unit-test
classpath and Robolectric has no shadow for it, so anything touching
`CarPropertyManager` cannot be unit-tested at all. Putting every `android.car`
type inside a private method body — never a field type, never a public
signature — keeps `VehicleInfoReader` loadable in Robolectric tests that never
call `start`, which matters because existing tests construct `PlexApi` and call
`plexTvHeaders()`.

## Read once, at startup, in memory

`App.onCreate` starts a background read; the result is cached for the process
and the `Car` object is disconnected immediately rather than held open. A car's
make and model do not change under a running process, so there is nothing to
invalidate.

Persisting it to preferences was considered and rejected. It would close a
narrow window — the first requests of the first run could fall back — at the
cost of a stored value that survives into a new car, which is precisely the
staleness #152 is about. Resolving lazily on the first header build was also
rejected: it puts a system-service connect on whatever thread asked, which is
an OkHttp dispatcher thread.

The window it leaves open is small. Sign-in needs a tap and browse needs the
car's media UI, both far later than `onCreate`. A request that does race the
read gets the `UNKNOWN` tier for that request only.

## `<uses-library>` stays `required="false"`, for a different reason than #153 gave

The issue framed `required="false"` as defending against a head unit lacking
the library. That is not what it does.

`android.car` is **not** in the package manager's shared-library registry on
the AAOS emulator image — `dumpsys package libraries` does not list it, and
`/system/etc/permissions/` does not declare it. The classes are on the
`BOOTCLASSPATH` instead, as `/system/framework/android.car.jar`. A
`<uses-library>` is checked against that registry and not against whether the
classes resolve, so `required="true"` fails the install with
`INSTALL_FAILED_MISSING_SHARED_LIBRARY` on an image where the classes are in
fact always present.

So the tag's job is covering two different ways platforms ship `android.car`:
where it is a registered shared library the tag links it onto the classloader
path, and where it is on the boot classpath the tag is an ignored no-op.
`required="false"` is what makes both work. This is worth writing down because
`required="true"` is the tempting reading — the app already requires
`android.hardware.type.automotive`, so "the library is always there" is true,
and acting on it breaks the install.

`useLibrary 'android.car'` in `app/build.gradle` is a separate matter and
unaffected: that is the compile classpath, and `android.car.jar` is not in
`android.jar`. It resolves from the compileSdk platform, where `optional.json`
registers it with `"manifest": false` — which is also what confirms AGP will
not inject the manifest tag on our behalf.

## Failures are absorbed, never propagated

One broad `catch (Throwable)` around the whole read. A denied permission raises
`SecurityException`, an unsupported property `IllegalArgumentException`, a car
service that is not up `CarNotConnectedException` or a null from
`Car.createCar`. All of them mean the same thing — leave the cache unset and
keep answering `UNKNOWN`.

`Throwable` rather than `Exception`, and the reason is not hypothetical.
`compileSdk` is 37 while `minSdk` is 28 and a head unit runs whatever it runs,
so every `android.car` method newer than the oldest runtime Siskin supports is
a `NoSuchMethodError` waiting to happen — and that is an `Error`, which
`catch (Exception)` does not see.

This was demonstrated rather than imagined. The first draft called
`getCarManager(Class<T>)`, an overload that exists in compileSdk 37's
`android.car.jar` and not in API 33's; the resulting uncaught `Error` killed
the reader thread, crash-looped the app on every launch, and wedged
`com.android.car.media` along with it. The call now uses the String-keyed
overload every version carries, and the broad catch is what makes the next such
mismatch a fallback instead of a crash loop.

The catch is broad and that is deliberate, but note it is nowhere near an
`either { }` block — the hazard the Arrow rule exists for does not apply here.

These headers ride on every request including the first `POST /pins`. A vehicle
that will not answer must never be able to fail a request.

## Non-ASCII names go out as UTF-8, because Plex asks for them that way

Škoda and Citroën ship Android Automotive, and OkHttp refuses to send a header
value outside `0x20–0x7E` — it throws rather than degrading, and on the Retrofit
path that lands uncaught on the dispatcher thread and takes the process with it.
These headers ride on the first `POST /pins`, so an affected car could not sign
in at all. The emulator reports `Toy Vehicle`, so nothing here would have shown
up in development.

Plex's own documentation answers the question:

> There's no standard way to send non-ASCII values as HTTP headers. We attempt
> to recognize and parse UTF-8 and ISO-8859-1. If you're sending something that
> may include non-ASCII characters (often `X-Plex-Device-Name`), use UTF-8 if
> possible.

So the name goes out unchanged, through `Headers.Builder.addUnsafeNonAscii`.
"Unsafe" there means "not what RFC 9110 prescribes", which is precisely what
Plex is asking for. Transliterating `Škoda` to `Skoda` was the alternative and
is strictly worse — it damages the name to avoid a problem the server does not
have. Percent-encoding is worse still: `X-Plex-Device` is proprietary, nothing
decodes it, and plex.tv would display the escape sequence.

This is a known rough edge rather than a novel one. Overseerr reported the
identical `Invalid character in header content ["X-Plex-Device-Name"]` for a
server name containing an emoji.

Control characters are still stripped, and in `VehicleIdentity` rather than at
the transport: a newline in a vendor string is header injection, and that is a
property of the value rather than of how it is sent. Keeping it in the pure
layer also keeps the Debug screen honest, since the panel renders the same
values that go on the wire.

## The Debug screen reports the tier, not just the value

A `Device` section on the Debug screen (Settings → version line) shows the three
header values and which tier produced them.

The tier is the point. `Cadillac / LYRIQ` from `CarPropertyManager` and
`Cadillac / LYRIQ` from `Build.*` are indistinguishable in the Plex row, and on
real hardware there is otherwise no way to tell whether the vehicle answered or
`Build.*` quietly covered for it. Every existing `debug_` string is
`translatable="false"` by the convention that panel already follows, so these
are too — the section costs no translations, which is most of why it is cheap
enough to include.

## Documentation

`docs/privacy-policy.md` changes in two places. The permissions table gains a
`CAR_INFO` row. The passage stating the install identifier is "created on the
head unit and not read from the vehicle or the operating system" stays true of
the identifier, but the app now reads something *else* from the vehicle and
sends it to Plex, and the policy should say so in plain words rather than leave
a reader to infer it from a table. Plex learning what car you drive is a real,
if mild, new disclosure.

Play's Data safety declaration does not change. The taxonomy has no data type
for device make or model, and "Device or other IDs" is defined as identifiers —
"an IMEI number, MAC address, Widevine Device ID, Firebase installation ID, or
advertising identifier" — which make and model are not.

## Testing

`VehicleIdentityTest` covers the ladder as a pure function: each tier, year
present and absent, blank and whitespace inputs, and make-without-model landing
on `BUILD`. `PlexIdentityTest` extends to the three headers at the `VEHICLE`
tier, the year appearing in `X-Plex-Device-Name` and nowhere else, and
`X-Plex-Device-Name` being *absent* at `UNKNOWN` — that last one is the guard
that a car which says nothing still sends exactly today's headers.

`VehicleInfoReader` gets no unit test, for the classpath reason above. It is
verified on the emulator, where the expected result is now known: `2020 Toy
Vehicle / Speedy Model`, source `VEHICLE`.

## What this does not decide

The client identifier. #153 argues that naming the row and carrying the
identifier across a trade-in are mutually exclusive goods, and that the naming
is an argument for a fresh identifier per car. That argument is input to #152;
nothing about identifier behaviour changes here.
