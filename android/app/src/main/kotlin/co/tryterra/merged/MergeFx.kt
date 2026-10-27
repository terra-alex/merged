package co.tryterra.merged

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.time.LocalDate
import java.time.Month
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Merge celebration drawn over the instrument (design: merged/design/FX.md, merged/design/UX-PASS.md).
 *
 * 1. **Cats**: pixel pop-tart cats (own artwork, a Nyan Cat homage) burst out from under
 *    the camera island and zoom all over the stage, rainbow trails behind, butts on fire. The PR's
 *    own cat (M and up) dives back under the island; the others burst like firework shells. A tap
 *    pops one early ([pop]).
 * 2. **Flashover**: as the last cat leaves, the island strikes, a burner rail lights, the
 *    hero tubes fill with flame and the changed digits catch fire (XL: a wall of fire).
 * 3. **Colony** (the CATS screen): a static pile of sleeping cats, one per PR this week. New ones fly in
 *    from under the island; a poked one hops and sits up; a rubbed one sends up hearts; the newest wake
 *    and fly a lap, or chase a laser dot, and land back on their spots.
 *
 * Every element is a pure function of t (ms since its start) with hash-seeded randomness: no per-frame
 * state, and frames dropped while dozing need no catch-up. The exceptions follow a finger (the pet cat,
 * the laser chasers) and integrate per frame. Fire is additive light (ADD); the cats and rainbows are
 * opaque. Stage px, inside MeterView's transform.
 */
class MergeFx(private val art: NixieArt) {

    /** A tube the fire can use: stage position, class, and the digit it ends up showing. */
    class Spot(val x: Float, val y: Float, val cls: Cls, val ch: Char?)

    private class Ink(val anchors: FloatArray, val pts: FloatArray) // tube-local (x, y) pairs
    private class Digit(val x: Float, val y: Float, val ink: Ink, val t0: Float, val tau: Float,
                        val hd: Float, val wd: Float, val argon: Boolean, val seed: Int, val rate: Float)

    private var fireAt = NEVER
    private var catsAt = NEVER
    private var tier = 0
    private var night = false
    private var hero: List<Spot> = emptyList()
    private var strong = 0
    private val heroClip = ArrayList<Path>()
    private val digits = ArrayList<Digit>()
    private val cats = ArrayList<Cat>()

    /** Everything off (a moment cut short, the screen changed, the rear dozing). */
    fun clear() {
        fireAt = NEVER; catsAt = NEVER; cats.clear()
        flyers.clear(); flyAt = NEVER; stirs.clear(); hearts.clear(); pokeIdx = -1; rubIdx = -1
        laserOn = false; chasers.clear()
        rockets.clear(); rocketAt = NEVER; peekAt = NEVER
        petHeld = false; flingAt = NEVER
    }

    fun active(now: Long) = (now >= catsAt && now - catsAt < catsEnd) || petActive(now) || colonyActive(now) ||
        (now >= rocketAt && now - rocketAt < ROCKET_MS + 700) || (now >= peekAt && now - peekAt < peekDur) ||
        (now >= fireAt && now - fireAt < if (night) 1000 else FIRE_END[tier])

    // ================================================================= cats

    /**
     * [v]: coat (see [coat]). [end]: what happens when its time is up — BURST like a firework shell, HOME onto its spot
     * (boomX, boomY) with a puff, DIVE back under the camera island, or EXIT with a puff at (boomX, boomY). [calm]: a
     * colony flight — rainbow only, half the trail, no fire. [idx]: its colony spot. [arrive]: the spot stays empty
     * until it lands. [cut]: popped at this local time (ms); [gold]: the last pop of a clean sweep.
     */
    private class Cat(val pts: FloatArray, val cum: FloatArray, val ts: Float, val dur: Float, val scale: Float, val seed: Int,
                      var enterT: Float = -1f, var enterY: Float = 0f, var boomX: Float = 0f, var boomY: Float = 0f, val v: Int = 0,
                      val end: Int = BURST, val calm: Boolean = false, val idx: Int = -1, val arrive: Boolean = false) {
        var cut = Float.NaN
        var gold = false
        var exitT = -1f
        var exitY = 0f
        fun endT() = if (cut.isNaN()) dur else cut
        /** When it's gone from view (list time): a dive slips under the island at exitT, before its path ends off-stage. */
        fun goneT() = if (end == DIVE && cut.isNaN() && exitT >= 0) exitT else ts + endT()
    }
    private var catsEnd = 0L

    /**
     * Launch the cat flyby at [at] in coat [v] (the PR's; the others vary); returns how long until the fire should
     * start. Longer than [maxLead] and every cat speeds up to fit (no loop-the-loops when squeezed).
     */
    fun cats(at: Long, tier: Int, v: Int = 0, maxLead: Long = Long.MAX_VALUE): Long {
        val n = intArrayOf(1, 2, 3, 5)[tier]
        val roam = floatArrayOf(1800f, 2400f, 2800f, 3300f)[tier]
        val seed = (at % 100_000).toInt()
        catsAt = at
        var k = 1f
        while (true) {
            cats.clear()
            var ts = 0f
            for (i in 0 until n) {
                val s = seed + 97 * i
                val dur = k * roam * (.85f + .3f * rnd(s, 0, 5))
                val cv = if (i == 0 || v == PUMPKIN) v else (v + i) % 4
                val cat = route(s, ts, dur, 1.2f + .4f * rnd(s, 0, 6), -120f, 80f + 440f * rnd(s, 0, 1),
                    v = cv, end = if (i == 0 && tier > S) DIVE else BURST, loops = k > .7f)
                var u = 0f // where it pops out of the camera edge
                while (u < 1f) { catPos(cat, u); if (px >= 0f) { cat.enterT = cat.ts + u * cat.dur; cat.enterY = py; break }; u += .002f }
                if (cat.end == DIVE) { // and where it slips back under it
                    u = 1f
                    while (u > 0f) { catPos(cat, u); if (px >= 0f) { cat.exitT = cat.ts + u * cat.dur; cat.exitY = py; break }; u -= .002f }
                } else { catPos(cat, 1f); cat.boomX = px; cat.boomY = py }
                fly(cv, 0); fly(cv, 1) // bake here, not in onDraw
                cats += cat
                ts += k * (180f + 120f * rnd(s, 0, 6))
            }
            val lead = cats.maxOf { it.goneT().toLong() } + 250 // the island strikes as the last cat bursts or dives
            if (lead <= maxLead || k < .25f) {
                catsEnd = cats.maxOf { (it.ts + it.dur).toLong() } + 1600 // the last firework falls and fades
                return lead
            }
            k *= min(.95f, (maxLead - 250f) / (lead - 250f))
        }
    }

    /**
     * A tap at (x, y) on a flyby cat (pose now or 50 ms ago, within 90 px) pops it: it bursts there and then. The last of a
     * clean sweep (2+ cats, every one popped) goes off as a big gold shell. True if one popped.
     */
    fun pop(x: Float, y: Float, now: Long): Boolean {
        if (now < catsAt || now - catsAt >= catsEnd) return false
        val t = (now - catsAt).toFloat()
        var best: Cat? = null
        var bd = 90f
        for (c in cats) {
            val lt = t - c.ts
            if (lt < 0 || lt >= c.endT() || !c.cut.isNaN() || (c.enterT >= 0 && t < c.enterT) || (c.exitT >= 0 && t > c.exitT)) continue
            for (back in floatArrayOf(0f, 50f)) {
                pose(c, max(0f, lt - back))
                val d = hypot(px - x, py - y)
                if (d < bd) { bd = d; best = c }
            }
        }
        val c = best ?: return false
        c.cut = t - c.ts
        pose(c, c.cut); c.boomX = px; c.boomY = py
        if (cats.size >= 2 && cats.all { !it.cut.isNaN() }) { c.gold = true; fly(GOLD, 0); fly(GOLD, 1) } // bake here, not in onDraw
        return true
    }

    /** When the island should strike (uptime): 250 ms after the last flyby cat bursts or dives — sooner once they're popped. */
    fun strikeAt(): Long = catsAt + (cats.maxOfOrNull { it.goneT().toLong() } ?: 0L) + 250

    /**
     * A random roam from (x0, y0) (the flyby: under the camera island), through a waypoint every ~⅓ s
     * anywhere on the stage (sometimes a loop-the-loop), ending where it bursts like a firework shell, diving
     * back under the island — or, given a home (x1, y1), hopping up out of its spot first and dropping down onto
     * the home last. Each leg gets a random share of the time, so cats surge and dawdle.
     */
    private fun route(s: Int, ts: Float, dur: Float, sc: Float, x0: Float, y0: Float, x1: Float = Float.NaN, y1: Float = 0f,
                      v: Int = 0, end: Int = if (x1.isNaN()) BURST else HOME, calm: Boolean = false, idx: Int = -1,
                      loops: Boolean = true, arrive: Boolean = false): Cat {
        val pts = ArrayList<Float>()
        fun pt(x: Float, y: Float) { pts += x; pts += y }
        val home = end == HOME
        pt(x0, y0)
        var x = x0; var y = y0
        if (home && x0 >= 0f) { y = max(40f, y0 - 60f - 30f * rnd(s, 0, 2)); pt(x, y) }
        for (k in 1..(dur / 330).toInt()) {
            if (loops && k > 1 && rnd(s, k, 7) < .22f && x in 140f..540f && y in 150f..450f) {
                val r = 55f + 40f * rnd(s, k, 8)
                val cw = if (rnd(s, k, 9) < .5f) 1f else -1f
                for (m in 1..4) { // circle a centre above the last waypoint and come back through it
                    val a = (PI / 2 + cw * m * PI / 2).toFloat()
                    pt(x + r * kotlin.math.cos(a), y - r + r * sin(a))
                }
                continue
            }
            var nx = 0f; var ny = 0f
            for (tries in 0 until 6) { // keep legs long so they zoom
                nx = 30f + 620f * rnd(s, k, 20 + tries); ny = 40f + 516f * rnd(s, k, 30 + tries)
                if (hypot(nx - x, ny - y) > 170f) break
            }
            pt(nx, ny); x = nx; y = ny
        }
        when (end) {
            HOME -> { pt(x1, max(40f, y1 - 80f)); pt(x1, y1) }
            DIVE -> pt(-170f, 80f + 440f * rnd(s, 99, 50))
            else -> {
                var bx = 0f; var by = 0f
                for (tries in 0 until 6) { // burst point: well inside the stage, a long leg away
                    bx = 130f + 420f * rnd(s, 99, 3 + tries); by = 100f + 340f * rnd(s, 99, 13 + tries)
                    if (hypot(bx - x, by - y) > 170f) break
                }
                pt(bx, by)
            }
        }
        return cat(pts.toFloatArray(), s, ts, dur, sc, x1, y1, v, end, calm, idx, arrive, home)
    }

    /** A cat along waypoints [p]; each leg gets a random share of the time ([slow]: a gentle hop out and a slow settle). */
    private fun cat(p: FloatArray, s: Int, ts: Float, dur: Float, sc: Float, x1: Float, y1: Float, v: Int, end: Int,
                    calm: Boolean, idx: Int, arrive: Boolean, slow: Boolean): Cat {
        val legs = p.size / 2 - 1
        val cum = FloatArray(legs + 1)
        for (j in 0 until legs) cum[j + 1] = cum[j] + hypot(p[2 * j + 2] - p[2 * j], p[2 * j + 3] - p[2 * j + 1]) * (.6f + .9f * rnd(s, j, 11)) *
            (if (slow && (j == 0 || j == legs - 1)) 2.5f else 1f)
        for (j in 1..legs) cum[j] /= cum[legs]
        return Cat(p, cum, ts, dur, sc, s, boomX = x1, boomY = y1, v = v, end = end, calm = calm, idx = idx, arrive = arrive)
    }

    private var px = 0f
    private var py = 0f

    /** Catmull-Rom through the cat's waypoints at u∈[0,1] (time-warped per leg) → (px, py). */
    private fun catPos(c: Cat, u: Float) {
        val n = c.pts.size / 2
        val uu = u.coerceIn(0f, 1f)
        var seg = 0
        while (seg < n - 2 && c.cum[seg + 1] <= uu) seg++
        val s = ((uu - c.cum[seg]) / max(1e-6f, c.cum[seg + 1] - c.cum[seg])).coerceIn(0f, 1f)
        fun p(i: Int, k: Int) = c.pts[2 * i.coerceIn(0, n - 1) + k]
        for (k in 0..1) {
            val p0 = p(seg - 1, k); val p1 = p(seg, k); val p2 = p(seg + 1, k); val p3 = p(seg + 2, k)
            val v = 0.5f * (2 * p1 + (p2 - p0) * s + (2 * p0 - 5 * p1 + 4 * p2 - p3) * s * s + (3 * p1 - p0 - 3 * p2 + p3) * s * s * s)
            if (k == 0) px = v else py = v
        }
    }

