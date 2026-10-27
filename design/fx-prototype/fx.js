// FLASHOVER: fire merge FX for Neon Tally. A pure function of (t ms, tier).
// All coordinates are STAGE px (device x − 296). Everything draws with 'lighter' (Android PorterDuff ADD).
'use strict';
const FX = (() => {
  const W = 680;
  const HERO = [{ x: 24, y: 28, w: 150, h: 452 }, { x: 184, y: 28, w: 150, h: 452 }];
  const BX = [404, 455, 506, 557, 608], BW = 44, ADDS_Y = 56, DELS_Y = 190, BH = 118;
  const RAIL_Y = 494, LAND = 495;
  const DUR = { S: 800, M: 2500, L: 3500, XL: 4500 };
  const ROLL = { S: 0, M: 1900, L: 3040, XL: 3040 };

  // ---------------------------------------------------------------- seeded randomness (no Math.random)
  function rnd(s, i, k) {
    let h = Math.imul(s + 1, 0x27d4eb2d) ^ Math.imul(i + 1, 0x165667b1) ^ Math.imul(k + 7, 0x9e3779b1);
    h = Math.imul(h ^ (h >>> 15), 0x85ebca6b);
    h = Math.imul(h ^ (h >>> 13), 0xc2b2ae35);
    return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
  }
  const lerp = (a, b, u) => a + (b - a) * u;
  const clamp = (v, a = 0, b = 1) => Math.min(b, Math.max(a, v));
  const sstep = (a, b, x) => { const u = clamp((x - a) / (b - a)); return u * u * (3 - 2 * u); };
  const noise = (x, s) => { const i = Math.floor(x), f = x - i; return lerp(rnd(s, i, 99), rnd(s, i + 1, 99), f * f * (3 - 2 * f)); };
  const snoise = (x, s) => 2 * noise(x, s) - 1;
  const decay = (t, t0, tau) => (t < t0 ? 0 : Math.exp(-(t - t0) / tau));
  const bump = (t, t0, tau) => (t < t0 ? sstep(t0 - 65, t0, t) : Math.exp(-(t - t0) / tau)); // swell into t0, decay after

  // ---------------------------------------------------------------- sprites (bake once)
  const FIRE = [[0, '#FFF1E0'], [0.12, '#FFD9B0'], [0.3, '#FF8A2A'], [0.55, '#FF5200'], [0.8, '#A02600'], [1, '#300A00']];
  const ARGON = [[0, '#F7F3FF'], [0.15, '#ECE4FF'], [0.35, '#A98CFF'], [0.6, '#6A45FF'], [0.85, '#35208F'], [1, '#100828']];
  const rgb = (h) => { const n = parseInt(h.slice(1), 16); return [n >> 16, (n >> 8) & 255, n & 255]; };
  function rampAt(stops, u) {
    for (let i = 1; i < stops.length; i++) if (u <= stops[i][0]) {
      const [p0, c0] = stops[i - 1], [p1, c1] = stops[i], f = (u - p0) / (p1 - p0);
      return rgb(c0).map((v, k) => Math.round(lerp(v, rgb(c1)[k], f)));
    }
    return rgb(stops[stops.length - 1][1]);
  }
  const canvas = (w, h) => { const c = document.createElement('canvas'); c.width = w; c.height = h; return c; };
  // radial sprite; stops = [[pos, '#hex' | [r,g,b], alpha]]; sy stretches it vertically
  function radial(size, stops, sy = 1) {
    const c = canvas(size, Math.round(size * sy)), g = c.getContext('2d');
    g.scale(1, sy);
    const gr = g.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2);
    for (const [p, col, a] of stops) gr.addColorStop(p, `rgba(${typeof col === 'string' ? rgb(col) : col},${a})`);
    g.fillStyle = gr; g.fillRect(0, 0, size, size);
    return c;
  }
  const PUFF_A = [[0, 1], [0.3, 0.6], [0.6, 0.2], [1, 0]];
  const PUFF_SY = 1.5;
  const puffs = (stops) => Array.from({ length: 16 }, (_, k) => radial(64, PUFF_A.map(([p, a]) => [p, rampAt(stops, k / 15), a]), PUFF_SY));
  const FIREP = puffs(FIRE), ARGP = puffs(ARGON);
  const FLARE = radial(128, [[0, '#FFF6EC', 1], [0.08, '#FFD9B0', 1], [0.22, '#FF8A2A', 0.55], [0.5, '#FF5200', 0.18], [1, '#FF5200', 0]]);
  const AFLARE = radial(128, [[0, '#F7F3FF', 1], [0.08, '#ECE4FF', 1], [0.22, '#A98CFF', 0.55], [0.5, '#6A45FF', 0.2], [1, '#6A45FF', 0]]);
  const EMBER = radial(16, [[0, '#FFF1E0', 1], [0.18, '#FFD9B0', 1], [0.32, '#FF8A2A', 0.6], [0.6, '#FF5200', 0.18], [1, '#FF5200', 0]]);
  // Flame tongue, 48×160, base at the bottom: round base, pointed tip; colour runs hot core → red edges and tip.
  function tongueSprite(stops) {
    const w = 48, h = 160, c = canvas(w, h), g = c.getContext('2d'), im = g.createImageData(w, h);
    for (let y = 0; y < h; y++) {
      const v = 1 - (y + 0.5) / h, hw = 0.5 * w * 0.95 * Math.sqrt(clamp(v / 0.16)) * Math.pow(1 - v, 0.7);
      for (let x = 0; x < w; x++) {
        const d = hw > 0 ? Math.abs(x + 0.5 - w / 2) / hw : 2;
        if (d >= 1) continue;
        const a = (1 - d * d) * clamp(v / 0.05) * (1 - Math.pow(v, 3)), col = rampAt(stops, clamp(0.04 + 0.8 * v + 0.35 * d)), i = (y * w + x) * 4;
        im.data[i] = col[0]; im.data[i + 1] = col[1]; im.data[i + 2] = col[2]; im.data[i + 3] = Math.round(255 * a);
      }
    }
    g.putImageData(im, 0, 0);
    return c;
  }
  const TONGUE = tongueSprite(FIRE), ATONGUE = tongueSprite(ARGON);
  // burner jet, 24×48, bottom-anchored: a violet gas cone inside a soft orange tip
  const JET = (() => {
    const c = canvas(24, 48), g = c.getContext('2d');
    g.globalCompositeOperation = 'lighter';
    g.globalAlpha = 0.55; g.drawImage(TONGUE, 2, 0, 20, 46);
    g.globalAlpha = 1; g.drawImage(ATONGUE, 5, 20, 14, 28);
    return c;
  })();
  // linear strips: manifold glow under the rail, cooling fuse trail (bright at x=0), gas fill (bright at the bottom)
  function strip(w, h, stops, vertical = false) {
    const c = canvas(w, h), g = c.getContext('2d');
    const gm = vertical ? g.createLinearGradient(0, h, 0, 0) : g.createLinearGradient(0, 0, w, 0);
    for (const [p, col, a] of stops) gm.addColorStop(p, `rgba(${rgb(col)},${a})`);
    g.fillStyle = gm; g.fillRect(0, 0, w, h);
    if (vertical) return c;
    const gy = g.createLinearGradient(0, 0, 0, h);
    gy.addColorStop(0, 'rgba(0,0,0,0)'); gy.addColorStop(0.5, 'rgba(0,0,0,1)'); gy.addColorStop(1, 'rgba(0,0,0,0)');
    g.globalCompositeOperation = 'destination-in'; g.fillStyle = gy; g.fillRect(0, 0, w, h);
    return c;
  }
  const MANIFOLD = strip(64, 24, [[0, '#6A45FF', 0.45], [1, '#6A45FF', 0.45]]);
  const TRAIL = { neon: strip(128, 10, [[0, '#FFD9B0', 1], [0.2, '#FF8A2A', 0.6], [0.5, '#FF5200', 0.25], [1, '#FF5200', 0]]),
                  argon: strip(128, 10, [[0, '#ECE4FF', 1], [0.2, '#A98CFF', 0.6], [0.5, '#6A45FF', 0.25], [1, '#6A45FF', 0]]) };
  const GASFILL = strip(8, 256, [[0, '#FF8A2A', 0.7], [0.3, '#FF5200', 0.35], [1, '#FF5200', 0]], true);
  const SPARK_COL = { neon: ['#FFF1E0', '#FFD9B0', '#FF8A2A', '#FF5200', '#A02600'], argon: ['#F7F3FF', '#ECE4FF', '#A98CFF', '#6A45FF', '#35208F'] };

  // ---------------------------------------------------------------- draw primitives (counted)
  let draws = 0, AM = 1;
  function spr(ctx, img, x, y, w, h, a) {
    a *= AM; if (a <= 0.004 || w < 0.5) return;
    ctx.globalAlpha = Math.min(1, a); ctx.drawImage(img, x - w / 2, y - h / 2, w, h); draws++;
  }
  function tubePath(ctx, T) {
    const r = T.w / 2, b = T.y + T.h;
    ctx.beginPath(); ctx.moveTo(T.x, T.y + r); ctx.arc(T.x + r, T.y + r, r, Math.PI, 0);
    ctx.lineTo(T.x + T.w, b - 8); ctx.arcTo(T.x + T.w, b, T.x + T.w - 8, b, 8);
    ctx.lineTo(T.x + 8, b); ctx.arcTo(T.x, b, T.x, b - 8, 8); ctx.closePath();
  }

  // A licking flame tongue: the sprite bent along a travelling two-octave wave and flickering in height.
  // Android: ONE drawBitmapMesh(meshWidth 1, meshHeight 16); here emulated with horizontal slices.
  function tongue(ctx, img, bx, by, w, h, a, t, ph, sway = 1) {
    a *= AM; if (h < 2 || a <= 0.004) return;
    const N = 24, sh = img.height / N, tt = t / 1000; // Android: mesh rows, any N is one draw
    const hf = h * (0.78 + 0.4 * noise(tt * 7 + ph, 5));
    ctx.globalAlpha = Math.min(1, a);
    for (let s = 0; s < N; s++) {
      const v = (s + 0.5) / N;
      const off = sway * w * 0.5 * Math.pow(v, 1.3) * (snoise(tt * 2.6 - v * 1.3 + ph, 6) + 0.5 * snoise(tt * 6.5 - v * 3.1 + ph, 7));
      const y1 = by - (hf * (s + 1)) / N, y0 = by - (hf * s) / N;
      ctx.drawImage(img, 0, img.height - (s + 1) * sh, img.width, sh, bx - w / 2 + off, y1, w, y0 - y1);
    }
    draws++;
  }
  // a tongue pointing along angle `rot` (0 = up, π/2 = right) from (x, y)
  function tongueRot(ctx, img, x, y, rot, w, h, a, t, ph) {
    ctx.save(); ctx.translate(x, y); ctx.rotate(rot); tongue(ctx, img, 0, 0, w, h, a, t, ph); ctx.restore();
  }

  // Flame that hit the ceiling: tongues hang from y≈8 and lick outward (dir ±1), igniting as the front passes.
  function ceilingFire(ctx, t, x0, dir, reach, t0, speed, tOut, seed) {
    const front = clamp(((t - t0) * speed) / reach), out = 1 - sstep(tOut, tOut + 160, t);
    if (front * out > 0.01) spr(ctx, FLARE, x0 + (dir * reach * front) / 2, 4, reach * front + 60, 70, 0.45 * out); // the hot gas layer
    for (let i = 0; i * 26 <= reach; i++) {
      const x = x0 + dir * i * 26, ti = t0 + (i * 26) / speed, e = sstep(ti, ti + 60, t) * out;
      if (e > 0.01) tongueRot(ctx, TONGUE, x, 4 + 8 * rnd(seed, i, 1), dir * (Math.PI / 2 + 0.3 + 0.4 * rnd(seed, i, 2)),
        38 + 22 * rnd(seed, i, 3), (100 + 110 * rnd(seed, i, 0)) * e, 0.45, t, seed + i * 2.9);
    }
  }

  // Detached flamelets. Puffs emitted at `rate`/s over [t0,t1); env(tb) thins the count and scales height/size.
  function flames(ctx, t, P) {
    const slot = 1000 / P.rate, n = Math.floor((P.t1 - P.t0) / slot), S = P.seed;
    for (let i = 0; i < n; i++) {
      const tb = P.t0 + (i + rnd(S, i, 0)) * slot;
      if (tb > t) break;
      const e = P.env(tb);
      if (e <= 0.01 || rnd(S, i, 1) > e * 2) continue;
      const k = Math.min(e, P.emax || 3);
      const L = lerp(P.life[0], P.life[1], rnd(S, i, 2)) * (0.45 + 0.55 * k);
      const a = (t - tb) / 1000; if (a >= L) continue;
      const u = a / L, sp = P.spawn(rnd(S, i, 3), rnd(S, i, 4), tb, i);
      const v0 = lerp(P.v0[0], P.v0[1], rnd(S, i, 5)) * (0.5 + 0.5 * k);
      const x = sp[0] + (sp[2] || 0) * a + (P.wob || 0) * u * Math.sin(6.283 * P.wobF * (0.7 + 0.6 * rnd(S, i, 7)) * a + 6.283 * rnd(S, i, 8));
      const s = lerp(P.size[0], P.size[1], rnd(S, i, 9)) * (0.6 + 0.4 * Math.min(k, 1.5)) * (1 - 0.6 * u + (P.grow || 0) * u);
      const al = P.alpha * Math.min(1, u * 6) * Math.pow(1 - u, 1.2);
      spr(ctx, P.ramp[Math.min(15, Math.floor(u * 16))], x, sp[1] - v0 * a - 0.5 * P.acc * a * a, s, s * PUFF_SY, al);
    }
  }

  // Ballistic sparks with linear drag; drawn as streaks batched into 5 colour buckets (Android: drawLines).
  function sparks(ctx, t, P) {
    const S = P.seed, segs = [[], [], [], [], []];
    for (let i = 0; i < P.n; i++) {
      const tb = P.tb(i, rnd(S, i, 0));
      const a = (t - tb) / 1000; if (a < 0) continue;
      const L = lerp(P.life[0], P.life[1], rnd(S, i, 1)); if (a >= L) continue;
      const [x0, y0] = P.spawn(rnd(S, i, 2), rnd(S, i, 3), tb, i);
      const ang = (lerp(P.ang[0], P.ang[1], rnd(S, i, 4)) * Math.PI) / 180, spd = lerp(P.spd[0], P.spd[1], rnd(S, i, 5));
      const vx = Math.cos(ang) * spd, vy = Math.sin(ang) * spd, g = P.g, tau = P.tau;
      const pos = (aa) => { const d = tau * (1 - Math.exp(-aa / tau)); return [x0 + vx * d, y0 + g * tau * aa + (vy - g * tau) * d]; };
      segs[Math.min(4, Math.floor((a / L) * 5))].push(pos(Math.max(0, a - P.streak / 1000)), pos(a));
    }
    const col = SPARK_COL[P.gas || 'neon'];
    ctx.lineCap = 'round';
    for (let b = 0; b < 5; b++) {
      const s = segs[b]; if (!s.length) continue;
      ctx.beginPath();
      for (let j = 0; j < s.length; j += 2) { ctx.moveTo(s[j][0], s[j][1]); ctx.lineTo(s[j + 1][0] + 0.01, s[j + 1][1]); }
      const fade = 1 - b * 0.17;
      ctx.strokeStyle = col[3]; ctx.globalAlpha = 0.3 * fade * AM; ctx.lineWidth = P.w * 3.5; ctx.stroke();
      ctx.strokeStyle = col[b]; ctx.globalAlpha = fade * AM; ctx.lineWidth = P.w * (1 - b * 0.12); ctx.stroke();
      draws += 2;
    }
  }

  // Burner rail: violet gas jets along y=RAIL_Y, lit from the camera island edge (x=0) out to x1,
  // then shut off right→left so the last jet dies back into the island.
  function rail(ctx, t, R) {
    let lit = 0;
    for (let j = 0; ; j++) {
      const x = 6 + 11 * j + (rnd(80, j, 0) - 0.5) * 4; if (x > R.x1) break;
      const f = x / R.x1, d = t - (R.ign0 + f * R.ignDur);
      if (d < 0) break;
      const pop = d < 40 ? (1.6 * d) / 40 : 1 + 0.6 * Math.exp(-(d - 40) / 70);
      const I = pop * clamp(1 - (t - (R.off0 + (1 - f) * R.offDur)) / 90) * R.boost(t);
      if (I <= 0.01) continue;
      lit = x;
      const fl = 0.5 + 0.7 * noise(t / 30 + j * 1.7, 11), h = 32 * I * fl * (0.7 + 0.6 * rnd(80, j, 1));
      spr(ctx, JET, x, RAIL_Y + 3 - h / 2, 18 * (0.8 + 0.2 * Math.min(I, 1.5)), h, Math.min(1, 0.8 * I));
    }
    if (lit) spr(ctx, MANIFOLD, lit / 2, RAIL_Y + 1, lit + 8, 20, 0.5 * clamp(R.boost(t)) * clamp((t - R.ign0) / 60));
    const fp = (t - R.ign0) / R.ignDur; // ignition front: a white-violet striker racing right
    if (fp >= 0 && fp <= 1.15) spr(ctx, AFLARE, fp * R.x1, RAIL_Y - 4, 54, 54, 0.95 * (1 - Math.max(0, fp - 1) / 0.15));
  }

  // The camera island's right edge (x=0) is the igniter: a violet seam flash + a striker spark at the rail.
  function strike(ctx, t, k) {
    if (t > 400) return;
    const a = t < 15 ? t / 15 : Math.exp(-(t - 15) / 70);
    spr(ctx, AFLARE, 0, 298, 44, 640, 0.6 * a * k);
    spr(ctx, AFLARE, 0, RAIL_Y - 4, 110 * k, 110 * k, a);
    spr(ctx, FLARE, 2, RAIL_Y - 4, 30, 30, a);
  }

  // Fire inside a hero tube (clipped to the glass): gas-fill glow, a wide back tongue, 5 licking tongues, flamelets.
  const TW = [0.36, 0.44, 0.5, 0.44, 0.36], TH = [0.62, 0.9, 1, 0.82, 0.58];
  function tubeFire(ctx, t, T, seed, env, H0, rate, t1) {
    const e = env(t);
    ctx.save(); tubePath(ctx, T); ctx.clip();
    if (e > 0.01) {
      const fh = T.h * Math.min(1, 0.12 + 0.3 * e), H = H0 * e, by = T.y + T.h + 6;
      spr(ctx, GASFILL, T.x + T.w / 2, T.y + T.h - fh / 2, T.w, fh, 0.3 * Math.min(e, 2));
      tongue(ctx, TONGUE, T.x + T.w / 2, by, T.w * 1.1, H * 1.15, 0.28, t, seed * 10 + 9, 0.6);
      for (let i = 0; i < 5; i++)
        tongue(ctx, TONGUE, T.x + T.w * (0.2 + 0.15 * i) + 4 * snoise(t / 400 + i, seed), by, T.w * TW[i], H * TH[i], 0.55, t, seed * 10 + i * 3.7);
    }
    flames(ctx, t, { seed, t0: 100, t1, rate, env, emax: 2.8,
      spawn: (r1, r2) => [T.x + T.w * (0.2 + 0.6 * r1), T.y + T.h - 10 - 30 * r2],
      life: [0.3, 0.5], v0: [150, 260], acc: 300, size: [14, 26], wob: 10, wobF: 2.5, alpha: 0.45, ramp: FIREP });
    ctx.restore();
  }
  // "Catching fire": tongues ignite along the landed digit's top edges, spreading left→right 14 ms apart,
  // plus a few flamelets flying off. anchors = top-edge points of the glyph (stage px), sorted by x.
  function digitFire(ctx, t, D) {
    const { anchors: an, ink, t0, tau, hd, wd, img, seed } = D;
    for (let j = 0; j < an.length; j++) {
      const tj = t0 + 14 * j, e = sstep(tj, tj + 90, t) * (t < tj + 90 ? 1 : Math.exp(-(t - tj - 90) / tau));
      if (e > 0.01) tongue(ctx, img, an[j][0], an[j][1] + 2, wd, hd * e * (0.65 + 0.5 * rnd(seed, j, 1)), 0.7, t, seed + j * 5.1, 0.8);
    }
    flames(ctx, t, { seed, t0, t1: t0 + 3 * tau, rate: D.rate, env: (tb) => decay(tb, t0, tau),
      spawn: (r1, r2) => { const p = ink[Math.floor(r1 * ink.length)]; return [p[0] + (r2 - 0.5) * 3, p[1]]; },
      life: [0.2, 0.4], v0: [60, 120], acc: 380, size: [wd * 0.5, wd * 0.9], wob: 4, wobF: 4, alpha: 0.5, ramp: img === TONGUE ? FIREP : ARGP });
  }
  function emberSpit(ctx, t, x, y, spread, n, t0, seed) {
    spr(ctx, FLARE, x, y + 15, 130, 110, 0.8 * sstep(t0 - 20, t0, t) * decay(t, t0, 80));
    sparks(ctx, t, { seed, n, tb: (i, r) => t0 + r * r * 160, spawn: (r1, r2) => [x + (r1 - 0.5) * spread, y + r2 * 30],
      ang: [-90 - 42, -90 + 42], spd: [380, 820], g: 1100, tau: 0.7, life: [0.5, 1.0], w: 1.7, streak: 40 });
  }

  // Fuse: a burning head runs right→left under a bank, passing each tube 240 ms before its roll-up starts.
  function fuse(ctx, t, roll, y, gas, seed, k) {
    const v = 51 / 110, tc0 = roll - 240;
    const tA = tc0 - (664 - 630) / v, tB = tc0 + (630 - 404) / v;
    if (t < tA || t > tB + 400) return;
    const xh = 630 - (Math.min(t, tB) - tc0) * v, on = t <= tB;
    const tl = Math.min(664 - xh, 116), fade = 1 - clamp((t - tB) / 120);
    if (tl > 1) spr(ctx, TRAIL[gas], xh + tl / 2, y, tl, 6, 0.85 * fade);
    if (on) {
      const fl = 0.75 + 0.5 * noise(t / 25, seed);
      spr(ctx, gas === 'neon' ? FLARE : AFLARE, xh, y, 48 * fl * k, 48 * fl * k, 0.95);
      tongue(ctx, gas === 'neon' ? TONGUE : ATONGUE, xh, y + 2, 12, 26 * k, 0.9, t, seed);
    }
    for (let q = 0; q < 5; q++) { // tube kick: a flame licks up into the tube's base as the head passes it
      const tk = tc0 + 110 * q, e = sstep(tk, tk + 40, t) * (1 - sstep(tk + 40, tk + 220, t));
      if (t >= tk && e > 0.01) tongue(ctx, gas === 'neon' ? TONGUE : ATONGUE, BX[4 - q] + BW / 2, y - 6, 30 * k, 56 * k * e, 0.8, t, seed + q * 3);
    }
    const rate = 280 * k;
    sparks(ctx, t, { seed, gas, n: Math.floor(((tB - tA) / 1000) * rate), tb: (i, r) => tA + (i + r) * (1000 / rate),
      spawn: (r1, r2, tb) => [630 - (tb - tc0) * v, y], ang: [-90 - 80, -90 + 80], spd: [140, 420],
      g: 1400, tau: 0.5, life: [0.08, 0.2], w: 1.5, streak: 22 });
  }

  // ---------------------------------------------------------------- the moment
  // ink: { hero|adds|dels: [{ ink, anchors } | null] }; changed = index of the hero tube whose digit changed.
  function draw(ctx, t, tier, night, ink, changed = 1) {
    draws = 0; AM = night ? 0.4 : 1;
    ctx.save(); ctx.globalCompositeOperation = 'lighter';
    const T = HERO[changed], other = HERO[1 - changed];
    const big = tier === 'L' || tier === 'XL', xl = tier === 'XL';
    const hc = ink.hero[changed], ho = ink.hero[1 - changed];

    if (tier === 'S' || night) { // a whisper: the landed digit flickers alight for a moment and sheds a few embers
      const land = tier === 'S' ? 315 : LAND;
      digitFire(ctx, t, { ...hc, t0: land, tau: 110, hd: 26, wd: 12, img: TONGUE, seed: 31, rate: 30 });
      sparks(ctx, t, { seed: 32, n: 7, tb: (i, r) => land + r * 120, spawn: (r1) => hc.anchors[Math.floor(r1 * hc.anchors.length)],
        ang: [-110, -70], spd: [70, 160], g: 120, tau: 0.6, life: [0.25, 0.32], w: 1.6, streak: 30 });
      ctx.restore(); return draws;
    }

    // XL flashover covers the instrument 480–1000; the fire underneath is thinned (saves draws)
    const cover = (tt) => (xl ? sstep(480, 510, tt) * (1 - sstep(780, 1000, tt)) : 0);

    // heat light: the whole room glows from below while the fire is big
    const heat = xl ? 0.15 * sstep(380, 495, t) * (1 - sstep(700, 1600, t)) + 0.04 * sstep(1200, 1600, t) * (1 - sstep(2700, 3100, t))
      : tier === 'L' ? 0.07 * sstep(300, 495, t) * (1 - sstep(900, 1800, t)) : 0;
    if (heat > 0.004) {
      const g = ctx.createLinearGradient(0, 596, 0, 260);
      g.addColorStop(0, `rgba(255,82,0,${heat * AM})`); g.addColorStop(1, 'rgba(255,82,0,0)');
      ctx.globalAlpha = 1; ctx.fillStyle = g; ctx.fillRect(0, 260, W, 336); draws++;
    }

    strike(ctx, t, big ? 1.3 : 1);
    rail(ctx, t, { x1: big ? 664 : 334, ign0: 30, ignDur: big ? 240 : 140, off0: { M: 1350, L: 3020, XL: 3350 }[tier], offDur: 260,
      boost: (tt) => (xl ? 1 + 1.4 * bump(tt, LAND, 300) : 1) * (1 - 0.35 * sstep(1500, 2200, tt)) });

    // fire inside the hero tubes
    const rise = (tb) => sstep(120, 330, tb);
    const envStrong = { M: (tb) => rise(tb) * (1 + 0.8 * bump(tb, LAND, 220)) * (1 - sstep(800, 1500, tb)),
      L: (tb) => rise(tb) * (1.1 + 1.5 * bump(tb, LAND, 280)) * (1 - 0.72 * sstep(800, 1600, tb)) * (1 - sstep(2650, 3050, tb)),
      XL: (tb) => rise(tb) * (1.2 + 1.6 * bump(tb, LAND, 300)) * (1 - 0.6 * cover(tb)) * (1 - 0.72 * sstep(900, 1700, tb)) * (1 - sstep(2900, 3300, tb)) }[tier];
    const envWeak = tier === 'M' ? (tb) => 0.55 * rise(tb) * (1 - sstep(700, 1300, tb)) : xl ? envStrong
      : (tb) => rise(tb) * (0.8 + 0.6 * bump(tb, LAND, 280)) * (1 - 0.72 * sstep(800, 1600, tb)) * (1 - sstep(2650, 3050, tb));
    tubeFire(ctx, t, T, 1, envStrong, 160, 60, big ? 3300 : 1500);
    tubeFire(ctx, t, other, 2, envWeak, 160, tier === 'M' ? 30 : 50, big ? 3300 : 1300);

    // the changed digits catch fire as they land (XL: once the flashover recedes, the digits are left burning)
    const df = xl ? 760 : LAND, m = big ? 1.25 : 1;
    digitFire(ctx, t, { ...hc, t0: df, tau: big ? 520 : 400, hd: 52 * m, wd: 20 * m, img: TONGUE, seed: 3, rate: 50 });
    if (big) digitFire(ctx, t, { ...ho, t0: df + 60, tau: 320, hd: 40, wd: 18, img: TONGUE, seed: 4, rate: 25 });
    for (let q = 0; q < 5; q++) {
      const ka = xl ? 760 : 390 + 30 * (4 - q), kd = xl ? 790 : 450 + 30 * (4 - q);
      if (ink.adds[q]) digitFire(ctx, t, { ...ink.adds[q], t0: ka, tau: 340, hd: 24 * m, wd: 11 * m, img: TONGUE, seed: 10 + q, rate: 12 });
      if (ink.dels[q]) digitFire(ctx, t, { ...ink.dels[q], t0: kd, tau: 340, hd: 24 * m, wd: 11 * m, img: ATONGUE, seed: 20 + q, rate: 12 });
    }

    // embers spat out of the changed tube's dome
    if (!xl) emberSpit(ctx, t, T.x + T.w / 2, 60, 70, big ? 50 : 30, LAND, 40); // XL: the flashover eruption replaces it
    if (tier === 'L') emberSpit(ctx, t, other.x + other.w / 2, 60, 70, 22, LAND + 60, 41);

    if (tier === 'L') { // overload: the tube vents through its dome and the flame spreads both ways along the ceiling
      const hx = T.x + T.w / 2, e = sstep(450, 520, t) * (1 - sstep(580, 880, t));
      if (e > 0.01) {
        tongue(ctx, TONGUE, hx, 70, 90, 120 * e, 0.6, t, 53);
      }
      ceilingFire(ctx, t, hx, 1, 220, 470, 0.9, 640, 51);
      ceilingFire(ctx, t, hx, -1, 140, 470, 0.9, 620, 52);
      flames(ctx, t, { seed: 50, t0: 440, t1: 820, rate: 60, env: (tb) => sstep(440, 495, tb) * (1 - sstep(560, 820, tb)),
        spawn: (r1, r2, tb, i) => [hx + (r1 - 0.5) * 40, 8 + 26 * r2, (rnd(50, i, 77) < 0.5 ? -1 : 1) * lerp(250, 750, r1)],
        v0: [-20, 30], acc: 0, life: [0.22, 0.4], size: [30, 56], grow: 0.8, wob: 8, wobF: 2, alpha: 0.4, ramp: FIREP });
    }

    if (xl) {
      // rollover: both tubes vent and flame races along the ceiling to the right edge…
      ceilingFire(ctx, t, 60, 1, 640, 340, 4.2, 560, 60);
      ceilingFire(ctx, t, 260, -1, 240, 360, 2.4, 540, 66);
      // …then FLASHOVER at the mercury flash: a wall of tongues from the rail, its front sweeping out of the camera edge
      for (let i = 0; i < 18; i++) {
        const back = i >= 11, bx = back ? (i - 11 + 0.5) * (W / 7) : (i + 0.5) * (W / 11) + 10 * snoise(i, 64);
        const ti = 470 + 90 * (bx / W), e = sstep(ti, ti + 60, t) * (1 - sstep(700, 1350, t));
        if (e > 0.01) tongue(ctx, TONGUE, bx, RAIL_Y + 12, back ? 190 : 120, (back ? 700 : 560) * e * (0.8 + 0.35 * rnd(65, i, 0)), back ? 0.35 : 0.5, t, 70 + i * 1.9, 0.8);
      }
      flames(ctx, t, { seed: 61, t0: 470, t1: 1300, rate: 110, emax: 1.3,
        env: (tb) => sstep(470, 495, tb) * (1 - sstep(700, 1250, tb)) * 1.3,
        spawn: (r1, r2, tb) => [r1 * W * sstep(470, 560, tb), RAIL_Y - 120 * r2],
        v0: [700, 1100], acc: 300, life: [0.3, 0.5], size: [50, 90], grow: 0.6, wob: 20, wobF: 1.5, alpha: 0.3, ramp: FIREP });
      const fl = sstep(LAND - 25, LAND, t) * decay(t, LAND, 120); // fireball out of the changed digit (mercury flash)
      spr(ctx, FLARE, T.x + T.w / 2, T.y + T.h * 0.5, 820, 820, 0.75 * fl);
      sparks(ctx, t, { seed: 62, n: 90, tb: (i, r) => LAND + r * 140, spawn: (r1, r2) => [r1 * W, RAIL_Y - r2 * 10],
        ang: [-118, -62], spd: [550, 1050], g: 1100, tau: 0.8, life: [0.7, 1.3], w: 2, streak: 34 });
      // ember fall after the flashover
      for (let i = 0; i < 60; i++) {
        const tb = 850 + rnd(63, i, 0) * 1100, a = (t - tb) / 1000, L = lerp(1.0, 1.8, rnd(63, i, 1));
        if (a < 0 || a >= L) continue;
        const vt = lerp(80, 180, rnd(63, i, 2)), d = 0.35 * (1 - Math.exp(-a / 0.35));
        const x = rnd(63, i, 3) * W + 10 * Math.sin(6.283 * 0.9 * a + 6.283 * rnd(63, i, 4));
        const y = -10 - 30 * rnd(63, i, 5) + vt * (a - d);
        const pulse = 0.7 + 0.3 * Math.sin(6.283 * 5 * a + 6.283 * rnd(63, i, 6)), u = a / L, s = lerp(8, 14, rnd(63, i, 7));
        spr(ctx, EMBER, x, y, s, s, pulse * Math.min(1, a / 0.08) * (u < 0.65 ? 1 : 1 - (u - 0.65) / 0.35));
      }
    }

    // roll-up: fuses burn along under both banks, lighting each tube just before it rolls
    const fk = tier === 'M' ? 0.8 : 1;
    fuse(ctx, t, ROLL[tier], 182, 'neon', 70, fk);
    fuse(ctx, t, ROLL[tier], 316, 'argon', 71, fk);

    ctx.restore();
    return draws;
  }

  return { draw, DUR, HERO, BX, BW, ADDS_Y, DELS_Y, BH };
})();
