package co.tryterra.merged

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Achievements, V2.md §3. */
enum class Badge(val title: String, val rule: String, val gas: Gas) {
    NET_NEGATIVE("NET NEGATIVE", "DELETED MORE THAN YOU ADDED IN A DAY", Gas.ARGON),
    EARLY_BIRD("EARLY BIRD", "A MERGE BETWEEN 5 AND 9 AM", Gas.NEON),
    TEN_K("10K DAY", "10,000 LINES CHANGED IN A DAY", Gas.NEON),
    FRIDAY("FRIDAY SHIP", "A FRIDAY MERGE FROM 5 PM", Gas.NEON),
    SPEEDRUN("SPEEDRUN", "OPENED TO MERGED IN UNDER A MINUTE", Gas.NEON),
    GOAL("GOAL MET", "HIT THE DAILY GOAL", Gas.NEON),
    ON_A_ROLL("ON A ROLL", "A 5-DAY WORKDAY STREAK", Gas.NEON),
    CENTURY("CENTURY", "EVERY 100 ALL-TIME MERGED PRs", Gas.NEON),
}

class Earned(val badge: Badge, val key: String, val day: LocalDate)

/** Awards persisted as {key: ISO day} in prefs "state" key "badges"; a key is earned once, ever. */
class BadgeBook(private val prefs: SharedPreferences) {
    private var raw: String? = null
    private var days: Map<String, LocalDate> = emptyMap()
    private val counts = IntArray(Badge.entries.size)
    private val firsts = arrayOfNulls<LocalDate>(Badge.entries.size)
    private val lasts = arrayOfNulls<LocalDate>(Badge.entries.size)

    /** Re-parsed only when the stored JSON changed (also by another BadgeBook: debug reset, account switch). */
    private fun load(): Map<String, LocalDate> {
        val s = prefs.getString(KEY, null)
        if (s == raw) return days
        raw = s
        val j = runCatching { JSONObject(s ?: "{}") }.getOrDefault(JSONObject())
        days = j.keys().asSequence().mapNotNull { k -> runCatching { k to LocalDate.parse(j.getString(k)) }.getOrNull() }.toMap()
        counts.fill(0); firsts.fill(null); lasts.fill(null)
        for ((k, d) in days) {
            val i = Badge.entries.find { it.name == k.substringBefore(':') }?.ordinal ?: continue
            counts[i]++
            if (firsts[i]?.isAfter(d) != false) firsts[i] = d
            if (lasts[i]?.isBefore(d) != false) lasts[i] = d
        }
        return days
    }

    fun count(b: Badge): Int { load(); return counts[b.ordinal] }
    fun first(b: Badge): LocalDate? { load(); return firsts[b.ordinal] }
    fun last(b: Badge): LocalDate? { load(); return lasts[b.ordinal] }
    fun earnedSince(day: LocalDate): List<Badge> = Badge.entries.filter { last(it)?.isBefore(day) == false }
    val total: Int get() { load(); return counts.count { it > 0 } }

    /**
     * ON A ROLL was already earned in the run that started on [streakStart]. Once per run, and the run's start moves
     * earlier while the backfill is still filling the history, so any award on or after it counts.
     */
    fun rolled(streakStart: LocalDate?): Boolean = streakStart != null && load().keys.any { k -> k.startsWith("ON_A_ROLL:") &&
        runCatching { LocalDate.parse(k.substringAfter(':')) }.getOrNull()?.let { it >= streakStart } == true }

    fun evaluate(s: Snapshot, zone: ZoneId, goal: Int, streak: Int, streakStart: LocalDate?, prevLifetime: Int?): List<Earned> {
        val today = Instant.ofEpochMilli(s.fetchedAtMs).atZone(zone).toLocalDate()
        val have = load()
        val rolled = rolled(streakStart)
        val new = awards(s.prs, zone, goal, streak, streakStart, s.lifetime, prevLifetime, today)
            .filter { it.key !in have && !(rolled && it.badge == Badge.ON_A_ROLL) }
        if (new.isNotEmpty() || !prefs.contains(KEY)) { // the first evaluation is written even when empty: it's the silent one
            val j = JSONObject()
            for ((k, d) in have) j.put(k, d.toString())
            for (e in new) j.put(e.key, e.day.toString())
            prefs.edit().putString(KEY, j.toString()).apply()
        }
        return new
    }

