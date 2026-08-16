# Plex credentials become a system account

**Date:** 2026-08-16
**Status:** Approved

## Context

Siskin authenticates against Plex and keeps the result in its own
`SharedPreferences`. Google's guidance for Android Automotive OS media apps is
not a suggestion about this:

> Android Automotive OS apps that have authentication **must** use
> `AccountManager`, for the following reasons:
>
> - **Better UX and ease of account management:** users can manage all their
>   accounts from the accounts menu in the system settings, including sign-in
>   and sign-out.
> - **"Guest" experiences:** cars are shared devices, which means OEMs can
>   enable "guest" experiences in the vehicle, where accounts cannot be added.
>   This restriction is achieved using `DISALLOW_MODIFY_ACCOUNTS` for
>   `AccountManager`.

Both reasons are about cars specifically, and both apply here. A head unit is a
shared device in a way a phone is not: the car has profiles, the accounts menu
is where a driver looks to see what they are signed in to, and an OEM can
forbid adding accounts on a guest profile. Siskin currently participates in
none of that. Its sign-out lives only inside its own settings screen, and a
restricted profile discovers the restriction by watching a PIN flow fail.

### How this came up

Sideways, from `android:allowBackup="false"`. That attribute arrived in
`295795ed` — an upstream tempo manifest cleanup from August 2023 — and has
never been a Siskin decision. Asking why backup was off led to asking what
would be *in* a backup, which led to the observation that `App.java` is the
only `getSharedPreferences` call site in the app: the Plex account token, the
server access token, the install identifier and every user setting share one
`<pkg>_preferences.xml`. Extraction rules cut at file granularity, so nothing
could be backed up without backing up the credentials too.

`AccountManager` dissolves that. Account credentials live in the system's
account store, outside the app's data directory, where backup structurally
cannot reach them — not by configuration, but by where they are. That makes
this worth doing on the conformance argument alone, and it happens to leave no
credential in the preferences file at all.

## What this deliberately does not decide

Backup stays off and `android:allowBackup="false"` stays exactly as it is.
Restoring settings — or a sign-in — onto a new car is a separate question that
this document does not answer, and #99's `DataExtractionRules` lint warning
stays honestly blocked on it rather than being resolved by a placeholder.