    private var dirX = 1f
    private var dirY = 0f
    private var bx = 0f
    private var by = 0f

    /** Cat pose at its local time [lt]: centre (px,py), unit heading (dirX,dirY), butt (bx,by). */
    private fun pose(c: Cat, lt: Float) {
        val u = lt / c.dur
        val du = 12f / c.dur // ±12 ms for the heading
        catPos(c, u + du); val x2 = px; val y2 = py
        catPos(c, u - du); val x1 = px; val y1 = py
        val l = max(1e-3f, hypot(x2 - x1, y2 - y1))
        dirX = (x2 - x1) / l; dirY = (y2 - y1) / l
        catPos(c, u)
        val back = 34f * c.scale
        bx = px - dirX * back; by = py - dirY * back + 4f * c.scale
    }

    /** [alpha]: the screen's dim (colony flights); a ghost cat flies at .55 of it. */
    private fun drawCats(c: Canvas, list: List<Cat>, t: Float, alpha: Float = 1f) {
        am = 1f; paint = overPaint
        for (q in 0 until list.size) {
            val cat = list[q]
            val lt = t - cat.ts
            val endT = cat.endT()
            val fireT = cat.goneT() - cat.ts // a dive's fire goes out as it slips under the island
            if (lt < 0 || lt > endT + 1600) continue
            trail(c, cat, lt)
            if (cat.enterT >= 0) { // burst out of the camera island
                val d = t - cat.enterT
                if (d >= -30 && d < 400) spr(c, aflare, 0f, cat.enterY, 160f, 160f, if (d < 0) (d + 30) / 30 else exp(-d / 120))
            }
            if (cat.exitT >= 0 && cat.cut.isNaN()) { // …and back into it
                val d = t - cat.exitT
                if (d >= -30 && d < 400) spr(c, aflare, 0f, cat.exitY, 140f, 140f, if (d < 0) (d + 30) / 30 else exp(-d / 120))
            }
            val s = cat.seed
            if (!cat.calm) { // the butt on fire: puffs left hanging in the air behind it, burning drips, and the jet itself
                flames(c, t, s + 1, cat.ts, cat.ts + fireT, if (cat.end == HOME) 55f else 90f, 1f, .25f, .45f, 30f, 80f, 220f, 16f * cat.scale, 30f * cat.scale,
                    0f, 6f, 3f, .55f, firep, { 1f }) { _, r2, tb, _ -> pose(cat, tb - cat.ts); sx = bx; sy = by + (r2 - .5f) * 10f; svx = 0f }
                val drips = (fireT / if (cat.end == HOME) 90 else 50).toInt()
                sparks(c, t, s + 2, drips, false, 40f, 140f, 60f, 220f, 900f, .8f, .35f, .7f, 1.6f, 26f,
                    { i, r -> cat.ts + (i + r) * fireT / drips }) { _, _, tb -> pose(cat, tb - cat.ts); sx = bx; sy = by }
            }
            if (lt > endT) {
                if (!cat.cut.isNaN() || cat.end == BURST) burst(c, cat, t)
                else if (cat.end == HOME || cat.end == EXIT) puff(c, cat, t)
                continue
            }
            pose(cat, lt)
            val ghost = colony.getOrNull(cat.idx)?.ghost == true
            drawCat(c, px, py, dirX, dirY, cat.scale, t, s, lt, cat.v, !cat.calm && lt < fireT,
                (255 * alpha.coerceIn(0f, 1f) * if (ghost) .55f else 1f).toInt())
        }
    }

    /** One cat centred at (x, y) heading (dx, dy): the burning butt ([fire]), then the cat facing where it's going. */
    private fun drawCat(c: Canvas, x: Float, y: Float, dx: Float, dy: Float, sc: Float, t: Float, seed: Int, lt: Float, v: Int = 0,
                        fire: Boolean = true, alpha: Int = 255) {
        if (fire) {
            val bx = x - dx * 34f * sc; val by = y - dy * 34f * sc + 4f * sc
            c.save(); c.translate(bx, by); c.rotate(Math.toDegrees(atan2(-dx, dy).toDouble()).toFloat()) // tongue (drawn up) points back
            tongue(c, tongueN, 0f, 8f, 60f * sc, 150f * sc, .95f, t, seed * 0.37f, .7f)
            tongue(c, tongueN, 0f, 6f, 28f * sc, 80f * sc, 1f, t, seed * 0.53f + 3f, .5f)
            c.restore()
        }
        val face = if (dx >= 0) 1f else -1f
        val tilt = Math.toDegrees(atan2(dy, abs(dx)).toDouble()).toFloat().coerceIn(-60f, 60f)
        val frame = fly(v, if ((lt / 70).toInt() % 2 == 0) 0 else 1)
        catPaint.alpha = alpha
        c.save(); c.translate(x, y); c.rotate(tilt * face); c.scale(face * sc, sc)
        c.drawBitmap(frame, -frame.width / 2f, -frame.height / 2f, catPaint)
        c.restore()
        catPaint.alpha = 255
    }

    // ================================================================= pet cat (drag on the rear screen)

    private var pet: Cat? = null
    private var petAt = NEVER
    private var petHeld = false
    private var petHome = false
    private var petX = 0f
    private var petY = 0f
    private var petDX = 1f
    private var petDY = 0f
    private var petTX = 0f
    private var petTY = 0f
    private var petLast = 0L
    private var flingAt = NEVER
    private var flingX = 0f
    private var flingY = 0f
    private var flingVX = 0f
    private var flingVY = 0f
    private var boomAt = NEVER
    private val hist = FloatArray(2 * (TRAIL + 4)) // butt positions, newest first, ~14 ms apart
    private var histN = 0
    private var histAt = 0L

    private fun petActive(now: Long) = petHeld || (flingAt != NEVER && now - boomAt < if (petHome) 400 else 1600)

    /** A finger rested, then dragged: a cat flies out from under the camera to it. Stage px. */
    fun petDown(x: Float, y: Float, now: Long) {
        if (petHeld) return
        pet = Cat(FloatArray(0), FloatArray(0), 0f, 0f, 1.35f, (now % 100_000).toInt())
        petAt = now; petHeld = true; petHome = false; flingAt = NEVER; boomAt = NEVER
        petX = -90f; petY = y.coerceIn(60f, 536f); petDX = 1f; petDY = 0f
        petTX = x; petTY = y; petLast = now; histN = 0
    }

    fun petMove(x: Float, y: Float) { petTX = x; petTY = y }

    /**
     * Let go. Slower than 700 px/s it's being set down: it flies home under the camera island (false). Faster, it
     * carries the finger's velocity (stage px/s) and bursts like the flyby cats (true).
     */
    fun petFling(vx: Float, vy: Float, now: Long): Boolean {
        if (!petHeld) return false
        petHeld = false
        flingAt = now; flingX = petX; flingY = petY
        val sp = hypot(vx, vy)
        petHome = sp < 700f
        if (petHome) { // ease in towards the island: x = x0 − 300a − 1400a²
            val a = (-300f + sqrt(300f * 300f + 4 * 1400f * (petX + 140f).coerceAtLeast(0f))) / (2 * 1400f)
            boomAt = now + (a * 1000).toLong()
            return false
        }
        val k = min(sp, 2400f) / sp
        flingVX = vx * k; flingVY = vy * k
        var a = 0f // burst after 0.7 s, or as it reaches the edge of the stage
        while (a < .7f) { flungPos(a); if (px !in 30f..650f || py !in 30f..566f) break; a += .01f }
        flungPos(a)
        boomAt = now + (a * 1000).toLong()
        pet?.boomX = px.coerceIn(30f, 650f); pet?.boomY = py.coerceIn(30f, 566f)
        return true
    }

    private fun flungPos(a: Float) {
        if (petHome) { px = flingX - 300f * a - 1400f * a * a; py = flingY - 40f * sin(min(1f, a / .5f) * PI.toFloat()); return }
        val d = .5f * (1 - exp(-a / .5f)); px = flingX + flingVX * d; py = flingY + flingVY * d
    }

    private fun drawPet(c: Canvas, now: Long) {
        val cat = pet ?: return
        am = 1f; paint = overPaint
        val t = (now - petAt).toFloat()
        val dt = (now - petLast).coerceIn(0, 50).toFloat(); petLast = now
        if (petHeld || now < boomAt) {
            val ox = petX; val oy = petY
            if (petHeld) { val k = 1 - exp(-dt / 70f); petX += (petTX - petX) * k; petY += (petTY - petY) * k } // chase the finger
            else { flungPos((now - flingAt) / 1000f); petX = px; petY = py }
            val mx = petX - ox; val my = petY - oy; val m = hypot(mx, my)
            if (m > .5f) { petDX = mx / m; petDY = my / m } // hovering keeps the last heading
            if (histN == 0 || now - histAt >= 14) {
                System.arraycopy(hist, 0, hist, 2, hist.size - 2)
                hist[0] = petX - petDX * 34f * cat.scale; hist[1] = petY - petDY * 34f * cat.scale + 4f * cat.scale
                histN = min(histN + 1, hist.size / 2); histAt = now
            }
            rainbowFromHist(c, hist, histN, cat.scale, t, 1f)
            drawCat(c, petX, petY, petDX, petDY, cat.scale, t, cat.seed, t)
        } else if (petHome) { // under the island: its trail fades, the island edge flares once
            rainbowFromHist(c, hist, histN, cat.scale, t, max(0f, 1 - (now - boomAt) / 300f))
            spr(c, aflare, 0f, petY, 140f, 140f, exp(-(now - boomAt) / 120f))
        } else {
            rainbowFromHist(c, hist, histN, cat.scale, t, max(0f, 1 - (now - boomAt) / 300f))
            burst(c, cat, (now - boomAt).toFloat())
        }
    }

    private fun rainbowFromHist(c: Canvas, h: FloatArray, hn: Int, sc: Float, t: Float, fade: Float) {
        var n = 0
        for (k in 4 until hn) { tx[n] = h[2 * k]; ty[n] = h[2 * k + 1]; n++ } // skip the newest: the flame (or the cat) is there
        rainbow(c, n, sc, (t / 90).toInt(), fade)
    }

    // ================================================================= colony (the CATS screen)

    /**
     * A sleeping cat: centre (x, y), sprite scale, facing left if [flip], [variant] = coat (see [coat]), with a "z" if
     * [zzz]; [pr] is the PR it stands for; a [ghost] draws at α .55.
     */
    class Sleeper(val x: Float, val y: Float, val scale: Float, val flip: Boolean, val variant: Int, val seed: Int, val zzz: Boolean,
                  val pr: Pr? = null, val ghost: Boolean = false)

    private class Stir(val idx: Int, val pose: Int, val start: Long, val end: Long)

    private var colony: List<Sleeper> = emptyList()
    private val flyers = ArrayList<Cat>() // wake-all laps, arrivals, laser cats flying home; ts relative to flyAt
    private var flyAt = NEVER
    private val stirs = ArrayList<Stir>()
    private val hearts = ArrayList<LongArray>() // (colony index, born)
    private var pokeIdx = -1
    private var pokeAt = NEVER
    private var pokeHop = false
    private var rubIdx = -1

    private fun flying(now: Long) = flyers.any { now - flyAt <= it.ts + it.endT() + 600 }

    /** Cats in the air, or a laser chase: hit-tests on the pile would be against empty spots. */
    fun busy(now: Long) = flying(now) || laserOn

    private fun colonyActive(now: Long) = flying(now) || laserOn || hearts.any { now - it[1] < HEART_MS } ||
        (pokeIdx >= 0 && pokeHop && now - pokeAt < HOP_MS)

    /** Set the colony (the CATS screen opened or its data changed). Cancels flights, stirs and pokes. */
    fun colony(cats: List<Sleeper>) {
        colony = cats; flyers.clear(); flyAt = NEVER; stirs.clear(); hearts.clear(); pokeIdx = -1; rubIdx = -1
        laserOn = false; chasers.clear()
        for (c in cats) for (p in 0..2) pose(p, c.variant) // bake here, not in onDraw
        pose(SIT, 0); pose(SIT, PUMPKIN) // the shelf's kittens
    }