    fun reset() { prefs.edit().remove(KEY).apply() }

    companion object {
        private const val KEY = "badges"

        /**
         * Every award the facts justify, seen before or not (pure, for BadgeRulesTest). Daily rules run per local day
         * of mergedAt in [zone]; lines are Pr.additions/deletions, which already exclude generated files.
         */
        fun awards(prs: List<Pr>, zone: ZoneId, goal: Int, streak: Int, streakStart: LocalDate?,
                   lifetime: Int, prevLifetime: Int?, today: LocalDate): List<Earned> {
            val out = ArrayList<Earned>()
            fun award(b: Badge, day: LocalDate, id: Any = day) { out += Earned(b, "${b.name}:$id", day) }
            prs.groupBy { Instant.ofEpochMilli(it.mergedAtMs).atZone(zone).toLocalDate() }.toSortedMap().forEach { (day, ps) ->
                val adds = ps.sumOf { it.additions }
                val dels = ps.sumOf { it.deletions }
                val hours = ps.map { Instant.ofEpochMilli(it.mergedAtMs).atZone(zone).hour }
                // Line rules wait until every PR that day has had its generated files taken out (a failed check retries).
                val checked = ps.all { it.genChecked }
                if (checked && dels > adds && dels > 0) award(Badge.NET_NEGATIVE, day)
                if (hours.any { it in 5..8 }) award(Badge.EARLY_BIRD, day)
                if (checked && adds + dels >= 10_000) award(Badge.TEN_K, day)
                if (day.dayOfWeek == DayOfWeek.FRIDAY && hours.any { it >= 17 }) award(Badge.FRIDAY, day)
                if (ps.any { it.createdAtMs > 0 && it.mergedAtMs - it.createdAtMs < 60_000 }) award(Badge.SPEEDRUN, day)
                if (ps.size >= goal) award(Badge.GOAL, day)
            }
            if (streak >= 5 && streakStart != null) award(Badge.ON_A_ROLL, today, streakStart)
            if (prevLifetime != null && lifetime / 100 > prevLifetime / 100) award(Badge.CENTURY, today, lifetime / 100 * 100)
            return out
        }

        /**
         * How close a badge is (UX-PASS #17): the detail card's line, and the shelf's short form under a locked badge
         * (null: nothing worth a fraction). [today] / [week]: the PRs merged today / this week.
         */
        fun progress(b: Badge, today: List<Pr>, week: List<Pr>, goal: Int, streak: Int, rolled: Boolean, lifetime: Int,
                     now: LocalDateTime): Pair<String, String?> {
            val adds = today.sumOf { it.additions }
            val dels = today.sumOf { it.deletions }
            return when (b) {
                Badge.NET_NEGATIVE -> "TODAY +%,d −%,d".format(adds, dels) to null
                Badge.EARLY_BIRD -> when {
                    now.hour in 5..8 -> "NOW · UNTIL 9 AM"
                    now.hour < 5 -> "NEXT CHANCE · 5 AM"
                    else -> "NEXT CHANCE · TOMORROW 5 AM"
                } to null
                Badge.TEN_K -> "TODAY %,d / 10,000 LINES".format(adds + dels) to "${(adds + dels) / 100 / 10.0}K/10K"
                Badge.FRIDAY -> (if (now.dayOfWeek == DayOfWeek.FRIDAY && now.hour >= 17) "NOW · ANY MERGE COUNTS"
                    else "NEXT CHANCE · FRI FROM 5 PM") to null
                Badge.SPEEDRUN -> (week.filter { it.createdAtMs > 0 }.minOfOrNull { it.mergedAtMs - it.createdAtMs }
                    ?.let { "FASTEST THIS WEEK · " + span(it) } ?: "NO TIMED MERGES THIS WEEK") to null
                Badge.GOAL -> "TODAY ${today.size} / $goal" to "${today.size}/$goal"
                Badge.ON_A_ROLL -> if (rolled) "EARNED THIS RUN" to null else "STREAK ${min(streak, 5)} / 5" to "${min(streak, 5)}/5"
                Badge.CENTURY -> (100 - lifetime % 100).let { n ->
                    "$n ${if (n == 1) "PR" else "PRs"} TO #%,d".format((lifetime / 100 + 1) * 100) to "$n TO GO"
                }
            }
        }

        private fun span(ms: Long): String {
            val m = ms / 60_000
            return when { m < 1 -> "UNDER A MINUTE"; m < 60 -> "$m MIN"; else -> "${m / 60}H ${m % 60}M" }
        }
    }
}

