# The website deploys itself to Cloudflare

**Date:** 2026-09-07
**Status:** Approved

## Context

The site is two pages. `docs/web/index.html` is hand-written HTML, committed,
and self-contained down to the icon as a data URI. `privacy.html` is rendered
from `docs/privacy-policy.md` by pandoc into `build/web/`, which is gitignored.

Publishing was an `scp` written down in a `flake.nix` comment, to an nginx
instance serving `codingismy11to7.us/siskin/`. That was deliberate — the comment
says a write to a public web root should stay a decision rather than a side
effect of running a render — and it worked: on 2026-09-07 both live pages were
byte-identical to what the repository produced.

But nothing *enforced* that. Drift would have been silent, and the only alarm
was someone thinking to check. The privacy policy in particular is a URL on the
Play listing, which Google fetches; a stale or missing page there is a store
problem rather than a cosmetic one.

`siskinapp.com` was registered at Cloudflare Registrar, which makes the zone,
the custom domain and any redirect rules a single account's problem instead of
three.

## Decisions

### Cloudflare Workers, not Pages

Pages is **not** deprecated: no sunset, no maintenance-mode declaration,
existing projects untouched. The deprecated thing is *Workers Sites*, the older
static-hosting mechanism Workers Static Assets replaced — easy to confuse, and
worth writing down because the confusion nearly picked the platform here.

What settled it is Cloudflare's own Pages documentation, which now carries:

> Workers supports most Pages use cases and offers a broader feature set. It is
> Cloudflare's primary platform for building applications. Start new projects
> with Workers.

This is a new project. `wrangler.jsonc` declares an assets-only Worker — no
`main`, so there is no script to maintain and nothing to keep secure.

### Cloudflare builds it; there is no GitHub Actions workflow

The alternative was a workflow using `cloudflare/wrangler-action` to deploy and
`marocchino/sticky-pull-request-comment` to post preview URLs, with Nix building
the site. It works, and it was the plan until the Workers Builds UI was actually
opened.

Workers Builds wins on three counts:

- **No workflow file.** The build and deploy commands live in the project's
  settings; the repository carries only what the build needs.
- **No repository secrets.** Workers Builds creates its own API token. The
  Actions route needed `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` added
  to GitHub.
- **Previews are a checkbox.** "Builds for non-production branches" defaults the
  non-production deploy command to `npx wrangler versions upload`, which mints a
  preview URL per version. The Actions route would have needed
  `--preview-alias pr-N` to make the URL predictable, because `wrangler-action`
  exposes a preview-URL output for Pages only — leaving `versions upload` to be
  scraped out of `command-output`.

### pandoc is fetched into the build, pinned by version and checksum

The Workers Builds image is Ubuntu 24.04 with `curl`, `git` and
`build-essential`, no pandoc and no root. Pandoc's official `linux-amd64`
release is a static binary, so it untars into the workspace and runs.
`scripts/build-site.sh` pins both the version and its sha256.

Three alternatives were rejected:

- **A JavaScript markdown renderer.** Available with one `npm install`, and it
  would produce different HTML than the local render — replacing a verifiable
  pipeline with a second one that only resembles it.
- **Hugo**, which the build image already ships. A different renderer with a
  layout to write; more work than pandoc, not less.
- **Committing the rendered `privacy.html`.** Puts a derived file in git, where
  the next person edits it directly and the markdown quietly stops being the
  source.

The pinned 3.7.0.2 binary renders the policy **byte-identically** to the pandoc
in `flake.lock`, verified against the live page. Should the two versions ever
drift apart, the principle that holds is the one the flake comment states —
a page can always be *regenerated* from this repository — not that the bytes
match across machines.

### One build script, two callers

`scripts/build-site.sh` is the implementation. `siskin-render-web` shrinks to a
wrapper that puts nixpkgs' pandoc on `PATH` and execs it, so a local render and
a deployed one cannot diverge.

The obvious alternative was an inline `curl … | tar … && pandoc …` in the
dashboard's Build command field. That is configuration living outside git,
unreviewable and unversioned, which is the precise failure the `scp` comment was
written against. The dashboard holds one stable string instead.

