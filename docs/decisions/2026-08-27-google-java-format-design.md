# Java is formatted by google-java-format in AOSP style

ktlint gave 170 Kotlin files an opinion about style and left the 19 Java files
without one — 2,908 lines, all in `app/src/main/java`, none under `test` or
`androidTest`, most inherited from tempo. The ktlint design said so itself, under
"Not covered": ktlint does not read them.

This is that follow-on. See #151 for the survey that preceded it.

## The editor did not pick this one for us

#126's strongest argument was that the choice had already been made elsewhere.
LazyVim's Kotlin extra wires ktlint into nvim-lint and conform with no
configuration, so "adopt ktlint" only ever meant "stop disagreeing with the
editor."

**That argument does not transfer.** `lazyvim.plugins.extras.lang.java` wires no
formatter and no linter — it installs `java-debug-adapter` and `java-test` and
stops. Java falls through to conform's `lsp_format = "fallback"`, which hands
formatting to jdtls and the Eclipse formatter. Two consequences, both worth
knowing before picking a style rather than after:

- **Today Java gets no editor formatting at all**, because jdtls does not start.
  That is #134, the lombok `-javaagent` LazyVim appends unconditionally.
- **When #134 is fixed, jdtls will format Java to Eclipse defaults** — a third
  style, agreeing with neither the tree nor the build.

So this gets ahead of a disagreement instead of inheriting one. #126's line still
holds: an editor that disagrees with CI is an editor you learn to ignore.

## AOSP is not a preference, it is what the tree already is

google-java-format 1.35.0 over `app/src/main/java`, measured both ways:

| Style | Files changed | Added | Removed | Changed lines |
|---|---|---|---|---|
| `--aosp` (4-space) | 18 / 19 | 902 | 779 | 1,681 |
| Google (2-space, the default) | 19 / 19 | 2,478 | 2,445 | 4,923 |

Across those files **457 lines begin with exactly four spaces, zero begin with
exactly two, and no file contains a hard tab.** Google style is a 2.9× larger
diff bought by re-indenting an Android codebase away from the Android
convention. `util/Util.java` is already conformant and is the one file AOSP
leaves untouched.

This is reflow and not import hygiene: **8 of the 1,681 changed lines are
`import` lines.** Nothing here finds an unused import anyone cared about.

Unlike ktlint there is no residue to argue about. google-java-format is a
formatter and not a linter, so it has no equivalent of that adoption's seven
judgement calls — it either rewrites a file or fails to parse it.

## Spotless, and only for Java

google-java-format ships no first-party Gradle plugin, so `com.diffplug.spotless`
is the route, with a `googleJavaFormat().aosp().reorderImports(true)` step —
see below for why that third call is not optional. #126 considered Spotless
for Kotlin and passed on it as "more configuration for a job that doesn't need it
yet." Java is the job that needs it.

That is **not** an argument for moving the Kotlin half over.
`org.jlleitschuh.gradle.ktlint` landed in #136, and rewriting 170 files a second
time to change which plugin invoked the same ktlint would be churn for its own
sake. The two sit side by side. Revisit consolidation if a third language ever
turns up.

Spotless cannot read Android source sets, so its `java` target is set explicitly
rather than inferred — a detail that looks like boilerplate and is not.

`spotlessCheck` and `spotlessApply` hang off `lint` and `lintFix` the way
`ktlintCheck` and `ktlintFormat` already do, because AGP's `lint` and `lintFix`
are thin umbrellas over `lintDebug` and `lintFixDebug` and one command should
cover every half. CI runs `./gradlew lint`, which is *above* `lintDebug`, so it
picks this up for free — the same reason `ktlintCheck` is reachable there.

`javafmt` and `javafmtFix` are registered as aliases so the command block reads
by language rather than by plugin: `ktlint`, `ktlintFix`, `javafmt`,
`javafmtFix`. They add no capability over `spotlessCheck` and `spotlessApply`,
exactly as `ktlint` and `ktlintFix` add none over the plugin's own tasks. The
symmetry is the point.

## One formatter, because two of them do not agree

This design originally copied ktlint's arrangement: pin google-java-format in
`libs.versions.toml`, pin `pkgs.google-java-format` in `flake.nix`, keep the two
numbers equal, and let the editor and the Claude hook format from the dev shell
while CI formats from Gradle. The stated guarantee was that equal version numbers
prevent "two formatters that disagree about the same file."

