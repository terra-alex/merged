"""Synthesises Merged's merge chimes (original sounds, no samples) into android/app/src/main/res/raw/.

merge_small.wav: S/M merges — two quick rising chiptune notes with a soft tail.
merge_big.wav:   L/XL merges — a sparkly C-major arpeggio, then a little synth "mew".
mew.wav:         a poked cat / the flyby launch — the same "mew" alone (MeterView plays it faster for small PRs).
sparkle.wav:     a badge unlock — a short sparkle shimmer over a soft C7.

Run: python3 design/sfx/make_sfx.py (stdlib only).
"""
import math
import os
import random
import struct
import wave

RATE = 44100
OUT = os.path.join(os.path.dirname(__file__), '..', '..', 'android', 'app', 'src', 'main', 'res', 'raw')


def note(buf, t0, f, dur, amp, vib=0.0):
    """Soft chiptune voice: triangle + a little 25% pulse, 3 ms attack, exponential decay, optional vibrato."""
    phase = 0.0
    for i in range(int(dur * RATE)):
        t = i / RATE
        ff = f * (1 + vib * math.sin(2 * math.pi * 6 * t) * min(1, t / 0.08))
        phase = (phase + ff / RATE) % 1
        tri = 4 * abs(phase - 0.5) - 1
        pulse = 1.0 if phase < 0.25 else -0.33
        env = min(1, t / 0.003) * math.exp(-t / (dur * 0.35))
        add(buf, t0, i, amp * env * (0.8 * tri + 0.2 * pulse))


def mew(buf, t0, amp):
    """A cartoon kitten "mew": pitch glides up then down while the brightness opens and closes (m-e-w)."""
    dur = 0.34
    phase = 0.0
    for i in range(int(dur * RATE)):
        t = i / RATE
        u = t / dur
        f = 820 + 620 * math.sin(math.pi * min(1, u * 1.25)) - 120 * u
        phase += f / RATE
        bright = math.sin(math.pi * u) ** 0.7
        s = sum(w * math.sin(2 * math.pi * k * phase) for k, w in ((1, 1.0), (2, 0.55 * bright), (3, 0.3 * bright), (4, 0.12 * bright)))
        env = min(1, t / 0.03) * (1 - u) ** 1.4
        add(buf, t0, i, amp * env * s / 1.6)


def sparkle(buf, t0, n, amp, seed):
    rnd = random.Random(seed)
    for k in range(n):
        f = rnd.uniform(2600, 4200)
        at = t0 + k * rnd.uniform(0.035, 0.06)
        for i in range(int(0.09 * RATE)):
            t = i / RATE
            add(buf, at, i, amp * math.exp(-t / 0.025) * math.sin(2 * math.pi * f * t))


def add(buf, t0, i, v):
    """Sample i of a sound starting at t0 s. Index from a rounded start: int((t0 + i/RATE) * RATE) skips/doubles samples (clicks)."""
    j = round(t0 * RATE) + i
    if 0 <= j < len(buf):
        buf[j] += v


def write(name, buf):
    lp = 0.0
    out = bytearray()
    peak = max(1e-9, max(abs(v) for v in buf))
    for i, v in enumerate(buf):
        lp += 0.55 * (v / peak - lp)  # gentle low-pass: rounds off the pulse's edges
        fade = min(1, (len(buf) - i) / (0.02 * RATE))
        out += struct.pack('<h', int(0.8 * 32767 * lp * fade))
    os.makedirs(OUT, exist_ok=True)
    with wave.open(os.path.join(OUT, name), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(bytes(out))


small = [0.0] * int(0.45 * RATE)
note(small, 0.000, 1046.5, 0.09, 0.8)            # C6
note(small, 0.065, 1568.0, 0.36, 0.9, vib=0.006)  # G6, rings
sparkle(small, 0.08, 3, 0.12, 1)
write('merge_small.wav', small)

big = [0.0] * int(1.05 * RATE)
for k, f in enumerate((1046.5, 1318.5, 1568.0, 2093.0)):  # C6 E6 G6 C7
    note(big, 0.055 * k, f, 0.12 if k < 3 else 0.32, 0.8, vib=0.006 if k == 3 else 0.0)
sparkle(big, 0.2, 6, 0.14, 2)
mew(big, 0.52, 0.85)
write('merge_big.wav', big)

solo = [0.0] * int(0.38 * RATE)
mew(solo, 0.0, 0.85)
write('mew.wav', solo)

sting = [0.0] * int(0.6 * RATE)
note(sting, 0.0, 2093.0, 0.42, 0.5, vib=0.006)  # C7
sparkle(sting, 0.02, 7, 0.16, 3)
write('sparkle.wav', sting)
print('wrote', os.path.abspath(OUT))
