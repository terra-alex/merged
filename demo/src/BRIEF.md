# "Merged" cinematic demo — production brief

A ~68 s shareable demo video (1920×1080, 30 fps, H.264 + AAC) of **Merged**: an Android app that turns the
Xiaomi 17 Pro Max's small **rear screen** (976×596 px; the left 296 px sit under the camera island) into a
Nixie-tube counter of the user's merged GitHub PRs. Read for the look: ../../design/SPEC.md
(§2 palette), design/V2.md (features), design/FX.md (merge celebration: pixel pop-tart cats with flaming butts and
rainbow trails, then fire), README.md. Screens: see scratchpad/v2/s-*.png and sheet-*.png for stills.

Palette: background #000; neon orange core #FFD9B0, mid #FF8A2A, glow #FF5200; argon violet #ECE4FF / #A98CFF /
#6A45FF; muted label #5E4C3E; event text #FFB27A. Fonts (OFL, in android/app/src/main/assets/fonts/):
Big Shoulders Display (Thin/Light/Medium) for numerals & the logo, Space Grotesk SemiBold for labels.
Tone: warm, retro-futuristic, playful (cats!), premium. No real company names, no Nyan Cat music or other
copyrighted material — everything original.

## Timeline (120 BPM, 4/4, 1 bar = 2.0 s; times in seconds)

| # | Time | Picture | Caption (lower third) | Sound cues (mixed separately by the editor) |
|---|---|---|---|---|
| 0 | 0–4 | Black → the phone's rear module fades in; the tubes strike in (~2.0) | — | pad swell + riser; soft relay click at 2.0 |
| 1 | 4–8 | Title card over the Today screen | MERGED · "your merged PRs, on the back of your phone" | beat enters at 4.0 (light) |
| 2 | 8–12 | Today, slow push-in | Today's PRs and lines, in Nixie tubes | — |
| 3 | 12–21 | XL merge: cats burst out at 12.0, zoom; FIRE flashover lands at 16.0; roll-up | Every merge is an event | merge chime 12.0; whooshes as cats pass; BIG impact at 16.0 = the DROP |
| 4 | 21–25 | Recent board, then a tapped detail card (~23) | Recent merges, with the details | whoosh 21.0; tap click 23.0 |
| 5 | 25–29 | Streak screen: goal bar surges and overdrives (~26) | Streaks and a daily goal | whoosh 25.0; electric zap/surge 26.0 |
| 6 | 29–35 | Badges: two unlocks (~29.2, ~32.2) | Badges to unlock | sparkle shimmer at each unlock |
| 7 | 35–41 | Cat colony asleep; tap at 36.0 → cats fly round, land ~39.5 | A sleeping cat for every PR this week | mew 36.0; landing pops ~39.5 |
| 8 | 41–45 | Open PRs board; a "ready" jewel strikes green (~41.3) | Open PRs at a glance | ping 41.3 |
| 9 | 45–51 | Weekly recap replays day by day | A weekly recap | ticks per day |
| 10 | 51–57 | A finger drags a cat round, flings it, it bursts into firework cats (~55) | Drag a cat. Fling it. | whoosh on fling; firework burst |
| 11 | 57–61 | Fast on-beat montage (1 bar of 4 half-beat cuts, 1 bar M merge) | — | music peak |
| 12 | 61–68 | End card | MERGED · Nixie tubes for your GitHub · rear-screen app for the Xiaomi 17 Pro Max | final chord + chime echo; fade to silence by 68 |

Working folder: this one (raw/ takes, gfx/, audio/, out/).
