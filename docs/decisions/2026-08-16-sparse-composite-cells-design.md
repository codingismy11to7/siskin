# A sparse composite keeps its cells

**Date:** 2026-08-16
**Status:** Approved

## Context

A composite tile is drawn from up to four covers. When it has four it draws a
2×2; when it has one, two or three it draws **the first cover, full-bleed**, and
the others are not drawn at all.

That rule shipped with the decade composites in
[#84](https://github.com/codingismy11to7/siskin/issues/84) and was inherited
unchanged by the Discover rows in
[#114](https://github.com/codingismy11to7/siskin/issues/114), which is how it
reached two browse surfaces without being re-examined.

**It is confusing in a real library.** A decade holding three albums draws one
cover, and nothing about the tile says the other two exist — it looks like an
album, and the row it decorates looks like it holds one thing. The information
the mosaic exists to carry is exactly what the fallback throws away.

## What this supersedes, and why the original reasoning no longer holds

[The decade composite design](2026-08-09-decade-composite-artwork-design.md)
chose that fallback deliberately and recorded why. It is quoted here rather than
paraphrased, because it is a good argument and the reversal has to beat it:

> The sparse rule is one rule rather than a special case. Repeating covers to
> fill four cells would claim albums that are not there and reads as a rendering
> bug; leaving cells empty looks like artwork that failed to load, which is the
> one thing this must never be confused with. A decade with two albums genuinely
> has no mosaic, and one cover says that honestly — degrading into exactly what
> an album tile already looks like.

Two of its three clauses still stand. **Repeating covers is still refused**, for
exactly the reason given. And an empty cell would indeed be a bad thing to
draw — *if it were visible*.

That is the clause that turns out to be wrong, and it is wrong because of a fact
about the surface rather than about the argument. **The car's browse background
is black, the composite has no alpha, and an undrawn cell is therefore black
too.** There is no empty box beside the covers to read as a failed load; the
tile's edges simply stop being visible and what remains is two or three covers
sitting in the corners of a square. A sparse composite reads as *composition*,
not as damage.

The other half of the argument — that one cover "says that honestly" — assumed
the honest thing to say is "this is one album". For a decade or a hub it is not:
the row holds three albums, and a tile that shows one is not being honest, it is
being quiet.

That earlier document is the historical record of what it decided and is not
edited. This one supersedes its *Four covers, in the largest grid that fills
completely* section.

## The rule

| covers | layout |
|---|---|
| 0 | no composite at all — the car draws its own placeholder |
| 1 | one cover, full-bleed |
| 2 | **diagonal** — top-left and bottom-right |
| 3 | top-left, top-right, bottom-left |
| 4 | the full 2×2 |

Cells are returned in fill order, because the draw loop pairs cell *n* with cover
*n*. Nothing is drawn into the remaining cells.

**Two is a diagonal rather than a filled top row.** Both keep square cells, and
the diagonal reads as a deliberate arrangement where a top row reads as a grid
that stopped halfway. Against a black background the distinction is the whole
effect: two covers on a diagonal are unmistakably two covers, where two covers
side by side with black beneath them still suggest a missing bottom row.

**Three fills in reading order** and leaves the bottom-right dark, which is the
conventional shape for "a grid with one missing" and the one that needs no
interpretation. It deliberately does not extend two's diagonal — the layouts are
per-count, not an accumulation, and the sequence is never seen animating from one
to the next.

**One stays full-bleed.** At one cover there is nothing to under-report, and a
quarter-size cover surrounded by three dark cells would be the one layout in this
set that really does look like a failed load — the objection above, arriving
where it actually applies. A Discover row draws its tile at 112px, so a quarter
cell there is 56px, and spending three quarters of that on nothing buys nothing.

**Blank cells are black rather than a near-black grey.** A grey cell would make
the composite read as a square object with a piece missing, which is the visible
empty box the old argument feared. Black lets the covers float.

## What changes

### `CompositeGrid.cells`

Gains the two- and three-cover cases. It stays a pure function returning plain
`Cell` data classes rather than `android.graphics.Rect`, for the reason its KDoc
already gives: `android.jar` is stubbed under `unitTests.returnDefaultValues`, so
a `Rect` built in a test is not reliably the `Rect` it looks like and an
assertion on it could pass while measuring nothing.

A sparse layout returns **fewer cells than the grid has positions** — two cells
for two covers, not four cells of which two are empty. An empty cell is an
absence, not a zero-area rectangle, and the draw loop needs no notion of "skip
this one".

### The candidate count, which inverts

`CompositeArt.buildLocked` currently asks for one cover when the pool holds fewer
than four:

```kotlin
val want = if (thumbs.size >= CompositeGrid.COVERS) CompositeGrid.COVERS else 1
```

**That was correct for the old rule and is wrong for this one.** It exists
because fewer than four candidates meant exactly one drawn cell, so loading the
rest was a full-size transcode spent on a cover no cell existed for. Under this
design two candidates mean two cells, so all of them are wanted, and the
special case goes: `pick` is handed `CompositeGrid.COVERS` and stops early on its
own when the pool is smaller.

`candidateEdge` inverts with it. Today the full edge is used whenever the pool
cannot fill a grid; now the only layout drawing a full-edge cover is the
one-cover case, so quarter-size requests become the norm:

```kotlin
val candidateEdge =
    if (thumbs.size == 1) CompositeGrid.SIZE else CompositeGrid.SIZE / 2
```

The degraded re-request survives unchanged in shape and narrows in scope: it now
fires only when a pool of two or more lands exactly one cover, and re-requests
that survivor at the full edge because the layout it fell back to is the
full-bleed one.

### The canvas is painted black explicitly

The composite is a `Bitmap.createBitmap(SIZE, SIZE, RGB_565)`, and undrawn cells
are whatever that allocation contains. In practice that is zeroed, and zero in
`RGB_565` is black — but "in practice" is the wrong basis for something now
load-bearing to the design. A `canvas.drawColor(Color.BLACK)` before the cells
are drawn costs one line and removes the assumption.

`RGB_565` carries no alpha, so black is the only blank available in any case;
this makes it deliberate rather than incidental.

## Both surfaces change, and that is the point

`CompositeGrid` is shared, so this reaches the Decades grid and the Discover list
together. Decades is where the confusion was actually hit, and Discover is where
sparse tiles are most common — a hub of three albums by one artist is an
ordinary shape there, and #114's `.distinct()` on the cover pool makes small
pools *more* likely rather than less.

The two surfaces draw at different sizes — a decade tile at ~260px measured on
the landscape AVD, a Discover row's thumbnail at 112px — so a quarter cell is
~130px on one and 56px on the other. That difference is a property of the
screens, not of this design; the composite is generated at 512 either way.

## The cache does not need invalidating, and would not notice if it did

A composite's cache id is a digest of its cover pool, and its URI carries that
plus the hour bucket. **Neither changes here** — the same pool still produces the
same id — so a tile cached under the old layout is served under the new one until
its bucket rolls.

That is at most an hour of a stale-looking tile on an upgraded install, on a
path where the tile was already re-drawn hourly, and it is self-healing without
any migration. Forcing it sooner would mean changing the id, which would strand
every cached composite rather than the handful drawn in the last hour.

## What deliberately does not change

- **Four covers is still the maximum, and `CompositeGrid.SIZE` is still 512.**
  The 2×2-over-3×3 argument is unaffected: it was about legibility at one output
  size, and nine cells are still worse than four.
- **Repeating a cover to fill a cell is still refused**, for the reason #84 gave.
- **No change to the pool, the digest, the URI, the guards, or eviction.** This
  is a change to where covers are drawn, and nothing upstream of that.
- **No new strings and no new drawables**, so the five-locale rule costs nothing
  and `MissingTranslation` stays at zero.
- **`browsableChildrenAsGrid` is untouched** on both nodes — Decades still grids,
  Discover is still permanently a list.

## Testing

`CompositeGridTest` is plain JUnit asserting rectangles directly, and extends to
cover this without new machinery:

- **Two returns two cells, and they are the diagonal** — top-left and
  bottom-right, in that order.
- **Three returns three cells** — top-left, top-right, bottom-left, in that
  order — and the bottom-right is *absent* rather than present-and-empty.
- **One still returns a single full-bleed cell**, and **four still returns the
  four quadrants**, so the cases that did not change are pinned against a
  rewrite that changes them by accident.
- **Cell count always equals cover count** for one through four, which is the
  invariant the draw loop depends on when it pairs cell *n* with cover *n*.
- **The midpoint is shared between adjacent cells**, as the existing test
  already checks for the 2×2, so an odd size leaves neither a seam nor an
  overhang on the sparse layouts either.

**The candidate-count change is not directly unit-testable, and inventing a test
that appears to cover it would be worse than saying so.** It lives in
`CompositeArt.buildLocked`, which is private and issues network work; `pick`
itself already returns three covers from a pool of three, so a test written
against `pick` would pass equally before and after. What actually pins the
behaviour is `cells`, which decides how many cells a count produces and is
covered above — the candidate count exists only to feed it.

That leaves the reverted optimisation resting on a comment rather than on a test,
which is a real gap and is why the comment must say *why* it was reverted rather
than merely what the code now does. It is the line a future reader is most likely
to "optimise" back.

Drawing itself stays untested for the reason it always has: Robolectric's
`Canvas` and `Bitmap` shadows produce no pixels, so an assertion on drawn output
would assert nothing while appearing to pass. The black canvas is therefore
verified by eye on the emulator rather than by a test that could not see it.

## Verification in the car

Worth doing on the landscape AVD, because this design rests on a claim about how
something looks: that black cells vanish into the background and the covers read
as composed. Browse Decades for a sparse decade and More → Discover for a hub
with fewer than four thumb-bearing items, and confirm a two-cover tile reads as
two covers on a diagonal rather than as a broken grid.

The portrait variant is a separate AVD and switching tears down whatever is
running, so it is not a step to take unprompted.
