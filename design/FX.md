# Merge FX: cats, then Flashover

Implemented in `android/…/MergeFx.kt`, driven from `MeterView.playNext()` / `play()`. It was picked from three concepts (Prism lasers, Live Wire arcs, Flashover fire) prototyped as HTML canvases; the fire prototype is in `fx-prototype/` (`proto.html?play=1&tier=XL`). On-device recording: `fx-prototype/on-device.png` (S, M, L, XL in sequence, 60 fps while playing, 0 fps between).

## Part 1: the cat flyby (added on the user's request)

Original pixel art (34×21 px at 3×, drawn 1.2–1.6× on stage, so roughly 120–170 px wide: a pop-tart body, grey head, rainbow trail; a Nyan Cat homage, not the original sprite).

- **Entry:** the cats burst out from under the camera island, each popping a violet flare at x = 0.
- **Roam:** each one roams along a random Catmull-Rom route, with a waypoint every ~⅓ s anywhere on the stage, long legs, and a 22 % chance per waypoint of a loop-the-loop. Each leg gets a random share of the flight time, so cats surge and dawdle.
- **Butts on fire:** two licking tongues point backwards, flame puffs hang in the air behind, and burning drips fall. Cats and their flames are opaque over-paint, because additive fire turns white over the rainbow. The rainbow starts about 4 samples behind the butt, so the flame fills the gap.
- **Finale:** each route ends well inside the stage, where the cat **bursts like a firework shell**: a bang flare, 48 golden crackle sparks, and 9 mini cats (0.36–0.5× size). The minis fly out radially with a little lift, droop under gravity (drag τ 0.55 s, g 420 px/s²) and fade over 1.1–1.5 s. Each has its own rainbow streak and burning butt.

| Tier | Cats | Roam (±15 %) | The moment starts at |
|---|---|---|---|
| S | 1 | 1.8 s | ≈ 2.0 s |
| M | 2 | 2.4 s | ≈ 2.9 s |
| L | 3 | 2.8 s | ≈ 3.5 s |
| XL | 5 | 3.3 s | ≈ 4.5 s |

Cats launch 180–300 ms apart. The whole existing moment (relay strike, cycles, delta beat, roll-up, fire) is shifted by the lead time, so the numbers don't move until the last firework bursts; the island strikes 250 ms after it. Night (23:00–07:00): no cats. Dozing: nothing (`settle()` clears the FX).

## Part 2: Flashover (the fire)

The design spec below is the one the concept agent wrote; the Kotlin port follows it line by line (same constants, same hash). Times are relative to the moment's t0, which is after the cats.

---


**Pitch.** The camera island is the igniter. On a merge it snaps a violet spark at its right edge, and a gas burner rail lights along the base of the instrument, racing out of the island like a stove ring catching. The hero tubes fill with licking flame from the bottom, as if the neon had overloaded into burning gas. When the new count lands, **the changed digit catches fire**: flame tongues ignite along its top edges, spreading left to right, and it spits a burst of embers out of its dome. The PR's own +/- digits catch fire too: additions burn orange, deletions burn **argon violet**. During the roll-up, a **lit fuse** burns right to left under each bank and passes each tube just before it rolls, so the existing cascade looks like the fuse is setting it off. Bigger PRs overload harder. On L the tube vents through its dome and flame spreads along the ceiling. On XL, flame rolls along the ceiling, a fireball bursts out of the new digit, and a wall of fire sweeps out of the camera edge across the whole instrument (the **flashover**). It recedes and leaves the digits burning while embers drift down. Every tier ends with the gas shutting off back into the island and a clean, static frame.

Prototype: `proto.html?t=<ms>&tier=S|M|L|XL[&night=1]` (one frame, pure function of t), `?play=1&tier=XL` (loop), `?stats=1` (peak draws per tier). Logic in `fx.js`. Sheets: `sheet-M.png`, `sheet-XL.png` (also `sheet-L.png`, `sheet-S.png`). Re-render with `shoot.sh <tier> "<t…>"` and `stats.sh`.

---

## 0. Conventions

