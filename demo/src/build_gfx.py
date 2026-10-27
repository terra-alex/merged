#!/usr/bin/env python3
"""Render the demo's overlay PNGs (bg, module, captions, title, end, grain) via headless Chrome + PIL.
Run: python3 build_gfx.py [name ...]   (no args = all)"""
import json, os, subprocess, sys
from PIL import Image, ImageChops

HERE = os.path.dirname(os.path.abspath(__file__))
SRC, OUT = os.path.join(HERE, "gfx-src"), os.path.join(HERE, "gfx")
FONTS = "file://" + os.path.abspath(os.path.join(HERE, "..", "..", "android", "app", "src", "main", "assets", "fonts")) + "/"
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
W, H = 1920, 1080

# ---- module geometry (video px). Rear panel 976x596 scaled x1.4754 -> 1440x879 ----
WIN = dict(x=240, y=101, w=1440, h=879, r=149)          # r = 101 device px corner * 1.4754
ISL = dict(x=214, y=75, w=677 - 214, h=1006 - 75)       # right edge == 677 == 240 + 437
MOD = dict(x=196, y=57, w=1528, h=967, r=148)   # r<=150 so the window bbox corners stay under the glass

HEAD = f"""<!doctype html><meta charset=utf-8><style>
@font-face{{font-family:BSD;font-weight:100;src:url({FONTS}BigShouldersDisplay-Thin.ttf)}}
@font-face{{font-family:BSD;font-weight:300;src:url({FONTS}BigShouldersDisplay-Light.ttf)}}
@font-face{{font-family:BSD;font-weight:500;src:url({FONTS}BigShouldersDisplay-Medium.ttf)}}
@font-face{{font-family:SG;font-weight:600;src:url({FONTS}SpaceGrotesk-SemiBold.ttf)}}
html,body{{margin:0;width:{W}px;height:{H}px;overflow:hidden;background:transparent}}
*{{box-sizing:border-box}}
.neon{{font-family:BSD;color:#FFE6CC;line-height:1;white-space:nowrap;
  text-shadow:0 0 2px #FFF1E0,0 0 6px #FFB27A,0 0 14px #FF8A2A,0 0 28px #FF8A2A,0 0 52px #FF5200,
              0 0 90px #FF5200,0 0 150px rgba(255,82,0,.55)}}
.sg{{font-family:SG;font-weight:600}}
</style>"""


def rrect(x, y, w, h, tl, tr=None, br=None, bl=None):
    tr = tl if tr is None else tr; br = tl if br is None else br; bl = tl if bl is None else bl
    return (f"M{x+tl},{y} H{x+w-tr} A{tr},{tr} 0 0 1 {x+w},{y+tr} V{y+h-br} A{br},{br} 0 0 1 {x+w-br},{y+h} "
            f"H{x+bl} A{bl},{bl} 0 0 1 {x},{y+h-bl} V{y+tl} A{tl},{tl} 0 0 1 {x+tl},{y} Z")


def hexmesh_defs(s=13):
    w = round(3 ** .5 * s, 3)
    d = f"M{w/2},0 L{w},{s/2} L{w},{1.5*s} L{w/2},{2*s} L0,{1.5*s} L0,{s/2} Z M{w/2},{2*s} L{w/2},{3*s}"
    return (f'<pattern id="hex" width="{w}" height="{3*s}" patternUnits="userSpaceOnUse">'
            f'<path d="{d}" fill="none" stroke="rgb(255,140,60)" stroke-width="1"/></pattern>')


