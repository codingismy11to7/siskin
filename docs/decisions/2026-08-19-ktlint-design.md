# ktlint gates Kotlin style, and the tree is formatted to match

Two checks gated this repository and neither had an opinion about style.
`lintDebug` is Android lint — correctness, API levels, resources. `-Werror` on
both compilers is the compilers' own warnings. Indentation, wrapping, import
order, trailing commas, chain formatting: nothing had ever looked at any of it
across 170 Kotlin files, most inherited from tempo and the rest written by hand.

This is the third axis. See #126 for the survey that preceded it.

## Why a Gradle plugin, and not the ktlint CLI in CI

#126 argued that CI runs on plain Ubuntu with no Nix, so the gate could not be
the flake's ktlint and had to come through Gradle. **That reasoning is wrong**
and worth recording as wrong, because it would justify the same move for the
next tool. ktlint ships as a single self-executing jar; a `curl` and a
`chmod` in `ci.yml` would have run the *identical binary the editor runs*, with
no plugin between them and no second version to keep aligned.

The real argument is ergonomic and it is enough on its own: every other check in
this project is a Gradle verb, and style should not be the one exception that
lives in YAML. `./gradlew ktlintCheck` sits next to `testDebugUnitTest` and
`lintDebug` rather than beside them in a different language. A `curl`-and-`chmod`
stanza in the middle of a workflow whose every other step is `./gradlew` is a
thing you have to read twice.

The plugin also makes the sweep reproducible by anyone: `./gradlew ktlintFormat`
is the same command on any machine, where the CLI is the same command only for
whoever has the dev shell.

What it costs is a second version to keep aligned, which the next section is
about.

## The version is pinned to the flake, not to the plugin

`libs.versions.toml` pins ktlint to 1.8.0 explicitly rather than accepting
`org.jlleitschuh.gradle.ktlint`'s own default, and that number has to equal
`flake.nix`'s `pkgs.ktlint`.

The failure mode this guards is specific: the editor lints from the dev shell on
every write, CI lints from Gradle, and two ktlints that disagree about the same
file produce a buffer that is clean and a build that is red. An editor that
disagrees with CI is an editor you learn to ignore, which costs more than the
check was ever worth.

Neither pin is automatic. A `flake.lock` update can move `pkgs.ktlint` without
touching `libs.versions.toml`, and nothing detects the drift — the symptom is a
diff appearing or disappearing under `ktlintFormat`. Checking them against each
other belongs to whoever bumps either.

## `ktlint_official`, and deliberately no `.editorconfig`

ktlint's default code style since 1.0 is `ktlint_official`, which is what the
editor already runs and what produced the 3,021 violations #126 measured. The
looser `intellij_idea` produces 1,213 — closer to what the code already looked
like, and rejected for exactly that reason: matching the editor is the whole
point.

**`.editorconfig` was not committed, and that is a decision rather than an
oversight.** #126 asked for one either way, on the grounds that it survives a
change in ktlint's own default. The counter-argument is that it would not: a
committed `ktlint_code_style = ktlint_official` pins a *name* whose meaning is
exactly what moves between versions, and a new default is as likely to be a new
rule as a changed option — which an `.editorconfig` does not enumerate at all.
It would buy the appearance of a frozen style without the substance.

So the policy is to track ktlint's defaults, and to take a reformat when a
version bump brings one. That is coherent with the premise: the style was chosen
*because* it is what the editor runs by default, so following the default is the
choice, not the absence of one. What actually holds the two ends together is the
version pin above, and that is where attention belongs on a bump.

Indentation stays at four spaces, which is Kotlin's own guide ("Use four spaces
for indentation. Do not use tabs.") and Android's. Two was considered and
dropped: ktlint would enforce it happily, but every library source you step into
and every snippet you paste is four, and the argument for changing is weaker
than it looks once the Java and XML in the tree are counted.

## `lint` and `lintFix` are AGP's, and that turned out to be the good shape

The obvious names were taken. AGP already registers `lint` **and** `lintFix` on
`:app`, the second being Android lint's own autofix pass. Rather than working
around the collision, `ktlintCheck` and `ktlintFormat` hang off them:

    tasks.named('lint') { dependsOn 'ktlintCheck' }
    tasks.named('lintFix') { dependsOn 'ktlintFormat' }

Both AGP tasks are thin umbrellas — `lint` runs `lintDebug`, `lintFix` runs
`lintFixDebug` — so this costs nothing and makes one command cover correctness
and style together. `ktlint` and `ktlintFix` are registered as the style-only
half, named to mirror them.

A task named `ktlint` sits in the same project as the plugin's `ktlint { }`
extension, which looks like it should be ambiguous. It is not: extensions win
regardless of declaration order. This was probed rather than assumed, because
the failure would have been silent — a `ktlint { version = ... }` block binding
to the task instead would have set `project.version` and left ktlint on the
plugin's default with no error anywhere.

Colons are not available in task names. `lint:fix` parses as task `fix` in
project `lint`; the colon is Gradle's project-path separator, not an npm-style
name separator.

## The sweep is alone in its commit, and the seven land ahead of it