- **Coordinates** are stage px (device x − 296; on device s = 1). Draw inside MeterView's existing stage transform, **after** tubes/signs/labels/lamp. Things centred on x = 0 spill under the camera island. That's harmless because it's invisible there, and nothing that matters sits left of 0.
- **Blend**: every FX draw uses `PorterDuffXfermode(ADD)` (as `plusPaint`). Paint alpha = the listed alpha × `AM` (AM = 1, or 0.4 at night).
- **Time** `t` = ms since the event's t0 (same clock as `play()`). Every element is a **pure function of t** (deterministic per-index randomness, no per-frame state). Iterate only particles whose birth is in `[t − maxLife, t]`.
- `U` = deterministic uniform [0,1) from hash(seed, index, k). Any integer hash is fine; use a distinct `k` per attribute.
- `n(x)` = 1D smooth value noise in [0,1] (hash at integers, smoothstep interpolation); `sn(x) = 2n(x) − 1`.
- `sstep(a,b,x)` = smoothstep. `decay(t,t0,τ) = t<t0 ? 0 : e^(−(t−t0)/τ)`. `bump(t,t0,τ) = t<t0 ? sstep(t0−65,t0,t) : e^(−(t−t0)/τ)` (swells into t0, decays after). `rise(t) = sstep(120,330,t)`.
- Landmarks used below: hero tube `T` = changed hero tube (units at x 184, w 150, y 28, h 452; tens at x 24), tube bottom y 480, `cx` = tube centre x. Hero lands at **495** (units) / **555** (tens), and at **315** in S. Bank tube at place p (0 = units, rightmost) lands in the delta beat at **390 + 30p** (adds) / **450 + 30p** (dels). Roll-up `roll` = 1900 (M) / 3040 (L, XL). Rail line `RAIL_Y = 494`.
- **Changed tubes** come from the Event: hero tubes whose digit changed are "strong", the other hero tube is "weak". If both changed (10th PR), both are strong and the tens tube's digit fire starts at 555. Bank tubes that are **lit after the delta beat** (the PR's own +N / −N) catch fire.

## 1. Sprites to pre-bake (once, at the view's scale; ≈ 0.9 MB total)

| Name | Size | Recipe |
|---|---|---|
| `FIREP[0..15]` | 64×96 | Radial gradient, circle r 32 scaled ×1.5 vertically. Alpha stops 0:1, .3:.6, .6:.2, 1:0. Colour = FIRE ramp at k/15. FIRE ramp: 0 `#FFF1E0`, .12 `#FFD9B0`, .3 `#FF8A2A`, .55 `#FF5200`, .8 `#A02600`, 1 `#300A00`. |
| `ARGP[0..15]` | 64×96 | Same, ARGON ramp: 0 `#F7F3FF`, .15 `#ECE4FF`, .35 `#A98CFF`, .6 `#6A45FF`, .85 `#35208F`, 1 `#100828`. |
| `TONGUE` / `ATONGUE` | 48×160 | Flame tongue, base at the bottom, built per pixel. `v = 1 − (y+.5)/160` (0 = base, 1 = tip). Half-width `hw = 22.8·sqrt(clamp(v/.16))·(1−v)^0.7`, `d = |x+.5−24|/hw` (skip d ≥ 1). Alpha `(1−d²)·clamp(v/.05)·(1−v³)`. Colour = ramp(clamp(.04 + .8v + .35d)), so the core is white-hot and the edges and tip run red. FIRE ramp / ARGON ramp. |
| `JET` | 24×48 | ADD-composite: TONGUE drawn at (2,0,20×46) α .55, then ATONGUE at (5,20,14×28) α 1. Gives a violet gas cone inside an orange tip. |
| `FLARE` / `AFLARE` | 128² | Radial: 0 `#FFF6EC` 1, .08 `#FFD9B0` 1, .22 `#FF8A2A` .55, .5 `#FF5200` .18, 1 → 0. Argon: `#F7F3FF`, `#ECE4FF`, `#A98CFF` .55, `#6A45FF` .2. |
| `EMBER` | 16² | Radial: 0 `#FFF1E0` 1, .18 `#FFD9B0` 1, .32 `#FF8A2A` .6, .6 `#FF5200` .18, 1 → 0. |
| `MANIFOLD` | 64×24 | `#6A45FF` α .45, vertical alpha falloff 0 → 1 (centre) → 0. |
| `TRAIL_N` / `TRAIL_A` | 128×10 | Horizontal: x=0 `#FFD9B0` 1, .2 `#FF8A2A` .6, .5 `#FF5200` .25, 1 → 0 (argon: `#ECE4FF`, `#A98CFF`, `#6A45FF`). Same vertical falloff as MANIFOLD. |
| `GASFILL` | 8×256 | Vertical, bottom → top: 0 `#FF8A2A` .7, .3 `#FF5200` .35, 1 → 0. |
| Heat light | shader | `LinearGradient(0,596 → 0,260, #FF5200 → transparent)`, built once. Alpha goes on the paint. |
| Glyph anchors | per glyph | From each **core-only** glyph mask (HERO and SMALL): `ink` = edge pixels (core α ≥ .5 and the pixel 3 px above, left or right is < .5), deduped on a 4 px grid. Top edges are listed twice. `anchors` = top-edge pixels, greedily picked topmost-first with spacing ≥ 18 px (HERO) / 10 (SMALL), max 8 / 3, then sorted by x. Store in glyph-local coords. |

