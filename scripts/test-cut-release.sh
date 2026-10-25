#!/usr/bin/env bash
# Runs scripts/cut-release.sh against throwaway repositories whose origin is a
# local bare one, with a stub gh that records its arguments instead of opening
# a pull request. CI runs this in the test job.
set -euo pipefail
export LC_ALL=C.UTF-8

CUT="$(cd "$(dirname "$0")" && pwd)/cut-release.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

NOTE=app/src/main/play/release-notes/en-US/default.txt

die() {
  echo "    $*" >&2
  exit 1
}

assert_eq() { # actual expected what
  [ "$1" = "$2" ] || die "$3: expected '$2', got '$1'"
}

assert_grep() { # pattern file
  grep -q -- "$1" "$2" || die "$2 does not match '$1'"
}

# Runs a command that must fail, and checks its stderr names the reason and
# that it left the clone exactly as it found it.
assert_refuses() { # reason command...
  local want="$1" err rc=0
  shift
  err="$("$@" 2>&1 >/dev/null)" || rc=$?
  [ "$rc" -ne 0 ] || die "expected '$*' to fail"
  [[ "$err" == *"$want"* ]] || die "stderr '$err' does not mention '$want'"
  assert_eq "$(git symbolic-ref --short HEAD)" main "branch after refusal"
}

# Creates a clone at $FIX/clone holding the three files a cut touches, pushed
# to $FIX/origin.git, and puts a stub gh first on PATH. Leaves the caller in
# the clone, on main.
fixture() { # [versionName]
  FIX="$(mktemp -d "$WORK/case.XXXXXX")"
  git init --quiet --bare -b main "$FIX/origin.git"
  git init --quiet -b main "$FIX/clone"
  cd "$FIX/clone"
  git config user.name test
  git config user.email test@example.com
  git remote add origin "$FIX/origin.git"
  mkdir -p "$(dirname "$NOTE")"
  printf '# a comment that must survive\nversionName=%s\n' "${1:-0.99.8}" >app/version.properties
  cat >CHANGELOG.md <<'EOF'
# Changelog

## [Unreleased] — Siskin

- The signed-out row no longer cuts its **message** off. Its title is short.
- Mix now draws from the whole of what you
  tapped. It used to stop at 500 tracks.

## [0.99.8] (2026-08-30)

- An entry that has already shipped. It must not move.
EOF
  echo 'The note currently on Play.' >"$NOTE"
  git add -A
  git commit --quiet -m init
  git push --quiet -u origin main 2>/dev/null
  mkdir -p "$FIX/bin"
  printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$@" >"%s/gh-args"\n' "$FIX" >"$FIX/bin/gh"
  chmod +x "$FIX/bin/gh"
  PATH="$FIX/bin:$PATH"
}

# Replaces the [Unreleased] section's body and pushes the change.
set_unreleased() { # body
  awk -v body="$1" '
    /^## \[Unreleased\]/ { print; print ""; if (body != "") { print body; print "" }; skip = 1; next }
    skip && /^## / { skip = 0 }
    !skip
  ' CHANGELOG.md >CHANGELOG.md.new
  mv CHANGELOG.md.new CHANGELOG.md
  git commit --quiet -am 'set unreleased'
  git push --quiet 2>/dev/null
}

cut() {
  CUT_DATE=2026-10-21 "$CUT" "$@" >/dev/null 2>&1
}

version() {
  sed -n 's/^versionName=//p' app/version.properties
}

test_patch_bump() {
  fixture 0.99.8
  cut patch
  assert_eq "$(version)" 0.99.9 versionName
  assert_grep '^# a comment that must survive$' app/version.properties
}

test_minor_bump() {
  fixture 1.2.3
  cut minor
  assert_eq "$(version)" 1.3.0 versionName
}

test_major_bump() {
  fixture 0.99.8
  cut major
  assert_eq "$(version)" 1.0.0 versionName
}

test_commits_pushes_and_opens_pr() {
  fixture 0.99.8
  cut major
  assert_eq "$(git symbolic-ref --short HEAD)" cut/1.0.0 branch
  assert_eq "$(git log -1 --format=%s)" "Cut 1.0.0" "commit subject"
  assert_eq "$(git rev-list --count origin/main..HEAD)" 1 "commits on the cut branch"
  assert_eq "$(git -C "$FIX/origin.git" rev-parse cut/1.0.0)" "$(git rev-parse HEAD)" "pushed branch"
  assert_eq "$(git status --porcelain)" "" "tree after cut"
  assert_grep '^pr$' "$FIX/gh-args"
  assert_grep '^create$' "$FIX/gh-args"
  assert_grep '^Cut 1.0.0$' "$FIX/gh-args"
  assert_grep '^cut/1.0.0$' "$FIX/gh-args"
}