/**
 * Badge icons as Nixie cathode wire in a [BOX]-unit square, drawn at any size. Each icon is a few parts (a wire baked
 * with NixieArt's glow passes, or a LIFE tube glyph) so the unlock can move them separately; at rest every part sits
 * where it was baked, so the unlock's last frame is draw(lit).
 */
class BadgeArt(private val art: NixieArt) {

    /** A wire [path] in box units, or (k > 0) the LIFE glyph [ch] scaled by k with its ink centred at (x, y). */
    private class Part(val path: Path?, val fill: Boolean = false, val ch: Char = ' ', val x: Float = 0f, val y: Float = 80f, val k: Float = 0f) {
        lateinit var sp: Sprite
    }
    private class Icon(val ghost: Sprite, val parts: Array<Part>)

    private val icons = ConcurrentHashMap<Badge, Icon>()
    private val digits by lazy { Array(10) { art.glyph(Cls.LIFE, Gas.NEON, '0' + it) } } // TEN_K, CENTURY: neon
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val plus = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val halos = Gas.entries.map { RadialGradient(80f, 80f, 96f, it.glow, it.glow and 0xFFFFFF, Shader.TileMode.CLAMP) }
    private val ember = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private var dim = 1f

    init { Thread({ Badge.entries.forEach { icon(it) } }, "badge-prewarm").start() }

    private fun icon(b: Badge) = icons.computeIfAbsent(b) { bake(it) } // a draw mid-bake waits instead of baking twice

    /** Static icon centred at (cx, cy), [size] = icon box side (stage px). lit=false → unlit ghost wire. */
    fun draw(c: Canvas, b: Badge, cx: Float, cy: Float, size: Float, lit: Boolean, alpha: Float) {
        val ic = icon(b)
        enter(c, cx, cy, size, alpha)
        sprite(c, ic.ghost, 1f, 0f)
        if (lit) for (q in ic.parts) part(c, q, 1f, 0f)
        c.restore()
    }