## 2. Primitives

**Tongue** `tongue(bmp, bx, by, w, h, a, ph, sway=1)`: one `drawBitmapMesh(bmp, 1, 16, verts)` (1 draw). With `T = t/1000`: `hf = h·(0.78 + 0.4·n(7T + ph))`. For mesh row r = 0..16, `v = r/16` (bitmap row 0 is the tip, so vertex row 0 has v = 1). Each row offsets x by `off(v) = sway·(w/2)·v^1.3·(sn(2.6T − 1.3v + ph) + 0.5·sn(6.5T − 3.1v + ph))` and sits at y = `by − hf·v`, spanning `bx − w/2 + off` → `bx + w/2 + off`. The wave travels up the flame, so it licks. Use different noise seeds for the height flicker and the two wave octaves. A rotated tongue is `save; translate(x,y); rotate(θ); tongue(0,0,…); restore` (θ = 0 points up).

**Flamelets** (puff particles): particle i is born at `tb = t0 + (i + U0)·1000/rate` over [t0, t1). Then `e = env(tb)`; skip if e ≤ .01 or U1 > 2e. `k = min(e, emax)`, life `L = lerp(life,U2)·(.45+.55k)`, age `a` in s, `u = a/L`. `v0 = lerp(v0,U5)·(.5+.5k)`. Position: `x = x0 + vx·a + wob·u·sin(2π·wobF·(.7+.6U7)·a + 2πU8)`, `y = y0 − v0·a − ½·acc·a²`. Size `s = lerp(size,U9)·(.6+.4·min(k,1.5))·(1 − .6u + grow·u)`. Draw `PUFF[floor(16u)]` at s × 1.5s, centred, alpha `A·min(1,6u)·(1−u)^1.2`.

**Sparks** (streaks): birth tb, spawn (x0,y0), angle θ, speed → (vx,vy), linear drag τ, gravity g (px/s², +down). With `d = τ(1 − e^(−a/τ))`, position is `x = x0 + vx·d`, `y = y0 + gτa + (vy − gτ)·d`. Each spark is one segment from pos(a − streak) to pos(a). Bucket by `b = floor(5u)` and draw each bucket with **two `drawLines` calls**: glow (`#FF5200` or `#6A45FF`, width 3.5w, α .3(1−.17b)) and core (bucket colour neon `#FFF1E0, #FFD9B0, #FF8A2A, #FF5200, #A02600` / argon `#F7F3FF, #ECE4FF, #A98CFF, #6A45FF, #35208F`, width w(1−.12b), α 1−.17b), round caps.

## 3. Elements (exact parameters)