test_rolls_changelog() {
  fixture 0.99.8
  cut major
  local want
  want="$(printf '%s\n' '# Changelog' '' '## [Unreleased] — Siskin' '' '## [1.0.0] (2026-10-21)' '' \
    '- The signed-out row no longer cuts its **message** off. Its title is short.')"
  assert_eq "$(head -n 7 CHANGELOG.md)" "$want" "changelog head"
  assert_eq "$(grep -c '^## \[Unreleased\]' CHANGELOG.md)" 1 "Unreleased headings"
  assert_grep '^## \[0.99.8\] (2026-08-30)$' CHANGELOG.md
}

test_drafts_note_from_first_sentences() {
  fixture 0.99.8
  cut major
  assert_eq "$(cat "$NOTE")" "$(printf '%s\n' \
    'The signed-out row no longer cuts its message off.' \
    'Mix now draws from the whole of what you tapped.')" "note"
}

test_note_skips_debug_bullets() {
  fixture 0.99.8
  set_unreleased "$(printf '%s\n' \
    '- **Debug:** The debug screen has side tabs. One per area.' \
    '- Mix now draws from the whole of what you tapped. It used to stop.' \
    '- **Debug:** A second debug entry,' \
    '  wrapped onto two lines. Still skipped.')"
  cut patch
  assert_eq "$(cat "$NOTE")" 'Mix now draws from the whole of what you tapped.' "note"
  assert_grep '^- \*\*Debug:\*\* The debug screen has side tabs' CHANGELOG.md
}

test_debug_only_release_drafts_empty_note() {
  fixture 0.99.8
  set_unreleased '- **Debug:** The debug screen has side tabs.'
  cut patch
  assert_eq "$(version)" 0.99.9 versionName
  assert_eq "$(cat "$NOTE")" "" "note"
}

test_note_keeps_whole_sentences_within_limit() {
  fixture 0.99.8
  local body="" i
  for i in $(seq 1 20); do
    body+="- Bullet $i says something long enough that twenty of them cannot fit. More."$'\n'
  done
  set_unreleased "${body%$'\n'}"
  cut patch
  [ "$(wc -m <"$NOTE")" -le 500 ] || die "note is $(wc -m <"$NOTE") characters"
  [ "$(wc -l <"$NOTE")" -ge 2 ] || die "note kept fewer than two sentences"
  if grep -qv 'fit\.$' "$NOTE"; then die "note holds a partial sentence: $(cat "$NOTE")"; fi
}

test_note_truncates_one_overlong_sentence() {
  fixture 0.99.8
  set_unreleased "- $(printf 'word%.0s ' $(seq 1 150))end."
  cut patch
  [ "$(wc -m <"$NOTE")" -le 500 ] || die "note is $(wc -m <"$NOTE") characters"
  [[ "$(cat "$NOTE")" == *"word…" ]] || die "note does not end in a whole word and an ellipsis: $(cat "$NOTE")"
}

test_refuses_bad_usage() {
  fixture
  local rc=0
  "$CUT" >/dev/null 2>&1 || rc=$?
  assert_eq "$rc" 2 "exit status with no argument"
  rc=0
  "$CUT" bogus >/dev/null 2>&1 || rc=$?
  assert_eq "$rc" 2 "exit status with a bogus argument"
}

test_refuses_minor_past_99() {
  fixture 0.99.8
  assert_refuses 'above 99' "$CUT" minor
  assert_eq "$(version)" 0.99.8 versionName
}

test_refuses_patch_past_99() {
  fixture 1.0.99
  assert_refuses 'above 99' "$CUT" patch
}

test_refuses_dirty_tree() {
  fixture
  touch stray
  assert_refuses 'uncommitted' "$CUT" patch
}

test_refuses_off_main() {
  fixture
  git switch --quiet -c elsewhere
  local err rc=0
  err="$("$CUT" patch 2>&1 >/dev/null)" || rc=$?
  [ "$rc" -eq 1 ] || die "expected exit 1, got $rc"
  [[ "$err" == *"check out main"* ]] || die "stderr '$err'"
}

test_refuses_unpushed_main() {
  fixture
  git commit --quiet --allow-empty -m local
  assert_refuses 'origin/main' "$CUT" patch
}

test_refuses_empty_unreleased() {
  fixture
  set_unreleased ""
  assert_refuses 'no entries' "$CUT" patch
}

test_refuses_when_gh_signed_out() {
  fixture 0.99.8
  # shellcheck disable=SC2016 # $1 $2 belong to the stub, not to this shell
  printf '#!/usr/bin/env bash\n[ "$1 $2" = "auth status" ] && exit 1\nexit 0\n' >"$FIX/bin/gh"
  assert_refuses 'gh is not signed in' "$CUT" patch
}

test_refuses_existing_tag() {
  fixture 0.99.8
  git tag v0.99.9
  assert_refuses 'v0.99.9 already exists' "$CUT" patch
}

failures=0
for t in $(declare -F | awk '$3 ~ /^test_/ { print $3 }'); do
  set +e
  (
    set -e
    "$t"
  )
  rc=$?
  set -e
  if [ "$rc" -eq 0 ]; then
    echo "ok   $t"
  else
    echo "FAIL $t"
    failures=$((failures + 1))
  fi
done
[ "$failures" -eq 0 ] || {
  echo "$failures failed" >&2
  exit 1
}