    /** The colony index whose centre is nearest (x, y), within [r] stage px; -1 if none (or that cat is away). */
    fun nearest(x: Float, y: Float, r: Float, now: Long): Int {
        var best = -1
        var bd = r
        for (i in colony.indices) {
            if (away(i, now)) continue
            val d = hypot(colony[i].x - x, colony[i].y - y)
            if (d <= bd) { bd = d; best = i }
        }
        return best
    }

    fun sleeper(i: Int): Sleeper? = colony.getOrNull(i)

    /** Not on its spot: flying (an arrival's spot stays empty until it lands), or chasing the laser. */
    private fun away(i: Int, now: Long): Boolean {
        if (chasers.any { it.idx == i }) return true
        val t = (now - flyAt).toFloat()
        return flyers.any { it.idx == i && t <= it.ts + it.endT() && (t >= it.ts || it.arrive) }
    }

    private fun startFlights(now: Long) { if (!flying(now)) { flyers.clear(); flyAt = now } }

    /**
     * The newest [max] cats (end of the list) wake 0–300 ms apart, fly a calm lap (rainbow only) for 1.2–1.6 s and drop
     * back onto their spots. Returns ms from now until the first lands (for one LAND buzz); 0 if a flight is running.
     */
    fun wake(now: Long, max: Int = 6): Long {
        if (flying(now) || laserOn) return 0
        startFlights(now)
        val run = (now % 100_000).toInt() // a new flight every time
        for (i in (colony.size - max).coerceAtLeast(0) until colony.size) {
            val h = colony[i]
            val s = h.seed + 97 * run
            flyers += route(s, 300f * rnd(s, 0, 5), 1200f + 400f * rnd(s, 0, 6), h.scale, h.x, h.y, h.x, h.y, h.variant,
                calm = true, idx = i, loops = false)
            fly(h.variant, 0); fly(h.variant, 1) // bake here, not in onDraw
        }
        return flyers.minOfOrNull { (it.ts + it.dur).toLong() } ?: 0
    }

    /**
     * New cats come home (UX-PASS #3/#4): each flies in from (fromX, fromY) — under the island, or off the open-PR shelf —
     * 250 ms apart, does a short calm lap and curls up on its spot. Returns ms until the first lands.
     */
    fun arrive(from: List<Triple<Int, Float, Float>>, now: Long): Long {
        if (from.isEmpty()) return 0
        startFlights(now)
        val t0 = (now - flyAt).toFloat()
        from.forEachIndexed { k, (i, x0, y0) ->
            val h = colony.getOrNull(i) ?: return@forEachIndexed
            val s = h.seed + 31
            val cat = route(s, t0 + 250f * k, 1300f + 300f * rnd(s, 0, 6), h.scale, x0, y0, h.x, h.y, h.variant,
                calm = true, idx = i, loops = false, arrive = true)
            if (x0 < 0f) { var u = 0f; while (u < 1f) { catPos(cat, u); if (px >= 0f) { cat.enterT = cat.ts + u * cat.dur; cat.enterY = py; break }; u += .002f } }
            flyers += cat
            fly(h.variant, 0); fly(h.variant, 1)
        }
        return flyers.filter { it.arrive }.minOf { (it.ts + it.dur).toLong() } - t0.toLong()
    }

    /** Cat [i] shows [pose] (HEAD_UP for "something's new") from [start] to [end] (uptime). The caller draws one frame at each edge. */
    fun stir(i: Int, pose: Int, start: Long, end: Long) { stirs += Stir(i, pose, start, end) }

    /** The colony indices nearest cat [i]'s spot (its neighbours look up when it lands). */
    fun neighbours(i: Int, n: Int): List<Int> {
        val h = colony.getOrNull(i) ?: return emptyList()
        return colony.indices.filter { it != i }.sortedBy { hypot(colony[it].x - h.x, colony[it].y - h.y) }.take(n)
    }

    /** Poke cat [i]: a `!` over its head; with [hop], a 380 ms hop and it sits up (until [unpoke] or 4 s). */
    fun poke(i: Int, now: Long, hop: Boolean) { pokeIdx = i; pokeAt = now; pokeHop = hop }

    fun unpoke() { pokeIdx = -1 }

    /** Cat [i] is being rubbed: its glyph is a heart instead of a z; [heart] sends one floating up (at most 4 alive). */
    fun rub(i: Int) { rubIdx = i }

    fun heart(i: Int, now: Long) {
        hearts.removeAll { now - it[1] >= HEART_MS }
        if (hearts.size >= 4) hearts.removeAt(0)
        hearts += longArrayOf(i.toLong(), now)
    }

    /** Sleepers (skipping cats that are away) + flights + the laser chase. Static when nothing moves. [alpha] = screen dim. */
    fun drawColony(c: Canvas, now: Long, alpha: Float) {
        val a = alpha.coerceIn(0f, 1f)
        for (i in colony.indices) {
            val h = colony[i]
            if (away(i, now)) continue
            var p = stirs.lastOrNull { it.idx == i && now >= it.start && now < it.end }?.pose ?: SLEEP
            var dy = 0f
            val poked = i == pokeIdx && now - pokeAt < POKE_MS
            if (poked && pokeHop) {
                p = SIT
                val u = (now - pokeAt) / HOP_MS.toFloat()
                if (u < 1f) dy = -26f * h.scale * 4 * u * (1 - u)
            }
            drawSprite(c, pose(p, h.variant), h.x, h.y + dy, h.scale, h.flip, a * if (h.ghost) .55f else 1f)
            // one glyph over its head, never mirrored: ! (just poked) > heart (being rubbed) > z (asleep)
            val g = when { poked -> bang; i == rubIdx -> heartBmp; h.zzz && p == SLEEP -> zzz; else -> null } ?: continue
            val zx = h.x + (if (h.flip) -42f else 42f) * h.scale; val zy = h.y + dy - 24f * h.scale; val r = 6f * h.scale
            dst.set(zx - r, zy - r, zx + r, zy + r)
            sleepPaint.alpha = (255 * a).toInt()
            c.drawBitmap(g, null, dst, sleepPaint)
        }
        for (hh in hearts) { // float up from the glyph slot and fade
            val u = (now - hh[1]) / HEART_MS.toFloat()
            val h = colony.getOrNull(hh[0].toInt()) ?: continue
            if (u < 0f || u >= 1f) continue
            val zx = h.x + (if (h.flip) -42f else 42f) * h.scale + 6f * sin(u * 9f); val zy = h.y - 24f * h.scale - 46f * u
            val r = 7f * (.8f + .4f * u)
            dst.set(zx - r, zy - r, zx + r, zy + r)
            sleepPaint.alpha = (255 * a * (if (u < .6f) 1f else 1 - (u - .6f) / .4f)).toInt()
            c.drawBitmap(heartBmp, null, dst, sleepPaint)
        }
        if (flyAt != NEVER) drawCats(c, flyers, (now - flyAt).toFloat(), a)
        if (laserOn || chasers.isNotEmpty()) drawLaser(c, now, a)
    }

    /** One colony sprite: [b] centred at (x, y), [sc], mirrored if [flip]. */
    private fun drawSprite(c: Canvas, b: Bitmap, x: Float, y: Float, sc: Float, flip: Boolean, alpha: Float) {
        sleepPaint.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
        c.save(); c.translate(x, y); c.scale(if (flip) -sc else sc, sc)
        c.drawBitmap(b, -b.width / 2f, -b.height / 2f, sleepPaint)
        c.restore()
    }

    /** A sitting-up cat (an open PR's kitten on the shelf; MeterView owns the shelf). */
    fun drawSit(c: Canvas, x: Float, y: Float, sc: Float, v: Int, flip: Boolean, alpha: Float) =
        drawSprite(c, pose(SIT, v), x, y, sc, flip, alpha)

    /** Touchdown: a little puff of sparks kicked up round its spot. */
    private fun puff(c: Canvas, cat: Cat, t: Float) {
        val t0 = cat.ts + cat.endT()
        sparks(c, t, cat.seed + 4, 14, false, -165f, -15f, 60f, 200f, 500f, .4f, .25f, .45f, 1.3f, 18f, { _, r -> t0 + r * 40 }) { r1, _, _ ->
            sx = cat.boomX + (r1 - .5f) * 70f * cat.scale; sy = cat.boomY + 22f * cat.scale
        }
    }

    // ----------------------------------------------------------------- laser dot (hold, then drag, on CATS)

    private class Chaser(val idx: Int, var x: Float, var y: Float, val k: Float) {
        var vx = 0f; var vy = 0f; var dx = 1f; var dy = 0f
        val hist = FloatArray(2 * (TRAIL / 2 + 4)); var histN = 0; var histAt = 0L
    }
    private val chasers = ArrayList<Chaser>()
    private var laserOn = false
    private var laserX = 0f
    private var laserY = 0f
    private var laserStill = 0L
    private var laserLast = 0L
    private var laserAt = 0L
    private var stillX = 0f
    private var stillY = 0f
    private var pounceAt = NEVER
    private var pouncer = -1
    /** A chaser pounced on the dot (MeterView buzzes LAND). */
    var onPounce: (() -> Unit)? = null

    /** The 3 newest cats wake and chase a red dot that sits just above the fingertip (x, y). Small-PR cats dart, XL ones lumber. */
    fun laserDown(x: Float, y: Float, now: Long) {
        laserOn = true; laserX = x; laserY = y - 44f; laserStill = now; laserLast = now; laserAt = now; pounceAt = NEVER; pouncer = -1
        stillX = laserX; stillY = laserY
        chasers.clear()
        for (i in (colony.size - 3).coerceAtLeast(0) until colony.size) {
            val h = colony[i]
            if (away(i, now)) continue
            chasers += Chaser(i, h.x, h.y, if (h.scale < .7f) 70f else if (h.scale < .9f) 45f else 28f)
            fly(h.variant, 0); fly(h.variant, 1)
        }
    }

    fun laserMove(x: Float, y: Float, now: Long) {
        laserX = x; laserY = y - 44f
        // still = within 8 px of where it last stopped (per-event deltas are tiny on a slow drag)
        if (hypot(laserX - stillX, laserY - stillY) > 8f) { laserStill = now; pounceAt = NEVER; stillX = laserX; stillY = laserY }
    }

    /** Let go: the dot goes out and the chasers fly home (calm, 0.7 s). Returns ms until the first lands (0: nobody's flying). */
    fun laserUp(now: Long): Long {
        if (!laserOn) return 0
        laserOn = false
        if (chasers.isEmpty()) return 0
        startFlights(now)
        val t0 = (now - flyAt).toFloat()
        for (ch in chasers) {
            val h = colony.getOrNull(ch.idx) ?: continue
            val pts = floatArrayOf(ch.x, ch.y, (ch.x + h.x) / 2, min(ch.y, h.y) - 60f, h.x, h.y - 80f, h.x, h.y)
            flyers += cat(pts, h.seed + 7, t0, 700f, h.scale, h.x, h.y, h.variant, HOME, true, ch.idx, true, true)
        }
        chasers.clear()
        return 700
    }