### Build watch paths keep unrelated PRs out

Most PRs here touch no web file, and a Cloudflare check on an Android-only
change is noise. Workers Builds filters this itself, with **Include paths** set
to the build's actual inputs:

    docs/web/**
    docs/privacy-policy.md
    scripts/build-site.sh
    wrangler.jsonc

That list is exhaustive rather than approximate: `build-site.sh` reads
`privacy-policy.md`, `style.html` and `index.html`, and `wrangler.jsonc`
governs the upload. Nothing else changes what gets published — `flake.nix`
affects only the local render. Adding an input to the build means adding it
here, or the site silently stops tracking it.

The setting is easy to conclude does not exist. It is absent from the project
creation wizard, whose Advanced section offers only a non-production deploy
command and a root directory — labelled "Path", which is *not* a filter — and
it is absent from the configuration documentation. It lives on the project's
settings page after creation.

Had it genuinely been missing, the fallbacks were all worse: a deploy command
that diffs against the base ref and exits early (bespoke shell, needed for both
`deploy` and `versions upload`), moving the site to its own repository (splits
the privacy policy from the app it describes), or turning previews off
(discards the reason for having them). Accepting the noise would have beaten
all three.

### The canonical policy URL is `/privacy`

`html_handling` defaults to `auto-trailing-slash`, which serves `privacy.html`
at `/privacy` and 307s `/privacy.html` to it. The Play Console therefore gets
the extensionless form, and the landing page's two relative links were changed
to match so a click does not spend a redirect.

`wrangler.jsonc` sets `html_handling` explicitly despite it being the default,
because a store listing depends on it and a silently changed default would break
a link Google checks.

### The old URL redirects rather than dying

`codingismy11to7.us/siskin/` keeps serving the real pages until the new domain
is verified, then becomes two nginx redirects:

    location = /siskin/privacy.html { return 301 https://siskinapp.com/privacy; }
    location /siskin/               { return 301 https://siskinapp.com/; }

The exact match on the policy is load-bearing: folding it into the prefix rule
would land everyone who bookmarked the policy on the landing page instead.

Nothing in the app references either URL, so no rebuild and no release is
involved in the move.

## The one-time cutover

Order matters, because Google fetches the privacy URL:

1. Merge this PR. The production deploy creates the Worker; verify both pages on
   the `workers.dev` URL.
2. Attach `siskinapp.com` as a Custom Domain, and a redirect rule for `www`.
   Verify `/` and `/privacy`.
3. Update the Play Console privacy policy URL — only now, once it resolves.
4. Replace swag's `/siskin/` with the redirects above.

The old URL serves real content through step 3 and redirects afterward. It is
never dead.

Dashboard settings, recorded because they are not in the repository: project
name `siskin` (must match `name` in `wrangler.jsonc`), build command
`./scripts/build-site.sh`, deploy command `npx wrangler deploy`, version command
`npx wrangler versions upload`, root directory `/`, production branch `main`,
non-production builds on, Cloudflare Access off, and the four include paths
above with Exclude left at its defaults.

## Verification

- The pinned static pandoc renders `privacy.html` byte-identically to the
  nixpkgs pandoc, and both match what was live on the old host.
- The first build on this branch is the real test of the build command, and it
  runs before the merge because non-production builds are enabled.

One known unknown: `wrangler versions upload` may refuse against a Worker that
has never had a successful production deploy. If this branch's preview fails
that way it is not a design fault — the merge creates the Worker and previews
work from the next PR onward.

## What this does not buy

- **The `workers.dev` subdomain stays public** beside the custom domain, because
  preview URLs depend on it being enabled.
- **No custom 404.** `not_found_handling` is left at its default; a 404 page is
  a thing to want later.
- **The Play Console URL is still typed by hand.** Nothing in the repository
  asserts what the listing points at.
- **Preview URLs are reachable by anyone holding the link.** Cloudflare Access
  could gate them; a public landing page and a public policy do not warrant it.