**A. Strike** (M, L, XL; k = 1 M, 1.3 L/XL), t ∈ [0, 400]: `a = t<15 ? t/15 : e^(−(t−15)/70)`.
AFLARE centred (0, 298), 44×640, α .6·a·k (the island seam flashes). AFLARE centred (0, 490), 110k², α a. FLARE centred (2, 490), 30², α a.

**B. Burner rail.** Jets j at `x = 6 + 11j + 4(U−.5)` up to x1 = 334 (M, 31 jets) / 664 (L/XL, 61 jets). Jet j ignites at `30 + (x/x1)·ignDur` (ignDur 140 M / 240 L/XL). With d = t − ignition: `pop = d<40 ? 1.6d/40 : 1 + .6e^(−(d−40)/70)`. Shut-off at `off0 + (1 − x/x1)·260`, then linear to 0 over 90 ms (off0 = 1350 M / 3020 L / 3350 XL), so the far jets die first and the last one dies at the island. `boost(t) = (XL ? 1 + 1.4·bump(t,495,300) : 1)·(1 − .35·sstep(1500,2200,t))`. `I = pop·off·boost`.
Jet: JET, bottom at y 497, h = `32·I·(.5 + .7n(t/30 + 1.7j))·(.7 + .6U)`, w = `18(.8 + .2min(I,1.5))`, α min(1, .8I).
Manifold: MANIFOLD stretched x −4 → last lit jet + 4, centred y 495, h 20, α `.5·clamp(boost)·clamp((t−30)/60)`.
Striker: AFLARE 54² at (p·x1, 490), p = (t−30)/ignDur ∈ [0, 1.15], α .95(1 − max(0, p−1)/.15).

**C. Tube fire** (each hero tube, `clipPath(tube outline)`). `e = env(t)`, `H = 160e`, base y = 486.
- GASFILL over (T.x, 480−fh, 150, fh), `fh = 452·min(1, .12 + .3e)`, α `.3·min(e,2)`.
- Back tongue: (cx, 486), w 165, h 1.15H, α .28, sway .6.
- 5 tongues i = 0..4: x = `T.x + 150(.2 + .15i) + 4·sn(t/400 + i)`, w = 150·[.36, .44, .5, .44, .36], h = H·[.62, .9, 1, .82, .58], α .55.
- Flamelets: rate 60 (strong) / 30 (M weak) / 50 (L/XL weak). t0 100, t1 1500 (M strong) / 1300 (M weak) / 3300 (L/XL). Spawn x = T.x + 150(.2 + .6U), y = 470 − 30U. life .3–.5 s, v0 150–260, acc 300, size 14–26, wob 10 @ 2.5 Hz, α .45, FIREP, emax 2.8, env = the tube's env.
- Envelopes:
  - M strong `rise·(1 + .8·bump(495,220))·(1 − sstep(800,1500))`. M weak `.55·rise·(1 − sstep(700,1300))`.
  - L strong `rise·(1.1 + 1.5·bump(495,280))·(1 − .72·sstep(800,1600))·(1 − sstep(2650,3050))`. L weak: same with `(.8 + .6·bump)`.
  - XL (both tubes) `rise·(1.2 + 1.6·bump(495,300))·(1 − .6·cover)·(1 − .72·sstep(900,1700))·(1 − sstep(2900,3300))`, where `cover = sstep(480,510)·(1 − sstep(780,1000))`. This thins the tube fire under the flashover and saves draws.

**D. Digit catches fire.** For anchor j (sorted by x): `tj = t0 + 14j`, `ej = sstep(tj, tj+90)·(t < tj+90 ? 1 : e^(−(t−tj−90)/τ))`. Tongue at (ax, ay + 2), w = wd, h = `hd·ej·(.65 + .5U)`, α .7, sway .8. Flamelets: rate R over [t0, t0+3τ], env `decay(tb,t0,τ)`, spawn at a random `ink` point ±1.5 px. life .2–.4, v0 60–120, acc 380, size .5–.9 wd, wob 4 @ 4 Hz, α .5, FIREP (ARGP + ATONGUE for deletions).