Two facts are worth recording for whoever picks that up. Android's
[Restore Credentials](https://developer.android.com/identity/sign-in/restore-credentials)
API is the purpose-built mechanism for "new device, already signed in", and it
requires API 35; the target vehicle runs Android 14, so it is out of reach
today. The alternative, Block Store, requires Google Play services, which this
app depends on nowhere — a fact the privacy policy leans on when it says Siskin
talks to no company but Plex.

## What the driver sees

A **Siskin** entry in the car's Settings › Accounts, drawn with the app's icon
and label, with a second line reading `Plex`.

That second line is the account *name*, and it is a constant rather than the
user's Plex identity. Siskin does not know who you are: its entire auth surface
is `POST /pins`, `GET /pins/{id}` and `GET /resources`, none of which return the
account holder. `GET /api/v2/user` would, and was rejected — it is a network
call and a new endpoint added to populate a line nobody reads twice, and it
would mean the app starts holding an identifier for the *person* rather than
only for the installation, which is a distinction `docs/privacy-policy.md`
draws on purpose.

Two fields on `/resources` look like identity and are not. `sourceTitle` is the
*owner's* username, present only on a server shared **with** you, so it would
name someone else on the driver's own head unit. `ownerId` is null for servers
you own.

Removing that entry signs Siskin out. Signing out from inside Siskin removes
that entry. They are the same operation reached from two places.

## Where the two credentials live

`accountToken` becomes the account's **password**. Plex issues no refresh token
and the value does not expire, so it behaves like a password rather than like
an OAuth access token — which is why the password slot fits and the authtoken
slot, with its invalidate-and-refresh machinery, does not.

`serverToken` becomes an **authtoken**, under a type that names the server it
belongs to:

```kotlin
setAuthToken(account, "plex.server:$machineIdentifier", serverToken)
peekAuthToken(account, "plex.server:$machineIdentifier")
```

### Why the type carries the machine identifier

`PlexSession`'s invariant is that `serverUri`, `musicSectionKey` and
`serverToken` never describe *different* servers. Today that holds because all
three are written inside one `edit { }` block. Moving one of them into the
account splits the write in two — a preferences edit and an account write —
with a window in between, and a browse request arriving in that window would
send one server's token to another. The failure is a 401, which
`MediaLibraryServiceCallback` turns into the "sign in again" affordance: a
spurious sign-out prompt, appearing under concurrency, with nothing in the logs
pointing at storage.

Naming the server in the token *type* removes the window instead of narrowing
it. A lookup for the current server returns `null` when the stored token
belongs to a different one, and `serverToken == null` already has a defined
meaning every reader handles — "a server the account owns, which accepts the
account token." The invariant stops depending on two writes landing together
and becomes a property of the data, which is strictly stronger than the single
`edit { }`: it survives someone later splitting that block.

Worst case inside the window is one request made with the account token instead
of a server token, which fails the same way an absent server token already
would, and self-heals on the next read. It is never a request carrying
credentials for a server other than the one being addressed.

**When `machineIdentifier` is null**, the untagged type `plex.server` is used.
Both session writes — `PlexSignInViewModel.chooseLibrary` and
`LibraryPickerRepository.selectLibrary` — stamp it from
`resource.clientIdentifier`, so this is defensive rather than expected. It
degrades to exactly today's guarantee, which is the right floor: dropping the
token instead would break shared servers, which genuinely need it.

Switching servers leaves the previous type's authtoken in the account store.
`invalidateAuthToken` on the way out clears it rather than leaving a credential
for a server the user has left.

## What stays in preferences

`serverUri`, `musicSectionKey` and `machineIdentifier`, written together in one
`edit { }` exactly as they are now, alongside every user setting. None of the
three is a secret; they describe which server and library, not permission to
reach them.

`clientIdentifier` stays too, and is worth naming because it is the one value
here that is neither a credential nor a setting. It identifies the
*installation* — Plex ties the PIN grant to it, and the privacy policy explains
that it is what makes the app appear as one device in a Plex account rather
than a new one after every drive. It is safe in a preferences file and unsafe
in a backup, since restoring it onto a second head unit would make two cars
claim one Plex device identity. Where it should live if backup is ever turned
on belongs to that decision, not this one; nothing here moves it.

## The seam

`PlexApi.session` keeps its signature. Callers still read a `PlexSession?` and
still assign a whole one, and the two session writes in `app/src/main` stay
single assignments — the property CLAUDE.md calls load-bearing is unchanged
from the outside.

Behind it, a new **`PlexAccountStore`** owns every `AccountManager` call: the
account's existence, the password, the tagged authtoken, removal, and the
accounts-updated listener. `PlexApi` holds one and touches the framework
nowhere.

`PlexApi.accountToken` and `PlexApi.serverToken` keep their `var` shape, with
the setters redirected at the store — assigning a token creates or updates the
account, assigning null removes it. That matters more than it looks: twenty-one
test files construct a bare `PlexApi()` and seed credentials through those
properties, and every one of them keeps compiling and passing unchanged. The
alternative — a constructor parameter — would touch all of them plus the
eleven `PlexApi()` sites in `app/src/main`, for no benefit the store's own
tests do not already provide.

## The account type, and the debug suffix

The account type string must be unique per installed app. `applicationId` is
`us.codingismy11to7.siskin` with `applicationIdSuffix = ".debug"` on debug
builds, so a hardcoded type would have the debug and release builds claiming
the same one — a conflict the platform resolves arbitrarily.

`AlbumArtContentProvider` solves the same problem with
`android:authorities="${applicationId}.albumart.provider"`, but that is a
*manifest* placeholder and `res/xml/authenticator.xml` is a resource, where
placeholders do not reach. So the type comes from a `resValue` in
`app/build.gradle`, yielding `us.codingismy11to7.siskin.plex` and
`us.codingismy11to7.siskin.debug.plex`, referenced as
`@string/plex_account_type` from both the XML and the code.

A generated resource also never enters `strings.xml`, so it cannot trip
`MissingTranslation` — which a literal `<string>` would, being untranslatable
prose that lint cannot know is untranslatable without `translatable="false"`.

## Signing in

`CarHostActivity` becomes the authenticator's add-account UI. The
authenticator's `addAccount` returns an `Intent` to it carrying
`EXTRA_FORCE_SIGN_IN`, which is the flag that already distinguishes "the car's
settings gear opened this" from "something needs you to sign in" — so the car's
"Add account" flow joins the existing browse-error `PendingIntent` on a path
that exists, rather than adding a third.

Sign-in creates the account instead of writing a token to preferences, and the
constant account name is what keeps that simple: there is one possible account,
so the rule "exactly one, ever" needs no enforcement beyond addressing it by
name. Concretely, setting the account token adds the account when it is absent
and sets its password when it is present.

**It must not be implemented as remove-then-add.** Removal fires the
accounts-updated listener described below, which clears the session — so a
sign-in that removed the old account first would trip its own sign-out handler
partway through and land in a state neither path intended.

## Signing out, from either direction

`PlexAccountStore` registers an accounts-updated listener. Removal — whether
from the car's Settings or from Siskin's own Sign out — clears the preferences
half of the session and invalidates the media session, so the car stops showing
a library it can no longer fetch. Both paths converge on one method; neither is
the special case.

## Restricted profiles

A profile carrying `DISALLOW_MODIFY_ACCOUNTS` cannot add an account, so sign-in
is not merely likely to fail — it is impossible. The sign-in screen checks
`UserManager.hasUserRestriction` before starting, and shows a state saying
sign-in is unavailable for this profile, rather than running the PIN flow to a
dead end where the failure looks like Plex being unreachable.

One new user-facing string, and therefore four translations with it, in
`res/values-{de,es,fr,it}/strings.xml`.

## No migration

Existing installs sign in again. Siskin ships on `automotive:internal` to a
readership of about two, so a migration path would be code written once, run
twice, and maintained forever.

One thing is not skipped: the stale credential keys are **deleted** from
preferences on first run after the upgrade. That is hygiene rather than
migration — a dead account token left in a file that may later be backed up is
exactly the kind of thing that outlives the reason it was there.

Skipping migration also removes the null-`machineIdentifier` case from the
tagging scheme in practice, since every session written from here on carries
one.

## No new permissions

`GET_ACCOUNTS` is not required to see accounts whose authenticator is the
calling app itself — the platform grants that on a signature match — and
`AUTHENTICATE_ACCOUNTS` was deprecated in API 23 and is unnecessary for an
app's own authenticator. The permissions table in `docs/privacy-policy.md` is
unchanged.

## Testing

`PlexAccountStore` gets a fake, so `PlexApi`'s existing tests keep their current
shape and do not gain a second reason to need Robolectric.

The store itself is tested under Robolectric against `ShadowAccountManager`:

- an account token round-trips through the password slot
- a server token stored for one machine identifier reads back as `null` for
  another — the invariant, asserted directly
- a null machine identifier falls back to the untagged type rather than
  dropping the token
- removing the account clears the preferences half of the session
- signing in twice leaves one account, not two

## Documents affected

`docs/privacy-policy.md` says the tokens are kept "in its own private storage on
the head unit." That stays true in substance — the account store is on the head
unit and is not readable by other apps — but the tokens are now held by the
system on Siskin's behalf and the account is visible in Settings, so the
paragraph gets an honest amendment rather than being left to imply a private
file.

`CHANGELOG.md` gets a bullet under `[Unreleased]`, at the top: sign-out moving
into the car's own Settings is user-visible behaviour.

## Alternatives considered

**Five separate userdata keys**, the idiomatic `AccountManager` shape. Rejected
because `setUserData` writes one key per call, so a session change becomes five
writes with four windows — reintroducing, with more surface, the exact race
described above.

**The whole session as one JSON value under a single userdata key.** This keeps
atomicity and would leave preferences holding nothing but settings, which makes
a future backup phase free. Rejected as more movement than the conformance goal
needs: it relocates three values that are not secrets, and buys atomicity that
tagging already provides without a blob where a reader expects keys.

**Splitting `<pkg>_preferences.xml` into a settings file and a credentials
file.** This was the obvious answer before `AccountManager` came up, and it is
strictly worse: it maintains a boundary by configuration that the account store
provides by construction, and nothing stops a later commit from writing a
secret to the wrong file.

**Naming the account after the server**, using `Resource.name` — already parsed,
no new endpoint. Rejected because it names the wrong noun: it is an account row,
and selecting a different library on the same account would rename the account.
