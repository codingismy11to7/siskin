# Five locales, all of them complete

**Date:** 2026-08-02
**Status:** Approved

## Context

The Play listing reported the app as available in ~88 languages, which is absurd
for a fork whose entire user-facing vocabulary is 41 strings. Investigating that
number turned up a second problem underneath it: the locales Siskin nominally
shipped were nearly empty.

## What was measured

**The 88 is not ours.** `app/build.gradle` declares no locale filter of any kind,
so the bundle inherits every locale its dependencies ship — AndroidX, Material,
Glide and media3 between them carry ~85. `aapt dump configurations` on the debug
APK listed 86, from `af` and `am` through `zu`. The 15 `values-<locale>`
directories in this repo contribute one of those 86. **Deleting locale
directories therefore does nothing to the reported number**; only a filter does,
because only a filter reaches dependency resources.

**The translations were vestigial.** After the fork's deletions, each locale
carried between one and four strings against the default locale's 41:

| locales | strings | content |
|---|---|---|
| de, fr, ca, es-rES, es-419, pl, ru | 4 | `app_name` + three tab labels |
| it, pt, ro, ko, tr, zh, zh-rCN, zh-rTW | 1 | `app_name` only |
| values (English) | 41 | — |

And every `app_name` was the literal string `Siskin`, identical to the default —
so eight of the fifteen directories contained no translation at all, and the
richest contained three words. A German driver saw `Alben`, `Interpreten` and
`Wiedergabelisten` surrounded by English sign-in, settings, More-tab, error and
shuffle-row strings.

**There were no unused strings.** All 41 are referenced from code, layouts or the
manifest, and lint reported no `ExtraTranslation`. The only genuinely dead
resource was `R.color.titleTextColor`, orphaned the same day by the deletion of
`ic_bookmark_sync.xml` in the car star rating change.

## Decision: five locales, fully translated

English, German, Spanish, French, Italian. Every one of the 40 translatable
strings exists in every one of them.

The alternative first proposed here was English-only: filter to `en`, delete all
fifteen directories, and treat localisation as a later project. It was rejected
on the size of the actual surface. Forty strings, most of them one to eight
words, is an afternoon of work — not the multi-release commitment that "localise
the app" usually implies. The argument for deleting rested on the *existing*
directories being worthless, which is true, but it conflated two different
things: the directories were worthless, the translation work is not, and doing
the work removes the reason to delete them.

What is deliberately *not* kept is the rest of the romance family — Catalan,
Portuguese, Romanian — along with Korean, Polish, Russian, Turkish and the three
Chinese variants. Between them they held four translated tab labels and eleven
copies of "Siskin". They are dropped rather than translated because each language
kept is a language that has to be maintained as screens change, and five is the
number worth carrying.

**One Spanish, not two.** `values-es-rES` and `values-b+es+419` collapse into a
single `values-es`. Bare `es` matches every Spanish region including Latin
America, so the split bought nothing but a second file to keep in sync, and the
two differed only in that one had three strings and the other had three strings.

## Decision: `localeFilters` is the mechanism

```groovy
androidResources {
    localeFilters += ["en", "de", "es", "fr", "it"]
}
```

AGP is 9.2.1, where `resConfigs` / `resourceConfigurations` has been removed;
`localeFilters` is its replacement. This is the only change that moves the
reported language count, and it works by filtering dependency resources — the
app's own directories are already exactly the five we want.

Whether a bare language tag also keeps regional qualifiers is verified by
measurement rather than asserted, since the app ships `values-es` and the answer
decides whether regions need listing explicitly.

## Decision: `app_name` becomes `translatable="false"`

It is a proper noun. It was present in all fifteen locale files with the identical
value `Siskin`, which is what happens when a translator is handed a string that
should never have been offered: they satisfy the tool by copying it. Marking it
non-translatable exempts it from `MissingTranslation` rather than silencing that
check with a duplicate, and removes the only entry eight of the deleted files had.

## Consequence: lint starts enforcing this instead of complaining about it

`MissingTranslation` was 30 of the 39 errors CLAUDE.md documents as the expected
baseline, and it had already drifted to 40 by the time this was written. With
five locales all complete it goes to **zero**, taking the baseline to nine — the
eight `UnsafeOptInUsageError` and one `UseAppTint` that have nothing to do with
translation.

That inverts what the check is for. CLAUDE.md currently warns that "every
user-facing string you add raises this count by one per locale", i.e. that the
error is expected noise. It now means a string was added without its four
translations, which is a real defect and worth failing on. CLAUDE.md is updated
to say so, because that section exists specifically to stop the next person
chasing a red lint that is not theirs.

## Risks, stated plainly

The translations are mine, without a native reviewer. For a personal tool in a
car this is a reasonable trade; it is recorded because Play listing a language
implies support, and a wrong string in the sign-in flow is harder to notice than
a wrong string on a screen someone looks at daily.

The string set is also still moving — `car_sign_in_*` landed the previous day —
so translations will need touching as screens change. The zeroed
`MissingTranslation` baseline is what makes that self-enforcing rather than
something to remember.

## Not in scope

Play Console store-listing translations, which are managed separately from the
bundle and cannot be verified from here; if the Console carries its own list, it
needs pruning there too. Translating the app's description or screenshots. Any
per-app language picker UI beyond trimming `locale_config.xml` to match what is
shipped.