| Digit | t0 | τ | hd | wd | R |
|---|---|---|---|---|---|
| Changed hero (M) | its landing (495 / 555) | 400 | 52 | 20 | 50 |
| Changed hero (L) | landing | 520 | 65 | 25 | 50 |
| Changed hero (XL) | **760** (as the flashover recedes) | 520 | 65 | 25 | 50 |
| Unchanged hero (L, XL) | 555 / 820 | 320 | 40 | 18 | 25 |
| Lit bank digits (M) | delta landing (adds 390+30p, dels 450+30p) | 340 | 24 | 11 | 12 |
| Lit bank digits (L) | delta landing | 340 | 30 | 13.75 | 12 |
| Lit bank digits (XL) | 760 adds / 790 dels | 340 | 30 | 13.75 | 12 |
| S / night: changed hero only | 315 (S) / landing (night) | 110 | 26 | 12 | 30 |

S/night also adds 7 sparks: births land + 120U, spawn at a random anchor, angle −110…−70°, speed 70–160, g 120, τ .6, life .25–.32, w 1.6, streak 30.

**E. Ember spit** (M, L) at t0 = 495 from (cx, 60): FLARE centred (cx, 75), 130×110, α `.8·sstep(475,495)·decay(495,80)`. Sparks: n 30 (M) / 50 (L), births `t0 + 160U²`, spawn (cx ± 35, 60 + 30U), angle −132…−48°, speed 380–820, g 1100, τ .7, life .5–1.0, w 1.7, streak 40. L adds 22 more from the other tube at t0 = 555.

**F. Ceiling fire** `ceilingFire(x0, dir, reach, t0, speed px/ms, tOut)`:
- `front = clamp((t−t0)·speed/reach)`, `out = 1 − sstep(tOut, tOut+160)`.
- Hot gas layer: FLARE centred (x0 + dir·reach·front/2, 4), size (reach·front + 60)×70, α .45·out.
- Tongues i with 26i ≤ reach: x = x0 + 26i·dir. Ignition `ti = t0 + 26i/speed`, `e = sstep(ti, ti+60)·out`. Rotated tongue at (x, 4 + 8U), θ = `dir·(90° + 17° + 23°U)` (hangs from the ceiling and licks outward), w 38 + 22U, h (100 + 110U)·e, α .45.

**G. L vent:** tongue (cx, 70), w 90, h 120e, α .6, with `e = sstep(450,520)·(1 − sstep(580,880))`. ceilingFire(cx, +1, 220, 470, .9, 640) and ceilingFire(cx, −1, 140, 470, .9, 620). Flamelets: rate 60 over 440–820, env `sstep(440,495)·(1 − sstep(560,820))`, spawn (cx ± 20, 8 + 26U) with vx = ±(250–750) (random sign), v0 −20…30, acc 0, life .22–.4, size 30–56, grow .8, wob 8 @ 2 Hz, α .4.

**H. XL flashover.**
- **Rollover:** ceilingFire(60, +1, 640, 340, 4.2, 560) and ceilingFire(260, −1, 240, 360, 2.4, 540).
- **Wall:** 11 front tongues at `bx = (i+.5)·680/11 + 10·sn(i)`, w 120, h `560·e·(.8 + .35U)`, α .5. Plus 7 back tongues at `bx = (i+.5)·680/7`, w 190, h `700·e·(…)`, α .35. All have base y 506 and sway .8. Each ignites at `470 + 90·bx/680` (the front sweeps out of the camera edge in 90 ms), with `e = sstep(ti, ti+60)·(1 − sstep(700,1350))`. As h falls, the wall recedes back down into the rail.
- Wall flamelets: rate 110 over 470–1300, env `1.3·sstep(470,495)·(1 − sstep(700,1250))`, emax 1.3. Spawn x = U·680·sstep(470,560,tb), y = 494 − 120U. v0 700–1100, acc 300, life .3–.5, size 50–90, grow .6, wob 20 @ 1.5 Hz, α .3.
- **Fireball** (the mercury flash): FLARE 820² centred on the changed hero tube (cx, 254), α `.75·sstep(470,495)·decay(495,120)`.
- **Eruption:** 90 sparks, births 495 + 140U, spawn (680U, 494 − 10U), angle −118…−62°, speed 550–1050, g 1100, τ .8, life .7–1.3, w 2, streak 34.
- **Ember fall:** 60 EMBERs, births 850 + 1100U, life 1.0–1.8 s.
  - `x = 680U + 10·sin(2π·.9a + φ)`, `y = −10 − 30U + vt·(a − .35(1 − e^(−a/.35)))`, with vt 80–180 px/s.
  - Size 8–14, α `(.7 + .3sin(2π·5a + φ))·min(1, a/.08)·(u < .65 ? 1 : (1−u)/.35)`.
