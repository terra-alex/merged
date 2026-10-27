#!/usr/bin/env python3
"""Soundtrack + sound effects for the Merged demo video, synthesised from scratch (stdlib only, no samples).

music.wav  68.0 s, 120 BPM, A minor / C major: warm synthwave saw pads (pumped by the kick) + chiptune
           square/triangle leads, following BRIEF.md's timeline (drop at 16.0, breakdown 32-35, peak 57-61,
           final Cmaj9 at 61 fading to silence by 67.5).
SFX        whoosh click impact zap sparkle mew pop ping tick burst riser (.wav, normalised to -1 dB true peak).

Run: python3 make_audio.py [music] [sfx]     (both by default; well under 2 min)
"""
import math
import os
import random
import sys
import time
import wave
from array import array
from functools import lru_cache

RATE = 44100
TAU = 2 * math.pi
OUT = os.path.dirname(os.path.abspath(__file__))


# ---------------------------------------------------------------- basics

def S(t):
    """Seconds -> samples. Always round once from absolute time: int((t0 + i/RATE)*RATE) skips/doubles samples (clicks)."""
    return round(t * RATE)


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def zeros(n):
    return array('d', bytes(8 * n))


def A(xs):
    return array('d', xs)


def noise(n, seed):
    r = random.Random(seed).random
    return [2 * r() - 1 for _ in range(n)]


def mix(dst, src, at, g=1.0):
    j = S(at)
    if j < 0:
        src, j = src[-j:], 0
    n = min(len(src), len(dst) - j)
    if n > 0 and g:
        dst[j:j + n] = A([a + g * b for a, b in zip(dst[j:j + n], src)])


def mix2(L, R, src, at, g=1.0, pan=0.0):
    """Constant-power pan (-1 = left, 1 = right)."""
    a = (pan + 1) * math.pi / 4
    mix(L, src, at, g * math.cos(a))
    mix(R, src, at, g * math.sin(a))


def mul(x, e):
    return [a * b for a, b in zip(x, e)]


def fade(x, fin, fout):
    n, a, b = len(x), max(1, S(fin)), max(1, S(fout))
    return [v * min(1.0, i / a, (n - 1 - i) / b) for i, v in enumerate(x)]


def norm1(x):
    pk = max(map(abs, x))
    return [v / pk for v in x]


def rms_db(*chans, t0=0.0, t1=None):
    seg = [c[S(t0):S(t1) if t1 else len(c)] for c in chans]
    ms = sum(math.sumprod(c, c) for c in seg) / max(1, sum(map(len, seg)))
    return 10 * math.log10(max(ms, 1e-20))


def peak_db(*chans):
    return 20 * math.log10(max(max(map(abs, c)) for c in chans) or 1e-10)


def write_wav(path, L, R=None):
    q = lambda c: array('h', (max(-32767, min(32767, round(v * 32767))) for v in c))
    if R is None:
        data = q(L)
    else:
        data = array('h', bytes(4 * len(L)))
        data[0::2], data[1::2] = q(L), q(R)
    if sys.byteorder == 'big':
        data.byteswap()
    with wave.open(path, 'wb') as w:
        w.setnchannels(1 if R is None else 2)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(data.tobytes())


# ---------------------------------------------------------------- oscillators, envelopes, filters

TN = 4096
_SIN = [math.sin(TAU * i / TN) for i in range(TN)]