    /**
     * The unlock at [t] ms (0..UNLOCK_MS): parts strike in (SPEC §8.1 flicker), overdrive + halo decay over ~1.8 s,
     * and each badge has its own motion. Pure function of t; overdrive, halo and pulses are additive light.
     */
    fun drawUnlock(c: Canvas, b: Badge, cx: Float, cy: Float, size: Float, t: Float, alpha: Float) {
        if (t >= UNLOCK_MS) return draw(c, b, cx, cy, size, true, alpha)
        val ic = icon(b)
        val p = ic.parts
        enter(c, cx, cy, size, alpha)
        val end = 1 - sstep(1900f, 2300f, t) // every flare is out before the static frame
        val od0 = decay(t, 220f, 450f) * end  // the strike-in's overdrive
        when (b) {
            Badge.NET_NEGATIVE -> { // the minus drains down the shaft into the arrow
                val drain = sstep(300f, 520f, t) * (1 - sstep(1050f, 1400f, t))
                val front = 52 + 172 * lin(380f, 1300f, t)
                val tip = decay(t, 780f, 380f) * end
                base(c, ic, b, .55f * od0 + .6f * tip)
                part(c, p[0], strike(t) * (1 - .6f * drain), od0 * (1 - drain))
                if (t > 380) {
                    c.save(); c.clipRect(-PAD, -PAD, BOX + PAD, front)
                    part(c, p[1], 1f, .2f * end + .9f * tip); c.restore()
                    c.save(); c.clipRect(-PAD, front - 16, BOX + PAD, front) // the white-hot drop at the front
                    add(c, p[1].sp, 1 - lin(1000f, 1200f, t)); c.restore()
                }
            }
            Badge.EARLY_BIRD -> { // the sun rises over the horizon, then its rays fan out
                val crest = decay(t, 1050f, 380f) * end
                val fan = decay(t, 1450f, 330f) * end
                base(c, ic, b, .45f * od0 + .7f * crest + .3f * fan)
                part(c, p[0], strike(t), od0)
                c.save(); c.clipRect(-PAD, -PAD, BOX + PAD, SUN_Y + 3 + 80 * sstep(1000f, 1350f, t)) // below the horizon
                c.translate(0f, 46 * (1 - easeOut(lin(220f, 1050f, t))))
                part(c, p[1], sstep(220f, 420f, t), .9f * crest); c.restore()
                for (i in 0..4) {
                    val u = back(lin(950f + 40 * abs(i - 2), 1450f, t))
                    c.save(); c.rotate((RAYS[i] + 90) * (u - 1), 80f, SUN_Y)
                    part(c, p[2 + i], sstep(950f, 1080f, t), .8f * fan); c.restore()
                }
            }
            Badge.TEN_K -> { // the three tubes roll, then land one by one
                base(c, ic, b, .35f * od0 + .55f * decay(t, 960f, 450f) * end)
                for (i in 0..2) {
                    val land = 560f + 200 * i
                    val q = p[i]
                    if (t < land) glyph(c, digits[(3 * i + (t / 45).toInt()) % 10], q.x, q.y, q.k, strike(t), .15f * end)
                    else glyph(c, q.sp, q.x, q.y, q.k, strike(t), decay(t, land, 380f) * end)
                }
            }
            Badge.FRIDAY -> { // the tongues lick (skewed about the base) and embers rise
                val env = sstep(220f, 420f, t) * (1 - sstep(1500f, 2100f, t))
                val a = strike(t)
                val od = od0 + .35f * env * (.5f + .5f * sin(TAU * t / 130))
                base(c, ic, b, .55f * od0 + .25f * env)
                val w = TAU * t / 1000
                for (i in 0..1) {
                    val sx = env * if (i == 0) .13f * sin(2.4f * w) + .05f * sin(5.9f * w + 1) else .2f * sin(3.3f * w + 2.2f)
                    val sy = 1 + env * if (i == 0) .07f * sin(3.2f * w + 2) else .12f * sin(4.3f * w)
                    c.save(); c.translate(80f, 138f); c.skew(sx, 0f); c.scale(1f, sy); c.translate(-80f, -138f)
                    part(c, p[i], a, od); c.restore()
                }
                for (k in 0..7) {
                    val u = (t - 300 - 150 * k) / 700
                    if (u <= 0 || u >= 1) continue
                    val x = 80 + 36 * (rnd(k) - .5f) + 7 * sin(TAU * (1.3f * u + rnd(k + 8)))
                    val y = 70 - 62 * u
                    val ea = min(1f, 6 * u) * (1 - u)
                    dot(c, x, y, 8f, b.gas.glow, .4f * ea)
                    dot(c, x, y, 3f, b.gas.core, ea)
                }
            }
            Badge.SPEEDRUN -> { // strikes top → bottom like lightning, then flashes white twice
                val f = (flash(t, 480f) + flash(t, 760f)) * end
                base(c, ic, b, .5f * od0 + .9f * f)
                c.save(); c.clipRect(-PAD, -PAD, BOX + PAD, 10 + 214 * lin(0f, 150f, t))
                part(c, p[0], strike(t), od0 + 1.4f * f); c.restore()
            }
            Badge.GOAL -> { // dot, then rings strike outward; three pulses ripple out through them
                var r1 = 0f; var r2 = 0f; var dot = 0f; var wave = 0f
                for (j in 0..2) {
                    val tw = 620f + 330 * j
                    val u = lin(tw, tw + 750, t)
                    if (u <= 0 || u >= 1) continue
                    val r = 54 * (.15f + 1.35f * u)
                    r1 += .9f * max(0f, 1 - abs(r - 30) / 12) * (1 - u)
                    r2 += .9f * max(0f, 1 - abs(r - 54) / 12) * (1 - u)
                    wave += .3f * (1 - u)
                    dot += .8f * decay(t, tw, 160f)
                }
                base(c, ic, b, (.5f * od0 + wave) * end)
                part(c, p[0], strike(t), od0 + dot * end)
                part(c, p[1], strike(t - 180), (decay(t, 400f, 450f) + r1) * end)
                part(c, p[2], strike(t - 360), (decay(t, 580f, 450f) + r2) * end)
                for (j in 0..2) {
                    val u = lin(620f + 330 * j, 1370f + 330 * j, t)
                    if (u <= 0 || u >= 1) continue
                    val s = .15f + 1.35f * u
                    c.save(); c.scale(s, s, 80f, 80f); add(c, p[2].sp, .75f * (1 - u).pow(1.5f) * end); c.restore()
                }
            }
            Badge.ON_A_ROLL -> { // the five links light in turn, then the 5
                var links = 0f
                for (i in 0..4) links += decay(t, 140f * i + 220, 300f)
                val five = decay(t, 1040f, 450f) * end
                base(c, ic, b, .15f * links * end + .7f * five)
                for (i in 0..4) part(c, p[i], strike(t - 140 * i), .9f * decay(t, 140f * i + 220, 300f) * end + .5f * five)
                part(c, p[5], strike(t - 820), 1.1f * five)
            }
            Badge.CENTURY -> { // counts up 0 → 100
                val n = (100 * (1 - (1 - lin(120f, 1250f, t)).pow(2.4f)) + 1e-4f).toInt()
                val land = decay(t, 1250f, 450f) * end
                base(c, ic, b, .3f * od0 + .8f * land)
                if (n >= 100) glyph(c, p[0].sp, p[0].x, p[0].y, p[0].k, strike(t - 1250), land)
                if (n >= 10) glyph(c, digits[n / 10 % 10], p[1].x, p[1].y, p[1].k, strike(t - T10), land)
                glyph(c, digits[n % 10], p[2].x, p[2].y, p[2].k, strike(t), land + .2f * od0)
            }
        }
        c.restore()
    }

