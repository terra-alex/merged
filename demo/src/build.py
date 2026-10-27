"""Assemble the Merged demo: phone rear-screen takes cut to a 120 BPM timeline inside the rear-module mockup,
with push-ins, a drop punch, bloom, grain, vignette, title/captions/end card, then the soundtrack + SFX mix.

python3 build.py video   -> out/video.mp4 (silent)
python3 build.py audio   -> out/mix.wav
python3 build.py final   -> out/merged-demo.mp4 (+ out/merged-demo-share.mp4, smaller)
"""
import json
import os
import subprocess
import sys

D = os.path.dirname(os.path.abspath(__file__))
RAW, GFX, AUD, OUT = (os.path.join(D, x) for x in ('raw', 'gfx', 'audio', 'out'))
os.makedirs(OUT, exist_ok=True)
FPS, T = 60, 68.0
SCREEN = (1440, 879)            # 976x596 x1.4754 — module.json window
WIN = json.load(open(os.path.join(GFX, 'module.json')))['window']
CX, CY = 1030, 468              # where the module group is centred (captions live bottom-left)

# (output start, duration, take, source in-point). None take = screen off (black).
SEGS = [
    (0.0, 2.0, None, 0),
    (2.0, 10.0, 'intro', 2.267),     # Today strikes in at 2.0
    (12.0, 9.0, 'merge-xl', 1.0),    # cats from 12.0, fire strike 16.0, flashover ~16.5
    (21.0, 4.0, 'recent', 1.7),      # detail card at 23.0
    (25.0, 3.5, 'goal', 0.733),      # surge ~26.0
    (28.5, 6.5, 'badges', 0.733),    # unlocks ~28.65 and ~31.95
    (35.0, 6.0, 'cats', 2.033),      # flight from 36.0
    (41.0, 4.0, 'open', 0.733),      # green jewel strikes ~41.2
    (45.0, 5.5, 'recap', 0.733),
    (50.5, 6.5, 'pet', 1.2),         # cat appears ~51.0, firework ~54.5
    (57.0, 0.5, 'cats', 4.0),        # montage, on the half-beats
    (57.5, 0.5, 'pet', 5.3),
    (58.0, 0.5, 'merge-xl', 5.55),
    (58.5, 0.5, 'badges', 1.25),
    (59.0, 2.0, 'merge-m', 2.95),    # an M merge's fire at ~59.15
    (61.0, 7.0, 'merge-m', 6.0),     # under the end card
]
CAPS = [(2, 8.2, 3.4), (3, 12.3, 3.5), (4, 21.2, 3.4), (5, 25.2, 3.0), (6, 28.7, 3.0),
        (7, 35.2, 3.4), (8, 41.2, 3.4), (9, 45.2, 3.4), (10, 50.7, 3.3)]
# group scale keyframes (t, s); a repeated t is a cut
ZOOM = [(0, .80), (4, .83), (8, .83), (12, .89), (16.0, .92), (16.09, .965), (16.8, .92), (21.0, .89),
        (21.0, .86), (25.0, .89), (25.0, .86), (28.5, .88), (28.5, .86), (35.0, .89), (35.0, .86), (41.0, .89),
        (41.0, .86), (45.0, .88), (45.0, .86), (50.5, .89), (50.5, .86), (57.0, .90), (57.0, .95), (59.0, .95),
        (59.0, .92), (61.0, .96), (68.0, .96)]


def zoom(t):
    """The group's scale at time t: linear between ZOOM keyframes (a repeated time is a cut)."""
    for (t0, v0), (t1, v1) in zip(ZOOM, ZOOM[1:]):
        if t < t1:
            return v0 if t1 == t0 else v0 + (v1 - v0) * (t - t0) / (t1 - t0)
    return ZOOM[-1][1]


_bg = None