**That guarantee is false, and it was measured rather than reasoned about.** At
the identical declared version 1.35.0, the standalone CLI and Spotless's
in-process invocation produce different output for
`SessionMediaItemDao.java:68-69` — a string concatenation inside an `@Query`
annotation, which Spotless indents four spaces deeper. Run the CLI over
Spotless's output and it puts the lines back. Neither is a version drift; they
are two builds of the same version. nixpkgs packages the CLI from the shaded
`google-java-format-1.35.0-all-deps.jar` release asset, which bundles its own
transitive dependencies, while Spotless resolves the thin Maven artifact and lets
Gradle resolve those dependencies independently. The exact mechanism is
unconfirmed; the disagreement is not.

So the pin cannot do the job it was introduced for, and **Spotless is the sole
authority.** `libs.versions.toml` is the only place a version appears.
`flake.nix` carries no formatter, the Claude hook invokes `./gradlew javafmtFix`
rather than a CLI, and there is no second implementation left to disagree with
the gate. What the check enforces and what a write produces are the same code
path by construction, rather than by two numbers being kept equal by hand.

That costs the dev shell a fast standalone binary and makes a formatting write a
Gradle invocation — roughly a second against a warm daemon. It buys the property
the pin was only pretending to provide.

## Two things that would otherwise look like bugs in this repository

**google-java-format 1.35.0 is not idempotent.** Formatting
`MediaManager.java` and then formatting the result again changes
`MediaManager.java:190`, a string-concatenation continuation line that gains one
indent level on the second pass. It converges there — a third pass is a no-op —
but it means a single sweep does not land on the formatter's own fixed point, and
the sweep in this change had to be followed by a second pass before
`spotlessCheck` would accept it. Anyone re-sweeping this tree should run the
formatter twice and expect the second run to do something.

**Spotless's `googleJavaFormat()` step ignores the chosen style when ordering
imports, unless told not to.** `reorderImports` defaults to `false`, and in that
state the import-ordering pass runs as `Style.GOOGLE` regardless of `.aosp()` —
so `.aosp()` alone governs indentation and wrapping but silently leaves imports
in Google's single flat block. AOSP groups them, `android.*` then `androidx.*`
then the rest with blank lines between, which is what this tree has. The
configuration therefore says `.aosp().reorderImports(true)`, and without that
second call the gate rejects every file with grouped imports while reporting
nothing about why.

## The two files headed for Kotlin are formatted anyway

`MediaManager.java` is #96 and `AlbumArtContentProvider.java` is #86, both
"rewrite in Kotlin," and between them they are 580 of the 1,681 changed lines —
35% of the sweep spent on files scheduled for deletion.

They are formatted regardless, and there is no exclusion list. A formatter with
exclusions is a suggestion rather than a gate, and the cost is asymmetric: the
cost of formatting a file that later gets deleted is zero, while the cost of a
permanently-excluded path is that the next Java file added quietly inherits the
exclusion. #96 and #86 have been open since Aug 9 and Aug 13 with no dates on
them, so sequencing behind them would mean this waits on two issues that are
themselves waiting, leaving Java ungated meanwhile.

## google-java-format reaches into jdk.compiler, and this project is on JDK 21

google-java-format uses `jdk.compiler` internals, so on JDK 16+ running it
in-process under Spotless can require `--add-exports` JVM arguments. **The
symptom is an `IllegalAccessError` out of Spotless, not a message about missing
flags**, which is why this is confirmed on the first commit rather than
diagnosed on the fourth. The fallback is the documented `--add-exports` set in
`org.gradle.jvmargs`; `gradle.properties` is expected to produce exactly one
configuration-time warning, so that count is checked afterwards rather than
assumed.

The flake's CLI is unaffected either way — it is already wrapped, and running it
over all 19 files exits clean.

## The hook is a second entry, and its exit code means something different

A `PostToolUse` hook already runs `ktlint --format` on every `.kt` file Claude
writes. Java gets a second entry beside it rather than another arm inside the
existing `case`, because `statusMessage` is static text: one shared command would
have to stop naming the tool that actually ran. Two entries cost one extra `sed`
per edit, each exiting early on a path it does not match.

It is shaped like its neighbour up to the command it runs — `sed` reads
`file_path` out of the payload because neither `jq` nor `python3` is on PATH
here, and the `case` exits 0 for every path it does not match.