# ------------------------------------------------------------------ bg
def bg():
    return HEAD + f"""<body style="background:#030202">
<svg width={W} height={H} style="position:absolute;inset:0"><defs>{hexmesh_defs()}
<radialGradient id="glow" cx=".5" cy=".5" r=".62" gradientTransform="translate(.5 .5) scale(1 1.25) translate(-.5 -.5)">
 <stop offset="0" stop-color="#FF6A1E" stop-opacity=".11"/><stop offset=".35" stop-color="#FF5200" stop-opacity=".05"/>
 <stop offset=".75" stop-color="#FF5200" stop-opacity=".012"/><stop offset="1" stop-color="#FF5200" stop-opacity="0"/></radialGradient>
<radialGradient id="mfade" cx=".5" cy=".5" r=".55" gradientTransform="translate(.5 .5) scale(1 1.5) translate(-.5 -.5)">
 <stop offset="0" stop-color="#fff" stop-opacity=".085"/><stop offset=".55" stop-color="#fff" stop-opacity=".04"/>
 <stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>
<mask id="m"><rect width={W} height={H} fill="url(#mfade)"/></mask>
<radialGradient id="vig" cx=".5" cy=".5" r=".75"><stop offset=".55" stop-color="#000" stop-opacity="0"/>
 <stop offset="1" stop-color="#000" stop-opacity=".75"/></radialGradient></defs>
<rect width={W} height={H} fill="url(#glow)"/>
<rect width={W} height={H} fill="url(#hex)" mask="url(#m)"/>
<rect width={W} height={H} fill="url(#vig)"/></svg></body>"""


# ------------------------------------------------------------------ module
def lens(cx, cy, R, k):
    """One camera lens: chrome ring, black gap, coated glass, inner elements, reflections."""
    return f"""<g transform="translate({cx} {cy})">
 <circle r="{R+10}" fill="#000" opacity=".85" filter="url(#b8)"/>
 <circle r="{R}" fill="url(#chrome)"/>
 <circle r="{R-2.5}" fill="url(#chrome2)"/>
 <circle r="{R-9}" fill="#020203"/>
 <circle r="{R-14}" fill="url(#barrel)"/>
 <circle r="{R-14}" fill="none" stroke="rgba(255,255,255,.07)" stroke-width="1"/>
 <circle r="{R-22}" fill="url(#glassL)"/>
 <circle r="{R*.52}" fill="none" stroke="rgba(130,120,255,.13)" stroke-width="2"/>
 <circle r="{R*.40}" fill="url(#core{k})"/>
 <circle r="{R*.40}" fill="none" stroke="rgba(255,255,255,.05)" stroke-width="1.5"/>
 <circle r="{R*.22}" fill="#000"/>
 <circle r="{R*.13}" fill="none" stroke="rgba(110,90,255,.22)" stroke-width="1.5"/>
 <g filter="url(#b3)">
  <path d="M{-(R-30)*.89},{-(R-30)*.45} A{R-30},{R-30} 0 0 1 {-(R-30)*.2},{-(R-30)*.98}" fill="none"
        stroke="rgba(255,255,255,.38)" stroke-width="7" stroke-linecap="round"/>
  <ellipse cx="{R*.30}" cy="{R*.34}" rx="{R*.30}" ry="{R*.14}" transform="rotate(-38 {R*.30} {R*.34})" fill="rgba(150,90,255,.22)"/>
  <ellipse cx="{R*.42}" cy="{R*.18}" rx="{R*.12}" ry="{R*.06}" transform="rotate(-60 {R*.42} {R*.18})" fill="rgba(80,255,170,.16)"/>
  <path d="M{(R-34)*.94},{-(R-34)*.34} A{R-34},{R-34} 0 0 1 {(R-34)*.87},{(R-34)*.5}" fill="none" stroke="rgba(255,138,42,.28)" stroke-width="5" stroke-linecap="round"/>
 </g>
 <circle cx="{-R*.30}" cy="{-R*.34}" r="{R*.055}" fill="#fff" opacity=".85" filter="url(#b1)"/>
 <circle cx="{-R*.14}" cy="{-R*.20}" r="{R*.022}" fill="#fff" opacity=".6"/>
 <path d="M{-R*.95},{R*.31} A{R},{R} 0 0 0 {-R*.31},{R*.95}" fill="none" stroke="rgba(255,255,255,.22)" stroke-width="1.5"/>
</g>"""