def _place(job):
    """One frame: the module group (RGBA) scaled about its centre by zoom(t) and put at (CX, CY) over the
    background, sub-pixel exact. ffmpeg's scale/overlay/perspective snap size and position to whole pixels, and
    PIL's affine transform freezes for a frame now and then while scaling — both read as the mockup shaking.
    resize() with a fractional source box is continuous (and antialiased)."""
    global _bg
    from PIL import Image
    if _bg is None:
        _bg = Image.open(f'{GFX}/bg.png').convert('RGBA')
    k, raw = job
    s = zoom(k / FPS)
    pad = Image.new('RGBA', (1920 + 2 * PAD, 1080 + 2 * PAD))  # the source box runs past the frame when s < 1
    pad.paste(Image.frombytes('RGBA', (1920, 1080), raw), (PAD, PAD))
    box = (-CX / s + 960 + PAD, -CY / s + 540 + PAD, (1920 - CX) / s + 960 + PAD, (1080 - CY) / s + 540 + PAD)
    g = pad.resize((1920, 1080), Image.BICUBIC, box=box)
    return Image.alpha_composite(_bg, g).convert('RGB').tobytes()


PAD = 360


def video():
    import multiprocessing as mp
    # Stage A: the takes cut to the timeline, under the module, as raw RGBA frames.
    a, fa, n = ['ffmpeg', '-v', 'error'], [], 0

    def inp(lst, *x):
        nonlocal n
        lst.extend(x); n += 1
        return n - 1

    imod = inp(a, '-loop', '1', '-framerate', str(FPS), '-t', str(T), '-i', f'{GFX}/module.png')
    segs = []
    for k, (t0, dur, take, src) in enumerate(SEGS):
        if take is None:
            fa.append(f'color=c=black:s={SCREEN[0]}x{SCREEN[1]}:r={FPS}:d={dur},format=rgba[s{k}]')
        else:
            i = inp(a, '-ss', str(src), '-t', str(dur + 0.5), '-i', f'{RAW}/{take}.mp4')
            fa.append(f'[{i}:v]fps={FPS},scale={SCREEN[0]}:{SCREEN[1]}:flags=lanczos,format=rgba,'
                      f'tpad=stop_mode=clone:stop_duration={dur},trim=duration={dur},setpts=PTS-STARTPTS[s{k}]')
        segs.append(f'[s{k}]')
    fa.append(f'{"".join(segs)}concat=n={len(segs)}:v=1:a=0,format=rgba[screen]')
    fa.append(f'color=c=black@0:s=1920x1080:r={FPS}:d={T},format=rgba[clear]')
    fa.append(f'[clear][screen]overlay={WIN["x"]}:{WIN["y"]}:shortest=1[g0]')
    fa.append(f'[g0][{imod}:v]overlay=0:0:shortest=1,trim=duration={T}[g]')
    with open(f'{OUT}/stageA.fc', 'w') as f:
        f.write(';\n'.join(fa))
    a += ['-filter_complex_script', f'{OUT}/stageA.fc', '-map', '[g]', '-f', 'rawvideo', '-pix_fmt', 'rgba', '-']

    # Stage C: bloom, title, captions, end card, grade, encode — fed the placed frames.
    c, fc, n = ['ffmpeg', '-y', '-v', 'error', '-stats', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', '1920x1080',
                '-r', str(FPS), '-i', '-'], [], 1
    fc.append('[0:v]format=gbrp,split[a][b]')  # bloom: neon spills a little past the glass
    fc.append('[b]scale=480:270,gblur=sigma=7,scale=1920:1080[blur]')
    fc.append('[a][blur]blend=all_mode=screen:all_opacity=0.55,format=rgba[lit]')
    cur = 'lit'
    fc.append(f'color=c=black:s=1920x1080:r={FPS}:d=4.4,format=rgba,colorchannelmixer=aa=0.62,'
              f'fade=in:st=0:d=0.5:alpha=1,fade=out:st=3.7:d=0.6:alpha=1,setpts=PTS+3.8/TB[dim]')
    fc.append(f'[{cur}][dim]overlay=eof_action=pass[c0]'); cur = 'c0'
    it = inp(c, '-loop', '1', '-framerate', str(FPS), '-t', '4.2', '-i', f'{GFX}/title.png')
    fc.append(f'[{it}:v]format=rgba,fade=in:st=0:d=0.6:alpha=1,fade=out:st=3.6:d=0.6:alpha=1,setpts=PTS+4.0/TB[ttl]')
    fc.append(f'[{cur}][ttl]overlay=eof_action=pass[c1]'); cur = 'c1'
    for row, t0, d in CAPS:
        ic = inp(c, '-loop', '1', '-framerate', str(FPS), '-t', str(d), '-i', f'{GFX}/cap-{row:02d}-crop.png')
        fc.append(f'[{ic}:v]format=rgba,fade=in:st=0:d=0.35:alpha=1,fade=out:st={d - 0.4}:d=0.4:alpha=1,setpts=PTS+{t0}/TB[cap{row}]')
        fc.append(f'[{cur}][cap{row}]overlay=0:787:eof_action=pass[k{row}]'); cur = f'k{row}'
    ie = inp(c, '-loop', '1', '-framerate', str(FPS), '-t', '7', '-i', f'{GFX}/end.png')
    fc.append(f'[{ie}:v]format=rgba,fade=in:st=0:d=0.8:alpha=1,setpts=PTS+61.0/TB[end]')
    fc.append(f'[{cur}][end]overlay=eof_action=pass[e]')
    fc.append(f'[e]format=yuv420p,vignette=angle=PI/4.6,noise=alls=3:allf=t,'
              f'fade=in:st=0:d=1.2,fade=out:st=67.0:d=1.0,trim=duration={T}[v]')
    with open(f'{OUT}/stageC.fc', 'w') as f:
        f.write(';\n'.join(fc))
    c += ['-filter_complex_script', f'{OUT}/stageC.fc', '-map', '[v]', '-r', str(FPS), '-c:v', 'libx264',
          '-preset', 'slow', '-crf', '19', '-pix_fmt', 'yuv420p', f'{OUT}/video.mp4']

    # Stage B in between: place each frame (in batches, so memory stays bounded).
    pa = subprocess.Popen(a, stdout=subprocess.PIPE)
    pc = subprocess.Popen(c, stdin=subprocess.PIPE)
    size, k = 1920 * 1080 * 4, 0
    with mp.Pool(max(2, (os.cpu_count() or 4) - 2)) as pool:
        while True:
            batch = []
            for _ in range(48):
                raw = pa.stdout.read(size)
                if len(raw) < size:
                    break
                batch.append((k, raw)); k += 1
            for frame in pool.map(_place, batch):
                pc.stdin.write(frame)
            if len(batch) < 48:
                break
    pc.stdin.close()
    assert pa.wait() == 0 and pc.wait() == 0, 'render failed'
    print('frames', k)


