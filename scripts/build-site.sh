#!/usr/bin/env bash
set -euo pipefail

# Builds the site published to siskinapp.com into build/web/.
#
# Two callers, one implementation. `siskin-render-web` from the dev shell puts
# nixpkgs' pandoc on PATH and execs this; Cloudflare Workers Builds runs it as
# the project's build command on an image that has no pandoc at all, which is
# why one is fetched when PATH has none. The pinned release below is the
# version nixpkgs currently carries, and the two render identically.
#
# See docs/decisions/2026-09-07-site-deploy-design.md.

PANDOC_VERSION=3.7.0.2
PANDOC_SHA256=8f8f67fdd540b6519326b0ac49d5c55c5d5d15e43920e80a086e02c8aff83268

# dirname rather than `git rev-parse`: Cloudflare clones the repository, but
# nothing here needs it to be one, and a build that works without git is one
# fewer assumption about someone else's image.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$root/build/web"

if command -v pandoc >/dev/null 2>&1; then
  pandoc=pandoc
else
  # Cached under build/, which is gitignored, so a rebuild in a warm workspace
  # skips the download.
  cache="$root/build/pandoc-$PANDOC_VERSION"
  if [ ! -x "$cache/bin/pandoc" ]; then
    echo "fetching pandoc $PANDOC_VERSION"
    tarball="$(mktemp)"
    trap 'rm -f "$tarball"' EXIT
    curl -fsSL -o "$tarball" \
      "https://github.com/jgm/pandoc/releases/download/$PANDOC_VERSION/pandoc-$PANDOC_VERSION-linux-amd64.tar.gz"
    echo "$PANDOC_SHA256  $tarball" | sha256sum -c - >/dev/null
    mkdir -p "$cache"
    tar xzf "$tarball" -C "$cache" --strip-components=1
  fi
  pandoc="$cache/bin/pandoc"
fi

mkdir -p "$out"

echo "rendering:"

# pagetitle, not `--metadata title`: the latter also emits a title block into
# the body, which duplicates the heading the markdown already starts with. This
# sets <title> alone and leaves the document's own h1 as the only one -- which
# is also what docs/web/style.html's `h1+p` subtitle rule expects.
"$pandoc" "$root/docs/privacy-policy.md" \
  --from markdown --to html5 --standalone \
  --variable pagetitle="Siskin Privacy Policy" \
  --include-in-header "$root/docs/web/style.html" \
  --output "$out/privacy.html"

# The landing page and the 404 are hand-written HTML and are copied rather than
# rendered. style.html is a header include, not a page, and deliberately does
# not travel with them.
cp "$root/docs/web/index.html" "$out/index.html"
cp "$root/docs/web/404.html" "$out/404.html"

for f in "$out/index.html" "$out/privacy.html" "$out/404.html"; do
  echo "  $f  ($(stat -c %s "$f") bytes)"
done