def module():
    w, i, m = WIN, ISL, MOD
    body = rrect(m['x'], m['y'], m['w'], m['h'], m['r']) + " " + rrect(w['x'], w['y'], w['w'], w['h'], w['r'])
    isl = rrect(i['x'], i['y'], i['w'], i['h'], 130, 14, 14, 130)
    lenses = lens(430, 250, 112, 1) + lens(430, 540, 124, 2) + lens(430, 830, 112, 3)
    return HEAD + f"""<body><svg width={W} height={H} style="position:absolute;inset:0"><defs>
<filter id="b1"><feGaussianBlur stdDeviation="1.2"/></filter>
<filter id="b3"><feGaussianBlur stdDeviation="3"/></filter>
<filter id="b8"><feGaussianBlur stdDeviation="8"/></filter>
<filter id="b40" x="-20%" y="-20%" width="140%" height="140%"><feGaussianBlur stdDeviation="38"/></filter>
<filter id="frost"><feTurbulence type="fractalNoise" baseFrequency=".9" numOctaves="2" seed="4"/>
 <feColorMatrix values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 .035 0"/></filter>
<clipPath id="bodyclip"><path d="{body}" clip-rule="evenodd"/></clipPath>
<clipPath id="outside"><path d="M0,0 H{W} V{H} H0 Z {rrect(m['x'], m['y'], m['w'], m['h'], m['r'])}" clip-rule="evenodd"/></clipPath>
<clipPath id="islclip"><path d="{isl}"/></clipPath>
<linearGradient id="glass" x1="0" y1="0" x2="1" y2="1">
 <stop offset="0" stop-color="#232328"/><stop offset=".28" stop-color="#101013"/><stop offset=".65" stop-color="#09090b"/>
 <stop offset="1" stop-color="#16161a"/></linearGradient>
<linearGradient id="sheen" x1="0" y1="0" x2="1" y2=".55">
 <stop offset=".18" stop-color="#fff" stop-opacity="0"/><stop offset=".27" stop-color="#fff" stop-opacity=".07"/>
 <stop offset=".31" stop-color="#fff" stop-opacity=".02"/><stop offset=".40" stop-color="#fff" stop-opacity="0"/></linearGradient>
<linearGradient id="rim" x1="0" y1="0" x2="0" y2="1">
 <stop offset="0" stop-color="#fff" stop-opacity=".42"/><stop offset=".25" stop-color="#fff" stop-opacity=".12"/>
 <stop offset=".8" stop-color="#fff" stop-opacity=".05"/><stop offset="1" stop-color="#fff" stop-opacity=".16"/></linearGradient>
<linearGradient id="warmrim" x1="0" y1="0" x2="1" y2="0">
 <stop offset="0" stop-color="#FF8A2A" stop-opacity="0"/><stop offset=".5" stop-color="#FF8A2A" stop-opacity=".22"/>
 <stop offset="1" stop-color="#FF8A2A" stop-opacity="0"/></linearGradient>
<linearGradient id="islfill" x1="0" y1="0" x2=".6" y2="1">
 <stop offset="0" stop-color="#09090b"/><stop offset=".5" stop-color="#030304"/><stop offset="1" stop-color="#060607"/></linearGradient>
<linearGradient id="islrim" x1="0" y1="0" x2="1" y2="1">
 <stop offset="0" stop-color="#fff" stop-opacity=".45"/><stop offset=".4" stop-color="#fff" stop-opacity=".10"/>
 <stop offset="1" stop-color="#fff" stop-opacity=".22"/></linearGradient>
<linearGradient id="chrome" x1="0" y1="0" x2="1" y2="1">
 <stop offset="0" stop-color="#c9c9d1"/><stop offset=".16" stop-color="#55555d"/><stop offset=".42" stop-color="#121215"/>
 <stop offset=".72" stop-color="#2a2a30"/><stop offset=".86" stop-color="#8a8a94"/><stop offset="1" stop-color="#1c1c21"/></linearGradient>
<linearGradient id="chrome2" x1="1" y1="0" x2="0" y2="1">
 <stop offset="0" stop-color="#222226"/><stop offset=".45" stop-color="#0b0b0d"/><stop offset="1" stop-color="#2c2c31"/></linearGradient>
<radialGradient id="barrel"><stop offset=".8" stop-color="#0a0a0d"/><stop offset=".93" stop-color="#16161b"/>
 <stop offset="1" stop-color="#070709"/></radialGradient>
<radialGradient id="glassL" cx=".42" cy=".38" r=".7"><stop offset="0" stop-color="#161b33"/>
 <stop offset=".45" stop-color="#080a16"/><stop offset=".85" stop-color="#030308"/><stop offset="1" stop-color="#010103"/></radialGradient>
<radialGradient id="core1" cx=".6" cy=".65"><stop offset="0" stop-color="#1b0f3a"/><stop offset="1" stop-color="#030208"/></radialGradient>
<radialGradient id="core2" cx=".6" cy=".65"><stop offset="0" stop-color="#0f1c2e"/><stop offset="1" stop-color="#020308"/></radialGradient>
<radialGradient id="core3" cx=".6" cy=".65"><stop offset="0" stop-color="#241238"/><stop offset="1" stop-color="#030208"/></radialGradient>
<radialGradient id="flash" cx=".4" cy=".35"><stop offset="0" stop-color="#f4ecd6"/><stop offset=".6" stop-color="#b9b19b"/>
 <stop offset="1" stop-color="#6f6a5c"/></radialGradient>
<pattern id="dimple" width="5" height="5" patternUnits="userSpaceOnUse"><circle cx="2.5" cy="2.5" r="1.1" fill="rgba(80,70,50,.35)"/></pattern>
</defs>
<!-- drop shadow + faint warm halo from the plate -->
<g clip-path="url(#outside)"><path d="{rrect(m['x'], m['y']+26, m['w'], m['h'], m['r'])}" fill="#000" opacity=".9" filter="url(#b40)"/></g>
<!-- glass body with the screen window punched out -->
<path d="{body}" fill="url(#glass)" fill-rule="evenodd"/>
<g clip-path="url(#bodyclip)">
 <rect x="{m['x']}" y="{m['y']}" width="{m['w']}" height="{m['h']}" fill="url(#sheen)"/>
 <path d="{rrect(w['x'], w['y'], w['w'], w['h'], w['r'])}" fill="none" stroke="#000" stroke-width="10"/>
 <path d="{rrect(w['x']-6, w['y']-6, w['w']+12, w['h']+12, w['r']+6)}" fill="none" stroke="rgba(255,255,255,.07)" stroke-width="1.2"/>
 <path d="{rrect(m['x'], m['y'], m['w'], m['h'], m['r'])}" fill="none" stroke="url(#rim)" stroke-width="3"/>
 <path d="{rrect(m['x']+3, m['y']+3, m['w']-6, m['h']-6, m['r']-3)}" fill="none" stroke="rgba(0,0,0,.9)" stroke-width="2"/>
</g>
<path d="M{m['x']+260},{m['y']+.75} H{m['x']+m['w']-260}" stroke="url(#warmrim)" stroke-width="1.5"/>
<path d="{rrect(m['x']-.5, m['y']-.5, m['w']+1, m['h']+1, m['r']+.5)}" fill="none" stroke="rgba(0,0,0,.9)" stroke-width="1"/>
<!-- camera island (raised, darker); its shadow stays on the glass body, never on the window -->
<g clip-path="url(#bodyclip)"><path d="{isl}" fill="#000" opacity=".9" filter="url(#b8)" transform="translate(4 10)"/></g>
<path d="{isl}" fill="url(#islfill)"/>
<g clip-path="url(#islclip)">
 <rect x="{i['x']}" y="{i['y']}" width="{i['w']}" height="{i['h']}" filter="url(#frost)"/>
 <rect x="{i['x']}" y="{i['y']}" width="{i['w']}" height="{i['h']}" fill="url(#sheen)" opacity=".7"/>
 <path d="{isl}" fill="none" stroke="url(#islrim)" stroke-width="3"/>
 <path d="{rrect(i['x']+2, i['y']+2, i['w']-4, i['h']-4, 128, 12, 12, 128)}" fill="none" stroke="rgba(0,0,0,.8)" stroke-width="2"/>
 {lenses}
 <!-- flash + sensor -->
 <g transform="translate(612 395)"><circle r="27" fill="#0a0a0c" stroke="#2a2a2f" stroke-width="2"/>
  <circle r="20" fill="url(#flash)"/><circle r="20" fill="url(#dimple)"/>
  <ellipse cx="-6" cy="-8" rx="8" ry="4" fill="#fff" opacity=".55" transform="rotate(-30 -6 -8)"/></g>
 <g transform="translate(612 685)"><circle r="13" fill="#060608" stroke="#26262b" stroke-width="1.5"/>
  <circle r="6" fill="#140c22"/><circle cx="-2.5" cy="-3" r="1.6" fill="#fff" opacity=".7"/></g>
 <circle cx="612" cy="540" r="4" fill="#000" stroke="rgba(255,255,255,.12)" stroke-width="1"/>
</g>
</svg></body>"""