    // ---------------------------------------------------------------- drawing (box units)

    private fun enter(c: Canvas, cx: Float, cy: Float, size: Float, alpha: Float) {
        dim = alpha
        c.save(); c.translate(cx, cy); c.scale(size / BOX, size / BOX); c.translate(-BOX / 2, -BOX / 2)
    }

    /** Halo (additive) under the ghost wire. */
    private fun base(c: Canvas, ic: Icon, b: Badge, halo: Float) {
        if (halo * dim > .01f) {
            haloPaint.shader = halos[b.gas.ordinal]
            haloPaint.alpha = (255 * (halo * dim).coerceIn(0f, 1f)).toInt()
            c.drawCircle(80f, 80f, 96f, haloPaint)
        }
        sprite(c, ic.ghost, 1f, 0f)
    }

    private fun part(c: Canvas, q: Part, a: Float, od: Float) =
        if (q.k == 0f) sprite(c, q.sp, a, od) else glyph(c, q.sp, q.x, q.y, q.k, a, od)

    private fun glyph(c: Canvas, sp: Sprite, x: Float, y: Float, k: Float, a: Float, od: Float) {
        c.save(); c.translate(x - Cls.LIFE.w * k / 2, y - .51f * Cls.LIFE.h * k); c.scale(k, k)
        sprite(c, sp, a, od); c.restore()
    }

    /** Lit at [a]; overdrive is the sprite again, additive (a second pass past 1 runs it white-hot). */
    private fun sprite(c: Canvas, sp: Sprite, a: Float, od: Float) {
        val al = a * dim
        if (al <= .004f) return
        paint.alpha = (255 * al.coerceIn(0f, 1f)).toInt()
        c.drawBitmap(sp.bmp, sp.dx, sp.dy, paint)
        if (od < .02f) return
        add(c, sp, min(od, 1f) * a)
        if (od > 1) add(c, sp, (od - 1) * a)
    }

    private fun add(c: Canvas, sp: Sprite, a: Float) {
        val al = a * dim
        if (al <= .004f) return
        plus.alpha = (255 * al.coerceIn(0f, 1f)).toInt()
        c.drawBitmap(sp.bmp, sp.dx, sp.dy, plus)
    }

    private fun dot(c: Canvas, x: Float, y: Float, w: Float, color: Int, a: Float) {
        ember.color = color; ember.alpha = (255 * (a * dim).coerceIn(0f, 1f)).toInt(); ember.strokeWidth = w
        c.drawPoint(x, y, ember)
    }

    // ---------------------------------------------------------------- baking

    private fun bake(b: Badge): Icon {
        val parts = geometry(b)
        for (q in parts) q.sp = q.path?.let { wire(it, q.fill, b.gas) } ?: art.glyph(Cls.LIFE, b.gas, q.ch)
        return Icon(ghost(parts, b.gas), parts)
    }

