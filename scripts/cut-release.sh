#!/usr/bin/env bash
# Cuts a Siskin release: bumps app/version.properties, closes [Unreleased] in
# CHANGELOG.md under the new version, drafts the Play release note, and opens
# the "Cut X.Y.Z" pull request whose merge publishes it.
#
# Text edits only -- it never runs Gradle, so it works where the Android SDK
# cannot. See docs/decisions/2026-10-21-release-automation-design.md.
set -euo pipefail

# wc -m counts characters only under a UTF-8 locale, and the changelog heading
# carries an em dash.
export LC_ALL=C.UTF-8

VERSION_FILE=app/version.properties
CHANGELOG=CHANGELOG.md
NOTE=app/src/main/play/release-notes/en-US/default.txt
UNRELEASED='## [Unreleased] — Siskin'
# Play's cap on "what's new", counted over the whole file with its trailing
# newline -- the same measure as CI's check.
NOTE_LIMIT=500

usage() {
  echo "usage: cut-release.sh major|minor|patch" >&2
  exit 2
}

die() {
  echo "cut-release: $*" >&2
  exit 1
}

chars() {
  printf '%s' "$1" | wc -m
}

next_version() { # current bump
  [[ "$1" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || die "versionName '$1' in $VERSION_FILE is not X.Y.Z"
  local major=$((10#${BASH_REMATCH[1]})) minor=$((10#${BASH_REMATCH[2]})) patch=$((10#${BASH_REMATCH[3]}))
  case "$2" in
    major) major=$((major + 1)) minor=0 patch=0 ;;
    minor) minor=$((minor + 1)) patch=0 ;;
    patch) patch=$((patch + 1)) ;;
  esac
  # versionCode is major*10000 + minor*100 + patch, so past 99 codes collide.
  if [ "$minor" -gt 99 ] || [ "$patch" -gt 99 ]; then
    die "$major.$minor.$patch has a component above 99, which versionCode cannot encode; bump the next component instead"
  fi
  echo "$major.$minor.$patch"
}

version_code() { # X.Y.Z
  local major minor patch
  IFS=. read -r major minor patch <<<"$1"
  echo $((major * 10000 + minor * 100 + patch))
}

unreleased_section() {
  awk -v h="$UNRELEASED" '$0 == h { f = 1; next } f && /^## / { exit } f' "$CHANGELOG"
}

# One line per changelog bullet: its first sentence, with bold and code-span
# markers stripped. A draft for the cut PR to rewrite, not a finished note.
first_sentences() {
  awk '
    function emit() {
      if (b == "") return
      gsub(/\*\*|`/, "", b)
      i = index(b, ". ")
      print (i ? substr(b, 1, i) : b)
      b = ""
    }
    /^- / { emit(); b = substr($0, 3); next }
    /^ +[^ ]/ && b != "" { sub(/^ +/, ""); b = b " " $0; next }
    { emit() }
    END { emit() }
  '
}

truncate_words() { # text limit -- the limit counts the ellipsis
  local out="" word
  local -a words
  read -ra words <<<"$1"
  for word in "${words[@]}"; do
    [ "$(chars "${out:+$out }$word…")" -le "$2" ] || break
    out="${out:+$out }$word"
  done
  printf '%s…' "$out"
}

# Keeps whole lines while the file, trailing newline included, fits
# NOTE_LIMIT. A first line too long on its own is cut at a word boundary.
fit_note() {
  local note="" line
  while IFS= read -r line; do
    if [ "$(chars "$note$line"$'\n')" -le "$NOTE_LIMIT" ]; then
      note+="$line"$'\n'
    elif [ -z "$note" ]; then
      note="$(truncate_words "$line" $((NOTE_LIMIT - 1)))"$'\n'
      break
    else
      break
    fi
  done
  printf '%s' "$note"
}

pr_body() { # version tag
  cat <<EOF
Cuts $1 (versionCode $(version_code "$1")). Merging this publishes it to \`automotive:internal\` and then tags \`$2\`.

**Rewrite the release note before merging.** \`$NOTE\` was drafted from the first sentence of each changelog bullet. It is the store's "what's new": 500 characters at most, which CI checks.

Once it is working in the car, run **Promote to production** from the Actions tab against \`$2\`.
EOF
}

[ $# -eq 1 ] || usage
case "$1" in
  major | minor | patch) bump="$1" ;;
  *) usage ;;
esac

cd "$(git rev-parse --show-toplevel)"

[ -z "$(git status --porcelain)" ] || die "the working tree has uncommitted changes"
[ "$(git symbolic-ref --quiet --short HEAD)" = main ] || die "check out main first"
git fetch --quiet --tags origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] ||
  die "main is not at origin/main; pull or push first"

next="$(next_version "$(sed -n 's/^versionName=//p' "$VERSION_FILE")" "$bump")"
tag="v$next"
if git rev-parse --quiet --verify "refs/tags/$tag" >/dev/null; then
  die "$tag already exists"
fi

grep -qxF "$UNRELEASED" "$CHANGELOG" || die "$CHANGELOG has no '$UNRELEASED' heading"
section="$(unreleased_section)"
grep -q '^- ' <<<"$section" || die "[Unreleased] in $CHANGELOG has no entries to release"

# CUT_DATE pins the date for the tests.
today="${CUT_DATE:-$(date +%F)}"
branch="cut/$next"

git switch --quiet -c "$branch"

sed -i "s/^versionName=.*/versionName=$next/" "$VERSION_FILE"

awk -v h="$UNRELEASED" -v r="## [$next] ($today)" \
  '$0 == h { print h; print ""; print r; next } { print }' "$CHANGELOG" >"$CHANGELOG.new"
mv "$CHANGELOG.new" "$CHANGELOG"

printf '%s\n' "$section" | first_sentences | fit_note >"$NOTE"

git add "$VERSION_FILE" "$CHANGELOG" "$NOTE"
git commit --quiet -m "Cut $next"
git push --quiet -u origin "$branch"
gh pr create --base main --head "$branch" --title "Cut $next" --body "$(pr_body "$next" "$tag")"

echo "Cut $next. You are on $branch: rewrite $NOTE there, then merge the PR to publish."
