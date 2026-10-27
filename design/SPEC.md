# Neon Tally: implementation spec

A desk counter built from Nixie tubes, shown on the Xiaomi 17 Pro Max rear screen. Two big neon tubes count **today's merged PRs**. A neon bank counts **lines added** and an argon-violet bank counts **lines removed**. A dimmer bank holds the **rolling 7-day PR count**. Each merge plays a relay strike and a cathode roll, and both line banks briefly show *that PR's own* +/− before rolling up to the new day totals. A tap switches to the **Long Count** view: the all-time PR count plus a 7-day rail with one pair of tubes per day.

Everything is one custom `View` drawing on `Canvas`, which replaces the placeholder `MeterView` in `merged/android`. It keeps the existing public API: `show(snap, fresh)`, `signedOut()`, `markStale()`, `onAnimating`, `onLongPress`, `debugCutoutPx`.

Mockups: `mockups/big.png` (24 PRs), `quiet.png` (2), `zero.png` (0 today, 9 in 7 days), `moment.png` (~900 ms into a merge), `alt.png` (Long Count).

**Implemented in `android/…/MeterView.kt` + `NixieArt.kt`, with these deliberate cuts (battery / YAGNI):** no random neon flicker (§9) — it was the only idle wake-up with no burn-in purpose; no dedicated rate-limit state (§7) — at 30 req/h against a 5000/h budget it can't trigger, and any error already cools the instrument; no midnight Handler (§9) — the 2-min poll recomputes the day; the overdrive glow-radius scaling is approximated by an additive re-draw of the baked glyph.

---

## 0. Decisions made in synthesis (vs. the original Nixie concept)