# SFX cue sheet: (time s, file, gain dB). The app's own merge chimes are used where the app plays them.
RAW_RES = os.path.join(D, '..', '..', 'android', 'app', 'src', 'main', 'res', 'raw')
CHIME_BIG = os.path.join(RAW_RES, 'merge_big.wav')
CHIME_SMALL = os.path.join(RAW_RES, 'merge_small.wav')
CUES = [
    (1.9, 'click', -8), (12.0, CHIME_BIG, -4), (13.0, 'whoosh', -14), (14.3, 'whoosh', -15),
    (16.0, 'impact', -2), (21.0, 'whoosh', -12), (23.0, 'click', -8), (25.0, 'whoosh', -12), (26.0, 'zap', -8),
    (28.5, 'whoosh', -13), (28.65, 'sparkle', -8), (31.95, 'sparkle', -8), (35.0, 'whoosh', -13),
    (36.0, 'mew', -6), (39.7, 'pop', -9), (40.0, 'pop', -11), (41.0, 'whoosh', -13), (41.2, 'ping', -7),
    (45.0, 'whoosh', -13), (45.4, 'tick', -8), (45.75, 'tick', -8), (46.6, 'sparkle', -14),
    (50.5, 'whoosh', -13), (51.0, 'mew', -8), (52.4, 'whoosh', -10), (54.5, 'burst', -5),
    (57.0, 'tick', -10), (57.5, 'tick', -10), (58.0, 'tick', -10), (58.5, 'tick', -10),
    (59.0, CHIME_SMALL, -6), (59.15, 'impact', -9), (61.0, CHIME_BIG, -5), (61.1, 'sparkle', -10),
]