# ------------------------------------------------------------------ captions / title / end
CAPTIONS = {2: "Today's PRs and lines, in Nixie tubes", 3: "Every merge is an event",
            4: "Recent merges, with the details", 5: "Streaks and a daily goal", 6: "Badges to unlock",
            7: "A sleeping cat for every PR this week", 8: "Open PRs at a glance", 9: "A weekly recap",
            10: "Drag a cat. Fling it."}
CAP_BASE = 930      # text baseline y; left edge x = 120
CAP_PX = 40


def caption(text):
    # Space Grotesk: ascent .984? -> place the line box so the baseline lands on CAP_BASE (measured & reported).
    return HEAD + f"""<body>
<div style="position:absolute;left:-200px;top:{CAP_BASE-190}px;width:1500px;height:330px;
 background:radial-gradient(ellipse 50% 50% at 42% 55%,rgba(0,0,0,.55),rgba(0,0,0,.25) 55%,transparent)"></div>
<div style="position:absolute;left:120px;top:{CAP_BASE-CAP_PX-26}px;width:64px;height:2px;background:#FF8A2A;
 box-shadow:0 0 4px #FF8A2A,0 0 10px #FF5200,0 0 18px rgba(255,82,0,.6)"></div>
<div id=t class=sg style="position:absolute;left:120px;bottom:{H-CAP_BASE+13}px;font-size:{CAP_PX}px;line-height:0;
 text-transform:uppercase;letter-spacing:.18em;color:#FFB27A;white-space:nowrap;
 text-shadow:0 0 3px rgba(255,138,42,.55),0 0 8px rgba(255,82,0,.67),0 0 22px rgba(255,82,0,.35)">{text}</div>
</body>"""