`ktlintFormat` reaches all but seven violations. The work landed as three
commits in that order — plugin, then the seven by hand, then the sweep — and the
ordering is load-bearing rather than tidy.

`git blame` does not *skip* an ignored revision. It re-attributes: the line is
pushed to the parent by matching it against its pre-change form. Pure
reformatting maps cleanly; a real edit hidden inside the sweep could not be
mapped and would land on whatever line it replaced, permanently and invisibly.
So the sweep contains nothing but reformatting, and the seven judgement calls
sit in the commit before it, outside the ignore list.

Landing the seven *before* rather than after also means the sweep is the last
content change, so nothing has to rebase across it twice.

`.git-blame-ignore-revs` could not ride along: a commit cannot contain its own
sha, and a rebase-merge mints a different one from the branch's regardless. It
is a follow-up written against what landed.

## `ktlintFormat` converges in two passes

The first run exits FAILED with violations that only became reachable once its
own rewrites were in place; the second is clean. A single run therefore looks
like it failed when it has merely not finished.

This matters because re-running the formatter is the documented way to resolve a
conflict when rebasing across the sweep — and someone doing that, seeing FAILED,
would reasonably conclude the sweep was broken.

## The formatter is not warning-neutral

A reformat across 161 files is not automatically behaviour-preserving, and with
`allWarningsAsErrors` it is not automatically *compiling* either. One case
surfaced:

    - is PlexSignInState.Connected -> Unit
    + is PlexSignInState.Connected -> { Unit }

`statement-wrapping` turned an expression branch into a block. As an expression,
`Unit` was the branch's value; inside a block it is a statement whose value is
discarded, which Kotlin warns about and `-Werror` rejects. `-> {}` says the same
thing and survives the formatter.

One in 161 files is a low rate, but it is not zero, and "a reformat cannot break
anything" is the assumption that would have missed it.

## Style is its own CI job

`ktlintCheck` is four seconds. Android lint is about sixty. They are one
`./gradlew lint` invocation locally and two jobs in CI, which is not about speed
so much as honesty: a job called "Unit tests and debug build" that also lints is
misnamed, and a style error reporting from behind four minutes of tests and an
APK build it does not need is a slow way to learn about a trailing comma. The
lint job compiles the sources again rather than inheriting them, which is the
price.

**Splitting a check out of a job silently un-gates it.** The repository ruleset
requires named status checks, and it named only "Unit tests and debug build" —
so the moment lint moved to its own job, a red lint stopped blocking a merge.
That is the same shape as the problem #126 opens with: a check nobody runs is
not a check, and a check that cannot block is not a gate. The ruleset was
updated by hand. Nothing detects this class of drift; a workflow job name and a
ruleset entry are two files that have to agree and only one of them is in the
repository.

## `MediaLibraryServiceCallback.kt` held `MediaLibrarySessionCallback`

ktlint's `filename` rule surfaced a real finding rather than a style nit. The
file and the class it contained had disagreed since the fork, and everything
except the filename agreed with the class: its own log tag, its superclass
`BaseSessionCallback`, its three test files, and media3's own type — it
implements `MediaLibrarySession.Callback`, so the *session* is what it is a
callback for.

The wrong spelling had spread to about twenty comments and KDoc references,
including two links that resolved to nothing. Those were corrected along with
the rename. The fourteen occurrences across ten files in `docs/decisions/` were
deliberately left alone: those record what the class was called when they were
written, and rewriting them would be tidier and also a small lie.

## The editor fixes style before the build ever checks it

A `PostToolUse` hook in `.claude/settings.json` runs `ktlint --format` on every
`.kt` file Claude writes. It is checked in rather than personal because it is a
property of the repository's style, not of one machine.

It reads `file_path` out of the hook payload with `sed` rather than `jq` —
neither `jq` nor `python3` is on PATH here, and adding one to the flake to parse
a single field would be a dependency bought for nothing.

Its exit code is deliberately not swallowed. A non-zero exit means ktlint found
something it cannot correct, which is a real edit to make; suppressing it would
reproduce, in the editor, exactly the "a check nobody executes" failure this
whole change exists to remove. Missing `ktlint` on PATH is a silent no-op, so
the hook degrades rather than breaks outside the dev shell.

## Not covered

- **#99 and #69** — Android lint warnings and build warnings. Different tools,
  different axis, unchanged by any of this.
- **The 19 Java files.** ktlint does not read them. They are also all
  convertible in principle, which is worth saying because "MediaManager cannot
  be Kotlin" is wrong — Java cannot *call* suspend functions, which is why
  `PlexScrobbler` exists as a bridge; converting `MediaManager` would remove the
  reason for the bridge rather than being blocked by it.
- **`.editorconfig`.** Deliberately absent. See above.
- **Ruleset as code.** GitHub has no `.github/rulesets/` it watches; it offers
  JSON export and import, the REST API, and a Terraform resource. A CI step
  asserting the workflow's job names against the ruleset's required checks would
  have caught the un-gating described above, and does not exist.
- **No changelog entry.** Nothing here changes what the app does in the car.