def audio():
    args, fc, labels = ['ffmpeg', '-y', '-v', 'error', '-i', f'{AUD}/music.wav'], [], []
    for k, (t, name, gain) in enumerate(CUES):
        path = name if name.startswith('/') else f'{AUD}/{name}.wav'
        args += ['-i', path]
        ms = int(t * 1000)
        fc.append(f'[{k + 1}:a]aformat=sample_rates=44100:channel_layouts=stereo,volume={gain}dB,adelay={ms}|{ms}[x{k}]')
        labels.append(f'[x{k}]')
    fc.append(f'{"".join(labels)}amix=inputs={len(labels)}:normalize=0[sfx]')
    # the music ducks a little under the effects
    fc.append('[0:a]aformat=sample_rates=44100:channel_layouts=stereo[mus]')
    fc.append(f'[sfx]apad=whole_dur={T}[sfxp]')  # the sidechain ends with its shortest input
    fc.append('[sfxp]asplit[sfx1][key]')
    fc.append('[mus][key]sidechaincompress=threshold=0.05:ratio=3:attack=5:release=250[ducked]')
    fc.append(f'[ducked][sfx1]amix=inputs=2:normalize=0,atrim=0:{T},afade=t=out:st={T - 1.5}:d=1.5[a]')
    subprocess.run(args + ['-filter_complex', ';'.join(fc), '-map', '[a]', '-ar', '48000', f'{OUT}/premix.wav'], check=True)
    # two-pass linear loudness normalisation: one gain for the whole film, so the intro stays quiet and the drop big
    m = subprocess.run(['ffmpeg', '-hide_banner', '-i', f'{OUT}/premix.wav', '-af', 'loudnorm=I=-14:TP=-1:LRA=20:print_format=json',
                        '-f', 'null', '-'], capture_output=True, text=True).stderr
    j = json.loads(m[m.rindex('{'):m.rindex('}') + 1])
    ln = (f"loudnorm=I=-14:TP=-1:LRA=20:linear=true:measured_I={j['input_i']}:measured_TP={j['input_tp']}:"
          f"measured_LRA={j['input_lra']}:measured_thresh={j['input_thresh']}:offset={j['target_offset']}")
    subprocess.run(['ffmpeg', '-y', '-v', 'error', '-i', f'{OUT}/premix.wav', '-af', ln, '-ar', '48000', f'{OUT}/mix.wav'], check=True)


def final():
    subprocess.run(['ffmpeg', '-y', '-v', 'error', '-i', f'{OUT}/video.mp4', '-i', f'{OUT}/mix.wav', '-map', '0:v', '-map', '1:a',
                    '-c:v', 'copy', '-c:a', 'aac', '-b:a', '256k', '-movflags', '+faststart', '-shortest',
                    f'{OUT}/merged-demo.mp4'], check=True)
    # a lighter copy for chat apps: 1080p30, ≤ 3 Mbps
    subprocess.run(['ffmpeg', '-y', '-v', 'error', '-i', f'{OUT}/merged-demo.mp4', '-r', '30', '-c:v', 'libx264', '-preset', 'slow',
                    '-crf', '23', '-maxrate', '3M', '-bufsize', '6M', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '192k',
                    '-movflags', '+faststart', f'{OUT}/merged-demo-share.mp4'], check=True)


if __name__ == '__main__':
    {'video': video, 'audio': audio, 'final': final}[sys.argv[1]]()