def logo(px):
    # trailing letter-spacing compensated by padding-left so the word stays optically centred
    return f'<div class=neon style="font-weight:300;font-size:{px}px;letter-spacing:.13em;padding-left:.13em">MERGED</div>'


def title():
    return HEAD + f"""<body>
<div style="position:absolute;inset:0;background:radial-gradient(ellipse 42% 40% at 50% 47%,rgba(0,0,0,.6),rgba(0,0,0,.3) 60%,transparent)"></div>
<div style="position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;padding-bottom:40px">
 {logo(300)}
 <div style="margin-top:34px;width:560px;height:1px;background:linear-gradient(90deg,transparent,#FF8A2A,transparent);
   box-shadow:0 0 8px #FF5200"></div>
 <div class=sg style="margin-top:30px;font-size:36px;letter-spacing:.06em;color:#FFB27A;
   text-shadow:0 0 8px rgba(255,82,0,.6),0 0 22px rgba(255,82,0,.3)">your merged PRs, on the back of your phone</div>
</div></body>"""


ICON = 'fill="none" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"'
ICONS = [  # merge glyph, pixel cat, flame — 64x64 viewbox
    ("o", f'<g transform="translate(-1 0)"><circle cx="20" cy="12" r="6" {ICON}/><circle cx="20" cy="52" r="6" {ICON}/>'
          f'<circle cx="48" cy="40" r="6" {ICON}/><path d="M20 18 V46 M20 18 C20 30 30 40 42 40" {ICON}/></g>'),
    ("v", f'<path d="M10 54 V18 L16 10 L24 20 H40 L48 10 L54 18 V54 Z" {ICON}/>'
          f'<path d="M24 34 v4 M40 34 v4 M29 45 h6" {ICON}/>'),
    ("o", f'<path d="M32 56 C18 56 12 46 14 36 C16 28 22 24 24 14 C30 20 30 26 30 30 C34 26 36 20 36 10 '
          f'C46 18 52 30 50 40 C48 50 42 56 32 56 Z" {ICON}/><path d="M32 50 C26 50 24 44 27 40 C29 37 30 35 31 32 C36 36 39 42 37 46 C36 48 34 50 32 50 Z" {ICON}/>'),
]
GLOW = {"o": ("#FFD9B0", "drop-shadow(0 0 2px #FF8A2A) drop-shadow(0 0 7px #FF8A2A) drop-shadow(0 0 16px #FF5200)"),
        "v": ("#ECE4FF", "drop-shadow(0 0 2px #A98CFF) drop-shadow(0 0 7px #A98CFF) drop-shadow(0 0 16px #6A45FF)")}


