# Roamed

An Android app that quietly records where you go and burns the fog off a world map as you travel,
so you can see how much of the planet you have actually filled in.

Nothing leaves the phone. There is no account, no server and no analytics — the only network calls
are for map tiles and (optionally) naming the countries you pass through.

## What it does

- **Fog of war map.** The whole world starts covered. Everywhere you have been is cut out of the
  fog, at roughly 300 m resolution, and stays cut out forever.
- **Background tracking.** A foreground service keeps a location subscription alive with the screen
  off, and restarts itself after a reboot. The persistent notification shows how much you have
  uncovered and can stop tracking without opening the app.
- **Honest numbers.** Uncovered area in km², percentage of Earth's land and of the whole planet,
  distance travelled, days out, countries and regions visited, and how much new ground you broke
  each year.
- **Your data stays yours.** Export a full backup as JSON, the uncovered area as GeoJSON, or your
  trail as GPX. Import a backup to merge an old phone's map into this one.

## Getting the APK

Every push builds debug and release APKs in GitHub Actions. Open the latest run under the
repository's **Actions** tab and download the `roamed-apks` artifact.

To build locally you need the Android SDK (API 35) and JDK 17+:

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/
./gradlew :core:test                # the maths, no device needed
```

## How the fog actually works

The world is divided using the standard Web-Mercator tile grid at **zoom 17** — about 305 m across
at the equator, 195 m at 50° latitude. A cell is either uncovered or it is not; there is no partial
state. That single decision is what keeps a decade of tracking down to a few hundred thousand rows
instead of millions of GPS points.

When a fix arrives:

1. **Fixes that are too vague are dropped** (default: worse than 75 m accuracy).
2. **Jitter is ignored.** A stationary phone wanders tens of metres between readings. Until you have
   moved further than roughly the accuracy of the fix, the previous position stays the anchor, so
   the odometer does not climb while the phone sits on a table overnight.
3. **Every cell the accuracy circle touches is uncovered** — not just the one you stand in.
4. **Consecutive fixes are joined up.** At 100 km/h with a fix every 25 seconds you move 700 m
   between readings, so the segment between them is walked at half-cell steps and uncovered too.
   Gaps longer than 3 km are treated as a flight, a tunnel or a glitch, and are *not* drawn — the
   map should not invent a line across the Atlantic.

Area is computed exactly rather than approximated: a Mercator cell is a lat/lon rectangle, whose
spherical area is `Δlon · R² · (sin φ_north − sin φ_south)`. Summing every cell in the grid
reconstructs the sphere between ±85.05° to within a rounding error, which is what
`TileMathTest` asserts.

**One caveat, stated plainly:** because a cell is all-or-nothing, walking 50 m down a street
uncovers a whole 300 m square. Uncovered area therefore flatters you at walking pace. It is
consistent, so progress over time is meaningful, but it is not a survey.

### Drawing it

The fog is a single `saveLayer`: fill it with a dark colour, then punch the uncovered cells out
with a `PorterDuff.CLEAR` paint. Drawing the *holes* is what makes it cheap — there are always far
fewer uncovered cells on screen than pixels to cover.

Cells are stored at z17, but drawing every one of them while looking at a whole continent would
mean hundreds of thousands of sub-pixel rectangles. `ExploredIndex` keeps the set bucketed by z10
ancestor for zoomed-in queries and memoises collapsed copies for zoomed-out ones, so the overlay
asks for "cells in this window at this level of detail" and gets back a bounded list either way.

Collapsing like that would normally lie to you: at world zoom a single 300 m cell would claim a
square a thousand kilometres across. So each drawn square also carries a count of how many stored
cells it actually stands for, and is erased *in proportion* — a region you drove through once
reads as a smudge, one you have covered thoroughly reads as solid. Two deliberate distortions in
that curve, both in `Coverage`:

- a **floor**, because a thoroughly explored city is about 0.0001 of a world-zoom square. Scaled
  honestly it would vanish the moment you zoom out, which is worse than overstating it. Anywhere
  you have been stays legible.
- a **gamma**, because the interesting range is the low end; linearly, everything under
  "half explored" would look identical.

At the storage zoom every square holds exactly one cell, so the coverage is always 1.0 and the
detailed view is untouched by any of this. The number on the stats screen is unaffected either
way — area is always summed from the z17 cells.

## Layout

```
core/   Plain Kotlin/JVM. Tile maths, the fog engine, the explored-cell index,
        statistics and the backup/GPX/GeoJSON formats. No Android types, so it
        is unit-tested on the JVM with no emulator.
app/    Everything Android: Room storage, the location service, and a Compose UI
        over an osmdroid map.
```

Keeping the geometry in a separate JVM module is deliberate: the parts most likely to be subtly
wrong are the parts that can be tested in a second.

## Settings worth knowing

| Setting | Default | What it trades |
| --- | --- | --- |
| Check position every | 25 s | Battery against how finely a fast journey is recorded |
| Only after moving | 20 m | The radio stays asleep while you sit still |
| High accuracy | off | Sharper edges, noticeably more battery |
| Reveal radius | 120 m | How generously a fix uncovers around itself |
| Ignore fixes worse than | 75 m | Rejecting rubbish fixes against missing indoor ones |
| Keep raw fixes for | 365 days | Storage. The uncovered map is kept forever regardless |

## Permissions

- **Location (fine)** — the entire point of the app.
- **Location (background / "all the time")** — required to keep uncovering with the screen off.
  Android insists this is asked for in a second, separate prompt, so the app asks only after
  foreground location has been granted.
- **Notifications** — Android requires a visible notification for a location foreground service.
- **Boot completed** — to resume tracking after a restart, if it was on.

## Attribution

Map tiles are served by [OpenStreetMap](https://www.openstreetmap.org/copyright) and its
contributors, rendered through [osmdroid](https://github.com/osmdroid/osmdroid). The app identifies
itself with its own user agent, as the OSM tile usage policy requires. Heavy use should be pointed
at your own tile server.