- **Heat light** (L, XL): rect (0,260)–(680,596), heat shader, paint α = H.
  - XL: `H = .15·sstep(380,495)·(1 − sstep(700,1600)) + .04·sstep(1200,1600)·(1 − sstep(2700,3100))`.
  - L: `H = .07·sstep(300,495)·(1 − sstep(900,1800))`.

**I. Fuse** (M, L, XL; k = .8 M, 1 L/XL). One per bank: y 182 (adds, neon) and y 316 (dels, argon).
- Speed `v = 51/110` px/ms: one tube pitch per 110 ms, the roll-up cascade's pitch. `tc0 = roll − 240`.
- Head `x = 630 − (t − tc0)·v` for t ∈ [tA = tc0 − 73 (x 664), tB = tc0 + 487 (x 404)].
- Trail: TRAIL from the head to min(head + 116, 664), h 6, α `.85·(1 − clamp((t−tB)/120))`.
- Head (t ≤ tB): FLARE/AFLARE at `48k·(.75 + .5n(t/25))`, α .95, plus a tongue (w 12, h 26k, α .9).
- Kick: as the head passes place p (`tp = tc0 + 110p`), a tongue at (tube centre x, y − 6), w 30k, h `56k·sstep(tp, tp+40)·(1 − sstep(tp+40, tp+220))`, α .8. That tube starts rolling 240 ms later.
- Sparks: rate 280k/s over [tA, tB], spawned at x(tb), angle −170…−10°, speed 140–420, g 1400, τ .5, life .08–.2, w 1.5, streak 22.

## 4. Timelines (aligned with SPEC §8.2)

**S** (≤ 0.8 s; most merges stay quiet)
| t | Existing | Fire |
|---|---|---|
| 90–315 | hero cycles 5 steps | nothing |
| 315–776 | hero lands (od .6), banks roll | the changed digit flickers alight (D, S row) and 7 embers lift off it. Last FX frame **776**. |

**M** (≤ 2.5 s)
| t | Existing | Fire |
|---|---|---|
| 0–90 | relay strike (gB .35) | Strike at the island edge (A). The rail ignites from the island, 30 → 170, x 6 → 334, under the hero tubes only (B). |
| 120–495 | hero cycles | Tube fire rises 120–330. Strong tube: H up to 160, swelling into 495. Weak tube at .55 (C). |
| 390–570 | delta beat lands | Each lit bank digit catches fire as it lands (+ orange, − violet), τ 340 (D). |
| 495 | hero lands, od 1.0 | The changed digit catches fire: 8 tongues spread 14 ms apart (D). Ember spit of 30 (E). Tube fire whoomphs (bump to e 1.8). |
| 540–1900 | hold | Tube fire dies 800–1500. Rail shuts off back into the island 1350–1700. |
| 1587–2267 | roll-up from 1900 | Fuses burn right→left under both banks; each tube kicks 240 ms before it rolls (I). Last FX frame **2316**. |