**What it runs is `./gradlew javafmtFix`, not a formatter binary**, which is
where the resemblance stops. The Kotlin hook shells out to the same ktlint the
Gradle plugin drives, so the two agree by version. Java has no such option: the
section above measured the CLI and Spotless disagreeing at one version, so the
only way for a write to produce what the check accepts is for the write to *be*
the check. The hook therefore runs the gate's own task rather than a second
implementation of it.

Two consequences follow and both are deliberate. It reformats the whole Java
source set rather than the one file that was written, because `spotlessApply` is
tree-scoped — harmless, since everything else is already conformant. And it costs
about a second against a warm Gradle daemon where the Kotlin hook is
instantaneous. That asymmetry is the price of the guarantee, not an oversight.

**Its exit code is not swallowed, and means a third thing.** A non-zero ktlint
exit means ktlint found something it cannot correct. A non-zero exit here means
the Gradle build failed — a parse error in the file just written, or a broken
build script. Both are worth surfacing and the causes are unrelated, so the
shared shape should not be read as a shared meaning.

**The hook is convenience, not the gate — even though it now runs the gate.** It
fires on `Edit` and `Write` tool calls only, so a file rewritten through `Bash`
with `sed` or a heredoc skips it entirely. `./gradlew lint` in CI is what
actually holds the line; the hook only keeps the gate from being the first thing
that notices.

## The sweep lands first, and #126's red window is not repeated

Two pull requests, in this order:

1. **The sweep alone** — `--aosp -i` over the 19 files, nothing else in the
   commit. `main` stays green because no Java gate exists yet.
2. **The gate** — Spotless, the pin, the `lint`/`lintFix` wiring, the alias
   tasks, the hook, the CLAUDE.md command block, this document, and the sweep's
   sha added to `.git-blame-ignore-revs`.

The second PR was expected to be green on arrival against an already-conformant
tree. It was not, and the three lines it had to reformat are the two hazards
above showing up in practice: `MediaManager.java:190` because one pass of a
non-idempotent formatter is not its own fixed point, and
`SessionMediaItemDao.java:68-69` because the sweep was applied by the CLI and the
gate is Spotless. Those three lines ride in the gate PR as an ordinary commit
rather than a second blame-ignored sweep — at three lines the blame cost is
nothing, and a reformatting-only revision is a thing worth reserving for sweeps
that would otherwise bury real authorship.

ktlint went the other way — `7f0997d3` added the gate, `4300c69a` fixed the seven
by hand, and `daaf3a35` swept — which left `main` red for two commits. That order
was forced there by the by-hand tier, which has to exist between the gate and the
sweep. google-java-format has no such tier, so repeating the shape would buy a
red window for nothing, and any unrelated PR opened during it would see a failing
base.

**Two PRs rather than two commits in one, because the sha arrives late.**
`.git-blame-ignore-revs` takes full SHAs, and GitHub's rebase-and-merge always
rewrites committer information and issues new ones — checked against this
repository's last twelve merged PRs, where no branch tip equals the sha that
landed. A sweep sharing a PR with the gate therefore has no knowable sha to
record while that PR is open. This is why #137 was separate from #126's sweep,
and the same constraint applies here; splitting at the PR boundary satisfies it
without a trailing one-line PR.

## Not covered

- **Checkstyle, SpotBugs, PMD.** Style formatting is the whole of this. A Java
  *linter* is a separate argument with a separate cost.
- **XML and resources.** Spotless can format them; nothing here says it should.
- **#99 and #69** — Android lint warnings and build warnings. Different axis,
  unchanged by any of this.
- **#96 and #86.** This does not wait on them; see above.
- **The nvim side**, which belongs in the nvim config flake rather than here —
  but note that the obvious move is now the wrong one. conform ships a
  `google-java-format` builtin, and wiring `formatters_by_ft` to it with `--aosp`
  would reintroduce exactly the second implementation this design removed: the
  editor would reformat on save to something the gate rejects, on the constructs
  where the two builds disagree. Whatever the editor runs has to be Spotless.
  Either way it closes the #134 hazard from the other side, since a configured
  conform formatter takes priority over the `lsp_format = "fallback"` path to
  jdtls.
- **A changelog entry.** Nothing here changes what the app does in the car.