| Issue | Resolution |
|---|---|
| Hero glass streak read as a "1" | Removed. The only reflection is a soft crescent inside the dome (§3 L6). |
| Blank hero tube read as a dim "0/8" | Blank hero tubes draw **mesh only, no ghost cathodes**. Blank small tubes draw ghosts at α 0.06. |
| Merge moment barely differed from rest | Grafted from Heft: both line banks show **this PR's delta**, overdriven, for about 1.4 s, then roll up to the totals. A relay strike and a sympathetic flare hit the whole instrument. |
| `24860` was slow to parse | The lit **DP cathode works as a thousands comma** on the thousands tube (`24,860`). |
| 9 px week bargraph was illegible | Removed. The default view keeps only a 7-day count, in 3 tubes at 60 % brightness. Per-day counts moved to the Long Count rail. |
| Labels | 17 px silkscreen at ≤ 31 % luma, ornament only. Nothing anyone needs depends on reading them. The "LIVE" word is gone; the lamp alone carries status. |
| Thin small-tube strokes | Small tubes use Big Shoulders Display **Medium 500** (hero stays Light 300). |
| Idle cost | **No breathing.** At rest the screen is a static frame (0 fps) with about 4 redraws per minute on average. |
| Week definition | **Rolling 7 days** everywhere, matching the scaffold's 7-day fetch window, so there is no empty counter on Monday morning. |
| Long Count milestone bar | Dropped. Next to the 4 tubes it read as a 5th digit. Centuries are celebrated by the XL tier instead. |
| YTD | Still fetched by the scaffold, **not displayed** (it's the least interesting number, and the layout has no room for it). |
| Stale / offline | Shown by **cooling** the whole instrument to a dim ember. The 17 px "OFFLINE" word is gone. |
| Night | From 23:00 to 07:00: brightness capped at 40 %, no halo, flare, surge or haptic. |

---

## 1. Canvas, stage, scaling

- Rear panel: 976×596 px, 450 dpi, OLED. The **left 296 px are under the camera island**. The design lives in a **stage of 680×596** whose origin is device (296, 0). All coordinates below are stage px, with fractions of stage W=680 / H=596 in brackets.
- `cutoutLeft = max(rootWindowInsets.displayCutout?.safeInsetLeft ?: 0, debugCutoutPx)`. The scaffold already draws under the cutout with `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`.
- `s = min((viewW − cutoutLeft)/680, viewH/596)`, `originX = cutoutLeft + ((viewW − cutoutLeft) − 680·s)/2`, `originY = (viewH − 596·s)/2`. On the device s = 1, origin = (296, 0). Bake every bitmap at scale s.
- Background is `#000000` everywhere: unlit OLED pixels are off.
- The rounded outer corners are r=101 device px. In stage coords the corner centres are (579, 101) top-right and (579, 495) bottom-right. Nothing below sits outside them, and everything is at least 24 px from the stage edges.

---

## 2. Palette

| Token | Hex | Use |
|---|---|---|
| bg | `#000000` | background |
| neon.core | `#FFD9B0` | lit neon digit core (PRs, additions, 7d, lifetime, rail), signs |
| neon.hot | `#FFF1E0` | overdriven neon core (od > 0.35) |
| neon.mid | `#FF8A2A` | tight glow, lit mesh in overdrive, today's rail letter |
| neon.glow | `#FF5200` | wide glow, halo |
| neon.ghost | `#3A2010` | unlit cathode wire (neon tubes) |
| neon.mesh | `rgb(255,140,60)` | hex anode mesh, warm inner radial |
| argon.core | `#ECE4FF` | lit deletions digit core, "−" sign |
| argon.hot | `#F7F3FF` | overdriven argon core |
| argon.mid | `#A98CFF` | argon tight glow |
| argon.glow | `#6A45FF` | argon wide glow / halo |
| argon.ghost | `#2A2350` | unlit cathode wire (argon) |
| argon.mesh | `rgb(150,130,255)` | argon mesh / inner radial |
| glass | `rgba(255,255,255,0.10)` | tube outline (`+0.10·od`) |
| label | `#5E4C3E` | silkscreen labels (31 % luma) |
| event | `#FFB27A` + glow `#FF5200` α0.67 r8 | labels while a merge is playing |
| lamp.live | `#FFD2A0` + glow `#FF8A2A` r4, `#FF5200` r12 | status lamp, data fresh |
| lamp.flash | `#FFF6EC` + glow `#FFB27A` r6, `#FF5200` r16, `#FF5200`α.53 r30 | lamp on poll success and on merge |
| lamp.stale | `#2A1D15`, no glow | lamp, data stale / signed out |

Measured on the mockups: blank-tube glass and mesh peak at 4.7 % luma (hero) and 9 % (small). Labels peak at 31 %.

---

## 3. Tube anatomy (identical for every tube; sizes per class)

### 3.1 Tube classes

| Class | w × h | Font (Big Shoulders Display) | textSize | mesh cell m | dome r | gases |
|---|---|---|---|---|---|---|
| HERO | 150 × 452 [.2206W × .7584H] | Light 300 | 390 [.6544H] | 11 | 75 | neon |
| HERO3 (100–999 PRs) | 104 × 452 [.1529W] | Light 300 | 300 [.5034H] | 11 | 52 | neon |
| SMALL | 44 × 118 [.0647W × .1980H] | Medium 500 | 100 [.1678H] | 7 | 22 | neon, argon |
| LIFE | 146 × 276 [.2147W × .4631H] | Light 300 | 236 [.3960H] | 10 | 73 | neon |
| LIFE5 (≥10,000) | 116 × 276 [.1706W] | Light 300 | 188 [.3154H] | 10 | 58 | neon |
| RAIL | 36 × 112 [.0529W × .1879H] | Medium 500 | 88 [.1477H] | 6 | 18 | neon |

**Glyph placement (all classes):** centre horizontally in the tube. Rendered cap height is ≈ 0.80 × textSize (measured: hero 313 px, small 81 px). Vertically, put the **ink-bounds centre of "0"** at `tubeTop + 0.51·h`: `baseline = tubeTop + 0.51h + bounds("0").height/2`.

### 3.2 Layers, back to front (inside the tube clip unless noted)

Tube outline path: a semicircular dome of radius w/2 on top, straight sides, and 8 px radius corners at the bottom.

- **L0 Halo** (outside the clip, only when `od·halo > 0`): ellipse rect `(x−0.6w, y−0.12h, 2.2w, 1.24h)`, RadialGradient (closest-side) from `gas.glow` at α `halo·od` to transparent.
- **L1 Warm inner radial** (lit tubes only): RadialGradient centred at (0.5w, 0.56h) with radii 0.55w × 0.55h, from `gas.mesh` α `(0.14 + 0.30·od)·lvl` to 0.
- **L2 Hex mesh anode**: a tile of `m × 1.732m` holding the path `M0,0 l m/2,0.866m l −m/2,0.866m M m/2,0.866m h m/2`, stroke 0.8 px `gas.mesh`, BitmapShader REPEAT. Mask it with a radial alpha from 1 at (0.5w, 0.52h) to 0 at radius (0.5w, 0.5h). Alpha: **lit 0.18·lvl**, **blank 0.10**. In overdrive add `0.37·od` and switch the stroke to `neon.mid` / `argon.mid` with a 3 px glow.
- **L3 Base band**: rect (0.08w, 0.925h, 0.84w, 0.075h − 3), r=2, `rgba(255,255,255,0.018)`.
- **L4 Ghost cathodes**: draw all ten digits "0".."9" stacked in the **Thin 100** weight, same textSize and position, solid `gas.ghost`, as **one group at one alpha**. Pre-composite them into one bitmap so overlaps don't add up. Group alpha: **0.50 behind a lit digit**, **0.06 in a blank SMALL/RAIL tube**, **0 in a blank HERO/LIFE tube** (mesh only, so a blank hero never reads as a digit).
- **L5 Glass outline** (not clipped): 1.5 px stroke, inset 0.75 px, `rgba(255,255,255, 0.10 + 0.10·od)`.
- **L6 Dome reflection** (not clipped): a single arc from (0.16w, 1.02r) to (0.56w, 0.22r), radius 0.8r, clockwise, stroke `max(2.5, 0.045w)` with round caps. Its LinearGradient runs along (0,0)→(w, 0.4w): α 0 → **0.07 + 0.08·od** at 45 % → 0. Blur 1.5 px (HERO/LIFE) or 0.6 px (SMALL/RAIL). Confined to the dome; there is **no vertical streak**.
- **L7 Afterglow digit** (cycling only): the previous digit at α `0.6·(1 − dt/30ms)·lvl·gB` for 30 ms after each switch.
- **L8 Lit digit**: glow plus core, alpha `lvl·gB`:
  - glow passes (BlurMaskFilter NORMAL, radius × k where `k = 1 + 1.1·od`): r2 `gas.mid` α1, r6 `gas.glow` α1, r16 `gas.glow` α0.67, r34 `gas.glow` α0.40, plus r70·od `gas.glow` α0.53 when od > 0.5;
  - core text: `gas.core`, or `gas.hot` when od > 0.35.
- **L9 Comma** (DP cathode; only on the **thousands-place tube** when the value is ≥ 1,000 and not in k/M mode; never drawn unlit): `sz = max(5, 0.12w)`, box left `x + w − 0.85·sz`, box top `y + h/2 + 0.29·textSize`. Draw a dot of diameter sz at box (0.7sz, 0.7sz), plus a tail: quad from (1.15sz, 0.85sz) via (1.0sz, 1.8sz) to (0.25sz, 2.1sz), stroke 0.32sz, round cap. Colour is `gas.core`/`hot`, glow r2 `mid` and r6 `glow`, alpha lvl.

`lvl` is per-tube brightness (bank level × flicker), `gB` is the global brightness (relay strike / night / cooled), and `od` is the overdrive amount in 0..1 (up to 1.4 for the XL flash).

### 3.3 Fixed brightness levels (lvl)
- Hero, additions: 1.0
- Deletions (argon): **0.9**. Blue subpixels wear fastest, so argon stays a little below neon. 1.0 on a net-negative day (§6.4).
- 7-day bank: **0.6**
- Long Count lifetime: 1.0. Rail: today 1.0, past days **0.55**.

---

## 4. Layout

### 4.1 TODAY view (default; see big/quiet/zero/moment.png)

| Element | Geometry (stage px) | Fractions |
|---|---|---|
| Hero tube 0 (tens) | x 24, y 28, 150×452 | x .0353, y .0470 |
| Hero tube 1 (units) | x 184, y 28 | x .2706 |
| Hero, HERO3 mode (≥100) | 3 tubes x 24 / 138 / 252, 104 wide, gap 10 | x .0353/.2029/.3706 |
| Hero label `PRs MERGED · TODAY` | text top-left (26, 508), baseline ≈ 521 | .0382, .8523 |
| Lines label `LINES` | text top-left (404, 28) | .5941, .0470 |
| **+ additions bank** (neon) | 5 SMALL tubes, y 56, x 404 / 455 / 506 / 557 / 608 (gap 7) → right edge 652 | y .0940; x .5941/.6691/.7441/.8191/.8941; right .9588 |
| "+" sign | centre (382, 115): bar 22×4 r2 and bar 4×22 r2 | .5618, .1930 |
| **− deletions bank** (argon) | 5 SMALL tubes, y 190, same x | y .3188 |
| "−" sign | centre (382, 249): bar 22×4 r2 | .5618, .4178 |
| 7-day label `7 DAYS` | text top-left (404, 336) | .5941, .5638 |
| **7-day bank** (neon, lvl 0.6) | 3 SMALL tubes, y 362, x 506 / 557 / 608 → bottom 480 | y .6074 |
| Status lamp | circle Ø10, centre (645, 515) | .9485, .8641 |

Hero and 7-day bottoms both sit at y 480 [.8054]. The lamp is 32 px inside the bottom-right r=101 arc. The sign glow uses shadows r3 `mid`, r10 `glow`, and r20 `glow` α0.53.

### 4.2 LONG COUNT view (tap; see alt.png)

| Element | Geometry | Fractions |
|---|---|---|
| Label `ALL-TIME PRs MERGED` | top-left (26, 22) | .0382, .0369 |
| Lifetime, 4 LIFE tubes | y 46, x 24 / 180 / 336 / 492 (gap 10) → bottom 322 | y .0772; x .0353/.2647/.4941/.7235 |
| Lifetime ≥ 10,000 | 5 LIFE5 tubes, x 24 / 148 / 272 / 396 / 520 (gap 8) | x .0353/.2176/.4000/.5824/.7647 |
| Label `LAST 7 DAYS` | top-left (26, 346) | .0382, .5805 |
| Rail, 7 pairs of RAIL tubes (oldest → today) | pair i left x = 24 + i·92.83 (24, 116.8, 209.7, 302.5, 395.3, 488.2, 581.0); tubes at x and x+39 (gap 3); y 372 → bottom 484 | pair pitch .1365W; y .6242 |
| Day letters | Space Grotesk 600 **20 px**, letter-spacing 0, centred on each 75 px pair, top y 500. Letter = weekday initial of that date. Today's letter is `neon.mid` with glow r6 `#FF5200`α0.6; others use `label` | y .8389 |

No lamp is drawn on this view; it auto-returns to Today.

---

## 5. Typography

| Role | Family / weight | Size | Notes |
|---|---|---|---|
| Hero digits | Big Shoulders Display **Light 300** | 390 (3 tubes: 300) | cap ≈ 313 px |
| Lines / 7-day digits, k/M | Big Shoulders Display **Medium 500** | 100 | cap ≈ 81 px |
| Lifetime digits | Big Shoulders Display **Light 300** | 236 (5 tubes: 188) | |
| Rail digits | Big Shoulders Display **Medium 500** | 88 | cap ≈ 70 px |
| Ghost cathodes | Big Shoulders Display **Thin 100** | same as the lit digit | |
| Labels | **Space Grotesk SemiBold 600** | 17 | uppercase in the source strings, letter-spacing 3.5 px (0.206 em), baseline = top + 13 |
| Rail day letters | Space Grotesk SemiBold 600 | 20 | letter-spacing 0 |

Bundle these OFL files from Google Fonts: `BigShouldersDisplay-Thin.ttf`, `-Light.ttf`, `-Medium.ttf`, and `SpaceGrotesk-SemiBold.ttf`. If only the variable file is available, build each weight **once** at startup with `Typeface.Builder(...).setFontVariationSettings("'wght' N")`, never in the draw path. Rule: anything that carries information is ≥ 70 px tall; anything smaller is decoration.

---

## 6. Data mapping

### 6.1 Derived numbers (recompute on every `show()`, and at local midnight)
```
zone = ZoneId.systemDefault(); d0 = LocalDate.now(zone)
start(d) = d.atStartOfDay(zone).toInstant().toEpochMilli()           // DST-safe
today    = snap.prs.filter { it.mergedAtMs >= start(d0) }
prsToday = today.size; adds = today.sumOf { additions }; dels = today.sumOf { deletions }
days[i], i=0..6 (6 = today) = snap.prs.count { mergedAtMs in [start(d0-(6-i)), start(d0-(5-i))) }
week7    = max(snap.windowCount, snap.prs.size)   // issueCount stays right even past the 1,000-node cap
lifetime = snap.lifetime
```
**Lines:** additions and deletions are shown **separately and exactly**. Adding and deleting are different work: a sum would hide cleanup, and a net figure goes negative on refactor days. Exact digits also mean every merge, even a 15-line one, visibly moves the low tubes.

### 6.2 Tube string formatting (right-aligned; leading zeros blank; units tube always lit, so 0 shows "0")
| Display | Rule |
|---|---|
| Hero | 0–99 in 2 HERO tubes; 100–999 in 3 HERO3 tubes; >999 clamps to `999` |
| ± banks (5 tubes) | 0–99,999 exact, with the comma on the thousands tube when ≥1,000. 100,000–9,999,999 → `floor(v/1000)` + `k` in the units tube (e.g. `124k`, max `9999k`), comma off. ≥10,000,000 → `floor(v/1e6)` + `M` |
| 7-day (3 tubes) | 0–999; clamp 999 |
| Lifetime | 4 tubes ≤9,999 (comma on tube 0 when ≥1,000); 5 tubes ≤99,999 (comma on the place-3 tube) |
| Rail (2 tubes per day) | 0–99; clamp 99 |

Glyphs needed per class: `0–9`, plus `k` and `M` for SMALL.

### 6.3 Merge tiers (by the PR's churn `c = additions + deletions`)
| Tier | Range | Rationale |
|---|---|---|
| S | c < 100 | under the user's median (~103) |
| M | 100 ≤ c < 1,239 | up to p90 |
| L | 1,239 ≤ c < 5,000 | |
| XL | c ≥ 5,000, **or** this merge pushes `lifetime` across a multiple of 100 | generated mega-PRs and centuries |

Counters have no full scale, so a 17,424-line PR rolls more digits and gets the XL moment. Nothing in the layout changes size.

### 6.4 Day states
- **Net-negative day** (`dels > adds && dels > 0`): argon lvl 1.0, neon additions lvl 0.75, and the lines label reads `LINES · NET NEGATIVE`. Cleanup gets celebrated rather than shown as a loss.
- **Personal best**: keep a persisted `daily` map (ISO date → PR count) for 90 days, updated from `days[]` on every `show()`. `best90` = max excluding today. When `prsToday > best90 && best90 ≥ 5`, the hero runs hot for the rest of the day (od floor 0.25, so the core draws `neon.hot` and the mesh is faintly lit) and the hero label reads `PRs MERGED · TODAY · BEST`. The merge that crosses it is at least tier L. The map simply starts at install; there is no backfill.
- **10th PR of the day**: the tens tube goes from blank to lit, so it **strikes** (§8.1). This happens naturally, once a day.

---

## 7. States

| State | Trigger | What the rear screen shows |
|---|---|---|
| **Warming up** | Signed in, no cached snapshot, first fetch in flight | Both hero tubes cycle random digits at 8 fps at lvl 0.35, clearly not a value. All other tubes blank. Lamp `stale`. **Never show 0 before real data.** |
| **Live** | Last success ≤ 10 min ago | Normal. The lamp flashes on each successful poll (§9). |
| **Stale (cooled)** | `now − snap.fetchedAtMs > 10 min`, checked on `markStale()` and every minute | Last known numbers stay up but cooled: `gB = 0.4`, glow radii ×0.6, a ColorMatrix saturation 0.5 filter on lit layers (dim ember), lamp `stale`. No flicker. When data returns: a cold-start strike-in (§8.1), then any fresh moments. |
| **Rate-limited** | HTTP 403/429 | The activity skips polls until `Retry-After` / `X-RateLimit-Reset` (fallback: skip 5 polls). The view treats this as Stale. |
| **Signed out** | `signedOut()` (401 clears the token in the scaffold) | All tubes blank (glass and mesh only), lamp `stale`, hero label in `event` colour: `SIGN IN ON THE PHONE`. Long-press opens Setup (already wired). |
| **Not signed in at launch** | No token | The scaffold already opens `SetupActivity` on the front screen (device flow, or paste a PAT). |

---

## 8. Animation primitives

All timings are in ms. `postInvalidateOnAnimation()` runs only while something animates (§10).

### 8.1 Primitives
- **cycle(tube, target, steps, stepMs = 45)**: show `(cur+1)%10, (cur+2)%10, …` for `steps` steps, then land on `target`. Each switch leaves a 30 ms afterglow (L7). From blank, start at a random digit. Landing time is `steps·45`. Max steps is 10, so every tube **lands crisp within ≤ 480 ms**.
- **strike(tube)** (blank → lit, cold start): α keyframes 0:0, 30:0.8, 60:0.15, 120:0.9, 160:0.3, 220:1.0 (linear between).
- **powerDown(tube)** (lit → blank): over 300 ms, α 1 → 0 with ease-out, and the core colour lerps to `gas.glow` (orange afterglow).
- **overdrive decay**: `od(t) = od0 · e^(−(t − tLand)/τ)`. τ = 600 for the hero, 500 for bank bloom, 1,800 during the delta hold. Snap to 0 below 0.02.
- **Relay strike**: `gB` = 0.35 for t ∈ [0, 60), linear back to 1 by t = 90.
- **Label crossfade**: 150 ms linear between two strings; event strings use the `event` colour.

### 8.2 Merge moment, tier M (t = 0 when the event starts; this is moment.png at t ≈ 900)
| t | What happens |
|---|---|
| 0–90 | **Relay strike** across the whole instrument. Lamp goes to `flash` and holds 400 ms, then decays to `live` over 300 ms. Haptic tick of 20 ms (`HapticFeedbackConstants.CONFIRM` or a 20 ms one-shot; never at night). |
| 90–240 | Labels crossfade: `LINES` → `THIS PR · <repo>` (repo name without the owner) and the hero label → `MERGED · <title>`. Strip the conventional-commit prefix `^\w+(\([^)]*\))?!?:\s*` from the title and ellipsize it at stage x 600. |
| 90–495 | **Hero cycle**: each hero tube whose digit changes cycles 9 steps; the tens tube starts 60 ms after the units. If a tube goes blank→lit (the 10th PR), it strikes instead. On landing, changed tubes take **od = 1.0, halo 0.8** and unchanged hero tubes take a sympathetic **od = 0.5, halo 0.35**. The lit mesh, the 2.1× glow and the `neon.hot` core all come from od. |
| 120–540 | **Delta beat**: both banks scramble to *this PR's* `+additions` / `−deletions`. Each tube cycles 6 steps, staggered 30 ms from the units leftward, and the deletions bank starts 60 ms after additions. Tubes that end blank power down. Landed tubes take **od 0.85, halo 0.35**, and the signs take od 0.8. |
| 540–1900 | **Hold**, so the PR's own size reads from arm's length. Bank od decays with τ = 1,800 and the hero with τ = 600. |
| 1900–2700 | **Roll-up** to the new day totals. The units tube starts first and each tube to its left starts 110 ms later, cycling `6 + placeIndex` steps. Each lands with a bloom of od 0.6, τ 500. Deletions start 150 ms after additions. The comma lights when its tube lands. `LINES` crossfades back at 1900. |
| 2600–2900 | **7-day bank**: the changed tubes cycle 6 steps and land with od 0.6 (still lvl 0.6). |
| ≈3500 | All od is below 0.02. |
| 6000 | Hero label crossfades back to `PRs MERGED · TODAY` (300 ms). Then `onAnimating(false)`. |

### 8.3 Other tiers
- **S** (c < 100): no relay strike, no haptic, no halo, no delta beat. The hero cycles 5 steps and lands with od 0.6. The banks roll straight from the old totals to the new ones, starting at t=150. Labels show the PR for 3 s. This keeps roughly 9 of this user's 24 daily merges quiet.
- **L**: everything in M, plus a hold of 2,500 instead of 1,360 (roll-up starts at t=3040), a hero halo of 1.0, and during roll-up **all 5 tubes of each bank cycle**, changed or not (a full cascade).
- **XL**: everything in L, plus a **mercury flash**: at hero landing the changed tubes' core is pure `#FFFFFF` for 150 ms (od 1.4). Then a **surge**: from t=495 to t=1200 every tube on screen (hero, both banks, 7-day) cycles 6 steps back to its current value. A global glow multiplier of 2.5× decays to 1 with τ = 1,000. For a century, the hero label reads `PR #2,400 · ALL-TIME`.
- **Night** (local 23:00–07:00): `gB` is capped at 0.4. There is no relay strike, halo, surge, flash or haptic, and od is capped at 0.4. Only the cycles and the delta beat play.

### 8.4 Triggering from poll diffs
This keeps the scaffold's `MeterActivity.publish()` logic. `seen` is the set of PR ids from the last snapshot, persisted.
1. `seen == null` (first ever run): `fresh = ∅`. Seed silently and show with a **cold-start strike-in** (§9). **The first load never celebrates.**
2. Otherwise `fresh = prs.filter { id ∉ seen && now − mergedAtMs < 6 h }.sortedBy { mergedAtMs }`, then `seen = ids(snap)`. A cached snapshot shown at launch is always passed with `fresh = emptyList()`.
3. In the view:
   - `fresh.size ≤ 3` and the previous success was < 30 min ago: queue one event per PR. Event k+1 starts at event k's t=2900, or t=3900 for L/XL. Hero and totals step one PR at a time. The intermediate totals come from subtracting the not-yet-played PRs.
   - `fresh.size > 3`, **or** the previous success was ≥ 30 min ago (catch-up after a gap): play **one combined event**. The delta is the sum of fresh, the tier is that of the largest PR, the hero rolls old→new directly, and the labels read `THESE N PRs` / `MERGED · N PRs`.
   - Numbers that change with no fresh PRs (a late-discovered PR, or a midnight rollover) take a plain roll-up (8.2's roll-up row only) with no moment.
4. A merge arriving while the Long Count view is up cuts straight to Today (no transition), then plays.
5. Taps are ignored while a moment plays.

---

## 9. Ambient and lifecycle motion

| Motion | Spec | Cost |
|---|---|---|
| **Rest** | Static frame. **No breathing.** | 0 fps |
| Poll lamp blink | On each successful poll: `flash` for 120 ms, then decay to `live` over 400 ms | ~30 frames per 2 min |
| Neon flicker | Every U(20 s, 60 s), one random lit tube drops to lvl × 0.85 for 2 frames. Off when stale or at night. | 2 frames |
| **Pixel drift** (burn-in) | Every 5 min, translate the whole stage by `(round(3·sin(2πt/37 min)), round(3·sin(2πt/53 min)))` px, so ±3 px | 1 frame |
| **Anti-poisoning sweep** (burn-in) | Every 15 min (skipped if a moment ran in the last 2 min), every **lit** tube in the current view cycles 10 steps at 30 ms. The start delay is `(tubeCentreX/680)·900 ms`, a left→right wave of ~1.2 s that lands back on the current values. Blank tubes stay blank. | ~72 frames per 15 min |
| **Cold-start strike-in** | On first `show()` after attach, and on screen-on after > 30 min dark: lit tubes strike in, sorted by x then y, 40 ms apart. No celebration. | ~1 s |
| **Tap transition** | Outgoing lit tubes power down (250 ms), then incoming tubes strike in, 30 ms apart. Total ≈ 550 ms. | — |
| **Midnight rollover** | A Handler posted for the next local midnight (re-posted daily) recomputes §6.1. The hero and banks cycle 10 steps to the new values; tubes that become blank power down. The 7-day bank and rail roll by one day. No moment. | ~1 s |

---

## 10. Frame budget
- **At rest: 0 fps.** One frame per drift step, lamp blink, flicker or sweep. On average that is about 4 short redraw bursts per minute, all under 1.2 s.
- **Animating** (moment, sweep, transitions, strike-in, warming up): drive frames with `postInvalidateOnAnimation()` while any tube has an active cycle, strike, powerDown, `od > 0` or label fade. Call `onAnimating(true)` on the first animated frame and `onAnimating(false)` when everything settles; the scaffold uses this to move the panel between its slowest refresh mode and 60 Hz.
- Draw nothing while the rear display is off; the activity's `onPause` already stops polling.

---

## 11. Rendering implementation

**Pre-bake** on a background thread at init, and again if the scale `s` changes:
1. For each (class, gas): `glassBlank` (L2 at blank alpha, L3, L4 at blank alpha, L5, L6), `glassLit` (L1, L2 lit, L3, L4 at 0.50, L5, L6) and `glassHot` (the L2 overdrive mesh in `mid` with its 3 px glow, the extra outline α0.10 and the extra reflection α0.08). At runtime `glassHot` is drawn over `glassLit` at alpha `od`.
2. For each (class, gas, glyph ∈ 0–9 [+k, M for SMALL]): a **glyph bitmap** cropped to the ink bounds + 60 px pad, holding the four glow passes and the core. About 10 MB in total, which is fine.
3. Overdrive at runtime: the glyph bitmap again with `BlendMode.PLUS` at α `0.6·od`, then a live `drawText` core in `gas.hot` at α `od` (no blur, cheap). The halo is `drawOval` with a cached RadialGradient per class, at paint alpha `halo·od`.
4. Cooled state: `ColorMatrixColorFilter` with saturation 0.5 on the glyph paint, plus `gB` 0.4.

**Frame:** `drawColor(#000)`, then translate(origin + drift), then for each tube draw halo, glassLit/glassBlank, glassHot·od, afterglow glyph, glyph·lvl·gB, overdrive pass and comma. Then signs, labels and lamp. Never use `BlurMaskFilter` or build a Typeface in `onDraw`.

**Model:** `Tube(rect, cls, gas, shown: Char?, target: Char?, cycle state, strikeT, od, odTau, halo, lvl)`. A view holds lists of tubes for both layouts. A small event queue holds pending merge events and a single clock read from `AnimationUtils.currentAnimationTimeMillis()`.

---

## 12. Persisted state
| Key | Where | Purpose |
|---|---|---|
| GitHub token | `Vault` (Keystore AES-GCM), existing | auth |
| `snapshot.json` | existing `Snapshot.save` | instant first paint with real numbers |
| `seen` (StringSet) | prefs `state`, existing | merge detection |
| `lastLifetime` (Int) | prefs `state` | century detection (XL) |
| `daily` (JSON date → prs, 90 days) | prefs `state` | `best90` |
| `lastSuccessMs` | taken from `snap.fetchedAtMs` | stale / catch-up rules |

Not persisted: the current view (always opens on Today), drift phase and sweep time (wall clock decides).

## 13. Data source (already implemented in `GitHub.kt`; keep it)
- One GraphQL call every **120 s while visible**. `search(type: ISSUE)` for `author:@me is:pr is:merged merged:>=<today−6 local midnight, ISO with offset>`, 100 per page, ≤ 10 pages, fields `id number title additions deletions mergedAt repository.nameWithOwner`. YTD and lifetime come from `issueCount` with `first: 0`, so they are exact past the 1,000-result cap.
- Scope `repo` (device flow) so private-repo PRs count. Fallback: paste a classic PAT (`repo`, `read:org`) in Setup, for orgs that restrict OAuth apps. For local testing, use `gh auth token` via the debug receiver.

## 14. Input
Superseded in detail by `UX-PASS.md` §3–§4 (screens, gesture grammar), which this summarises.

Views, in swipe order: **Today → Recent → Streak → Week → All-time → Badges → Cats → Open** (v2 screens: `V2.md`). Open is last, so it's one swipe right of Today. Pips at (30 + 14·i, 568) show where you are, except on Today. When the rear leaves STATE_ON it goes home: the doze face is always Today.
- **Layers**, first match wins: a merge moment (tap a flying cat within 90 px: it pops; elsewhere the lamp blinks; a swipe settles the numbers and moves), a show (tap: Today; swipe: move on), a card (tap: close), signed out / token expired (any tap: Setup), then the screen's own map.
- **A pause decides.** Moving within 250 ms of touchdown is judged at UP: one-way with |dx| > 90 and |dx| > 1.5|dy| is a swipe, however slow. Resting ≥ 250 ms, then moving, is a hold-drag. Still for 1.5 s is Setup. No movement is a tap.
- **Tap** acts on what's under it, at UP, with no wait (only Today waits 300 ms, for its double-tap). With no target it goes to the next view; on Cats it wakes the newest.
- **Recent**: the last 5 merged PRs, newest first, as rows of `3H AGO · REPO` + title. The highlighted row advances every 2.6 s and the two banks roll to that PR's +/−. **Tap a row**: a detail card (`OWNER/REPO · #N`, the full title, then `MERGED TODAY 15:34 · 1H AGO`, `OPEN 9M BEFORE MERGE`, `6 FILES · 2 COMMITS`, `2 REVIEWS · 3 COMMENTS`, `branch → base`, `IGNORED 2 GENERATED FILES · 12,034 LINES`); `SIZE · M` under the banks. Tap to close.
- **Open**: tap a row for its card; tap a count band (x > 392) to list just that colour, again for the next 5, past the end for all; the header strip goes next.
- **Badges**: tap one for its card (name, rule, how close, times earned, first/last).
- **Cats**: tap a cat (nearest within 60 px) to poke it (it hops and sits up, its tag `API · #482 · +46 −55 · 2H AGO` fills the header for 4 s), again for its card; tap the header strip to wake the newest 6; moving at once and reversing within 70 px of a cat is a rub (hearts, a purr); hold, then drag: a laser dot the newest 3 chase.
- **Double-tap Today**: replay the most recent merge's moment, with at least the M flyby (asked-for replays always get the cats). Today rewinds to the totals just before it, then plays it again. HyperOS would sleep the rear on the double-tap; `MeterActivity.keepDoubleTap()` opts the window out (`REAR-AOD.md`).
- **Hold, then drag** (not Cats, not in quiet hours): a cat flies out of the camera island and chases the finger (exponential follow, τ 70 ms), with its rainbow and burning butt. Released slower than 700 px/s it flies home under the island; faster, it carries the finger's velocity (max 2400) with drag τ 0.5 s, and bursts into mini cats after 0.7 s or at the stage edge. See `FX.md`.
- **Hold still for 1.5 s**: Setup on the front screen (`SETUP · ON YOUR PHONE` on the rear, or `UNLOCK YOUR PHONE FOR SETUP`), which also sets the daily goal and quiet hours. Also in the launcher icon's shortcuts.

## 15. Test hooks and acceptance
Debug receiver (existing): `--es demo big|quiet|zero|off` should render to match `big.png` / `quiet.png` / `zero.png`. `--ei merge 1200` triggers an L-tier moment, `--ei merge 50` an S tier and `--ei merge 6000` an XL tier.

Check on the panel:
1. A blank hero tube never reads as a digit.
2. `24,860` reads in < 1 s at arm's length.
3. Each cycle lands crisp within 500 ms.
4. Argon is never brighter than neon (except on a net-negative day).
5. Idle shows no frame activity (GPU profiler flat).
6. The first ever launch fires no moment, and a restart with a cached snapshot fires none either. After a gap of ≥ 30 min, at most one combined moment plays.
7. 3-digit hero, `124k` and lifetime ≥ 10,000 all lay out inside the stage.
8. Night mode caps brightness.

v2 hooks (`V2.md`): `--es show goal|badge:EARLY_BIRD|badge:all|open|recap|cats|screen:STREAK` plays a showcase or opens a screen; `--ei goal 3` sets the daily goal; `--es badges reset` clears the badge book.