    /** NixieArt.bakeGlyph's recipe on a wire: blurred mid/glow passes under the core-coloured stroke. */
    private fun wire(path: Path, fill: Boolean, gas: Gas): Sprite {
        val r = RectF().also { path.computeBounds(it, true) }
        val e = W / 2 + PAD
        val left = floor(r.left - e)
        val top = floor(r.top - e)
        val bmp = Bitmap.createBitmap(ceil(r.right + e - left).toInt(), ceil(r.bottom + e - top).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp).apply { translate(-left, -top) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = W; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        for ((rad, col, a) in listOf(Triple(2f, gas.mid, 255), Triple(6f, gas.glow, 255), Triple(16f, gas.glow, 170), Triple(34f, gas.glow, 102))) {
            p.color = col; p.alpha = a; p.maskFilter = BlurMaskFilter(rad, BlurMaskFilter.Blur.NORMAL)
            c.drawPath(path, p)
        }
        p.maskFilter = null; p.color = gas.core
        c.drawPath(path, p)
        return Sprite(bmp, left, top)
    }

    /** Every cathode unlit: thin wire in gas.ghost, no glow (like the tubes' ghost digits). */
    private fun ghost(parts: Array<Part>, gas: Gas): Sprite {
        val bmp = Bitmap.createBitmap(BOX.toInt() + 8, BOX.toInt() + 8, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp).apply { translate(4f, 4f) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gas.ghost; strokeWidth = GW; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val tp = art.textPaint(Cls.LIFE).apply { color = gas.ghost }
        for (q in parts) {
            if (q.path != null) {
                p.style = if (q.fill) Paint.Style.FILL else Paint.Style.STROKE
                c.drawPath(q.path, p)
                continue
            }
            c.save(); c.translate(q.x - Cls.LIFE.w * q.k / 2, q.y - .51f * Cls.LIFE.h * q.k); c.scale(q.k, q.k)
            c.drawText(q.ch.toString(), art.textX(Cls.LIFE, q.ch, tp), art.baseline(Cls.LIFE), tp)
            c.restore()
        }
        return Sprite(bmp, -4f, -4f)
    }

    private fun wire(fill: Boolean = false, f: Path.() -> Unit) = Part(Path().apply(f), fill)
    private fun tube(ch: Char, x: Float, k: Float = .5f) = Part(null, ch = ch, x = x, k = k)
    private fun Path.arc(cx: Float, cy: Float, r: Float, a0: Float, sweep: Float) = arcTo(cx - r, cy - r, cx + r, cy + r, a0, sweep, false)

    private fun geometry(b: Badge): Array<Part> = when (b) {
        Badge.NET_NEGATIVE -> arrayOf(
            wire { moveTo(48f, 36f); lineTo(112f, 36f) },
            wire { moveTo(80f, 60f); lineTo(80f, 124f); moveTo(56f, 100f); lineTo(80f, 124f); lineTo(104f, 100f) },
        )
        Badge.EARLY_BIRD -> arrayOf(
            wire { moveTo(16f, SUN_Y); lineTo(144f, SUN_Y); moveTo(50f, SUN_Y + 16); lineTo(110f, SUN_Y + 16) },
            wire { moveTo(50f, SUN_Y); arc(80f, SUN_Y, 30f, 180f, 180f) },
            *Array(5) { i -> wire { moveTo(80 + 42 * cosd(RAYS[i]), SUN_Y + 42 * sind(RAYS[i])); lineTo(80 + 58 * cosd(RAYS[i]), SUN_Y + 58 * sind(RAYS[i])) } },
        )
        Badge.TEN_K -> arrayOf(tube('1', 36f), tube('0', 80f), tube('K', 124f))
        Badge.FRIDAY -> arrayOf(
            wire {
                moveTo(80f, 138f)
                cubicTo(54f, 138f, 40f, 120f, 44f, 98f); cubicTo(48f, 78f, 64f, 70f, 64f, 44f)
                cubicTo(80f, 56f, 86f, 72f, 84f, 86f); cubicTo(92f, 80f, 96f, 70f, 94f, 60f)
                cubicTo(112f, 76f, 120f, 100f, 114f, 116f); cubicTo(109f, 131f, 96f, 138f, 80f, 138f)
            },
            wire {
                moveTo(80f, 128f)
                cubicTo(68f, 128f, 62f, 118f, 66f, 106f); cubicTo(69f, 97f, 77f, 94f, 78f, 82f)
                cubicTo(90f, 92f, 97f, 106f, 93f, 117f); cubicTo(90f, 125f, 86f, 128f, 80f, 128f)
            },
        )
        Badge.SPEEDRUN -> arrayOf(wire { moveTo(96f, 22f); lineTo(52f, 88f); lineTo(78f, 88f); lineTo(64f, 138f); lineTo(110f, 70f); lineTo(84f, 70f); close() })
        Badge.GOAL -> arrayOf(
            wire(fill = true) { addCircle(80f, 80f, 7f, Path.Direction.CW) },
            wire { addCircle(80f, 80f, 30f, Path.Direction.CW) },
            wire { addCircle(80f, 80f, 54f, Path.Direction.CW) },
        )
        Badge.ON_A_ROLL -> Array(6) { i ->
            if (i == 5) tube('5', 80f, .4f)
            else wire { // a link: annular sector 46..58 with round ends, centred on the ring at -90° + 72°·i
                val f = -90f + 72 * i
                moveTo(80 + 58 * cosd(f - 25), 80 + 58 * sind(f - 25))
                arc(80f, 80f, 58f, f - 25, 50f)
                arc(80 + 52 * cosd(f + 25), 80 + 52 * sind(f + 25), 6f, f + 25, 180f)
                arc(80f, 80f, 46f, f + 25, -50f)
                arc(80 + 52 * cosd(f - 25), 80 + 52 * sind(f - 25), 6f, f + 155, 180f)
                close()
            }
        }
        Badge.CENTURY -> arrayOf(tube('1', 36f), tube('0', 80f), tube('0', 124f))
    }

    companion object {
        const val UNLOCK_MS = 2400L
        @Volatile private var shared: BadgeArt? = null

        /** One per process (the activity is recreated on the move to the rear); baking starts right away, off the UI thread. */
        fun get(art: NixieArt): BadgeArt = shared ?: synchronized(this) { shared ?: BadgeArt(art).also { shared = it } }

        private const val BOX = 160f   // icon box, bake units
        private const val PAD = 64f    // glow room, as NixieArt
        private const val W = 5.5f     // lit wire
        private const val GW = 2.4f    // ghost wire
        private const val SUN_Y = 101f // EARLY_BIRD horizon
        private const val TAU = 6.2831855f
        private val RAYS = floatArrayOf(-162f, -126f, -90f, -54f, -18f)
        private val T10 = 120 + 1130 * (1 - .9f.pow(1 / 2.4f)) // when CENTURY's count reaches 10

        private fun cosd(a: Float) = cos(Math.toRadians(a.toDouble())).toFloat()
        private fun sind(a: Float) = sin(Math.toRadians(a.toDouble())).toFloat()
        private fun lin(a: Float, b: Float, t: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
        private fun sstep(a: Float, b: Float, t: Float) = lin(a, b, t).let { it * it * (3 - 2 * it) }
        private fun decay(t: Float, t0: Float, tau: Float) = if (t < t0) 0f else exp(-(t - t0) / tau)
        private fun easeOut(u: Float) = 1 - (1 - u) * (1 - u)
        private fun back(u: Float) = (u - 1).let { 1 + it * it * (2.7f * it + 1.7f) } // ease-out with overshoot
        private fun rnd(k: Int) = (sin(k * 12.9898f) * 43758.547f).let { it - floor(it) }
        private fun flash(t: Float, t0: Float) = if (t < t0 - 20) 0f else if (t < t0) (t - t0 + 20) / 20 else exp(-(t - t0) / 80)

        /** SPEC §8.1 strike keyframes: 0:0, 30:.8, 60:.15, 120:.9, 160:.3, 220:1. */
        private val KT = floatArrayOf(0f, 30f, 60f, 120f, 160f, 220f)
        private val KV = floatArrayOf(0f, .8f, .15f, .9f, .3f, 1f)
        private fun strike(dt: Float): Float {
            if (dt < 0) return 0f
            for (i in 1 until KT.size) if (dt < KT[i]) return KV[i - 1] + (KV[i] - KV[i - 1]) * (dt - KT[i - 1]) / (KT[i] - KT[i - 1])
            return 1f
        }
    }
}