@lru_cache(None)
def table(wave_, H):
    """Single-cycle band-limited table with H harmonics (Lanczos-smoothed), plus a guard sample."""
    t = [0.0] * TN
    for k in range(1, H + 1):
        if wave_ == 'saw':
            a, off = 1 / k, 0
        elif wave_ == 'pulse':                     # 25 % duty, the NES lead: cosine series
            a, off = math.sin(math.pi * k * 0.25) / k, TN // 4
        elif k % 2 == 0:
            continue
        elif wave_ == 'sq':
            a, off = 1 / k, 0
        else:                                      # 'tri'
            a, off = (-1) ** (k // 2) / (k * k), 0
        x = math.pi * k / (H + 1)
        a *= math.sin(x) / x
        t = [v + a * _SIN[(k * i + off) % TN] for i, v in enumerate(t)]
    pk = max(map(abs, t))
    return [v / pk for v in t] + [t[0] / pk]


def osc(wave_, f, n, cents=0.0, ph=0.0, vib=0.0):
    """Wavetable oscillator, harmonics stop below 15 kHz (no aliasing). vib = vibrato depth, fades in over 0.15 s."""
    tab = table('tri', 1) if wave_ == 'sine' else table(wave_, max(1, min(40, int(15000 / f))))
    inc = f * 2 ** (cents / 1200) * TN / RATE
    p = (ph % 1.0) * TN
    out = [0.0] * n
    if not vib:
        for i in range(n):
            k = int(p)
            a = tab[k]
            out[i] = a + (p - k) * (tab[k + 1] - a)
            p += inc
            if p >= TN:
                p -= TN
    else:
        w, nd = TAU * 5.5 / RATE, 0.15 * RATE
        for i in range(n):
            k = int(p)
            a = tab[k]
            out[i] = a + (p - k) * (tab[k + 1] - a)
            p += inc * (1 + vib * min(1.0, i / nd) * math.sin(w * i))
            if p >= TN:
                p -= TN
    return out


def blep(p, dt):
    """PolyBLEP residual for a naive saw (cheap band-limiting for swept-pitch SFX)."""
    if p < dt:
        x = p / dt
        return x + x - x * x - 1
    if p > 1 - dt:
        x = (p - 1) / dt
        return x * x + x + x + 1
    return 0.0


def adsr(gate, a, d, s, r):
    g, na, nr = max(1, S(gate)), max(1, S(a)), max(1, S(r))
    nd = d * RATE
    e = [i / na if i < na else s + (1 - s) * math.exp((na - i) / nd) for i in range(g)]
    end = e[-1]
    return e + [end * (1 - i / nr) for i in range(1, nr + 1)]


def lp(x, fc, poles=1):
    """One/two-pole low-pass; fc in Hz or a function of time (re-evaluated every 32 samples)."""
    n = len(x)
    y = zeros(n)
    s1 = s2 = 0.0
    for b in range(0, n, 32):
        c = 1 - math.exp(-TAU * (fc(b / RATE) if callable(fc) else fc) / RATE)
        e = min(b + 32, n)
        if poles == 1:
            for i in range(b, e):
                s1 += c * (x[i] - s1)
                y[i] = s1
        else:
            for i in range(b, e):
                s1 += c * (x[i] - s1)
                s2 += c * (s1 - s2)
                y[i] = s2
    return y


def svf(x, fc, q=0.707, mode='bp'):
    """TPT state-variable filter (stable under fast sweeps). 'bp' is unity gain at fc."""
    n = len(x)
    y = zeros(n)
    k = 1 / q
    cl, cb, ch = {'lp': (1, 0, 0), 'bp': (0, k, 0), 'hp': (0, 0, 1)}[mode]
    z1 = z2 = 0.0
    for b in range(0, n, 16):
        g = math.tan(math.pi * min(fc(b / RATE) if callable(fc) else fc, 18000) / RATE)
        a1 = 1 / (1 + g * (g + k))
        a2, a3 = g * a1, g * g * a1
        for i in range(b, min(b + 16, n)):
            xi = x[i]
            v3 = xi - z2
            v1 = a1 * z1 + a2 * v3
            v2 = z2 + a2 * z1 + a3 * v3
            z1, z2 = 2 * v1 - z1, 2 * v2 - z2
            y[i] = cl * v2 + cb * v1 + ch * (xi - k * v1 - v2)
    return y


def comb(x, D, g):
    """Feedback comb with a 2-tap damping average, computed D samples at a time (the loop lag is >= D)."""
    n, P, h = len(x), D + 1, g * 0.5
    y = zeros(n + P)
    for i0 in range(0, n, D):
        i1 = min(i0 + D, n)
        y[P + i0:P + i1] = A([a + h * (p + q) for a, p, q in
                              zip(x[i0:i1], y[P + i0 - D:P + i1 - D], y[P + i0 - D - 1:P + i1 - D - 1])])
    return y[P:]


def allpass(x, D, g=0.5):
    n = len(x)
    v = zeros(n + D)
    for i0 in range(0, n, D):
        i1 = min(i0 + D, n)
        v[D + i0:D + i1] = A([a + g * b for a, b in zip(x[i0:i1], v[i0:i1])])
    return A([b - a for a, b in zip(x, v)])


def reverb(x):
    """Freeverb-style room (4 combs + 2 allpasses per side), fed a 250 Hz-6 kHz band so it never muddies the low end."""
    x = lp(x, 6000)
    x = A([a - b for a, b in zip(x, lp(x, 250))])
    outs = []
    for sp in (0, 23):
        y = zeros(len(x))
        for D in (1116, 1188, 1277, 1356):
            y = A([a + b for a, b in zip(y, comb(x, D + sp, 0.86))])
        for D in (556, 441):
            y = allpass(y, D + sp)
        outs.append(y)
    return outs


def riser(dur, seed):
    """Noise through a rising band-pass + a gliding A2->A5 tone with speeding tremolo; full level at its very end."""
    n = S(dur)
    fc = lambda t: 250 * 36 ** ((t / dur) ** 1.6)
    L, R = svf(noise(n, seed), fc, 1.2, 'bp'), svf(noise(n, seed + 1), fc, 1.2, 'bp')
    ph = tp = 0.0
    for i in range(n):
        u = i / n
        ph += 110 * 8 ** (u ** 1.4) / RATE
        tp += (4 + 12 * u * u) / RATE
        tone = (math.sin(TAU * ph) + 0.45 * math.sin(2 * TAU * ph) + 0.25 * math.sin(3 * TAU * ph)) \
            * (0.8 + 0.2 * math.sin(TAU * tp)) * u ** 1.4
        e = u ** 0.9
        L[i] = 2.2 * L[i] * e + 0.22 * tone
        R[i] = 2.2 * R[i] * e + 0.22 * tone
    pk = max(peak_db(L, R), -60)
    g = 10 ** (-pk / 20)
    return fade([v * g for v in L], 0.01, 0.005), fade([v * g for v in R], 0.01, 0.005)


def crackle(chans, rnd, t0, t1, rate, amp, hp=2000):
    """Fire / firework crackle: Poisson-timed 0.2-1 ms noise micro-bursts, random level and pan, high-passed."""
    n = len(chans[0])
    ex = [[0.0] * n, [0.0] * n]
    rmax = max(rate(t0 + (t1 - t0) * k / 50) for k in range(51)) * 1.1
    t = t0
    while True:
        t += rnd.expovariate(rmax)
        if t >= t1:
            break
        if rnd.random() * rmax > rate(t):
            continue
        a = amp(t) * rnd.random() ** 2
        p = (rnd.uniform(-1, 1) + 1) * math.pi / 4
        m = rnd.randint(9, 45)
        b = [a * (2 * rnd.random() - 1) * (1 - i / m) for i in range(m)]
        mix(ex[0], b, t, math.cos(p))
        mix(ex[1], b, t, math.sin(p))
    for c in (0, 1):
        mix(chans[c], lp(svf(ex[c], hp, 0.7, 'hp'), 12000), 0)


# ---------------------------------------------------------------- drums & instruments (cached renders)

@lru_cache(None)
def kick(soft):
    n = S(0.5)
    out = [0.0] * n
    ph = 0.0
    sweep, tau = (70, 0.045) if soft else (130, 0.03)
    for i in range(n):
        t = i / RATE
        ph += (46 + sweep * math.exp(-t / tau)) / RATE
        out[i] = math.sin(TAU * ph) * math.exp(-t / 0.17) * min(1.0, t / 0.001)
    if not soft:                                   # beater click
        m = S(0.005)
        for i, v in enumerate(lp(noise(m, 3), 5000)):
            out[i] += 0.5 * v * (1 - i / m)
    return fade([math.tanh(1.5 * v) / math.tanh(1.5) for v in out], 0, 0.06)


@lru_cache(None)
def snare():
    n = S(0.28)
    body = svf(noise(n, 21), 1800, 0.7, 'hp')
    out = []
    for i in range(n):
        t = i / RATE
        tone = math.sin(TAU * 185 * t) * math.exp(-t / 0.045) + 0.4 * math.sin(TAU * 330 * t) * math.exp(-t / 0.03)
        out.append(0.55 * tone + 0.8 * body[i] * math.exp(-t / 0.075))
    return norm1(fade(out, 0.0008, 0.03))


@lru_cache(None)
def clap():
    n = S(0.35)
    lo, hi = svf(noise(n, 31), 1250, 0.8, 'bp'), svf(noise(n, 32), 3500, 1.0, 'bp')
    out = []
    for i in range(n):
        t = i / RATE
        e = 0.9 * sum(math.exp(-(t - d) / 0.005) for d in (0, 0.011, 0.022) if t >= d) \
            + 0.7 * (1 - math.exp(-t / 0.012)) * math.exp(-t / 0.1)
        out.append((lo[i] + 0.4 * hi[i]) * e)
    return norm1(fade(out, 0.0005, 0.04))


@lru_cache(None)
def hat(open_, seed):
    n = S(0.3 if open_ else 0.07)
    a, b = svf(noise(n, 40 + seed), 7000, 0.6, 'hp'), svf(noise(n, 50 + seed), 10500, 2.0, 'bp')
    tau = (0.1 if open_ else 0.018) * RATE
    return norm1(fade([(p + 0.5 * q) * math.exp(-i / tau) for i, (p, q) in enumerate(zip(a, b))],
                      0.0004, 0.03 if open_ else 0.01))


@lru_cache(None)
def crash():
    n = S(3.0)
    chans = []
    for seed in (61, 62):
        a, b = svf(noise(n, seed), 4200, 0.6, 'hp'), svf(noise(n, seed + 10), 6800, 1.5, 'bp')
        chans.append([(p + 0.6 * q) * math.exp(-i / (0.85 * RATE)) for i, (p, q) in enumerate(zip(a, b))])
    g = 1 / max(max(map(abs, c)) for c in chans)
    return tuple(fade([v * g for v in c], 0.002, 0.4) for c in chans)


@lru_cache(None)
def bass_note(m, gate):
    f, n = hz(m), S(gate) + S(0.03)
    x = [0.5 * a + 0.65 * b for a, b in zip(osc('saw', f, n), osc('sine', f, n))]
    x = lp(x, lambda t: 300 + 1700 * math.exp(-t / 0.08), 2)
    return mul(x, adsr(gate, 0.003, 0.15, 0.7, 0.03))


@lru_cache(None)
def arp_note(m):
    x = lp(osc('sq', hz(m), S(0.22)), lambda t: 600 + 5200 * math.exp(-t / 0.04), 2)
    return [v * min(1.0, i / 88) * math.exp(-i / (0.07 * RATE)) for i, v in enumerate(fade(x, 0, 0.03))]


@lru_cache(None)
def lead_note(m, gate):
    f, n = hz(m), S(gate) + S(0.07)
    x = [0.7 * a + 0.45 * b for a, b in zip(osc('pulse', f, n, vib=0.0045), osc('tri', f / 2, n, vib=0.0045))]
    return mul(lp(x, 7000), adsr(gate, 0.004, 0.3, 0.72, 0.07))


@lru_cache(None)
def counter_note(m, gate):
    f, n = hz(m), S(gate) + S(0.1)
    x = [0.85 * a + 0.2 * b for a, b in zip(osc('tri', f, n, vib=0.003), osc('pulse', f, n))]
    return mul(x, adsr(gate, 0.003, 0.15, 0.45, 0.1))


@lru_cache(None)
def pad_chord(notes, gate, a, d, s, r):
    """Supersaw-ish pad: 3 detuned saws per note (left / centre / right), random start phases."""
    n = S(gate) + S(r)
    rnd = random.Random(sum(notes))
    L, R = [0.0] * n, [0.0] * n
    for m in notes:
        for cents, pan in ((-11, -0.9), (0, 0.0), (11, 0.9)):
            mix2(L, R, osc('saw', hz(m), n, cents, rnd.random()), 0, 1 / len(notes), pan)
    env = adsr(gate, a, d, s, r)
    return mul(L, env), mul(R, env)


# ---------------------------------------------------------------- the score

CHORDS = {  # pad voicing, arp tones, bass root
    'Am': ((57, 60, 64, 67), (57, 64, 69, 72), 33),
    'F': ((53, 57, 60, 64), (53, 60, 65, 69), 29),
    'C': ((55, 60, 64, 67), (55, 60, 64, 67), 36),
    'G': ((55, 59, 62, 67), (55, 62, 67, 71), 31),
    'Dm': ((53, 57, 62, 65), (50, 57, 62, 65), 38),
    'Em': ((55, 59, 64, 67), (52, 59, 64, 67), 40),
}
INTRO_PAD = (45, 57, 60, 64, 71)       # Am(add9) with a low A
END_PAD = (48, 55, 60, 64, 71, 74)     # Cmaj9
SEQ = (0, 1, 2, 3, 2, 1, 2, 3)
KICK_G, BASS_G, PAD_G, ARP_G, LEAD_G, COUNTER_G = 0.7, 0.32, 0.34, 0.155, 0.38, 0.27   # mix levels

# (start s, beats, chord, section). Bars are 2 s; the 2-beat 'lift' bar moves the downbeats onto odd seconds,
# so 35, 41, 45, 51, 57 and 61 (the brief's cuts) all land on downbeats.
ARR = [(0, 4, 'Am', 'intro'), (2, 4, 'Am', 'intro'),
       (4, 4, 'Am', 'g1'), (6, 4, 'F', 'g1'), (8, 4, 'C', 'g2'), (10, 4, 'G', 'g2'),
       (12, 4, 'F', 'build'), (14, 4, 'G', 'build')]
ARR += [(16 + 2 * i, 4, c, 'drop') for i, c in enumerate('Am F C G Am F C G'.split())]
ARR += [(32, 4, 'F', 'break'), (34, 2, 'G', 'lift')]
ARR += [(35 + 2 * i, 4, c, 'var1' if i < 4 else 'var2' if i < 8 else 'var3')
        for i, c in enumerate('Am F C G Am F C G Dm Em G'.split())]
ARR += [(57, 4, 'Am', 'peak'), (59, 4, 'F', 'peak'), (61, 14, 'C', 'end')]
GROOVE = ('drop', 'var1', 'var2', 'var3', 'peak')

# melodies: "slot:note:len" in 16ths, one string per bar
HOOK = ["0:E5:6 6:D5:6 12:C5:2 14:D5:2", "0:C5:6 6:A4:6 12:C5:2 14:E5:2",
        "0:G5:6 6:E5:6 12:D5:2 14:E5:2", "0:D5:6 6:B4:4 10:G4:2 12:A4:2 14:B4:2",
        "0:E5:6 6:G5:6 12:A5:4", "0:A5:6 6:G5:6 12:F5:2 14:E5:2",
        "0:E5:6 6:D5:6 12:C5:2 14:D5:2", "0:D5:6 6:E5:6 12:B4:4"]
COUNTER = ["2:E5:2 4:A5:2 6:C6:4 10:B5:2 12:A5:4", "2:F5:2 4:A5:2 6:C6:4 10:E6:2 12:C6:4",
           "2:E5:2 4:G5:2 6:C6:4 10:D6:2 12:E6:4", "2:D6:2 4:B5:2 6:G5:4 10:A5:2 12:B5:4"]
BREAK_ECHO = ["0:A5:6 6:G5:6 12:E5:4"]
VAR3 = ["0:F5:6 6:E5:6 12:D5:2 14:E5:2", "0:G5:6 6:B5:6 12:A5:2 14:G5:2",
        "0:G5:2 2:A5:2 4:B5:2 6:D6:2 8:B5:2 10:D6:2 12:E6:4"]
PEAK = ["0:A5:6 6:C6:6 12:B5:2 14:C6:2", "0:C6:6 6:A5:6 12:G5:2 14:B5:2"]

PAD_FC = [(0, 250), (3.9, 2200), (4, 2000), (12, 2600), (15.9, 6500), (16, 5000), (24, 6000), (31.9, 5000),
          (32.3, 700), (33.9, 900), (35, 5500), (43, 4200), (51, 6000), (56.9, 7000), (57, 7500), (61, 7500),
          (68, 700)]
CRASHES = [(4, 0.35), (16, 1.0), (24, 0.55), (35, 0.9), (43, 0.55), (51, 0.5), (57, 1.0), (59, 0.5), (61, 0.9)]
ROLLS = [(13.0, 14.0, 0.25, .25, .35), (14.0, 15.5, 0.125, .35, .75), (15.5, 16.0, 0.0625, .75, 1.0),
         (31.0, 32.0, 0.125, .4, .85), (34.0, 34.5, 0.125, .4, .7), (34.5, 35.0, 0.0625, .7, 1.0),
         (55.0, 56.0, 0.25, .3, .4), (56.0, 56.5, 0.125, .45, .7), (56.5, 57.0, 0.0625, .7, 1.0)]
RISERS = [(0, 4, 0.45), (12, 4, 0.5), (34, 1, 0.45), (55, 2, 0.45)]
SECTIONS = [('intro: pad swell + riser', 0, 4), ('light groove, building', 4, 16), ('DROP + hook', 16, 32),
            ('breakdown + lift', 32, 35), ('groove variations + counter-melody', 35, 57), ('peak', 57, 61),
            ('outro: Cmaj9 rings out', 61, 68)]


def parse(bar):
    pc = dict(C=0, D=2, E=4, F=5, G=7, A=9, B=11)
    out = []
    for tok in bar.split():
        slot, name, ln = tok.split(':')
        out.append((int(slot), 12 * (int(name[-1]) + 1) + pc[name[0]], int(ln)))
    return out


def curve(points):
    """Exponential (log-frequency) interpolation through (t, hz) breakpoints."""
    def f(t):
        for (t0, a), (t1, b) in zip(points, points[1:]):
            if t < t1:
                return a * (b / a) ** max(0.0, (t - t0) / (t1 - t0))
        return points[-1][1]
    return f


def limit(L, R, ceil, att=0.003, rel=0.12):
    """Linked look-ahead peak limiter: backward pass = linear 3 ms attack ramp into every peak, forward = exp release."""
    g = A([min(1.0, ceil / max(abs(a), abs(b), 1e-9)) for a, b in zip(L, R)])
    step = 1 / (att * RATE)
    for i in range(len(g) - 2, -1, -1):
        v = g[i + 1] + step
        if v < g[i]:
            g[i] = v
    c = 1 - math.exp(-1 / (rel * RATE))
    prev = g[0]
    for i in range(1, len(g)):
        prev += c * (1 - prev)
        if g[i] < prev:
            prev = g[i]
        g[i] = prev
    return A(mul(L, g)), A(mul(R, g)), min(g)


def make_music():
    t_start = time.time()
    N = S(68.0)
    drL, drR, padL, padR, synL, synR, bass, send, duck = (zeros(N) for _ in range(9))
    kicks = []

    def hit(src, t, g, pan=0.0, rev=0.0):
        mix2(drL, drR, src, t, g, pan)
        if rev:
            mix(send, src, t, g * rev)

    def kick_at(t, vel, soft=False):
        hit(kick(soft), t, KICK_G * vel)
        kicks.append((t, vel))

    def melody(fn, bars, t0, g, pan=0.0):
        for b, bar in enumerate(bars):
            for slot, m, ln in parse(bar):
                t = t0 + 2 * b + slot * 0.125
                v = fn(m, round(ln * 0.125 * 0.92, 4))
                mix2(synL, synR, v, t, g, pan)
                for k, (eg, ep) in enumerate(((0.3, 0.6), (0.17, -0.6), (0.09, 0.6)), 1):  # dotted-8th ping-pong
                    mix2(synL, synR, v, t + 0.375 * k, g * eg, ep)

    for t0, beats, ch, sec in ARR:
        pad, arp, root = CHORDS[ch]
        bar = beats * 0.5
        groove = sec in GROOVE

        # pads (width via mid/side per chord, so a width change can never click)
        P = None
        if sec == 'intro' and t0 == 0:
            P, w, g = pad_chord(INTRO_PAD, 4.0, 3.4, 1, 1, 0.4), 1.0, 1.0
        elif sec in ('g1', 'g2', 'build') or groove:
            P = pad_chord(pad, bar, 0.02, 0.8, 0.85, 0.3)
            w = 1.35 if sec == 'var1' else 1.6 if groove else 1.0
            g = {'g1': 0.8, 'g2': 0.9}.get(sec, 1.0)
        elif sec == 'break':
            P, w, g = pad_chord(pad, bar, 0.3, 1, 0.9, 0.6), 1.2, 0.85
        elif sec == 'lift':
            P, w, g = pad_chord(pad, bar, 0.05, 1, 0.9, 0.25), 1.2, 0.9
        elif sec == 'end':
            P, w, g = pad_chord(END_PAD, 6.3, 0.01, 1.8, 0.3, 0.2), 1.5, 1.1
        if P:
            Lc, Rc = P
            mix(padL, [(a + b) / 2 + w * (a - b) / 2 for a, b in zip(Lc, Rc)], t0, g)
            mix(padR, [(a + b) / 2 - w * (a - b) / 2 for a, b in zip(Lc, Rc)], t0, g)

        # plucky arpeggio
        if sec in ('g1', 'g2', 'build', 'break') or groove:
            step8 = sec in ('g1', 'break')
            spread = 0.55 if groove else 0.3
            for k in range(0, beats * 4, 2 if step8 else 1):
                t = t0 + k * 0.125
                j = k // 2 if step8 else k
                g = ARP_G * (1.0 if k % 4 == 0 else 0.78) \
                    * {'g1': 0.75, 'g2': 0.85, 'build': 0.85 + 0.25 * (t - 12) / 4, 'break': 0.6}.get(sec, 1.0)
                mix2(synL, synR, arp_note(arp[SEQ[j % 8]]), t, g, spread if j % 2 else -spread)

        # bass
        if sec in ('g1', 'g2', 'build') or groove:
            pat = (0,) * 8 if sec == 'g1' else (0, 0, 0, 12, 0, 0, 0, 12) if sec in ('g2', 'build') else (0, 12) * 4
            for k in range(beats * 2):
                t = t0 + k * 0.25
                if sec == 'build' and t >= 15.0:
                    break
                mix(bass, bass_note(root + pat[k % 8], 0.2), t, BASS_G * (0.75 if sec == 'g1' else 1.0))
        elif sec == 'break':
            mix(bass, bass_note(root, 1.9), t0, BASS_G * 0.85)
        elif sec == 'end':
            mix(bass, bass_note(root, 4.5), t0, BASS_G)

        # drums
        if sec == 'end':
            kick_at(t0, 1.0)
        for k in range(beats * 4 if sec not in ('intro', 'break', 'lift', 'end') else 0):
            t = t0 + k * 0.125
            q, beat = k % 4, k // 4
            if q == 0:
                if sec in ('g1', 'g2'):
                    kick_at(t, 0.6 if sec == 'g1' else 0.72, soft=True)
                elif sec == 'build' and t < 15.0:
                    kick_at(t, 0.85)
                elif groove:
                    kick_at(t, 1.0)
                if beat in (1, 3) and (groove or sec == 'g2' or (sec == 'build' and t0 == 12)):
                    hit(clap(), t, 0.36 * (1.0 if groove else 0.6 if sec == 'g2' else 0.8), 0, 0.35)
                    if sec == 'peak':
                        hit(snare(), t, 0.22, 0, 0.3)
            if sec == 'g1':
                if q == 2:
                    hit(hat(False, k % 3), t, 0.13 * 0.6, 0.25)
            elif sec in ('g2', 'build', 'var1') and not (sec == 'var1' and k == 14):
                hit(hat(False, k % 3), t, 0.13 * (0.5, 0.32, 0.8, 0.4)[q] * (1.0 if sec == 'var1' else 0.8), 0.25)
            elif q == 2 or (sec == 'var1' and k == 14):
                hit(hat(True, k % 2), t, 0.11 * 0.7, -0.2)
            elif groove:
                hit(hat(False, k % 3), t, 0.13 * (0.5, 0.35, 0, 0.45)[q] * (1.15 if sec == 'peak' else 1.0), 0.25)

    for t0, t1, step, v0, v1 in ROLLS:
        k = 0
        while t0 + k * step < t1 - 1e-9:
            t = t0 + k * step
            hit(snare(), t, 0.3 * (v0 + (v1 - v0) * (t - t0) / (t1 - t0)), 0, 0.3)
            k += 1
    for t, v in CRASHES:
        cL, cR = crash()
        mix(drL, cL, t, 0.2 * v)
        mix(drR, cR, t, 0.2 * v)
        mix(send, cL, t, 0.03 * v)
    for i, (t, dur, g) in enumerate(RISERS):
        rL, rR = riser(dur, 200 + i)
        mix(synL, rL, t, g * 0.5)
        mix(synR, rR, t, g * 0.5)

    melody(lead_note, HOOK, 16, LEAD_G)
    melody(counter_note, BREAK_ECHO, 32, COUNTER_G)
    melody(counter_note, COUNTER, 35, COUNTER_G)
    melody(lead_note, HOOK[4:], 43, LEAD_G)
    melody(counter_note, COUNTER, 43, COUNTER_G * 0.6, -0.35)
    melody(lead_note, VAR3, 51, LEAD_G)
    melody(lead_note, PEAK, 57, LEAD_G * 1.08)
    melody(lead_note, ["0:C6:28"], 61, LEAD_G)
    print(f'  arranged      {time.time() - t_start:5.1f} s')

    # sidechain: every kick ducks pads (and a little bass), 6 ms down, 0.32 s back up
    nd, na = S(0.32), S(0.006)
    shape = [min(1.0, i / na) * (1 - i / nd) ** 2 for i in range(nd)]
    for t, v in kicks:
        j = S(t)
        duck[j:j + nd] = A([max(a, v * b) for a, b in zip(duck[j:j + nd], shape)])
    fc = curve(PAD_FC)
    padL = A([PAD_G * v * (1 - 0.55 * k) for v, k in zip(lp(padL, fc, 2), duck)])
    padR = A([PAD_G * v * (1 - 0.55 * k) for v, k in zip(lp(padR, fc, 2), duck)])
    bass = A([v * (1 - 0.35 * k) for v, k in zip(bass, duck)])
    del duck
    for name, chans in (('drums', (drL, drR)), ('bass', (bass,)), ('pads', (padL, padR)), ('synths', (synL, synR))):
        print(f'  stem {name:7s} drop RMS {rms_db(*chans, t0=16, t1=32):6.1f} dB')
    if os.environ.get('STEMS'):                       # debug: dump stems to measure their loudness with ffmpeg
        for name, chans in (('drums', (drL, drR)), ('bass', (bass, bass)), ('pads', (padL, padR)), ('synths', (synL, synR))):
            write_wav(os.path.join(OUT, f'stem_{name}.wav'), *chans)
    send = A([s + 0.11 * (a + b) + 0.17 * (c + d) for s, a, b, c, d in zip(send, padL, padR, synL, synR)])
    print(f'  processed     {time.time() - t_start:5.1f} s')

    L = A([a + b + c + d for a, b, c, d in zip(drL, bass, padL, synL)])
    R = A([a + b + c + d for a, b, c, d in zip(drR, bass, padR, synR)])
    del drL, drR, padL, padR, synL, synR, bass
    rvL, rvR = reverb(send)
    del send
    rg = 10 ** ((rms_db(L, R, t0=16, t1=32) - 13 - rms_db(rvL, rvR, t0=16, t1=32)) / 20)  # wet 13 dB under dry
    L = A([a + rg * b for a, b in zip(L, rvL)])
    R = A([a + rg * b for a, b in zip(R, rvR)])
    del rvL, rvR
    print(f'  reverb        {time.time() - t_start:5.1f} s')

    # master: gain to ~-16 dB mean (≈ -14 LUFS), limit to -1.5 dBFS, then the final fade (silent from 67.5)
    t_f0, t_f1 = 63.0, 67.5
    fenv = A([min(1.0, i / S(0.005)) * (1.0 if i < S(t_f0) else 0.0 if i >= S(t_f1) else
            0.5 * (1 + math.cos(math.pi * (i - S(t_f0)) / (S(t_f1) - S(t_f0))))) for i in range(N)])
    ceil, target = 10 ** (-1.5 / 20), -16.0
    gain = 10 ** ((target - rms_db(L, R)) / 20)
    for _ in range(4):
        oL, oR, gr = limit(A([v * gain for v in L]), A([v * gain for v in R]), ceil)
        oL, oR = A(mul(oL, fenv)), A(mul(oR, fenv))
        err = target - rms_db(oL, oR)
        if abs(err) < 0.15:
            break
        gain *= 10 ** (err / 20)
    print(f'  mastered      {time.time() - t_start:5.1f} s  (max limiter reduction {20 * math.log10(gr):.1f} dB)')

    assert len(oL) == len(oR) == S(68.0)
    assert peak_db(oL, oR) <= -1.0
    assert not any(oL[S(t_f1):]) and not any(oR[S(t_f1):])
    write_wav(os.path.join(OUT, 'music.wav'), oL, oR)
    print(f'music.wav  {len(oL) / RATE:.3f} s  mean {rms_db(oL, oR):.1f} dB  peak {peak_db(oL, oR):.2f} dBFS')
    for name, a, b in SECTIONS:
        print(f'  {a:4.1f}-{b:4.1f}  {name:36s} RMS {rms_db(oL, oR, t0=a, t1=b):6.1f} dB')


# ---------------------------------------------------------------- sound effects

def true_peak(chans):
    """Inter-sample peak: 4x windowed-sinc interpolation around every loud sample."""
    pk = best = max(max(map(abs, c)) for c in chans)
    ks = [[math.sin(math.pi * (f - j)) / (math.pi * (f - j)) * (0.5 + 0.5 * math.cos(math.pi * (f - j) / 9))
           for j in range(-8, 10)] for f in (0.25, 0.5, 0.75)]
    for c in chans:
        for i in range(8, len(c) - 10):
            if abs(c[i]) > 0.5 * pk:
                seg = c[i - 8:i + 10]
                best = max(best, *(abs(math.sumprod(seg, k)) for k in ks))
    return best


def save(name, chans, fin=0.001, fout=0.015):
    """Tiny fades, then normalise to -1 dBTP (true peak, so bright transients don't overshoot after encoding)."""
    chans = [fade(c, fin, fout) for c in chans]
    g = 10 ** (-1 / 20) / true_peak(chans)
    chans = [[v * g for v in c] for c in chans]
    write_wav(os.path.join(OUT, name), *chans)
    print(f'{name:12s} {len(chans[0]) / RATE:6.3f} s  {len(chans)}ch  peak {peak_db(*chans):5.1f} dBFS  '
          f'TP {20 * math.log10(true_peak(chans)):5.1f}  '
          f'RMS {rms_db(*chans):6.1f} dB')


def sfx_whoosh():
    T = 0.6
    n = S(T)
    shape = lambda t: math.sin(math.pi * min(1.0, t / T) ** 0.75)
    a = svf(noise(n, 71), lambda t: 300 * 11 ** shape(t), 0.9, 'bp')
    b = lp(noise(n, 72), 450, 2)
    x = [(p + 2.0 * q) * shape(i / RATE) ** 1.5 for i, (p, q) in enumerate(zip(a, b))]
    ang = [(0.3 + 1.4 * i / n) * math.pi / 4 for i in range(n)]       # pans left -> right as it passes
    return [[v * math.cos(w) for v, w in zip(x, ang)], [v * math.sin(w) for v, w in zip(x, ang)]]


def sfx_click():
    n = S(0.11)
    exc = [0.0] * n
    for at, amp, seed in ((0.0, 1.0, 81), (0.0065, 0.45, 82), (0.017, 0.18, 83)):   # contact + two bounces
        m = S(0.0012)
        mix(exc, [amp * v * (1 - i / m) for i, v in enumerate(noise(m, seed))], at)
    ring, body = svf(exc, 2300, 6, 'bp'), svf(exc, 850, 2.5, 'bp')
    out = [3 * r + 2 * b + 0.15 * e + 0.3 * math.sin(TAU * 150 * i / RATE) * math.exp(-i / (0.012 * RATE))
           * min(1.0, i / 30) for i, (r, b, e) in enumerate(zip(ring, body, exc))]
    return [lp(out, 4500, 2)]


def sfx_impact():
    T = 2.5
    n = S(T)
    rnd = random.Random(90)
    boom, ph = [], 0.0
    for i in range(n):
        t = i / RATE
        ph += (30 + 75 * math.exp(-t / 0.11)) / RATE
        boom.append(math.tanh(2.2 * math.sin(TAU * ph) * math.exp(-t / 0.65)) * min(1.0, t / 0.002))
    m = S(0.08)
    hitn = [v * math.exp(-i / (0.015 * RATE)) for i, v in enumerate(lp(noise(m, 91), 2500, 2))]
    chans = []
    for c in (0, 1):
        whump = lp(noise(n, 92 + c), lambda t: 220 + 2800 * math.exp(-t / 0.14), 2)
        roar = lp(noise(n, 94 + c), 750, 2)
        flick = lp(noise(n, 96 + c), 7)
        out = [0.9 * boom[i]
               + 1.6 * whump[i] * min(1.0, i / 350) * math.exp(-i / (0.35 * RATE))
               + 2.2 * roar[i] * max(0.1, 0.65 + 28 * flick[i]) * min(1.0, i / (0.08 * RATE)) * math.exp(-i / (0.95 * RATE))
               for i in range(n)]
        mix(out, hitn, 0, 0.8)
        chans.append(out)
    crackle(chans, rnd, 0.04, 2.4, lambda t: 70 * math.exp(-t / 0.7) + 8, lambda t: 0.5 * math.exp(-t / 1.2))
    return chans


def sfx_zap():
    T = 0.8
    n = S(T)
    rnd = random.Random(100)
    jit = lp(noise(n, 101), 30)
    env = [min(1.0, i / 350) * (0.35 + 0.65 * min(1.0, i / (0.6 * RATE)))
           * (math.exp(-(i / RATE - 0.6) / 0.06) if i > 0.6 * RATE else 1.0) for i in range(n)]
    whine, ph = [], 0.0
    for i in range(n):
        ph += 1800 * 2.9 ** min(1.0, i / (0.6 * RATE)) / RATE
        whine.append(0.12 * math.sin(TAU * ph) * env[i])
    chans = []
    for c, det in ((0, 1.0), (1, 1.012)):
        p, raw = 0.0, []
        for i in range(n):
            dt = (75 + 95 * min(1.0, i / (0.6 * RATE)) ** 1.5) * det * (1 + 3 * jit[i]) / RATE
            p += dt
            if p >= 1:
                p -= 1
            raw.append(2 * p - 1 - blep(p, dt))
        bp = svf(raw, lambda t: 700 * 6.5 ** min(1.0, t / 0.6), 3.5, 'bp')
        chans.append([e * (0.8 * b + 0.25 * r) * (0.7 + 0.3 * math.sin(TAU * 100 * i / RATE)) + w
                      for i, (e, b, r, w) in enumerate(zip(env, bp, raw, whine))])
    crackle(chans, rnd, 0.0, 0.75, lambda t: 60 + 160 * min(1.0, t / 0.6) if t < 0.62 else 25,
            lambda t: 0.7 * (0.4 + min(1.0, t / 0.6)), hp=3000)
    m = S(0.015)                                                      # the overdrive "crack" at the top of the surge
    snap = svf([v * (1 - i / m) for i, v in enumerate(noise(m, 102))], 2500, 0.7, 'hp')
    for ch in chans:
        mix(ch, snap, 0.6, 0.8)
    return chans


def sfx_sparkle():
    T = 1.2
    n = S(T)
    rnd = random.Random(110)
    L, R = [0.0] * n, [0.0] * n
    scale = [2093.0, 2349.3, 2637.0, 3136.0, 3520.0, 4186.0, 4698.6, 5274.0, 6272.0]  # C major pentatonic, high
    for k in range(60):
        u = k / 60
        t0 = 0.95 * u ** 1.5 + rnd.uniform(0, 0.03)                   # dense at first, thinning out
        f = scale[min(8, max(0, int(u * 6 + rnd.uniform(-1.5, 2.5))))] * (1 + rnd.uniform(-0.003, 0.003))
        tau, amp = rnd.uniform(0.03, 0.1), rnd.uniform(0.35, 1.0) * (1 - 0.6 * u)
        m = S(min(T - t0, tau * 6))
        w = TAU * f / RATE
        ping = [amp * min(1.0, i / 44, (m - 1 - i) / 220) * math.exp(-i / (tau * RATE))
                * (math.sin(w * i) + 0.25 * math.sin(2.76 * w * i)) for i in range(m)]
        mix2(L, R, ping, t0, 1.0, rnd.uniform(-0.8, 0.8))
    bed = [0.22 * min(1.0, i / (0.03 * RATE)) * math.exp(-i / (0.35 * RATE)) * (0.6 + 0.4 * math.sin(TAU * 11 * i / RATE))
           * sum(math.sin(TAU * f * i / RATE) for f in (1046.5, 1318.5, 1568.0)) / 3 for i in range(n)]
    for ch, seed in ((L, 111), (R, 112)):
        air = svf(noise(n, seed), 8000, 0.7, 'hp')
        mix(ch, [b + 0.08 * a * min(1.0, i / (0.06 * RATE)) * math.exp(-i / (0.3 * RATE))
                 for i, (a, b) in enumerate(zip(air, bed))], 0)
    return [L, R]


def sfx_mew():
    """Cartoon kitten mew (after design/sfx/make_sfx.py): pitch glides up then down while brightness opens and closes."""
    dur, n = 0.36, S(0.4)
    out, ph = [0.0] * n, 0.0
    for i in range(S(dur)):
        t = i / RATE
        u = t / dur
        f = 780 + 640 * math.sin(math.pi * u ** 0.85) - 140 * u + 12 * math.sin(TAU * 9 * t) * u
        ph += f / RATE
        br = math.sin(math.pi * u) ** 0.7
        s = sum(w * math.sin(TAU * k * ph) for k, w in ((1, 1.0), (2, 0.55 * br), (3, 0.3 * br), (4, 0.12 * br)))
        out[i] = min(1.0, t / 0.03) * (1 - u) ** 1.4 * s / 1.6
    return [lp(out, 6000)]


def sfx_pop():
    n = S(0.25)
    out, ph = [], 0.0
    for i in range(n):
        t = i / RATE
        ph += (165 + 430 * math.exp(-t / 0.016)) / RATE
        out.append(math.sin(TAU * ph) * math.exp(-t / 0.05) * min(1.0, t / 0.0015)
                   + 0.45 * math.sin(TAU * 95 * t) * math.exp(-t / 0.07) * min(1.0, t / 0.003))
    mix(out, lp(noise(S(0.002), 120), 3000), 0, 0.15)
    return [out]


def sfx_ping():
    n = S(1.1)
    L, R = [0.0] * n, [0.0] * n
    for at, f0, amp in ((0.0, 1318.5, 0.55), (0.075, 1760.0, 1.0)):     # E6 grace, A6 strike
        for ch, det in ((L, 1.0035), (R, 0.9965)):
            parts = [(TAU * f0 * r / RATE, a, tau * RATE) for r, a, tau in
                     ((1, 1, 0.45), (det, 0.5, 0.4), (2, 0.2, 0.18), (2.76, 0.14, 0.1), (5.4, 0.06, 0.04))]
            mix(ch, [amp * min(1.0, i / 44) * sum(a * math.exp(-i / tau) * math.sin(w * i) for w, a, tau in parts)
                     for i in range(n - S(at))], at)
    return [L, R]


def sfx_tick():
    n = S(0.07)
    exc = [0.0] * n
    for at, amp, seed in ((0.0, 1.0, 130), (0.0085, 0.35, 131)):       # tooth + escapement
        mix(exc, noise(S(0.0005), seed), at, amp)
    return [[3 * a + 1.5 * b + 0.2 * c for a, b, c in zip(svf(exc, 3400, 7, 'bp'), svf(exc, 1250, 3, 'bp'), exc)]]


def sfx_burst():
    T = 1.5
    n = S(T)
    rnd = random.Random(140)
    nb = lp(noise(n, 141), lambda t: 500 + 3500 * math.exp(-t / 0.07), 2)
    boom, ph = [], 0.0
    for i in range(n):
        t = i / RATE
        ph += (48 + 70 * math.exp(-t / 0.05)) / RATE
        boom.append(0.8 * math.sin(TAU * ph) * math.exp(-t / 0.16) * min(1.0, t / 0.002)
                    + 1.2 * nb[i] * min(1.0, t / 0.002) * math.exp(-t / 0.06))
    chans = []
    for c in (0, 1):
        sizzle = svf(noise(n, 142 + c), 6500, 0.7, 'hp')
        chans.append([b + 0.12 * s * min(1.0, i / (0.25 * RATE)) * math.exp(-i / (0.45 * RATE))
                      for i, (b, s) in enumerate(zip(boom, sizzle))])
    crackle(chans, rnd, 0.1, 1.4, lambda t: 260 * math.exp(-((t - 0.35) / 0.3) ** 2) + 15,
            lambda t: 0.9 * max(0.0, 1 - (t - 0.1) / 1.4) + 0.1, hp=2500)
    for _ in range(25):                                                 # glittering stars
        t0, f, tau = rnd.uniform(0.12, 1.2), rnd.uniform(4000, 8000), rnd.uniform(0.006, 0.015)
        m = S(tau * 6)
        w = TAU * f / RATE
        mix2(*chans, [0.12 * min(1.0, i / 20, (m - 1 - i) / 60) * math.exp(-i / (tau * RATE)) * math.sin(w * i)
                      for i in range(m)], t0, 1.0, rnd.uniform(-0.9, 0.9))
    return chans


def make_sfx():
    save('whoosh.wav', sfx_whoosh(), 0.005, 0.02)
    save('click.wav', sfx_click(), 0.0005, 0.01)
    save('impact.wav', sfx_impact(), 0.0005, 0.3)
    save('zap.wav', sfx_zap(), 0.001, 0.02)
    save('sparkle.wav', sfx_sparkle(), 0.001, 0.1)
    save('mew.wav', sfx_mew(), 0.002, 0.02)
    save('pop.wav', sfx_pop(), 0.0005, 0.02)
    save('ping.wav', sfx_ping(), 0.0005, 0.1)
    save('tick.wav', sfx_tick(), 0.0002, 0.01)
    save('burst.wav', sfx_burst(), 0.0005, 0.2)
    save('riser.wav', list(riser(4.0, 150)), 0.01, 0.005)


if __name__ == '__main__':
    what = sys.argv[1:] or ['sfx', 'music']
    t = time.time()
    if 'sfx' in what:
        make_sfx()
    if 'music' in what:
        make_music()
    print(f'done in {time.time() - t:.1f} s')