    private fun drawLaser(c: Canvas, now: Long, alpha: Float) {
        val dt = (now - laserLast).coerceIn(0, 50) / 1000f; laserLast = now
        if (laserOn && pounceAt == NEVER && now - laserStill > 400 && chasers.isNotEmpty()) { // held still: the nearest pounces
            pounceAt = now
            pouncer = chasers.indices.minByOrNull { hypot(chasers[it].x - laserX, chasers[it].y - laserY) } ?: -1
            onPounce?.invoke()
        }
        am = 1f; paint = overPaint
        chasers.forEachIndexed { j, ch ->
            val h = colony.getOrNull(ch.idx) ?: return@forEachIndexed
            val pounce = j == pouncer && now - pounceAt < 300
            val lt = (now - laserAt).toFloat() // not raw uptime: a float of weeks of uptime only resolves ~100 ms
            val ang = TAU * j / 3 + lt / 900f // circle the dot a body-length out, each on its own side
            val tx0 = laserX + if (pounce) 0f else 78f * h.scale * kotlin.math.cos(ang)
            val ty0 = laserY + if (pounce) 0f else 46f * h.scale * sin(ang)
            val k = if (pounce) 160f else ch.k
            ch.vx += (k * (tx0 - ch.x) - 2 * sqrt(k) * ch.vx) * dt
            ch.vy += (k * (ty0 - ch.y) - 2 * sqrt(k) * ch.vy) * dt
            val sp = hypot(ch.vx, ch.vy)
            if (sp > 1100f) { ch.vx *= 1100f / sp; ch.vy *= 1100f / sp }
            ch.x += ch.vx * dt; ch.y += ch.vy * dt
            if (sp > 30f) { ch.dx = ch.vx / sp; ch.dy = ch.vy / sp }
            if (ch.histN == 0 || now - ch.histAt >= 14) {
                System.arraycopy(ch.hist, 0, ch.hist, 2, ch.hist.size - 2)
                ch.hist[0] = ch.x - ch.dx * 34f * h.scale; ch.hist[1] = ch.y - ch.dy * 34f * h.scale + 4f * h.scale
                ch.histN = min(ch.histN + 1, ch.hist.size / 2); ch.histAt = now
            }
            rainbowFromHist(c, ch.hist, ch.histN, h.scale, lt, 1f)
            drawCat(c, ch.x, ch.y, ch.dx, ch.dy, h.scale, lt, h.seed, lt, h.variant, fire = false,
                alpha = (255 * alpha * if (h.ghost) .55f else 1f).toInt())
        }
        if (laserOn) { // the dot: a hot red core in a soft glow
            paint = addPaint
            spr(c, redGlow, laserX, laserY, 64f, 64f, 1f)
            dotPaint.alpha = 255; c.drawCircle(laserX, laserY, 5f, dotPaint)
            paint = overPaint
        }
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE0E0.toInt() }

    // ----------------------------------------------------------------- the goal surge's rocket cat, the peeking cat

    private val rockets = ArrayList<Cat>()
    private var rocketAt = NEVER

    /** The goal bargraph's cat (UX-PASS #9): it rockets along the tube at y from x0 out of the right end, then a puff. */
    fun rocket(at: Long, x0: Float, y: Float, v: Int) {
        rocketAt = at; rockets.clear()
        val pts = floatArrayOf(x0, y, x0 + 160f, y - 5f, 470f, y - 2f, 760f, y)
        rockets += cat(pts, (at % 100_000).toInt(), 0f, ROCKET_MS.toFloat(), .55f, 650f, y, v, EXIT, false, -1, false, false)
        fly(v, 0); fly(v, 1)
    }

    private var peekAt = NEVER
    private var peekDur = 0L
    private var peekY = 0f
    private var peekX = 0f
    private var peekV = 0

    /**
     * A cat's head peeks out from under the camera island at height [y], reaching to centre x [x] (UX-PASS #8, and its
     * paw for #19's rescue): slides out in 300 ms, bobs, blinks once, slides back by [dur].
     */
    fun peek(at: Long, y: Float, x: Float, dur: Long, v: Int) { peekAt = at; peekY = y; peekX = x; peekDur = dur; peekV = v; fly(v, 0) }

    fun unpeek() { peekAt = NEVER }

    private fun drawPeek(c: Canvas, now: Long) {
        val t = (now - peekAt).toFloat()
        if (t < 0 || t >= peekDur) return
        val u = when {
            t < 300f -> 1 - (1 - t / 300f).pow(3)
            t > peekDur - 300f -> (1 - (t - (peekDur - 300f)) / 300f).pow(2)
            else -> 1f
        }
        val x = -80f + (peekX + 80f) * u
        val y = peekY + 3f * sin(t / 170f)
        val frame = fly(peekV, 0)
        catPaint.alpha = 255
        c.save(); c.translate(x, y); c.scale(1.1f, 1.1f)
        c.drawBitmap(frame, -frame.width / 2f, -frame.height / 2f, catPaint)
        if (t in 1300f..1420f) { // a blink: fur over both eyes (sprite px (22, 10) and (28, 10), 3 px each)
            blinkPaint.color = 0xFF9C9C9C.toInt()
            c.drawRect(-frame.width / 2f + 22 * 3, -frame.height / 2f + 10 * 3, -frame.width / 2f + 24 * 3, -frame.height / 2f + 12 * 3, blinkPaint)
            c.drawRect(-frame.width / 2f + 28 * 3, -frame.height / 2f + 10 * 3, -frame.width / 2f + 30 * 3, -frame.height / 2f + 12 * 3, blinkPaint)
        }
        c.restore()
    }

    private val blinkPaint = Paint()

    /** Firework: the cat bursts with a bang and golden crackle into a ring of mini cats that fly out
     *  like stars from a shell, droop under gravity (drag τ .55 s) and fade, each with its own rainbow
     *  streak and burning butt. A [Cat.gold] shell (a clean sweep) is bigger and brighter. Pure function of t. */
    private fun burst(c: Canvas, cat: Cat, t: Float) {
        val t0 = cat.ts + cat.endT()
        val a = (t - t0) / 1000
        if (a < 0 || a > 1.6f) return
        val s = cat.seed
        val g = if (cat.gold) 1.7f else 1f
        paint = addPaint
        spr(c, flare, cat.boomX, cat.boomY, 460f * cat.scale * g, 460f * cat.scale * g, decay(t, t0, 130f * g))
        spr(c, aflare, cat.boomX, cat.boomY, 220f * g, 220f * g, .9f * decay(t, t0, 80f))
        sparks(c, t, s + 3, if (cat.gold) 110 else 48, false, -180f, 180f, 180f, 640f * g, 500f, .7f, .6f, 1.1f, 1.6f, 30f,
            { _, r -> t0 + r * 40 }) { _, _, _ -> sx = cat.boomX; sy = cat.boomY }
        paint = overPaint
        val minis = if (cat.gold) 14 else MINIS
        for (k in 0 until minis) {
            val life = lerp(1.1f, 1.5f, rnd(s, k, 41))
            if (a >= life) continue
            val ang = TAU * k / minis + (rnd(s, k, 42) - .5f) * .5f
            val spd = lerp(260f, 470f, rnd(s, k, 43)) * if (cat.gold) 1.25f else 1f
            val vx = kotlin.math.cos(ang) * spd
            val vy = sin(ang) * spd - 120f // a little lift, like a shell's stars
            val tau = .55f; val gr = 420f
            val e = exp(-a / tau); val d = tau * (1 - e)
            val x = cat.boomX + vx * d
            val y = cat.boomY + gr * tau * a + (vy - gr * tau) * d
            val dx = vx * e; val dy = gr * tau + (vy - gr * tau) * e
            val v = max(1e-3f, hypot(dx, dy)); val ux = dx / v; val uy = dy / v
            val fade = if (a < .7f * life) 1f else max(0f, 1 - (a - .7f * life) / (.3f * life))
            val sc = cat.scale * lerp(.36f, .5f, rnd(s, k, 44))
            val back = 30f * sc
            val len = (v * .12f).coerceIn(16f, 80f) * sc
            streak.strokeWidth = 2.2f * sc + .6f
            for (j in 0..5) { // a short rainbow streak behind it
                val off = (j - 2.5f) * 2.2f * sc
                streak.color = stripes[j]; streak.alpha = (230 * fade).toInt()
                c.drawLine(x - ux * back - uy * off, y - uy * back + ux * off,
                    x - ux * (back + len) - uy * off, y - uy * (back + len) + ux * off, streak)
            }
            c.save(); c.translate(x - ux * back, y - uy * back); c.rotate(Math.toDegrees(atan2(-ux, uy).toDouble()).toFloat())
            tongue(c, tongueN, 0f, 4f, 26f * sc, 64f * sc, .95f * fade, t, s + k * 1.3f, .7f)
            c.restore()
            val face = if (ux >= 0) 1f else -1f
            val tilt = Math.toDegrees(atan2(uy, abs(ux)).toDouble()).toFloat().coerceIn(-70f, 70f)
            val frame = fly(if (cat.gold) GOLD else 0, if ((a * 1000 / 70).toInt() % 2 == 0) 0 else 1)
            catPaint.alpha = (255 * fade).toInt()
            c.save(); c.translate(x, y); c.rotate(tilt * face); c.scale(face * sc, sc)
            c.drawBitmap(frame, -frame.width / 2f, -frame.height / 2f, catPaint)
            c.restore()
        }
        catPaint.alpha = 255
    }