def end():
    icons = "".join(f'<svg width=76 height=76 viewBox="0 0 64 64" style="stroke:{GLOW[g][0]};filter:{GLOW[g][1]}">{p}</svg>'
                    for g, p in ICONS)
    return HEAD + f"""<body style="background:#000">
<svg width={W} height={H} style="position:absolute;inset:0"><defs>{hexmesh_defs()}
<radialGradient id="mf" cx=".5" cy=".42" r=".42" gradientTransform="translate(.5 .42) scale(1 1.6) translate(-.5 -.42)">
 <stop offset="0" stop-color="#fff" stop-opacity=".06"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>
<mask id="m"><rect width={W} height={H} fill="url(#mf)"/></mask></defs>
<rect width={W} height={H} fill="url(#hex)" mask="url(#m)"/></svg>
<div style="position:absolute;inset:0;background:radial-gradient(ellipse 38% 32% at 50% 40%,rgba(255,82,0,.10),transparent)"></div>
<div style="position:absolute;left:0;right:0;top:250px;display:flex;flex-direction:column;align-items:center">
 {logo(260)}
 <div class=sg style="margin-top:46px;font-size:44px;letter-spacing:.04em;color:#FFB27A;
   text-shadow:0 0 8px rgba(255,82,0,.6),0 0 24px rgba(255,82,0,.3)">Nixie tubes for your GitHub</div>
 <div style="margin-top:60px;display:flex;gap:72px">{icons}</div>
</div>
<div class=sg style="position:absolute;left:0;right:0;top:900px;text-align:center;font-size:22px;letter-spacing:.24em;
 text-transform:uppercase;color:#8C7562;padding-left:.24em">a rear-screen app for the Xiaomi 17 Pro Max</div>
</body>"""