**L** (≤ 3.5 s): everything in M, plus:
| t | Fire |
|---|---|
| 0–90 | Strike at k 1.3. The rail runs the full width, 30 → 270, x 6 → 664 (under the 7-day bank too). |
| 300–1800 | Heat light (H .07). |
| 120–495 | Both tubes burn (weak at .8); the strong one swells to e 2.6 (H ≈ 416, filling the tube). |
| 450–880 | **Vent**: the strong tube vents through its dome, and fire spreads along the ceiling 220 px right / 140 px left (G). |
| 495 / 555 | Both hero digits catch fire (strong hd 65; weak hd 40). Ember spits of 50 + 22. |
| 1600–2650 | Low pilot fire in both tube bases (e ≈ .3). |
| 2727–3407 | Fuses (roll 3040). Tube fire out by 3050, rail shut-off 3020–3370. Last FX frame **3468**. |

**XL** (≤ 4.5 s; also every 100th lifetime PR): everything in L except the vent and spits, plus:
| t | Existing | Fire |
|---|---|---|
| 340–560 | hero cycling | **Rollover**: flame races along the ceiling from the hero domes to the right edge in ~150 ms (H). Both tubes at e up to 2.8. |
| 470–560 | — | **Flashover**: the wall of tongues sweeps out of the camera edge across the stage. The rail flares (boost 2.4). |
| 495 | mercury flash, od 1.4 | **Fireball** from the changed digit. 90-spark eruption from the rail. Heat light peaks (.15). |
| 495–1200 | surge (all tubes cycle) | The wall burns, then recedes into the rail 700–1350. Tube fire thinned under it. |
| 760–1400 | — | As the wall clears, both hero digits and the PR's bank digits are left burning (D, XL rows). |
| 850–3750 | hold | **Ember fall**: 60 embers drift down. Low pilot fire in the tube bases until 3300. |
| 2727–3407 | roll-up 3040 | Fuses. |
| 3350–3700 | — | The rail shuts off into the island. Last FX frame **3696**. |

Queued events fit their slots: M ends 2316 < 2900; L/XL end 3468/3696 < 3900.

## 5. Cost

Peak draws per frame, measured by the prototype's `?stats=1`. A tongue counts as one `drawBitmapMesh`; sparks count as 2 `drawLines` per colour bucket.

| Tier | Peak | At | Dominated by |
|---|---|---|---|
| S / night | 18 | 488 ms | 8 digit tongues + flamelets |
| M | 142 | 828 ms | tube flamelets (~50), rail 31, tongues ~25 |
| L | 225 | 764 ms | rail 61, flamelets, tongues ~45 |
| XL | 278 | 1112 ms | wall flamelets, rail 61, 18 wall tongues, embers |

- There is no blur at runtime: everything is a pre-baked bitmap, `drawBitmapMesh`, `drawLines`, or one gradient rect.
- Two `clipPath`s per frame (hero tubes) while tube fire burns.
- Allocation-free per frame: reuse a `FloatArray(68)` for mesh verts and one `FloatArray` per spark bucket.
- 0 fps returns as soon as the last FX frame passes. Add `now − fxT0 < fxEnd[tier]` (S 800, M 2400, L 3500, XL 3750) to the `animating` test in `onDraw`.

## 6. Night and dozing

- **Night (23:00–07:00):** every tier plays only the S "whisper" at `AM = 0.4`: the changed hero digit flickers alight for ~400 ms with 7 embers. Its t0 is the hero landing (495, or 315 for S). No strike, rail, tube fire, spits, fuses, heat, fireball or flashover. Peak 18 draws, done by 956 ms.
- **Rear dozing / `isStill()`:** no FX. The existing `still` path settles the moment, and the FX clock is simply never drawn.
- **Long Count view:** a merge already cuts to Today first, so the FX always draws on the Today layout.

## 7. Port notes

- Make one `Fire` object holding the sprites plus the per-event inputs: t0, tier, night, changed hero tube indices, and the lit bank tubes with their glyph anchors/ink. Anchors come from the glyph bake (§1) offset by the tube's glyph origin.
- In `play()`, set `fxT0 = t0`, `fxTier = e.tier`. In `onDraw`, after `drawLamp`, call `if (screen == TODAY && now − fxT0 < fxEnd) fire.draw(c, now − fxT0)`.
- The prototype's backdrop is a static PNG. On device the tubes keep cycling, flaring and rolling underneath exactly as now; Flashover only adds light on top.