    private val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.BUTT }

    private val stripes = intArrayOf(0xFFFF2A2A.toInt(), 0xFFFF9A1F.toInt(), 0xFFFFEE33.toInt(),
        0xFF3DFF4A.toInt(), 0xFF2AA8FF.toInt(), 0xFF7A3DFF.toInt())
    private val trailPath = Path()
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.BUTT
    }
    private val tx = FloatArray(2 * TRAIL)
    private val ty = FloatArray(2 * TRAIL)
    private val tnx = FloatArray(2 * TRAIL)
    private val tny = FloatArray(2 * TRAIL)

    /** Six stripes along the butt's recent path (half as long on a calm colony flight). */
    private fun trail(c: Canvas, cat: Cat, lt: Float) {
        var n = 0
        val endT = cat.endT()
        for (k in 0 until if (cat.calm) TRAIL / 2 else TRAIL) {
            val tk = min(lt, endT) - (k + 4) * 14f // starts ~4 samples back: the butt flame fills the gap
            if (tk < 0) break
            pose(cat, tk); tx[n] = bx; ty[n] = by; n++
        }
        val fade = if (lt > endT) max(0f, 1 - (lt - endT) / 300) else 1f // gone as the cat bursts
        rainbow(c, n, cat.scale, (lt / 90).toInt(), fade)
    }

    /** Six stripes along the n points in (tx, ty), stepping up and down like the classic trail. */
    private fun rainbow(c: Canvas, n: Int, sc: Float, phase: Int, fade: Float) {
        if (n < 2 || fade <= 0f) return
        for (k in 0 until n) { // normals from neighbouring samples
            val a = max(0, k - 1); val b = min(n - 1, k + 1)
            val dx = tx[a] - tx[b]; val dy = ty[a] - ty[b]; val l = max(1e-3f, hypot(dx, dy))
            tnx[k] = -dy / l; tny[k] = dx / l
        }
        val sh = 6f * sc
        trailPaint.strokeWidth = sh + 0.8f
        for (j in 0..5) {
            trailPath.reset()
            for (k in 0 until n) {
                val off = (j - 2.5f) * sh + if ((k + phase) / 2 % 2 == 0) 2f else -2f
                val x = tx[k] + tnx[k] * off; val y = ty[k] + tny[k] * off
                if (k == 0) trailPath.moveTo(x, y) else trailPath.lineTo(x, y)
            }
            trailPaint.color = stripes[j]; trailPaint.alpha = (235 * fade).toInt()
            c.drawPath(trailPath, trailPaint)
        }
    }

    private val catPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val catA = bakeCat(0)
    private val catB = bakeCat(1)
    private val flyV = arrayOfNulls<Bitmap>(2 * COATS).also { it[0] = catA; it[1] = catB } // frame pairs per coat
    private fun fly(v: Int, f: Int) = flyV[2 * v + f] ?: bakeCat(f, v).also { flyV[2 * v + f] = it }

    /** 34×21 pixel cat at 3 px per pixel; frame 1 moves the legs and tail. [v]: coat. */
    private fun bakeCat(frame: Int, v: Int = 0): Bitmap {
        val bmp = Bitmap.createBitmap(34 * 3, 21 * 3, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp).apply { scale(3f, 3f) }
        val p = Paint()
        fun r(x: Int, y: Int, w: Int, h: Int, col: Int) { p.color = col; c.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), p) }
        fun dot(x: Int, y: Int, col: Int) = r(x, y, 1, 1, col)
        fun clear(x: Int, y: Int) { p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR); dot(x, y, 0); p.xfermode = null }
        val k = Color.BLACK; val fur = 0xFF9C9C9C.toInt(); val crust = CRUST[v]
        val frost = FROST[v]; val sprinkle = SPRINKLE[v]; val cheek = 0xFFFF8FA3.toInt()
        // tail, wagging
        val ty = if (frame == 0) 7 else 10
        r(0, ty, 7, 4, k); r(1, ty + 1, 5, 2, fur)
        // legs, scurrying
        for (lx in intArrayOf(8, 12, 21, 25)) { val x = lx - frame; r(x - 1, 16, 5, 4, k); r(x, 17, 3, 2, fur) }
        // pop-tart body
        r(6, 0, 21, 17, k); r(7, 1, 19, 15, crust)
        for ((x, y) in listOf(6 to 0, 26 to 0, 6 to 16, 26 to 16)) clear(x, y)
        for ((x, y) in listOf(7 to 1, 25 to 1, 7 to 15, 25 to 15)) dot(x, y, k)
        r(9, 3, 15, 11, frost)
        for ((x, y) in listOf(9 to 3, 23 to 3, 9 to 13, 23 to 13)) dot(x, y, crust)
        for ((x, y) in listOf(11 to 5, 14 to 4, 17 to 6, 12 to 9, 15 to 11, 18 to 9, 10 to 12, 16 to 8)) dot(x, y, sprinkle)
        // head with ears
        r(20, 4, 4, 3, k); r(21, 5, 2, 2, fur); clear(20, 4)
        r(28, 4, 4, 3, k); r(29, 5, 2, 2, fur); clear(31, 4)
        r(19, 6, 14, 12, k); r(20, 7, 12, 10, fur)
        for ((x, y) in listOf(19 to 6, 32 to 6, 19 to 17, 32 to 17)) clear(x, y)
        r(21, 5, 2, 2, fur); r(29, 5, 2, 2, fur)
        r(22, 10, 2, 2, k); dot(22, 10, Color.WHITE)
        r(28, 10, 2, 2, k); dot(28, 10, Color.WHITE)
        r(20, 13, 2, 2, cheek); r(30, 13, 2, 2, cheek)
        dot(25, 12, k); dot(26, 12, k)
        r(24, 14, 4, 1, k); dot(23, 13, k); dot(28, 13, k)
        return bmp
    }

    private val sleepPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val poses = arrayOfNulls<Bitmap>(3 * COATS)

    /** Colony sprite: SLEEP, HEAD_UP (awake, head raised: something's new) or SIT (poked; an open PR's kitten). */
    private fun pose(p: Int, v: Int) = poses[3 * v + p] ?: (if (p == SIT) bakeCat(0, v) else bakeSleeper(v, p == HEAD_UP))
        .also { poses[3 * v + p] = it }

    /**
     * 34×20 pixel cat curled up asleep, same family as [bakeCat]: head down on its paws, eyes shut, tail wrapped round.
     * [up]: the head lifted 3 px off its paws with its eyes open.
     */
    private fun bakeSleeper(v: Int, up: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(34 * 3, 20 * 3, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp).apply { scale(3f, 3f) }
        val p = Paint()
        fun r(x: Int, y: Int, w: Int, h: Int, col: Int) { p.color = col; c.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), p) }
        fun dot(x: Int, y: Int, col: Int) = r(x, y, 1, 1, col)
        fun clear(x: Int, y: Int) { p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR); dot(x, y, 0); p.xfermode = null }
        val k = Color.BLACK; val fur = 0xFF9C9C9C.toInt(); val crust = CRUST[v]; val cheek = 0xFFFF8FA3.toInt()
        // pop-tart body
        r(3, 2, 21, 15, k); r(4, 3, 19, 13, crust)
        for ((x, y) in listOf(3 to 2, 23 to 2, 3 to 16, 23 to 16)) clear(x, y)
        for ((x, y) in listOf(4 to 3, 22 to 3, 4 to 15, 22 to 15)) dot(x, y, k)
        r(6, 5, 15, 9, FROST[v])
        for ((x, y) in listOf(6 to 5, 20 to 5, 6 to 13, 20 to 13)) dot(x, y, crust)
        for ((x, y) in listOf(8 to 7, 11 to 6, 14 to 8, 17 to 6, 9 to 11, 13 to 12, 16 to 10, 19 to 9)) dot(x, y, SPRINKLE[v])
        // tail: up the back and round the bottom
        r(0, 11, 4, 9, k); r(1, 12, 2, 6, fur); clear(0, 11); clear(3, 11); clear(0, 19)
        r(1, 16, 24, 4, k); r(2, 17, 22, 2, fur); r(1, 16, 2, 2, fur)
        // head, down on its paws (eyes shut) or lifted (eyes open)
        val o = if (up) -3 else 0
        r(20, 5 + o, 4, 3, k); r(21, 6 + o, 2, 2, fur); clear(20, 5 + o)
        r(28, 5 + o, 4, 3, k); r(29, 6 + o, 2, 2, fur); clear(31, 5 + o)
        r(19, 7 + o, 14, 12, k); r(20, 8 + o, 12, 10, fur)
        for ((x, y) in listOf(19 to 7 + o, 32 to 7 + o, 19 to 18 + o, 32 to 18 + o)) clear(x, y)
        r(21, 6 + o, 2, 2, fur); r(29, 6 + o, 2, 2, fur)
        if (up) { r(22, 11 + o, 2, 2, k); dot(22, 11 + o, Color.WHITE); r(28, 11 + o, 2, 2, k); dot(28, 11 + o, Color.WHITE) }
        else { r(21, 12, 3, 1, k); r(28, 12, 3, 1, k) }
        r(20, 14 + o, 2, 2, cheek); r(30, 14 + o, 2, 2, cheek)
        dot(25, 13 + o, k); dot(26, 13 + o, k)
        r(24, 15 + o, 4, 1, k); dot(23, 14 + o, k); dot(28, 14 + o, k)
        // paws under the chin, over the head's outline (drawn last, as the original sprite)
        r(24, 16, 10, 4, k); r(25, 17, 3, 2, fur); r(29, 17, 4, 2, fur); clear(33, 16); clear(33, 19); clear(24, 19)
        return bmp
    }

    /** 4×4 px glyphs at 3 px per pixel for the slot over a colony cat's head. */
    private fun glyph4(col: Int, vararg rows: String) = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b); val p = Paint().apply { color = col }
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> if (ch == '#') c.drawRect(x * 3f, y * 3f, x * 3f + 3, y * 3f + 3, p) } }
    }
    private val zzz = glyph4(0xFF5E4C3E.toInt(), "####", "  # ", " #  ", "####") // label colour: asleep
    private val bang = glyph4(0xFFFFB27A.toInt(), " ## ", " ## ", "    ", " ## ")  // event colour: just poked
    private val heartBmp = Bitmap.createBitmap(15, 15, Bitmap.Config.ARGB_8888).also { b -> // cheek pink, 5×5
        val c = Canvas(b); val p = Paint().apply { color = 0xFFFF8FA3.toInt() }
        for ((y, row) in listOf(" # # ", "#####", "#####", " ### ", "  #  ").withIndex())
            row.forEachIndexed { x, ch -> if (ch == '#') c.drawRect(x * 3f, y * 3f, x * 3f + 3, y * 3f + 3, p) }
    }
    private val redGlow = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).also { b ->
        Canvas(b).drawRect(0f, 0f, 32f, 32f, Paint().apply {
            shader = RadialGradient(16f, 16f, 16f, intArrayOf(0xFFFF6A5A.toInt(), 0x88FF1A0A.toInt(), 0x00FF0000), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
        })
    }

    // ================================================================= Flashover (fire)

    /** Start the fire moment at [at] (the moment's t0). [changed]: hero tubes whose digit changes. */
    fun fire(at: Long, tier: Int, night: Boolean, hero: List<Spot>, changed: List<Boolean>, adds: List<Spot>, dels: List<Spot>) {
        fireAt = at; this.tier = tier; this.night = night; this.hero = hero
        strong = changed.indexOfLast { it }.takeIf { it >= 0 } ?: hero.lastIndex
        heroClip.clear(); hero.forEach { heroClip += tubePath(it) }
        digits.clear()
        val xl = tier == XL
        val big = tier >= L
        if (tier == S || night) { digit(hero[strong], if (tier == S) 315f else LAND, 110f, 26f, 12f, false, 31, 30f); return }
        val m = if (big) 1.25f else 1f
        val from = if (xl) 760f else LAND // XL: the digits are left burning as the flashover recedes
        hero.forEachIndexed { i, s ->
            val place = hero.lastIndex - i
            if (changed[i]) digit(s, from + 60f * place, if (big) 520f else 400f, 52f * m, 20f * m, false, 3 + i, 50f)
            else if (big) digit(s, from + 60f, 320f, 40f, 18f, false, 6 + i, 25f)
        }
        adds.forEachIndexed { q, s -> digit(s, if (xl) 760f else 390f + 30f * (adds.lastIndex - q), 340f, 24f * m, 11f * m, false, 10 + q, 12f) }
        dels.forEachIndexed { q, s -> digit(s, if (xl) 790f else 450f + 30f * (dels.lastIndex - q), 340f, 24f * m, 11f * m, true, 20 + q, 12f) }
    }

    private fun digit(s: Spot, t0: Float, tau: Float, hd: Float, wd: Float, argon: Boolean, seed: Int, rate: Float) {
        val ch = s.ch ?: return
        val ink = ink(s.cls, ch) ?: return
        digits += Digit(s.x, s.y, ink, t0, tau, hd, wd, argon, seed, rate)
    }

    fun draw(c: Canvas, now: Long) {
        if (now >= fireAt && now - fireAt < if (night) 1000 else FIRE_END[tier]) drawFire(c, (now - fireAt).toFloat())
        if (now >= catsAt && now - catsAt < catsEnd) drawCats(c, cats, (now - catsAt).toFloat())
        if (now >= rocketAt && now - rocketAt < ROCKET_MS + 700) drawCats(c, rockets, (now - rocketAt).toFloat())
        if (petActive(now)) drawPet(c, now)
        drawPeek(c, now)
    }

    private fun drawFire(c: Canvas, t: Float) {
        am = if (night) 0.4f else 1f; paint = addPaint
        if (tier == S || night) {
            digits.forEach { digitFire(c, t, it) }
            val d = digits.firstOrNull() ?: return
            sparks(c, t, 32, 7, false, -110f, -70f, 70f, 160f, 120f, .6f, .25f, .32f, 1.6f, 30f, { _, r -> d.t0 + r * 120 }) { r1, _, _ ->
                val a = d.ink.anchors; val j = (r1 * (a.size / 2)).toInt() * 2; sx = d.x + a[j]; sy = d.y + a[j + 1]
            }
            return
        }
        val xl = tier == XL
        val big = tier >= L
        val heat = if (xl) .15f * sstep(380f, 495f, t) * (1 - sstep(700f, 1600f, t)) + .04f * sstep(1200f, 1600f, t) * (1 - sstep(2700f, 3100f, t))
            else if (big) .07f * sstep(300f, 495f, t) * (1 - sstep(900f, 1800f, t)) else 0f
        if (heat > .004f) { heatPaint.alpha = (255 * heat * am).toInt(); c.drawRect(0f, 260f, 680f, 596f, heatPaint) }

        strike(c, t, if (big) 1.3f else 1f)
        rail(c, t, if (big) 664f else 334f, if (big) 240f else 140f, floatArrayOf(0f, 1350f, 3020f, 3350f)[tier], xl)

        hero.forEachIndexed { i, s -> tubeFire(c, t, s, heroClip[i], 1 + i, i == strong) }
        digits.forEach { digitFire(c, t, it) }

        val T = hero[strong]
        val cx = T.x + T.cls.w / 2
        if (!xl) emberSpit(c, t, cx, 60f, 70f, if (big) 50 else 30, LAND, 40) // XL: the flashover eruption replaces it
        if (tier == L) hero.forEachIndexed { i, s -> if (i != strong) emberSpit(c, t, s.x + s.cls.w / 2, 60f, 70f, 22, LAND + 60, 41 + i) }

        if (tier == L) { // overload: the tube vents through its dome and flame spreads along the ceiling
            val e = sstep(450f, 520f, t) * (1 - sstep(580f, 880f, t))
            if (e > .01f) tongue(c, tongueN, cx, 70f, 90f, 120f * e, .6f, t, 53f)
            ceilingFire(c, t, cx, 1f, 220f, 470f, .9f, 640f, 51)
            ceilingFire(c, t, cx, -1f, 140f, 470f, .9f, 620f, 52)
            flames(c, t, 50, 440f, 820f, 60f, 3f, .22f, .4f, -20f, 30f, 0f, 30f, 56f, .8f, 8f, 2f, .4f, firep,
                { tb -> sstep(440f, 495f, tb) * (1 - sstep(560f, 820f, tb)) }) { r1, r2, _, i ->
                sx = cx + (r1 - .5f) * 40; sy = 8 + 26 * r2; svx = (if (rnd(50, i, 77) < .5f) -1 else 1) * lerp(250f, 750f, r1)
            }
        }

        if (xl) {
            // rollover: flame races along the ceiling to the right edge…
            ceilingFire(c, t, 60f, 1f, 640f, 340f, 4.2f, 560f, 60)
            ceilingFire(c, t, 260f, -1f, 240f, 360f, 2.4f, 540f, 66)
            // …then FLASHOVER: a wall of tongues from the rail, its front sweeping out of the camera edge
            for (i in 0 until 18) {
                val back = i >= 11
                val bx = if (back) (i - 11 + .5f) * (680f / 7) else (i + .5f) * (680f / 11) + 10 * snoise(i.toFloat(), 64)
                val ti = 470 + 90 * (bx / 680)
                val e = sstep(ti, ti + 60, t) * (1 - sstep(700f, 1350f, t))
                if (e > .01f) tongue(c, tongueN, bx, RAIL_Y + 12, if (back) 190f else 120f,
                    (if (back) 700f else 560f) * e * (.8f + .35f * rnd(65, i, 0)), if (back) .35f else .5f, t, 70 + i * 1.9f, .8f)
            }
            flames(c, t, 61, 470f, 1300f, 110f, 1.3f, .3f, .5f, 700f, 1100f, 300f, 50f, 90f, .6f, 20f, 1.5f, .3f, firep,
                { tb -> sstep(470f, 495f, tb) * (1 - sstep(700f, 1250f, tb)) * 1.3f }) { r1, r2, tb, _ ->
                sx = r1 * 680 * sstep(470f, 560f, tb); sy = RAIL_Y - 120 * r2; svx = 0f
            }
            val fl = sstep(LAND - 25, LAND, t) * decay(t, LAND, 120f) // fireball out of the changed digit
            spr(c, flare, cx, T.y + T.cls.h * .5f, 820f, 820f, .75f * fl)
            sparks(c, t, 62, 90, false, -118f, -62f, 550f, 1050f, 1100f, .8f, .7f, 1.3f, 2f, 34f, { _, r -> LAND + r * 140 }) { r1, r2, _ ->
                sx = r1 * 680; sy = RAIL_Y - r2 * 10
            }
            for (i in 0 until 60) { // embers drifting down after the flashover
                val tb = 850 + rnd(63, i, 0) * 1100
                val a = (t - tb) / 1000
                val life = lerp(1f, 1.8f, rnd(63, i, 1))
                if (a < 0 || a >= life) continue
                val vt = lerp(80f, 180f, rnd(63, i, 2)); val d = .35f * (1 - exp(-a / .35f))
                val x = rnd(63, i, 3) * 680 + 10 * sin(TAU * .9f * a + TAU * rnd(63, i, 4))
                val y = -10 - 30 * rnd(63, i, 5) + vt * (a - d)
                val pulse = .7f + .3f * sin(TAU * 5 * a + TAU * rnd(63, i, 6))
                val u = a / life
                val s = lerp(8f, 14f, rnd(63, i, 7))
                spr(c, ember, x, y, s, s, pulse * min(1f, a / .08f) * (if (u < .65f) 1f else 1 - (u - .65f) / .35f))
            }
        }

        // roll-up: fuses burn along under both banks, lighting each tube just before it rolls
        val fk = if (tier == M) .8f else 1f
        val roll = if (big) 3040f else 1900f
        fuse(c, t, roll, 182f, false, 70, fk)
        fuse(c, t, roll, 316f, true, 71, fk)
    }

    private fun envStrong(tb: Float): Float {
        val rise = sstep(120f, 330f, tb)
        return when (tier) {
            M -> rise * (1 + .8f * bump(tb, LAND, 220f)) * (1 - sstep(800f, 1500f, tb))
            L -> rise * (1.1f + 1.5f * bump(tb, LAND, 280f)) * (1 - .72f * sstep(800f, 1600f, tb)) * (1 - sstep(2650f, 3050f, tb))
            else -> rise * (1.2f + 1.6f * bump(tb, LAND, 300f)) * (1 - .6f * sstep(480f, 510f, tb) * (1 - sstep(780f, 1000f, tb))) *
                (1 - .72f * sstep(900f, 1700f, tb)) * (1 - sstep(2900f, 3300f, tb))
        }
    }

    private fun envWeak(tb: Float): Float {
        val rise = sstep(120f, 330f, tb)
        return when (tier) {
            M -> .55f * rise * (1 - sstep(700f, 1300f, tb))
            L -> rise * (.8f + .6f * bump(tb, LAND, 280f)) * (1 - .72f * sstep(800f, 1600f, tb)) * (1 - sstep(2650f, 3050f, tb))
            else -> envStrong(tb)
        }
    }

    private val tw = floatArrayOf(.36f, .44f, .5f, .44f, .36f)
    private val th = floatArrayOf(.62f, .9f, 1f, .82f, .58f)

    /** Fire inside a hero tube, clipped to its glass: gas glow, a back tongue, 5 licking tongues, flamelets. */
    private fun tubeFire(c: Canvas, t: Float, s: Spot, clip: Path, seed: Int, isStrong: Boolean) {
        val e = if (isStrong) envStrong(t) else envWeak(t)
        val w = s.cls.w; val h = s.cls.h
        c.save(); c.clipPath(clip)
        if (e > .01f) {
            val fh = h * min(1f, .12f + .3f * e); val hh = 160 * e; val base = s.y + h + 6
            spr(c, gasfill, s.x + w / 2, s.y + h - fh / 2, w, fh, .3f * min(e, 2f))
            tongue(c, tongueN, s.x + w / 2, base, w * 1.1f, hh * 1.15f, .28f, t, seed * 10f + 9, .6f)
            for (i in 0..4) tongue(c, tongueN, s.x + w * (.2f + .15f * i) + 4 * snoise(t / 400 + i, seed), base,
                w * tw[i], hh * th[i], .55f, t, seed * 10f + i * 3.7f)
        }
        val rate = if (isStrong) 60f else if (tier == M) 30f else 50f
        val t1 = if (tier >= L) 3300f else if (isStrong) 1500f else 1300f
        flames(c, t, seed, 100f, t1, rate, 2.8f, .3f, .5f, 150f, 260f, 300f, 14f, 26f, 0f, 10f, 2.5f, .45f, firep,
            { tb -> if (isStrong) envStrong(tb) else envWeak(tb) }) { r1, r2, _, _ -> sx = s.x + w * (.2f + .6f * r1); sy = s.y + h - 10 - 30 * r2; svx = 0f }
        c.restore()
    }

    /** "Catching fire": tongues ignite along the digit's top edges 14 ms apart, plus flamelets flying off. */
    private fun digitFire(c: Canvas, t: Float, d: Digit) {
        if (t < d.t0) return
        val a = d.ink.anchors
        val img = if (d.argon) tongueA else tongueN
        for (j in 0 until a.size / 2) {
            val tj = d.t0 + 14 * j
            val e = sstep(tj, tj + 90, t) * (if (t < tj + 90) 1f else exp(-(t - tj - 90) / d.tau))
            if (e > .01f) tongue(c, img, d.x + a[2 * j], d.y + a[2 * j + 1] + 2, d.wd, d.hd * e * (.65f + .5f * rnd(d.seed, j, 1)), .7f, t, d.seed + j * 5.1f, .8f)
        }
        val pts = d.ink.pts
        flames(c, t, d.seed, d.t0, d.t0 + 3 * d.tau, d.rate, 3f, .2f, .4f, 60f, 120f, 380f, d.wd * .5f, d.wd * .9f, 0f, 4f, 4f, .5f,
            if (d.argon) argp else firep, { tb -> decay(tb, d.t0, d.tau) }) { r1, r2, _, _ ->
            val j = (r1 * (pts.size / 2)).toInt() * 2; sx = d.x + pts[j] + (r2 - .5f) * 3; sy = d.y + pts[j + 1]; svx = 0f
        }
    }

    private fun emberSpit(c: Canvas, t: Float, x: Float, y: Float, spread: Float, n: Int, t0: Float, seed: Int) {
        spr(c, flare, x, y + 15, 130f, 110f, .8f * sstep(t0 - 20, t0, t) * decay(t, t0, 80f))
        sparks(c, t, seed, n, false, -132f, -48f, 380f, 820f, 1100f, .7f, .5f, 1f, 1.7f, 40f, { _, r -> t0 + r * r * 160 }) { r1, r2, _ ->
            sx = x + (r1 - .5f) * spread; sy = y + r2 * 30
        }
    }

    /** Flame that hit the ceiling: tongues hang from y≈8 and lick outward (dir ±1) as the front passes. */
    private fun ceilingFire(c: Canvas, t: Float, x0: Float, dir: Float, reach: Float, t0: Float, speed: Float, tOut: Float, seed: Int) {
        val front = clamp((t - t0) * speed / reach)
        val out = 1 - sstep(tOut, tOut + 160, t)
        if (front * out > .01f) spr(c, flare, x0 + dir * reach * front / 2, 4f, reach * front + 60, 70f, .45f * out)
        var i = 0
        while (i * 26 <= reach) {
            val ti = t0 + i * 26 / speed
            val e = sstep(ti, ti + 60, t) * out
            if (e > .01f) {
                c.save(); c.translate(x0 + dir * i * 26, 4 + 8 * rnd(seed, i, 1))
                c.rotate(Math.toDegrees((dir * (PI / 2 + .3 + .4 * rnd(seed, i, 2)))).toFloat())
                tongue(c, tongueN, 0f, 0f, 38 + 22 * rnd(seed, i, 3), (100 + 110 * rnd(seed, i, 0)) * e, .45f, t, seed + i * 2.9f)
                c.restore()
            }
            i++
        }
    }

    /** The camera island's edge is the igniter: a violet seam flash and a striker spark at the rail. */
    private fun strike(c: Canvas, t: Float, k: Float) {
        if (t > 400) return
        val a = if (t < 15) t / 15 else exp(-(t - 15) / 70)
        spr(c, aflare, 0f, 298f, 44f, 640f, .6f * a * k)
        spr(c, aflare, 0f, RAIL_Y - 4, 110 * k, 110 * k, a)
        spr(c, flare, 2f, RAIL_Y - 4, 30f, 30f, a)
    }

    /** Burner rail: violet gas jets lit from the island edge out to [x1], shut off right→left back into it. */
    private fun rail(c: Canvas, t: Float, x1: Float, ignDur: Float, off0: Float, xl: Boolean) {
        val boost = (if (xl) 1 + 1.4f * bump(t, LAND, 300f) else 1f) * (1 - .35f * sstep(1500f, 2200f, t))
        var lit = 0f
        var j = 0
        while (true) {
            val x = 6 + 11f * j + (rnd(80, j, 0) - .5f) * 4
            if (x > x1) break
            val f = x / x1
            val d = t - (30 + f * ignDur)
            if (d < 0) break
            val pop = if (d < 40) 1.6f * d / 40 else 1 + .6f * exp(-(d - 40) / 70)
            val i = pop * clamp(1 - (t - (off0 + (1 - f) * 260)) / 90) * boost
            if (i > .01f) {
                lit = x
                val h = 32 * i * (.5f + .7f * noise(t / 30 + j * 1.7f, 11)) * (.7f + .6f * rnd(80, j, 1))
                spr(c, jet, x, RAIL_Y + 3 - h / 2, 18 * (.8f + .2f * min(i, 1.5f)), h, min(1f, .8f * i))
            }
            j++
        }
        if (lit > 0) spr(c, manifold, lit / 2, RAIL_Y + 1, lit + 8, 20f, .5f * clamp(boost) * clamp((t - 30) / 60))
        val fp = (t - 30) / ignDur
        if (fp in 0f..1.15f) spr(c, aflare, fp * x1, RAIL_Y - 4, 54f, 54f, .95f * (1 - max(0f, fp - 1) / .15f))
    }

    /** A burning head runs right→left under a bank, passing each tube 240 ms before its roll-up starts. */
    private fun fuse(c: Canvas, t: Float, roll: Float, y: Float, argon: Boolean, seed: Int, k: Float) {
        val v = 51f / 110
        val tc0 = roll - 240
        val tA = tc0 - (664 - 630) / v
        val tB = tc0 + (630 - 404) / v
        if (t < tA || t > tB + 400) return
        val xh = 630 - (min(t, tB) - tc0) * v
        val tl = min(664 - xh, 116f)
        if (tl > 1) spr(c, if (argon) trailA else trailN, xh + tl / 2, y, tl, 6f, .85f * (1 - clamp((t - tB) / 120)))
        val img = if (argon) tongueA else tongueN
        if (t <= tB) {
            val fl = .75f + .5f * noise(t / 25, seed)
            spr(c, if (argon) aflare else flare, xh, y, 48 * fl * k, 48 * fl * k, .95f)
            tongue(c, img, xh, y + 2, 12f, 26 * k, .9f, t, seed.toFloat())
        }
        for (q in 0 until 5) { // a flame licks up into each tube's base as the head passes it
            val tk = tc0 + 110 * q
            val e = sstep(tk, tk + 40, t) * (1 - sstep(tk + 40, tk + 220, t))
            if (t >= tk && e > .01f) tongue(c, img, 404f + 51 * (4 - q) + 22, y - 6, 30 * k, 56 * k * e, .8f, t, seed + q * 3f)
        }
        val rate = 280 * k
        sparks(c, t, seed, ((tB - tA) / 1000 * rate).toInt(), argon, -170f, -10f, 140f, 420f, 1400f, .5f, .08f, .2f, 1.5f, 22f,
            { i, r -> tA + (i + r) * (1000 / rate) }) { _, _, tb -> sx = 630 - (tb - tc0) * v; sy = y }
    }

    // ================================================================= primitives

    private var am = 1f // global alpha (night)
    private val addPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val overPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    /** Additive light for the fire; plain over-paint for the cats' flames, which burn over opaque rainbows. */
    private var paint = addPaint
    private val dst = RectF()
    private val mesh = FloatArray(68)
    private var sx = 0f
    private var sy = 0f
    private var svx = 0f

    private fun spr(c: Canvas, b: Bitmap, x: Float, y: Float, w: Float, h: Float, a: Float) {
        val al = a * am
        if (al <= .004f || w < .5f || h < .5f) return
        paint.alpha = (255 * min(1f, al)).toInt()
        dst.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
        c.drawBitmap(b, null, dst, paint)
    }

    /** A licking flame tongue: the sprite bent along a travelling two-octave wave. One drawBitmapMesh. */
    private fun tongue(c: Canvas, b: Bitmap, bx: Float, by: Float, w: Float, h: Float, a: Float, t: Float, ph: Float, sway: Float = 1f) {
        val al = a * am
        if (h < 2 || al <= .004f) return
        val tt = t / 1000
        val hf = h * (.78f + .4f * noise(tt * 7 + ph, 5))
        for (r in 0..16) {
            val v = 1 - r / 16f // bitmap row 0 is the tip
            val off = sway * w * .5f * v.pow(1.3f) * (snoise(tt * 2.6f - v * 1.3f + ph, 6) + .5f * snoise(tt * 6.5f - v * 3.1f + ph, 7))
            val y = by - hf * v
            mesh[r * 4] = bx - w / 2 + off; mesh[r * 4 + 1] = y
            mesh[r * 4 + 2] = bx + w / 2 + off; mesh[r * 4 + 3] = y
        }
        paint.alpha = (255 * min(1f, al)).toInt()
        c.drawBitmapMesh(b, 1, 16, mesh, 0, null, 0, paint)
    }

    /**
     * Flamelets: puffs emitted at [rate]/s over [t0, t1); [env] thins and scales them.
     * [spawn] (r1, r2, birth, index) sets (sx, sy) and an x velocity svx.
     */
    private inline fun flames(c: Canvas, t: Float, seed: Int, t0: Float, t1: Float, rate: Float, emax: Float,
                              l0: Float, l1: Float, v00: Float, v01: Float, acc: Float, z0: Float, z1: Float, grow: Float,
                              wob: Float, wobF: Float, alpha: Float, ramp: Array<Bitmap>,
                              env: (Float) -> Float, spawn: (Float, Float, Float, Int) -> Unit) {
        val slot = 1000 / rate
        val n = floor((t1 - t0) / slot).toInt()
        for (i in 0 until n) {
            val tb = t0 + (i + rnd(seed, i, 0)) * slot
            if (tb > t) break
            if (t - tb > l1 * 1000 * (.45f + .55f * emax)) continue // longest a puff can live
            val e = env(tb)
            if (e <= .01f || rnd(seed, i, 1) > e * 2) continue
            val k = min(e, emax)
            val life = lerp(l0, l1, rnd(seed, i, 2)) * (.45f + .55f * k)
            val a = (t - tb) / 1000
            if (a >= life) continue
            val u = a / life
            spawn(rnd(seed, i, 3), rnd(seed, i, 4), tb, i)
            val v0 = lerp(v00, v01, rnd(seed, i, 5)) * (.5f + .5f * k)
            val x = sx + svx * a + wob * u * sin(TAU * wobF * (.7f + .6f * rnd(seed, i, 7)) * a + TAU * rnd(seed, i, 8))
            val s = lerp(z0, z1, rnd(seed, i, 9)) * (.6f + .4f * min(k, 1.5f)) * (1 - .6f * u + grow * u)
            spr(c, ramp[min(15, (u * 16).toInt())], x, sy - v0 * a - .5f * acc * a * a, s, s * 1.5f,
                alpha * min(1f, u * 6) * (1 - u).pow(1.2f))
        }
    }

    private val buckets = Array(5) { FloatArray(256) }
    private val counts = IntArray(5)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val sparkN = intArrayOf(0xFFFFF1E0.toInt(), 0xFFFFD9B0.toInt(), 0xFFFF8A2A.toInt(), 0xFFFF5200.toInt(), 0xFFA02600.toInt())
    private val sparkA = intArrayOf(0xFFF7F3FF.toInt(), 0xFFECE4FF.toInt(), 0xFFA98CFF.toInt(), 0xFF6A45FF.toInt(), 0xFF35208F.toInt())

    /**
     * Ballistic sparks with linear drag ([tau] s) and gravity [g] px/s², drawn as streaks,
     * batched into 5 colour buckets by age (2 drawLines each). [spawn] (r1, r2, birth) sets (sx, sy).
     */
    private inline fun sparks(c: Canvas, t: Float, seed: Int, n: Int, argon: Boolean, a0: Float, a1: Float, s0: Float, s1: Float,
                              g: Float, tau: Float, l0: Float, l1: Float, w: Float, streak: Float,
                              tb: (Int, Float) -> Float, spawn: (Float, Float, Float) -> Unit) {
        counts.fill(0)
        for (i in 0 until n) {
            val born = tb(i, rnd(seed, i, 0))
            val a = (t - born) / 1000
            if (a < 0) continue
            val life = lerp(l0, l1, rnd(seed, i, 1))
            if (a >= life) continue
            spawn(rnd(seed, i, 2), rnd(seed, i, 3), born)
            val ang = Math.toRadians(lerp(a0, a1, rnd(seed, i, 4)).toDouble())
            val spd = lerp(s0, s1, rnd(seed, i, 5))
            val vx = (kotlin.math.cos(ang) * spd).toFloat(); val vy = (sin(ang) * spd).toFloat()
            val b = min(4, (a / life * 5).toInt())
            var arr = buckets[b]
            if (counts[b] + 4 > arr.size) { arr = arr.copyOf(arr.size * 2); buckets[b] = arr }
            val aa = max(0f, a - streak / 1000)
            for (q in 0..1) {
                val at = if (q == 0) aa else a
                val d = tau * (1 - exp(-at / tau))
                arr[counts[b]++] = sx + vx * d + if (q == 1) .01f else 0f
                arr[counts[b]++] = sy + g * tau * at + (vy - g * tau) * d
            }
        }
        val col = if (argon) sparkA else sparkN
        for (b in 0 until 5) {
            if (counts[b] == 0) continue
            val fade = 1 - b * .17f
            linePaint.color = col[3]; linePaint.alpha = (255 * min(1f, .3f * fade * am)).toInt(); linePaint.strokeWidth = w * 3.5f
            c.drawLines(buckets[b], 0, counts[b], linePaint)
            linePaint.color = col[b]; linePaint.alpha = (255 * min(1f, fade * am)).toInt(); linePaint.strokeWidth = w * (1 - b * .12f)
            c.drawLines(buckets[b], 0, counts[b], linePaint)
        }
    }

    private fun tubePath(s: Spot) = Path().apply {
        val w = s.cls.w; val h = s.cls.h; val r = w / 2; val x = s.x; val y = s.y
        moveTo(x, y + r)
        arcTo(RectF(x, y, x + w, y + w), 180f, 180f)
        lineTo(x + w, y + h - 8); quadTo(x + w, y + h, x + w - 8, y + h)
        lineTo(x + 8, y + h); quadTo(x, y + h, x, y + h - 8)
        close()
    }

    // ================================================================= glyph ink (where a digit catches fire)

    private val inks = HashMap<String, Ink?>()

    /**
     * Edge points of the digit's core stroke (flamelet spawns; top edges count twice) and
     * well-spaced top-edge anchors for the tongues, in tube-local px. Cached per glyph.
     */
    private fun ink(cls: Cls, ch: Char): Ink? = inks.getOrPut("$cls$ch") {
        val w = cls.w.toInt(); val h = cls.h.toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val p = art.textPaint(cls)
        Canvas(bmp).drawText(ch.toString(), art.textX(cls, ch, p), art.baseline(cls), p)
        val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        bmp.recycle()
        fun on(x: Int, y: Int) = x in 0 until w && y in 0 until h && (px[y * w + x] ushr 24) >= 128
        val pts = ArrayList<Float>(); val top = ArrayList<Float>(); val seen = HashSet<Int>()
        for (y in 3 until h - 3) for (x in 3 until w - 3) {
            if (!on(x, y)) continue
            val up = !on(x, y - 3)
            if (!up && on(x - 3, y) && on(x + 3, y)) continue
            if (!seen.add((x / 4) * 1000 + y / 4)) continue
            pts += x.toFloat(); pts += y.toFloat()
            if (up) { pts += x.toFloat(); pts += y.toFloat(); top += x.toFloat(); top += y.toFloat() }
        }
        if (pts.isEmpty()) return@getOrPut null
        val minD = if (cls.big) 18f else 10f
        val maxN = if (cls.big) 8 else 3
        val order = (0 until top.size / 2).sortedBy { top[2 * it + 1] }
        val picked = ArrayList<Int>()
        for (i in order) {
            if (picked.size >= maxN) break
            if (picked.all { hypot(top[2 * it] - top[2 * i], top[2 * it + 1] - top[2 * i + 1]) >= minD }) picked += i
        }
        val anchors = picked.sortedBy { top[2 * it] }.flatMap { listOf(top[2 * it], top[2 * it + 1]) }.toFloatArray()
        Ink(anchors, pts.toFloatArray())
    }

    // ================================================================= sprites (baked once, ≈1 MB)

    private val fireRamp = floatArrayOf(0f, .12f, .3f, .55f, .8f, 1f) to
        intArrayOf(0xFFF1E0, 0xFFD9B0, 0xFF8A2A, 0xFF5200, 0xA02600, 0x300A00)
    private val argonRamp = floatArrayOf(0f, .15f, .35f, .6f, .85f, 1f) to
        intArrayOf(0xF7F3FF, 0xECE4FF, 0xA98CFF, 0x6A45FF, 0x35208F, 0x100828)

    private fun rampAt(r: Pair<FloatArray, IntArray>, u: Float): Int {
        val (pos, col) = r
        for (i in 1 until pos.size) if (u <= pos[i]) {
            val f = (u - pos[i - 1]) / (pos[i] - pos[i - 1])
            return lerpRgb(col[i - 1], col[i], f)
        }
        return col.last()
    }

    private fun lerpRgb(a: Int, b: Int, f: Float): Int {
        fun ch(s: Int) = ((a shr s and 255) + ((b shr s and 255) - (a shr s and 255)) * f).toInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun argb(a: Float, rgb: Int) = ((255 * a.coerceIn(0f, 1f)).toInt() shl 24) or (rgb and 0xFFFFFF)

    /** Radial sprite: stops (pos, rgb, alpha); [sy] stretches it vertically. */
    private fun radial(size: Int, pos: FloatArray, rgb: IntArray, alpha: FloatArray, sy: Float = 1f): Bitmap {
        val bmp = Bitmap.createBitmap(size, (size * sy).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp).apply { scale(1f, sy) }
        val cols = IntArray(pos.size) { argb(alpha[it], rgb[it]) }
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply {
            shader = RadialGradient(size / 2f, size / 2f, size / 2f, cols, pos, Shader.TileMode.CLAMP)
        })
        return bmp
    }

    private fun puffs(r: Pair<FloatArray, IntArray>) = Array(16) { k ->
        val col = rampAt(r, k / 15f)
        radial(64, floatArrayOf(0f, .3f, .6f, 1f), IntArray(4) { col }, floatArrayOf(1f, .6f, .2f, 0f), 1.5f)
    }

    /** Flame tongue, 48×160, tip at the top: round base, pointed tip, white-hot core → red edges. */
    private fun tongueSprite(r: Pair<FloatArray, IntArray>): Bitmap {
        val w = 48; val h = 160
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val v = 1 - (y + .5f) / h
            val hw = .5f * w * .95f * kotlin.math.sqrt(clamp(v / .16f)) * (1 - v).pow(.7f)
            for (x in 0 until w) {
                val d = if (hw > 0) abs(x + .5f - w / 2f) / hw else 2f
                if (d >= 1) continue
                val a = (1 - d * d) * clamp(v / .05f) * (1 - v * v * v)
                px[y * w + x] = argb(a, rampAt(r, clamp(.04f + .8f * v + .35f * d)))
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Horizontal strip (bright at x=0) with a soft vertical falloff. */
    private fun strip(w: Int, h: Int, pos: FloatArray, rgb: IntArray, alpha: FloatArray): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), 0f, IntArray(pos.size) { argb(alpha[it], rgb[it]) }, pos, Shader.TileMode.CLAMP)
        })
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0, Color.BLACK, 0), null, Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
        return bmp
    }

    private val firep = puffs(fireRamp)
    private val argp = puffs(argonRamp)
    private val tongueN = tongueSprite(fireRamp)
    private val tongueA = tongueSprite(argonRamp)
    private val flare = radial(128, floatArrayOf(0f, .08f, .22f, .5f, 1f), intArrayOf(0xFFF6EC, 0xFFD9B0, 0xFF8A2A, 0xFF5200, 0xFF5200),
        floatArrayOf(1f, 1f, .55f, .18f, 0f))
    private val aflare = radial(128, floatArrayOf(0f, .08f, .22f, .5f, 1f), intArrayOf(0xF7F3FF, 0xECE4FF, 0xA98CFF, 0x6A45FF, 0x6A45FF),
        floatArrayOf(1f, 1f, .55f, .2f, 0f))
    private val ember = radial(16, floatArrayOf(0f, .18f, .32f, .6f, 1f), intArrayOf(0xFFF1E0, 0xFFD9B0, 0xFF8A2A, 0xFF5200, 0xFF5200),
        floatArrayOf(1f, 1f, .6f, .18f, 0f))
    private val jet = Bitmap.createBitmap(24, 48, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b)
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
        p.alpha = 140; c.drawBitmap(tongueN, null, RectF(2f, 0f, 22f, 46f), p)
        p.alpha = 255; c.drawBitmap(tongueA, null, RectF(5f, 20f, 19f, 48f), p)
    }
    private val manifold = strip(64, 24, floatArrayOf(0f, 1f), intArrayOf(0x6A45FF, 0x6A45FF), floatArrayOf(.45f, .45f))
    private val trailN = strip(128, 10, floatArrayOf(0f, .2f, .5f, 1f), intArrayOf(0xFFD9B0, 0xFF8A2A, 0xFF5200, 0xFF5200), floatArrayOf(1f, .6f, .25f, 0f))
    private val trailA = strip(128, 10, floatArrayOf(0f, .2f, .5f, 1f), intArrayOf(0xECE4FF, 0xA98CFF, 0x6A45FF, 0x6A45FF), floatArrayOf(1f, .6f, .25f, 0f))
    private val gasfill = Bitmap.createBitmap(8, 256, Bitmap.Config.ARGB_8888).also { b ->
        Canvas(b).drawRect(0f, 0f, 8f, 256f, Paint().apply {
            shader = LinearGradient(0f, 256f, 0f, 0f, intArrayOf(argb(.7f, 0xFF8A2A), argb(.35f, 0xFF5200), argb(0f, 0xFF5200)),
                floatArrayOf(0f, .3f, 1f), Shader.TileMode.CLAMP)
        })
    }
    private val heatPaint = Paint().apply {
        shader = LinearGradient(0f, 596f, 0f, 260f, 0xFFFF5200.toInt(), 0x00FF5200, Shader.TileMode.CLAMP)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    // ================================================================= maths

    private fun noise(x: Float, s: Int): Float {
        val i = floor(x).toInt(); val f = x - i
        return lerp(rnd(s, i, 99), rnd(s, i + 1, 99), f * f * (3 - 2 * f))
    }

    private fun snoise(x: Float, s: Int) = 2 * noise(x, s) - 1

    companion object {
        private const val NEVER = -1_000_000_000_000L // far past (Long.MIN_VALUE would overflow now − t0)
        private const val S = 0
        private const val M = 1
        private const val L = 2
        private const val XL = 3
        private const val LAND = 495f
        private const val RAIL_Y = 494f
        private const val TRAIL = 22
        private const val MINIS = 9 // mini cats per firework
        private const val TAU = (2 * PI).toFloat()
        private val FIRE_END = longArrayOf(800, 2400, 3500, 3750)
        // How a cat's flight ends.
        private const val BURST = 0
        private const val HOME = 1
        private const val DIVE = 2
        private const val EXIT = 3
        // Colony poses.
        const val SLEEP = 0
        const val HEAD_UP = 1
        const val SIT = 2
        const val POKE_MS = 4000L // a poked cat sits up (and its tag shows) this long
        private const val HOP_MS = 380L
        private const val HEART_MS = 900L
        private const val ROCKET_MS = 650L
        /** Coats: 0..3 frostings by repo rank (pink, blue, mint, lemon), then the rare and seasonal ones. */
        const val COATS = 6
        const val PUMPKIN = 4
        const val GOLD = 5
        private val FROST = intArrayOf(0xFFFF99EE.toInt(), 0xFF8CCBFF.toInt(), 0xFF9EF2C4.toInt(), 0xFFFFEE8A.toInt(),
            0xFFFF9A3C.toInt(), 0xFFFFE27A.toInt())
        private val SPRINKLE = intArrayOf(0xFFFF3399.toInt(), 0xFFFFEE33.toInt(), 0xFFFF3399.toInt(), 0xFF2AA8FF.toInt(),
            0xFF4A2A10.toInt(), 0xFFFFFFFF.toInt())
        private val CRUST = intArrayOf(0xFFFFCC99.toInt(), 0xFFFFCC99.toInt(), 0xFFFFCC99.toInt(), 0xFFFFCC99.toInt(),
            0xFFFFCC99.toInt(), 0xFFE0A93A.toInt())
        /** Sleeping sprite at scale 1, stage px. */
        internal const val SLEEP_W = 102f
        internal const val SLEEP_H = 60f

        /** The week's top 3 repos (by PRs, then name) wear frostings 0..2; everyone else 3. */
        fun rank(prs: List<Pr>): Map<String, Int> = prs.groupingBy { it.repo }.eachCount().entries
            .sortedWith(compareBy({ -it.value }, { it.key })).take(3).mapIndexed { i, e -> e.key to i }.toMap()

        /** A cat's coat (UX-PASS §5): rare gold (decided by the PR id, ~1 in 400) > pumpkin for Halloween week > repo rank. */
        fun coat(p: Pr, rank: Map<String, Int>, today: LocalDate): Int = when {
            rnd(p.id.hashCode(), 0, 88) < .0025f -> GOLD
            today.month == Month.OCTOBER && today.dayOfMonth >= 25 -> PUMPKIN
            else -> rank[p.repo] ?: 3
        }

        /** The other rare one (~1 in 400): a ghost cat, drawn at α .55. */
        fun ghost(p: Pr) = rnd(p.id.hashCode(), 0, 88).let { it >= .0025f && it < .005f }

        /**
         * Deterministic pile for the colony screen: one cat per PR (oldest first), scale by tier, coat by [coat], seed by
         * PR id. Fits inside the stage below y [top] (the label row, or the open-PR shelf) and >= 24 px from the edges;
         * shrinks all cats when crowded; returns at most [max] (newest kept) — caller shows "+N" for the rest.
         */
        fun pile(prs: List<Pr>, max: Int = 60, top: Float = 52f, coat: (Pr) -> Int = { Math.floorMod(it.repo.hashCode(), 4) }): List<Sleeper> {
            val ps = prs.sortedWith(compareBy<Pr>({ it.mergedAtMs }, { it.id })).takeLast(max)
            var k = 1f
            while (true) { stack(ps, k, top, coat)?.let { return it }; k *= .95f }
        }

        /**
         * Rows from the floor (y 560) up, oldest first, each cat resting on whatever is under it (so no cat is ever under
         * a newer one); odd rows hold less, so they sit brick-offset. Null if the pile pokes above [top] at shrink [k].
         * ponytail: no rounded-corner test — [24, 656] × [52, 560] already lies inside both corner arcs.
         */
        private fun stack(ps: List<Pr>, k: Float, top: Float, coat: (Pr) -> Int): List<Sleeper>? {
            val n = ps.size
            val seed = IntArray(n) { ps[it].id.hashCode() }
            val sc = FloatArray(n) { val c = ps[it].additions + ps[it].deletions; k * when { c < 100 -> .62f; c < 1239 -> .78f; c < 5000 -> .95f; else -> 1.15f } }
            val gap = FloatArray(n) { (2f + 6f * rnd(seed[it], 0, 3)) * k }
            val box = FloatArray(4 * n) // x0 y0 x1 y1 of each placed cat
            val out = ArrayList<Sleeper>(n)
            var i = 0
            var row = 0
            while (i < n) {
                val span = 632f - if (row % 2 == 1) .35f * SLEEP_W * k else 0f
                var j = i; var w = 0f
                while (j < n) {
                    val nw = if (j == i) SLEEP_W * sc[j] else w + gap[j - 1] + SLEEP_W * sc[j]
                    if (j > i && nw > span) break
                    w = nw; j++
                }
                var x = 24f + (632f - w) * (.3f + .4f * rnd(seed[i], row, 4))
                for (q in i until j) {
                    val cw = SLEEP_W * sc[q]; val ch = SLEEP_H * sc[q]
                    var b = 560f
                    for (m in 0 until q) if (box[4 * m] < x + cw && box[4 * m + 2] > x) b = min(b, box[4 * m + 1])
                    b -= 3f * k * rnd(seed[q], 0, 5)
                    if (b - ch < top) return null
                    box[4 * q] = x; box[4 * q + 1] = b - ch; box[4 * q + 2] = x + cw; box[4 * q + 3] = b
                    out += Sleeper(x + cw / 2, b - ch / 2, sc[q], rnd(seed[q], 0, 6) < .3f, coat(ps[q]),
                        seed[q], rnd(seed[q], 0, 7) < .33f, ps[q], ghost(ps[q]))
                    x += cw + gap[q]
                }
                i = j; row++
            }
            return out
        }

        // internal, not private: a private companion member costs a synthetic accessor per call, and these run thousands of times a frame
        internal fun rnd(s: Int, i: Int, k: Int): Float {
            var h = ((s + 1) * 0x27d4eb2d) xor ((i + 1) * 0x165667b1) xor ((k + 7) * 0x9e3779b1.toInt())
            h = (h xor (h ushr 15)) * 0x85ebca6b.toInt()
            h = (h xor (h ushr 13)) * 0xc2b2ae35.toInt()
            return (((h xor (h ushr 16)).toLong() and 0xFFFFFFFFL) / 4294967296.0).toFloat().coerceAtMost(.9999999f)
        }

        internal fun lerp(a: Float, b: Float, u: Float) = a + (b - a) * u
        internal fun clamp(v: Float) = v.coerceIn(0f, 1f)
        internal fun sstep(a: Float, b: Float, x: Float): Float { val u = clamp((x - a) / (b - a)); return u * u * (3 - 2 * u) }
        internal fun decay(t: Float, t0: Float, tau: Float) = if (t < t0) 0f else exp(-(t - t0) / tau)
        internal fun bump(t: Float, t0: Float, tau: Float) = if (t < t0) sstep(t0 - 65, t0, t) else exp(-(t - t0) / tau)
    }
}