# ------------------------------------------------------------------ render + post
def shoot(name, html):
    src = os.path.join(SRC, name + ".html"); out = os.path.join(OUT, name + ".png")
    open(src, "w").write(html)
    subprocess.run([CHROME, "--headless=new", f"--user-data-dir={HERE}/.chrome", "--force-device-scale-factor=1",
                    f"--screenshot={out}", f"--window-size={W},{H}", "--default-background-color=00000000",
                    "--hide-scrollbars", "--virtual-time-budget=3000", "file://" + src],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return out


def dither(path, sigma=1.6):
    """+-1-2 LSB gaussian noise on an opaque plate so 8-bit dark gradients don't band after H.264."""
    im = Image.open(path).convert("RGB")
    n = Image.effect_noise((W, H), sigma)
    im = Image.merge("RGB", [ImageChops.add(c, n, 1, -128) for c in im.split()])
    im.save(path, optimize=True)


def grain():
    g = Image.effect_noise((W, H), 48)                       # grey ~N(128, 48)
    a = Image.new("L", (W, H), 15)                           # 15/255 = 5.9 %
    Image.merge("RGBA", (g, g, g, a)).save(os.path.join(OUT, "grain.png"), optimize=True)


def check_module():
    im = Image.open(os.path.join(OUT, "module.png")); A = im.getchannel("A")
    w, R = WIN, WIN['r']
    x1, x2, y1, y2 = w['x'], w['x'] + w['w'], w['y'], w['y'] + w['h']

    island_x2, bad, clear = 677, 0, 0
    for y in range(y1, y2):
        for x in range(x1, x2):
            a = A.getpixel((x, y))
            if x >= island_x2 and _inside_by(x, y, x1, x2, y1, y2, R, 1.5):
                bad += a != 0; clear += a == 0
            elif x < island_x2 or not _inside_by(x, y, x1, x2, y1, y2, R, -1.5):
                bad += a != 255          # island area, or bbox corners outside the rounded window
    print(f"module: bad px in window bbox: {bad}; fully clear px: {clear}")
    assert bad == 0
    return clear


def _inside_by(x, y, x1, x2, y1, y2, R, m):
    """True if pixel centre is at least m px inside the rounded window edge."""
    px, py = x + .5, y + .5
    cx = min(max(px, x1 + R), x2 - R); cy = min(max(py, y1 + R), y2 - R)
    if (px, py) != (cx, cy):
        return R - ((px - cx) ** 2 + (py - cy) ** 2) ** .5 >= m
    return min(px - x1, x2 - px, py - y1, y2 - py) >= m


if __name__ == "__main__":
    os.makedirs(SRC, exist_ok=True); os.makedirs(OUT, exist_ok=True)
    want = set(sys.argv[1:])
    jobs = {"bg": bg, "module": module, "title": title, "end": end}
    jobs.update({f"cap-{n:02d}": (lambda t=t: caption(t)) for n, t in CAPTIONS.items()})
    for name, fn in jobs.items():
        if want and name not in want and not (name.startswith("cap") and "caps" in want):
            continue
        p = shoot(name, fn())
        if name in ("bg", "end"):
            dither(p)
        print("wrote", p)
    if not want or "grain" in want:
        grain(); print("wrote grain")
    if not want or "module" in want:
        json.dump({"canvas": [W, H],
                   "window": {**{k: WIN[k] for k in "xywh"}, "corner_radius": WIN['r'],
                              "note": "rear panel 976x596 x1.4754; left 437px (x240-676) covered by island"},
                   "visible_screen": {"x": 677, "y": WIN['y'], "w": WIN['x'] + WIN['w'] - 677, "h": WIN['h']},
                   "island": {**ISL, "radius_left": 130, "radius_right": 14},
                   "module_body": {**{k: MOD[k] for k in "xywh"}, "corner_radius": MOD['r']},
                   "scale": 1.4754}, open(os.path.join(OUT, "module.json"), "w"), indent=1)
        check_module()
