package co.tryterra.merged

import android.app.NotificationManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Display
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * "Neon Tally" — a Nixie-tube counter for today's merged PRs and lines.
 * Full design: merged/design/SPEC.md (section refs below are to it).
 *
 * Battery contract: at rest nothing animates and no frame is drawn. Frames run
 * only while a tube is cycling/striking/glowing down, via
 * postInvalidateOnAnimation; future timeline steps use one postDelayed kick.
 */
class MeterView(ctx: Context) : View(ctx) {

    var debugCutoutPx = 0
        set(v) { field = v; invalidate() }
    var onLongPress: (() -> Unit)? = null
    /** true while frames are being driven (lets the activity raise the refresh rate). */
    var onAnimating: ((Boolean) -> Unit)? = null
    /** Rear is dozing: ask the activity to push one frame to the suspended panel. */
    var onNeedFrame: (() -> Unit)? = null

    /**
     * The rear in DOZE_SUSPEND (dim always-on, panel self-refreshing) or OFF can't
     * show animation: frames only land during a short draw wake lock. So "still"
     * means: no moments, no rolls — just put the right numbers up and push once.
     */
    fun isStill() = display?.state.let { it == Display.STATE_DOZE_SUSPEND || it == Display.STATE_OFF }

    private fun frame() { if (isStill()) onNeedFrame?.invoke() else invalidate() }

    /** Drop any moment in flight and snap every tube to [t]. The doze face is always Today. */
    private fun settle(t: Totals) {
        queue.clear(); timeline.clear(); momentUntil = 0; nextSlot = 0
        shows.clear(); showUntil = 0
        fx?.clear()
        leave(); screen = Screen.TODAY
        hero.forEach { it.lvl = 1f }
        applyInstant(t, fade = 0)
    }

    private val art = NixieArt.get(ctx.assets)
    /** Merge celebration (cats, then fire); baked on the first merge, not at launch. */
    private var fx: MergeFx? = null
    private fun fx() = fx ?: MergeFx(art).also { fx = it }
    private val prefs = ctx.getSharedPreferences("state", Context.MODE_PRIVATE)
    private val book = BadgeBook(prefs)
    /** Badge icons; baked the first time the Badges screen shows. */
    private val badgeArt = BadgeArt.get(art)
    private val vibrator = ctx.getSystemService(Vibrator::class.java)
    private val rnd = java.util.Random()

    // ---------------------------------------------------------------- tubes

    private inner class Tube(
        val x: Float, val y: Float, val cls: Cls, val gas: Gas,
        var lvl: Float = 1f, val commaSlot: Boolean = false,
    ) {
        var ch: Char? = null      // landed value (null = blank tube)
        var comma = false
        var cyEnd = 0L
        private var cyStart = 0L
        private var cyFrom = 0
        private var cyStep = 45L
        private var pending: FloatArray? = null // od, halo, tau to apply on landing
        private var strikeAt = -1L
        private var downAt = -1L
        private var downFace: Char? = null
        private var od0 = 0f
        private var odAt = 0L
        private var tau = 600f
        var halo = 0f
        var odFloor = 0f
        var whiteUntil = 0L
        private var lastFace: Char? = null
        var prevFace: Char? = null
        var switchAt = -1000L

        fun set(c: Char?, cm: Boolean) {
            ch = c; comma = cm && c != null; cyEnd = 0; pending = null; strikeAt = -1; downAt = -1; od0 = 0f
        }

        fun strike(at: Long) { strikeAt = at; downAt = -1 }

        fun flare(now: Long, od: Float, halo: Float, tau: Float = 600f) {
            od0 = od; odAt = now; this.halo = halo; this.tau = tau
        }

        /** Animate to [target]; [force] cycles even when it's unchanged (sweep / surge). */
        fun to(now: Long, target: Char?, cm: Boolean, steps: Int, stepMs: Long = 45, od: Float = 0f,
               halo: Float = 0f, tau: Float = 600f, strikeIfBlank: Boolean = false, force: Boolean = false) {
            val cur = ch
            if (target == null) {
                if (cur == null) return
                downFace = faceAt(now); downAt = now
                ch = null; comma = false; cyEnd = 0; pending = null
                return
            }
            if (target == cur && !force) { comma = cm; return }
            downAt = -1
            if (cur == null && strikeIfBlank) {
                ch = target; comma = cm; cyEnd = 0; strikeAt = now
                if (od > 0) flare(now + 220, od, halo, tau)
                return
            }
            cyFrom = faceAt(now)?.digitToIntOrNull() ?: rnd.nextInt(10)
            cyStart = now; cyStep = stepMs; cyEnd = now + steps * stepMs
            ch = target; comma = cm
            pending = if (od > 0) floatArrayOf(od, halo, tau) else null
        }

        fun faceAt(t: Long): Char? = when {
            downAt >= 0 && t - downAt < 300 -> downFace
            t < cyEnd -> '0' + (cyFrom + 1 + ((t - cyStart) / cyStep).toInt()) % 10
            else -> ch
        }

        /** Per-frame bookkeeping: apply landing flare, track digit switches for afterglow. */
        fun tick(t: Long) {
            pending?.let { if (t >= cyEnd) { flare(cyEnd, it[0], it[1], it[2]); pending = null } }
            val f = faceAt(t)
            if (f != lastFace) { prevFace = if (f == null) null else lastFace; switchAt = t; lastFace = f }
        }

        fun alpha(t: Long): Float = when {
            downAt >= 0 && t - downAt < 300 -> (1f - (t - downAt) / 300f).let { it * it }
            strikeAt >= 0 && t < strikeAt -> 0f
            strikeAt >= 0 && t - strikeAt < 220 -> strikeCurve(t - strikeAt)
            else -> 1f
        }

        private fun rawOd(t: Long) = if (t < odAt) od0 else od0 * exp(-(t - odAt) / tau)
        fun od(t: Long): Float = max(odFloor, rawOd(t).let { if (it < 0.02f) 0f else it })

        fun active(t: Long) = t < cyEnd || pending != null || rawOd(t) >= 0.02f || t < whiteUntil ||
            (downAt >= 0 && t - downAt < 300) || (strikeAt >= 0 && t - strikeAt < 220) || t - switchAt < 30
    }

    /** SPEC §8.1 strike keyframes: 0:0, 30:.8, 60:.15, 120:.9, 160:.3, 220:1. */
    private fun strikeCurve(dt: Long): Float {
        val k = longArrayOf(0, 30, 60, 120, 160, 220)
        val v = floatArrayOf(0f, .8f, .15f, .9f, .3f, 1f)
        for (i in 1 until k.size) if (dt < k[i]) return v[i - 1] + (v[i] - v[i - 1]) * (dt - k[i - 1]) / (k[i] - k[i - 1])
        return 1f
    }

    // --------------------------------------------------------------- layout (SPEC §4)

    private var hero = heroTubes(2)
    private val adds = List(5) { Tube(404f + 51 * it, 56f, Cls.SMALL, Gas.NEON, commaSlot = it == 1) }
    private val dels = List(5) { Tube(404f + 51 * it, 190f, Cls.SMALL, Gas.ARGON, 0.9f, commaSlot = it == 1) }
    private val week = List(3) { Tube(506f + 51 * it, 362f, Cls.SMALL, Gas.NEON, 0.6f) }
    private var life = lifeTubes(4)
    private val rail = List(7) { i ->
        val x = 24f + i * 92.83f
        List(2) { j -> Tube(x + 39 * j, 372f, Cls.RAIL, Gas.NEON, if (i == 6) 1f else 0.55f) }
    }

    private fun heroTubes(n: Int) = if (n == 2) List(2) { Tube(24f + 160 * it, 28f, Cls.HERO, Gas.NEON) }
        else List(3) { Tube(24f + 114 * it, 28f, Cls.HERO3, Gas.NEON) }

    private fun lifeTubes(n: Int) = if (n == 4) List(4) { Tube(24f + 156 * it, 46f, Cls.LIFE, Gas.NEON, commaSlot = it == 0) }
        else List(5) { Tube(24f + 124 * it, 46f, Cls.LIFE5, Gas.NEON, commaSlot = it == 1) }

    /** Swipe (or tap) through these, in this order; OPEN is last so it's one swipe right of Today. design/V2.md. */
    private enum class Screen { TODAY, LONG, RECENT, STREAK, WEEK, BADGES, CATS, OPEN }
    private var screen = Screen.TODAY

    private fun visibleTubes(): List<Tube> = when (screen) {
        Screen.TODAY -> hero + adds + dels + week
        Screen.LONG -> life + rail.flatten()
        Screen.RECENT -> radds + rdels
        Screen.STREAK -> stk + bestBank + todayBank
        Screen.WEEK -> wprs + wadds + wdels
        Screen.OPEN -> oReady + oWait + oNeeds
        Screen.BADGES, Screen.CATS -> emptyList()
    }

    /** Big counters on Streak / Week: 2 LIFE tubes, or 3 narrower ones from 100 (clear of the ± signs at x 382). */
    private fun bigTubes(n: Int) = if (n == 2) List(2) { Tube(24f + 156 * it, 46f, Cls.LIFE, Gas.NEON) }
        else List(3) { Tube(24f + 122 * it, 46f, Cls.LIFE5, Gas.NEON) }

    private var stk = bigTubes(2)
    private val bestBank = List(3) { Tube(506f + 51 * it, 56f, Cls.SMALL, Gas.NEON, 0.6f) }
    private val todayBank = List(2) { Tube(557f + 51 * it, 214f, Cls.SMALL, Gas.NEON) }
    private var wprs = bigTubes(2)
    private val wadds = List(5) { Tube(404f + 51 * it, 56f, Cls.SMALL, Gas.NEON, commaSlot = it == 1) }
    private val wdels = List(5) { Tube(404f + 51 * it, 190f, Cls.SMALL, Gas.ARGON, 0.9f, commaSlot = it == 1) }
    private val oReady = List(2) { Tube(557f + 51 * it, 52f, Cls.SMALL, Gas.NEON) }
    private val oWait = List(2) { Tube(557f + 51 * it, 210f, Cls.SMALL, Gas.NEON, 0.6f) }
    private val oNeeds = List(2) { Tube(557f + 51 * it, 368f, Cls.SMALL, Gas.ARGON, 0.9f) }

    // Recent PRs: a departures board of the last 5 merges; the banks cycle through each one's +/−.
    private val radds = List(5) { Tube(404f + 51 * it, 56f, Cls.SMALL, Gas.NEON, commaSlot = it == 1) }
    private val rdels = List(5) { Tube(404f + 51 * it, 190f, Cls.SMALL, Gas.ARGON, 0.9f, commaSlot = it == 1) }
    private var recent: List<Pr> = emptyList()
    private var recentIdx = 0
    private var recentFocus = false // a tapped PR's detail card is up (no auto-advance)
    private var recentCycle = 0     // token for the current auto-advance loop
    private var focusLayout: StaticLayout? = null

    /**
     * Right-aligned tube characters (null = blank, leading zeros blank, units
     * always lit) + whether the thousands comma lights. Overflow: k/M for the
     * line banks, clamp to all-9s otherwise. SPEC §6.2.
     */
    private fun digits(v: Int, n: Int, kM: Boolean = false): Pair<Array<Char?>, Boolean> {
        var s = v.coerceAtLeast(0).toString()
        var comma = v >= 1000 && n >= 4
        if (s.length > n) {
            if (kM) { s = if (v < 10_000_000) "${v / 1000}k" else "${v / 1_000_000}M"; comma = false }
            else { s = "9".repeat(n); comma = n >= 4 }
        }
        val out = arrayOfNulls<Char>(n)
        for (i in s.indices) out[n - s.length + i] = s[i]
        return out to comma
    }

    private fun setBank(tubes: List<Tube>, v: Int, kM: Boolean = false) {
        val (cs, comma) = digits(v, tubes.size, kM)
        tubes.forEachIndexed { i, t -> t.set(cs[i], comma && t.commaSlot) }
    }

    /** Units first, each tube to its left [stagger] later, cycling 6+place steps (SPEC §8.2 roll-up). */
    private fun rollBank(tubes: List<Tube>, v: Int, start: Long, kM: Boolean = false, stagger: Long = 110,
                         steps: (Int) -> Int = { 6 + it }, od: Float = 0f, halo: Float = 0f, tau: Float = 500f,
                         all: Boolean = false) {
        val (cs, comma) = digits(v, tubes.size, kM)
        tubes.forEachIndexed { i, t ->
            val place = tubes.size - 1 - i
            val target = cs[i]
            val cm = comma && t.commaSlot
            if (target == null && t.ch == null) return@forEachIndexed
            if (target == t.ch && !all) { t.comma = cm; return@forEachIndexed }
            at(start + place * stagger) { now -> t.to(now, target, cm, steps(place), od = od, halo = halo, tau = tau, force = all) }
        }
    }

    /** Hero digits; rebuilds the 2↔3 tube layout if the count crossed 99. */
    private fun rollHero(prs: Int, now: Long, steps: Int, od: Float, halo: Float, sympathetic: Boolean) {
        val n = if (prs >= 100) 3 else 2
        if (hero.size != n) { hero = heroTubes(n); setBank(hero, prs); strikeIn(now, 40, hero); return }
        val (cs, _) = digits(prs, n)
        hero.forEachIndexed { i, t ->
            val place = n - 1 - i
            if (cs[i] != t.ch) {
                at(now + place * 60L) { tt -> t.to(tt, cs[i], false, steps, od = od, halo = halo, strikeIfBlank = true) }
            } else if (sympathetic && t.ch != null) {
                at(now + steps * 45L) { tt -> t.flare(tt, 0.5f, 0.35f) }
            }
        }
    }

    // --------------------------------------------------------------- data → model (SPEC §6)

    private data class Totals(val prs: Int, val adds: Int, val dels: Int, val week: Int, val lifetime: Int, val days: List<Int>)

    private var snap: Snapshot? = null
    private var shown: Totals? = null
    private var signedOut = false
    private var cooled = false
    private var best90 = 0
    private var dayLetters = List(7) { "" }

    private fun startOf(d: LocalDate) = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun totals(s: Snapshot): Totals {
        val d0 = LocalDate.now()
        val today = s.prs.filter { it.mergedAtMs >= startOf(d0) }
        val days = (0..6).map { i ->
            val a = startOf(d0.minusDays(6L - i)); val b = startOf(d0.minusDays(5L - i))
            s.prs.count { it.mergedAtMs in a until b }
        }
        return Totals(today.size, today.sumOf { it.additions }, today.sumOf { it.deletions },
            max(s.windowCount, s.prs.size), s.lifetime, days)
    }

    private fun Totals.plus(p: Pr): Totals {
        val idx = 6 - ((startOf(LocalDate.now()) - p.mergedAtMs + DAY_MS - 1) / DAY_MS).toInt().coerceAtLeast(0)
        val isToday = idx == 6
        return copy(
            prs = prs + if (isToday) 1 else 0,
            adds = adds + if (isToday) p.additions else 0,
            dels = dels + if (isToday) p.deletions else 0,
            week = week + 1, lifetime = lifetime + 1,
            days = days.mapIndexed { i, v -> if (i == idx) v + 1 else v },
        )
    }

    private fun Totals.minus(p: Pr): Totals {
        val up = copy(prs = 0, adds = 0, dels = 0, week = 0, lifetime = 0, days = List(7) { 0 }).plus(p)
        return copy(prs = prs - up.prs, adds = adds - up.adds, dels = dels - up.dels, week = week - 1,
            lifetime = lifetime - 1, days = days.mapIndexed { i, v -> v - up.days[i] })
    }

    /**
     * Records the window's PRs/day into the persisted history (streaks, backfilled further by the activity)
     * and returns the 90-day personal best so it survives the 7-day fetch window. SPEC §6.4.
     */
    private fun updateBest(t: Totals): Int {
        val today = LocalDate.now()
        history = Progress.record(prefs, t.days.withIndex().associate { (i, v) -> today.minusDays(6L - i) to v }, today)
        val cutoff = today.minusDays(90)
        return history.filterKeys { it >= cutoff && it != today }.values.maxOrNull() ?: 0
    }

    private var history: Map<LocalDate, Int> = emptyMap()
    private var goal = Progress.DEFAULT_GOAL
    private var streak = 0

    private fun tierOf(churn: Int) = when { churn < 100 -> S; churn < 1239 -> M; churn < 5000 -> L; else -> XL }

    /** Called by the activity after every successful poll (and with cached data at launch). */
    fun show(s: Snapshot, fresh: List<Pr>) {
        val now = SystemClock.uptimeMillis()
        val prev = snap
        snap = s
        signedOut = false
        cooled = System.currentTimeMillis() - s.fetchedAtMs > STALE_MS
        val t = totals(s)
        best90 = updateBest(t)
        dayLetters = (0..6).map { LocalDate.now().minusDays(6L - it).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }
        contentDescription = "${t.prs} PRs merged today, ${t.adds} lines added, ${t.dels} removed"
        val today = LocalDate.now()
        goal = Progress.goal(context)
        streak = Progress.streak(history, today)
        val virgin = !prefs.contains("badges") // first evaluation awards the window silently
        val earned = book.evaluate(s, ZoneId.systemDefault(), goal, streak, Progress.streakStart(history, today), prev?.takeIf { it.login == s.login }?.lifetime)
        if (fresh.isNotEmpty()) buzz(fresh.maxOf { tierOf(it.additions + it.deletions) })
        if (isStill()) { shown = t; settle(t); frame(); return } // moments while dozing are skipped
        lamp(now, hold = 120, decay = 400)
        invalidate()

        val old = shown
        shown = t
        if (old == null) { // cold start: lay out, then strike the lit tubes in (SPEC §9)
            hero.forEach { it.lvl = 1f }
            applyInstant(t)
            setLabel(heroLabel, heroLabelText(t), false, now, 0)
            strikeIn(now, 40)
            return
        }
        // Long Count isn't animated (it's off-screen during moments); keep it current.
        if (t.lifetime != old.lifetime || t.days != old.days) applyLong(t)
        refreshScreen(prev, now)
        if (fresh.isEmpty()) {
            if (t != old) plainRoll(t, now)
            applyDayState(t, now)
            queueShows(prev, s, if (virgin) emptyList() else earned.filter { it.day == today }, false, now)
            return
        }
        // Base = the totals before these merges; then step up one PR at a time,
        // or one combined moment for a burst / after a gap (SPEC §8.4).
        var base = fresh.fold(t) { acc, p -> acc.minus(p) }
        val catchUp = prev == null || s.fetchedAtMs - prev.fetchedAtMs >= 30 * 60_000L
        val events = if (fresh.size > 3 || catchUp) listOf(event(base, t, fresh))
        else fresh.map { p -> val before = base; base = base.plus(p); event(before, base, listOf(p)) }
        queue.addAll(events)
        queueShows(prev, s, if (virgin) emptyList() else earned.filter { it.day == today }, events.any { it.goal }, now)
        if (now >= nextSlot) playNext(now)
        invalidate()
    }

    /**
     * Fresh merges landed: like a notification, by the ringer — silent or DND: nothing; vibrate: a short buzz (more
     * pulses for bigger merges); ringer on: the buzz and a chime (design/sfx: two rising notes, or for L/XL an
     * arpeggio and a little "mew"). Never at night.
     */
    private fun buzz(tier: Int, force: Boolean = false) {
        if (isNight() && !force) return
        val ringer = context.getSystemService(AudioManager::class.java).ringerMode
        val dnd = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
        if (ringer == AudioManager.RINGER_MODE_SILENT || dnd) return
        if (vibrator?.hasVibrator() == true) {
            val pulses = when (tier) { S -> 1; M -> 2; else -> 3 }
            val timings = ArrayList<Long>().apply { add(0); repeat(pulses) { if (it > 0) add(90); add(45) }; if (tier == XL) { add(140); add(320) } }
            val amps = IntArray(timings.size) { if (it % 2 == 0) 0 else if (tier == XL && it == timings.lastIndex) 200 else 255 }
            // Plain (no notification usage): HyperOS drops app vibrations tagged notification/ringtone
            // ("not vibrating for ringtones or notify for MIUI"), hence the ringer/DND checks above. We're the
            // rear display's resumed activity, so this counts as foreground even with the front screen in use.
            vibrator.vibrate(VibrationEffect.createWaveform(timings.toLongArray(), amps, -1))
        }
        if (ringer == AudioManager.RINGER_MODE_NORMAL) sfx.play(if (tier >= L) chimeBig else chimeSmall, 1f, 1f, 1, 0, 1f)
    }

    private val sfx = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(NOTIFY).build()
    private val chimeSmall = sfx.load(ctx, R.raw.merge_small, 1)
    private val chimeBig = sfx.load(ctx, R.raw.merge_big, 1)

    fun signedOut() {
        signedOut = true
        snap = null; shown = null
        queue.clear(); timeline.clear(); momentUntil = 0; nextSlot = 0
        shows.clear(); showUntil = 0
        fx?.clear()
        removeCallbacks(warm)
        (hero + adds + dels + week + life + rail.flatten()).forEach { it.set(null, false) }
        leave(); screen = Screen.TODAY
        setLabel(heroLabel, "SIGN IN ON THE PHONE", true, SystemClock.uptimeMillis(), 0)
        frame()
    }

    fun markStale() {
        val s = snap
        if (s != null && shown != null && totals(s) != shown) show(s, emptyList()) // day rolled over while offline
        if (s == null) { removeCallbacks(warm); hero.forEach { it.set(null, false) } } // offline, nothing cached: stop warming
        val was = cooled
        cooled = s == null || System.currentTimeMillis() - s.fetchedAtMs > STALE_MS
        if (cooled != was || s == null) frame()
    }

    /** The rear is dimming with the front screen off: put the doze face (Today) up while we can still draw. */
    fun dozing() { if (screen != Screen.TODAY || timeline.isNotEmpty() || queue.isNotEmpty() || shows.isNotEmpty()) shown?.let { settle(it); frame() } }

    /** Screen back on after a long dark spell: replay the cold-start strike-in. */
    fun wake() { if (shown != null && !isStill()) { strikeIn(SystemClock.uptimeMillis(), 40); invalidate() } }

    private fun applyInstant(t: Totals, fade: Long = 150) {
        if (hero.size != (if (t.prs >= 100) 3 else 2)) hero = heroTubes(if (t.prs >= 100) 3 else 2)
        setBank(hero, t.prs); setBank(adds, t.adds, kM = true); setBank(dels, t.dels, kM = true); setBank(week, t.week)
        applyLong(t)
        applyDayState(t, SystemClock.uptimeMillis(), fade)
    }

    private fun applyLong(t: Totals) {
        if (life.size != (if (t.lifetime >= 10_000) 5 else 4)) life = lifeTubes(if (t.lifetime >= 10_000) 5 else 4)
        setBank(life, t.lifetime)
        rail.forEachIndexed { i, pair -> setBank(pair, t.days[i]) }
    }

    /** Net-negative day + personal-best glow. SPEC §6.4. */
    private fun applyDayState(t: Totals, now: Long, fade: Long = 150) {
        val netNeg = t.dels > t.adds && t.dels > 0
        adds.forEach { it.lvl = if (netNeg) 0.75f else 1f }
        dels.forEach { it.lvl = if (netNeg) 1f else 0.9f }
        val hot = t.prs > best90 && best90 >= 5
        hero.forEach { it.odFloor = if (hot) 0.25f else 0f }
        if (now >= momentUntil) {
            setLabel(linesLabel, if (netNeg) "LINES · NET NEGATIVE" else "LINES", false, now, fade)
            setLabel(heroLabel, heroLabelText(t), false, now, fade)
        }
    }

    private fun heroLabelText(t: Totals) =
        if (t.prs > best90 && best90 >= 5) "PRs MERGED · TODAY · BEST" else "PRs MERGED · TODAY"

    /** Numbers moved without a fresh merge (late-found PR, midnight): roll quietly. */
    private fun plainRoll(t: Totals, now: Long) {
        rollHero(t.prs, now, 6, 0f, 0f, sympathetic = false)
        rollBank(adds, t.adds, now, kM = true)
        rollBank(dels, t.dels, now + 150, kM = true)
        rollBank(week, t.week, now, steps = { 6 })
    }

    // --------------------------------------------------------------- merge moments (SPEC §8)

    private class Event(
        val before: Totals, val after: Totals, val pa: Int, val pd: Int, val tier: Int,
        val repo: String, val title: String, val n: Int, val century: Int?, val goal: Boolean,
    )

    private val queue = ArrayDeque<Event>()
    private var nextSlot = 0L
    private var momentUntil = 0L
    private var lastMomentAt = -1_000_000L

    private fun event(before: Totals, after: Totals, prs: List<Pr>): Event {
        var tier = tierOf(prs.maxOf { it.additions + it.deletions })
        val century = if (after.lifetime / 100 > before.lifetime / 100) after.lifetime / 100 * 100 else null
        if (century != null) tier = XL
        if (best90 >= 5 && before.prs <= best90 && after.prs > best90) tier = max(tier, L)
        val goalHit = before.prs < goal && after.prs >= goal // hitting the daily goal is a big one too
        if (goalHit) tier = max(tier, L)
        val p = prs.last()
        return Event(before, after, prs.sumOf { it.additions }, prs.sumOf { it.deletions }, tier,
            p.repo.substringAfter('/'), p.title.replace(CONVENTIONAL, ""), prs.size, century, goalHit)
    }

    private fun playNext(now: Long) {
        val e = queue.removeFirstOrNull() ?: return runShows(now)
        // Cats zoom around first (not at night); the moment proper starts as the last one leaves.
        val lead = if (isNight()) 0L else fx().cats(now, e.tier)
        play(e, now + lead)
        nextSlot = now + lead + if (e.tier >= L) 3900 else 2900
        at(nextSlot) { playNext(it) }
    }

    private fun play(e: Event, t0: Long) {
        val night = isNight()
        val big = e.tier >= L
        if (screen != Screen.TODAY) { leave(); screen = Screen.TODAY } // a merge cuts straight back to Today
        momentUntil = t0 + if (e.tier == S) 3000 else 6000
        lastMomentAt = t0
        startFire(e, t0, night)
        lamp(t0, hold = 400, decay = 300)
        if (e.tier > S && !night) relayAt = t0 // (the buzz went off when the merge was detected)
        val heroText = when {
            e.century != null -> "PR #%,d · ALL-TIME".format(e.century)
            e.n > 1 -> "MERGED · ${e.n} PRs"
            else -> "MERGED · ${e.title}"
        }
        at(t0 + 90) { now ->
            setLabel(heroLabel, heroText, true, now)
            if (e.tier > S) setLabel(linesLabel, if (e.n > 1) "THESE ${e.n} PRs" else "THIS PR · ${e.repo}", true, now)
        }
        val heroSteps = if (e.tier == S) 5 else 9
        at(t0 + 90) { now ->
            rollHero(e.after.prs, now, heroSteps, if (e.tier == S) 0.6f else 1f,
                when { e.tier == S || night -> 0f; big -> 1f; else -> 0.8f }, sympathetic = e.tier > S)
        }
        if (e.tier == S) {
            at(t0 + 150) { now ->
                rollBank(adds, e.after.adds, now, kM = true, od = 0.6f)
                rollBank(dels, e.after.dels, now + 150, kM = true, od = 0.6f)
                rollBank(week, e.after.week, now + 300, steps = { 6 }, od = 0.6f)
            }
            at(t0 + 3000) { now -> if (lastMomentAt == t0) settleMoment(e, now) }
            return
        }
        // Delta beat: the banks show THIS PR's own +/−, overdriven, then roll up to the day totals.
        at(t0 + 120) { now -> rollBank(adds, e.pa, now, kM = true, stagger = 30, steps = { 6 }, od = .85f, halo = .35f, tau = 1800f, all = true) }
        at(t0 + 180) { now -> rollBank(dels, e.pd, now, kM = true, stagger = 30, steps = { 6 }, od = .85f, halo = .35f, tau = 1800f, all = true) }
        at(t0 + 420) { now -> signOd0 = 0.8f; signAt = now }
        if (e.tier == XL && !night) {
            at(t0 + 495) { now ->
                hero.forEach { it.whiteUntil = now + 150; it.flare(now, 1.4f, 1f) }
                (adds + dels + week).filter { it.ch != null }.forEach {
                    it.to(now, it.ch, it.comma, 6, od = 0.8f, halo = 0.3f, tau = 1000f, force = true)
                }
            }
        }
        val roll = t0 + if (big) 3040 else 1900
        at(roll) { now ->
            setLabel(linesLabel, if (e.after.dels > e.after.adds && e.after.dels > 0) "LINES · NET NEGATIVE" else "LINES", false, now)
            rollBank(adds, e.after.adds, now, kM = true, od = 0.6f, all = big)
            rollBank(dels, e.after.dels, now + 150, kM = true, od = 0.6f, all = big)
        }
        at(roll + 700) { now -> rollBank(week, e.after.week, now, steps = { 6 }, od = 0.6f) }
        at(t0 + 6000) { now -> if (lastMomentAt == t0) settleMoment(e, now) }
    }

    /** End of a moment: a poll that landed mid-moment may have moved the totals past [e].after — roll to them. */
    private fun settleMoment(e: Event, now: Long) {
        val t = shown ?: return
        if (t != e.after) plainRoll(t, now)
        applyDayState(t, now)
    }

    /** Flashover needs where each digit will be and what it will show when it catches fire. */
    private fun startFire(e: Event, t0: Long, night: Boolean) {
        val n = if (e.after.prs >= 100) 3 else 2
        val (cs, _) = digits(e.after.prs, n)
        val heroAfter = heroTubes(n).mapIndexed { i, t -> MergeFx.Spot(t.x, t.y, t.cls, cs[i]) }
        val changed = heroAfter.indices.map { hero.size != n || hero[it].ch != cs[it] }
        fun delta(tubes: List<Tube>, v: Int) = digits(v, tubes.size, kM = true).first.let { d ->
            tubes.mapIndexed { i, t -> MergeFx.Spot(t.x, t.y, t.cls, d[i]) }
        }
        fx().fire(t0, e.tier, night, heroAfter, changed, delta(adds, e.pa), delta(dels, e.pd))
    }

    // --------------------------------------------------------------- timeline + ambient

    private class Step(val at: Long, val run: (Long) -> Unit)
    private val timeline = ArrayList<Step>()

    private fun at(time: Long, run: (Long) -> Unit) { timeline += Step(time, run); invalidate() }

    /**
     * Earliest step first, each run at its *scheduled* time, so a moment that went
     * overdue while the screen was off collapses to the right end state instead of
     * interleaving its delta-beat and roll-up sub-steps.
     */
    private fun runDue(now: Long) {
        while (true) {
            val step = timeline.filter { it.at <= now }.minByOrNull { it.at } ?: return
            timeline.remove(step)
            step.run(step.at)
        }
    }

    private fun strikeIn(now: Long, gap: Long, tubes: List<Tube> = visibleTubes()) {
        tubes.filter { it.ch != null }.sortedWith(compareBy({ it.x }, { it.y }))
            .forEachIndexed { i, t -> t.strike(now + i * gap) }
    }

    private var longToken = 0

    private fun busy(now: Long) = shown == null || now < momentUntil || queue.isNotEmpty() || now < showUntil || shows.isNotEmpty()

    /** Next (+1) or previous (−1) view; anything but Today returns there after 20 s. */
    private fun nextScreen(dir: Int) {
        val now = SystemClock.uptimeMillis()
        if (busy(now)) return
        val all = Screen.entries
        leave()
        screen = all[(screen.ordinal + dir + all.size) % all.size]
        enter(now)
        strikeIn(now, 30)
        armReturn(now)
        frame()
    }

    /** Anything but Today returns there after 20 s without a touch. */
    private fun armReturn(now: Long) {
        val token = ++longToken
        if (screen != Screen.TODAY) at(now + 20_000) { if (token == longToken && screen != Screen.TODAY) goHome(it) }
    }

    private fun goHome(now: Long) { leave(); screen = Screen.TODAY; strikeIn(now, 30); frame() }

    /** Screen-specific setup as a screen comes up. */
    private fun enter(now: Long) {
        when (screen) {
            Screen.RECENT -> startRecent(now)
            Screen.STREAK -> enterStreak(now, surge = false)
            Screen.WEEK -> startRecap(now)
            Screen.OPEN -> enterOpen(now)
            Screen.CATS -> fx().colony(MergeFx.pile(snap?.prs.orEmpty(), COLONY_MAX))
            Screen.TODAY, Screen.LONG, Screen.BADGES -> Unit
        }
    }

    /** Drop the screen's transient state (focus cards, a recap or cat flight in progress). */
    private fun leave() {
        recentFocus = false; openFocus = -1; badgeFocus = null; unlocking = null; alertId = null
        recapToken++
        if (screen == Screen.CATS) fx?.colony(emptyList())
    }

    /** New data while a screen other than Today is up: keep its numbers current. */
    private fun refreshScreen(prev: Snapshot?, now: Long) {
        if (busy(now)) return
        when (screen) {
            Screen.STREAK -> enterStreak(now, surge = false)
            Screen.OPEN -> if (openFocus < 0) enterOpen(now)
            Screen.CATS -> if (prev?.prs?.map { it.id } != snap?.prs?.map { it.id }) enter(now)
            else -> Unit
        }
    }

    // --------------------------------------------------------------- showcases (design/V2.md)
    // After a merge moment, or when a poll brings news, the screen cuts away for a few seconds
    // (goal surge, badge unlocks, an open PR turning green/red, the Friday recap), then back to Today.

    private class Show(val screen: Screen, val ms: Long, val start: (Long) -> Unit)
    private val shows = ArrayDeque<Show>()
    private var showUntil = 0L

    private fun queueShows(prev: Snapshot?, s: Snapshot, earned: List<Earned>, goalHit: Boolean, now: Long) {
        if (isNight()) return
        if (goalHit) shows += Show(Screen.STREAK, 3400) { enterStreak(it, surge = true) }
        earned.filter { it.badge != Badge.GOAL }.distinctBy { it.badge }.forEach { shows += unlockShow(it.badge) }
        if (prev != null) {
            val was = prev.open.associate { it.id to it.status.light }
            s.open.filter { p -> was[p.id].let { it != null && it != p.status.light } && p.status.light.let { it == OpenPr.Light.GREEN || it == OpenPr.Light.RED } }
                .minByOrNull { it.status.light.ordinal }?.let { shows += alertShow(it) }
        }
        if (recapDue(s)) shows += Show(Screen.WEEK, recapMs() + 3500) {
            prefs.edit().putString("recapWeek", LocalDate.now().with(DayOfWeek.MONDAY).toString()).apply(); startRecap(it)
        }
        if (queue.isEmpty()) kickShows(now)
    }

    private fun unlockShow(b: Badge) = Show(Screen.BADGES, BadgeArt.UNLOCK_MS + 900) { unlocking = b; unlockAt = it + 150 }
    private fun alertShow(p: OpenPr) = Show(Screen.OPEN, 6000) { alertId = p.id; alertAt = it + 200; enterOpen(it) }
    private fun recapShow() = Show(Screen.WEEK, recapMs() + 3500) { startRecap(it) }

    /** Fri from 17:00, or the weekend, once per week (marked when it plays), if anything merged this week. */
    private fun recapDue(s: Snapshot): Boolean {
        val now = LocalDateTime.now()
        val d = now.dayOfWeek
        if (!(d == DayOfWeek.FRIDAY && now.hour >= 17 || d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY)) return false
        val monday = now.toLocalDate().with(DayOfWeek.MONDAY)
        return display?.state == Display.STATE_ON && prefs.getString("recapWeek", null) != monday.toString() &&
            shows.none { it.screen == Screen.WEEK } && s.prs.any { it.mergedAtMs >= startOf(monday) }
    }

    private fun kickShows(now: Long) { if (queue.isEmpty() && now >= showUntil && shows.isNotEmpty()) runShows(now) }

    /** Play the next showcase (after the moment, if one is still up); the last one hands back to Today. */
    private fun runShows(now: Long) {
        if (queue.isNotEmpty() || now < showUntil) return // a moment's (or show's) own chain gets back here
        if (now < momentUntil) { at(momentUntil + 200) { runShows(it) }; return }
        if (isStill() || isNight()) { shows.clear(); return }
        val sh = shows.removeFirstOrNull() ?: return
        longToken++ // a pending 20 s return would cut it short
        leave()
        screen = sh.screen
        sh.start(now)
        strikeIn(now, 30)
        showUntil = now + sh.ms
        at(showUntil) { t -> showUntil = 0; if (shows.isNotEmpty()) runShows(t); if (showUntil == 0L && screen != Screen.TODAY) goHome(t) }
        frame()
    }

    /** Debug builds (DebugReceiver `--es show …`): goal | badge:NAME | badge:all | open | recap | cats | screen:NAME | buzz:S…XL (even at night). */
    fun debug(cmd: String) {
        val now = SystemClock.uptimeMillis()
        val s = snap ?: return
        when {
            cmd == "goal" -> shows += Show(Screen.STREAK, 3400) { enterStreak(it, surge = true) }
            cmd == "badge:all" -> Badge.entries.forEach { shows += unlockShow(it) }
            cmd.startsWith("badge:") -> shows += unlockShow(Badge.valueOf(cmd.substringAfter(':')))
            cmd == "open" -> s.open.minByOrNull { it.status.light.ordinal }?.let { shows += alertShow(it) }
            cmd == "recap" -> shows += recapShow()
            cmd.startsWith("buzz:") -> buzz(listOf("S", "M", "L", "XL").indexOf(cmd.substringAfter(':')).coerceAtLeast(0), force = true)
            cmd == "cats" -> { if (busy(now)) return; leave(); screen = Screen.CATS; enter(now); fx().wake(now); armReturn(now); frame() }
            cmd.startsWith("screen:") -> { if (busy(now)) return; leave(); screen = Screen.valueOf(cmd.substringAfter(':')); enter(now); strikeIn(now, 30); armReturn(now); frame() }
        }
        kickShows(now)
    }

    // --------------------------------------------------------------- streak + goal

    private var barFrom = 0f
    private var barTo = 0f
    private var barAt = -10_000L
    private var surgeAt = -10_000L

    private fun enterStreak(now: Long, surge: Boolean) {
        val t = shown ?: return
        val today = LocalDate.now()
        val n = if (streak >= 100) 3 else 2
        if (stk.size != n) stk = bigTubes(n)
        setBank(stk, streak); setBank(bestBank, Progress.bestStreak(history, today)); setBank(todayBank, t.prs)
        val target = t.prs / goal.toFloat()
        // The bar fills up as the screen arrives; the goal-hitting merge surges it from the last PR's mark.
        barFrom = if (surge) max(0f, (t.prs - 1) / goal.toFloat()) else 0f
        barTo = target; barAt = now + 250
        surgeAt = if (surge) barAt + BAR_MS else -10_000L
    }

    private fun bar(now: Long): Float {
        val u = ((now - barAt) / BAR_MS.toFloat()).coerceIn(0f, 1f)
        return barFrom + (barTo - barFrom) * (1 - (1 - u) * (1 - u) * (1 - u))
    }

    // --------------------------------------------------------------- week recap

    private var weekStats: Progress.Week? = null
    private var recapDay = 6 // day columns revealed so far (Mon = 0)
    private var recapToken = 0
    private val bestLabel = Label("")
    private val sumLabel = Label("")

    private fun recapMs() = 350L + 350L * LocalDate.now().dayOfWeek.value + 900

    /** Banks from zero, then day by day the day lights and the tubes roll up to the running totals. */
    private fun startRecap(now: Long) {
        val s = snap ?: return
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val w = Progress.week(s.prs, today, zone, goal).also { weekStats = it }
        val n = if (w.prs >= 100) 3 else 2
        if (wprs.size != n) wprs = bigTubes(n)
        setBank(wprs, 0); setBank(wadds, 0, kM = true); setBank(wdels, 0, kM = true)
        recapDay = -1
        setLabel(bestLabel, "", false, now, 0); setLabel(sumLabel, "", false, now, 0)
        val token = ++recapToken
        var p = 0; var a = 0; var d = 0
        val last = today.dayOfWeek.value - 1
        for (i in 0..last) {
            val day = s.prs.filter { Instant.ofEpochMilli(it.mergedAtMs).atZone(zone).toLocalDate() == w.start.plusDays(i.toLong()) }
            p += day.size; a += day.sumOf { it.additions }; d += day.sumOf { it.deletions }
            val pp = p; val aa = a; val dd = d
            at(now + 350 + 350L * i) { tt ->
                if (token != recapToken) return@at
                recapDay = i
                rollBank(wprs, pp, tt, stagger = 60, steps = { 4 }, od = 0.4f)
                rollBank(wadds, aa, tt, kM = true, stagger = 40, steps = { 4 }, od = 0.4f)
                rollBank(wdels, dd, tt + 60, kM = true, stagger = 40, steps = { 4 }, od = 0.4f)
            }
        }
        val end = now + 350 + 350L * (last + 1)
        at(end + 150) { tt -> if (token == recapToken) setLabel(bestLabel, bestText(w), false, tt, 400) }
        at(end + 500) { tt -> if (token == recapToken) setLabel(sumLabel, summaryText(w), false, tt, 400) }
    }

    private fun bestText(w: Progress.Week) = w.best?.let { "BEST · ${it.repo.substringAfter('/').uppercase()} #${it.number} · +%,d −%,d".format(it.additions, it.deletions) }
        ?: "NO MERGES YET THIS WEEK"

    private fun summaryText(w: Progress.Week): String {
        val badges = book.earnedSince(w.start).size
        return "STREAK $streak · GOAL ${w.goalDays} / ${w.workdaysSoFar} DAYS · $badges ${if (badges == 1) "BADGE" else "BADGES"}"
    }

    // --------------------------------------------------------------- open PRs

    private var openRows: List<OpenPr> = emptyList()
    private var openFocus = -1 // row whose detail card is up
    private var alertId: String? = null
    private var alertAt = -10_000L

    private fun enterOpen(now: Long) {
        val open = snap?.open.orEmpty()
        val sorted = open.sortedWith(compareBy({ it.status.light.ordinal }, { -it.updatedAtMs }))
        val alert = sorted.firstOrNull { it.id == alertId } // an alert row is always on the board, first
        openRows = (listOfNotNull(alert) + sorted.filter { it.id != alertId }).take(5)
        openFocus = -1
        setBank(oReady, open.count { it.status.light == OpenPr.Light.GREEN })
        setBank(oWait, open.count { it.status.light == OpenPr.Light.AMBER })
        setBank(oNeeds, open.count { it.status.light == OpenPr.Light.RED })
        focusLayout = null
    }

    private fun tapOpen(sx: Float, sy: Float) {
        val now = SystemClock.uptimeMillis()
        if (busy(now)) return
        if (openFocus >= 0) openFocus = -1
        else {
            val row = floor((sy - 52f) / 86f).toInt()
            if (sx > 392f || sy < 52f || row !in openRows.indices) { nextScreen(+1); return }
            openFocus = row; focusLayout = null
        }
        armReturn(now); frame()
    }

    // --------------------------------------------------------------- badges

    private var badgeFocus: Badge? = null
    private var unlocking: Badge? = null
    private var unlockAt = -10_000L

    private fun tapBadges(sx: Float, sy: Float) {
        val now = SystemClock.uptimeMillis()
        if (busy(now)) return
        if (badgeFocus != null) badgeFocus = null
        else {
            val col = floor((sx - 24f) / 158f).toInt(); val row = floor((sy - 52f) / 240f).toInt()
            val b = Badge.entries.getOrNull(row * 4 + col)
            if (col !in 0..3 || row !in 0..1 || b == null) { nextScreen(+1); return }
            badgeFocus = b; focusLayout = null
        }
        armReturn(now); frame()
    }

    private fun tapCats() {
        val now = SystemClock.uptimeMillis()
        if (busy(now) || isStill() || isNight()) return
        fx().wake(now)
        armReturn(now); invalidate()
    }

    private fun startRecent(now: Long) {
        recent = snap?.prs?.sortedByDescending { it.mergedAtMs }?.take(5).orEmpty()
        recentIdx = 0
        recentFocus = false
        showRecent(now, instant = true)
        cycleRecent(now)
    }

    /** Advance the highlight every 2.6 s while the list (not a focused card) is up. */
    private fun cycleRecent(now: Long) {
        val token = ++recentCycle
        fun tick(t: Long) {
            if (screen != Screen.RECENT || recentFocus || token != recentCycle || recent.size < 2) return
            recentIdx = (recentIdx + 1) % recent.size
            showRecent(t, instant = false)
            at(t + 2600) { tick(it) }
        }
        at(now + 2600) { tick(it) }
    }

    /** Tap on the Recent board: a row opens that PR's detail card; tapping the card goes back to the list. */
    private fun tapRecent(sx: Float, sy: Float) {
        val now = SystemClock.uptimeMillis()
        if (busy(now)) return
        if (recentFocus) {
            recentFocus = false
            cycleRecent(now)
        } else {
            val row = floor((sy - 52f) / 86f).toInt()
            if (sx > 392f || sy < 52f || row !in recent.indices) { nextScreen(+1); return }
            recentFocus = true
            focusLayout = null
            if (row != recentIdx) { recentIdx = row; showRecent(now, instant = false) }
        }
        armReturn(now)
        frame()
    }

    private fun showRecent(now: Long, instant: Boolean) {
        val p = recent.getOrNull(recentIdx)
        val a = p?.additions ?: 0
        val d = p?.deletions ?: 0
        if (instant) { setBank(radds, a, kM = true); setBank(rdels, d, kM = true) }
        else { rollBank(radds, a, now, kM = true, od = 0.5f); rollBank(rdels, d, now + 120, kM = true, od = 0.5f) }
    }

    /** Double-tap: celebrate the most recent merge again (Today rewinds to just before it). */
    private fun replay() {
        val now = SystemClock.uptimeMillis()
        val t = shown ?: return
        val p = snap?.prs?.maxByOrNull { it.mergedAtMs } ?: return
        if (busy(now) || isStill()) return
        val b = t.minus(p)
        val n = if (b.prs >= 100) 3 else 2
        if (hero.size != n) hero = heroTubes(n)
        setBank(hero, b.prs); setBank(adds, b.adds, kM = true); setBank(dels, b.dels, kM = true); setBank(week, b.week)
        leave(); screen = Screen.TODAY; longToken++
        queue.add(event(b, t, listOf(p)))
        playNext(now)
        invalidate()
    }

    /** No snapshot yet: hero tubes shuffle dimly (clearly not a value). Never show 0 before data. */
    private val warm = object : Runnable {
        override fun run() {
            if (shown != null || signedOut || isStill()) return
            hero.forEach { it.set('0' + rnd.nextInt(10), false); it.lvl = 0.35f }
            invalidate()
            postDelayed(this, 125)
        }
    }

    /** Every 5 min: pixel drift (burn-in); every 15 min: anti-poisoning sweep. SPEC §9. */
    private val ambient = object : Runnable {
        var ticks = 0
        override fun run() {
            val now = SystemClock.uptimeMillis()
            snap?.let { cooled = System.currentTimeMillis() - it.fetchedAtMs > STALE_MS }
            if (++ticks % 3 == 0 && shown != null && !isStill() && now - lastMomentAt > 120_000 && now >= momentUntil) {
                visibleTubes().filter { it.ch != null }.forEach { t ->
                    at(now + ((t.x + t.cls.w / 2) / 680f * 900).toLong()) { tt -> t.to(tt, t.ch, t.comma, 10, 30, force = true) }
                }
            }
            if (drift() != drawnDrift) frame() // pixel drift (burn-in) — pushed to the panel even while dozing
            postDelayed(this, 5 * 60_000L)
        }
    }

    // --------------------------------------------------------------- touch
    // Only reaches us while the rear is awake: once it dims (10 s after the last touch), HyperOS
    // swallows touches until a double-tap wakes it (secure double_tap_to_wake).
    // Tap: next view (Recent/Open rows and Badges open a detail card, Cats wake, Week replays).
    // Double-tap: replay the last merge. Swipe ←/→: next/previous view.
    // Drag (held, or still moving after a flick would be over): a cat chases the finger;
    // let go and it's flung, then bursts. Long-press still: Setup on the front screen.

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var dragging = false
    private var lastTapAt = -1000L
    private var vt: VelocityTracker? = null
    private var tapX = 0f
    private var tapY = 0f
    private val singleTap = Runnable {
        when (screen) {
            Screen.RECENT -> tapRecent(tapX, tapY)
            Screen.OPEN -> tapOpen(tapX, tapY)
            Screen.BADGES -> tapBadges(tapX, tapY)
            Screen.CATS -> tapCats()
            Screen.WEEK -> if (!busy(SystemClock.uptimeMillis())) { startRecap(SystemClock.uptimeMillis()); armReturn(SystemClock.uptimeMillis()) }
            else -> performClick()
        }
    }
    private var longFired = false
    private val longPress = Runnable { if (!moved && !dragging) { longFired = true; onLongPress?.invoke() } }

    // Stage transform from the last frame, to map touches into stage px.
    private var stageX0 = 0f
    private var stageY0 = 0f
    private var stageS = 1f

    override fun performClick(): Boolean { super.performClick(); nextScreen(+1); return true }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        val sx = (e.x - stageX0) / stageS
        val sy = (e.y - stageY0) / stageS
        if (BuildConfig.DEBUG && e.actionMasked != MotionEvent.ACTION_MOVE) android.util.Log.d("Merged.Touch",
            "${MotionEvent.actionToString(e.actionMasked)} still=${isStill()} busy=${busy(now)} moved=$moved dragging=$dragging")
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = now; downX = e.x; downY = e.y; moved = false; dragging = false; longFired = false
                vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) }
                postDelayed(longPress, 800)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (!moved && hypot(e.x - downX, e.y - downY) > slop) { moved = true; removeCallbacks(longPress) }
                if (moved && !dragging && now - downAt > 350 && !busy(now) && !isStill() && !isNight()) {
                    dragging = true; fx().petDown(sx, sy, now)
                }
                if (dragging) { fx().petMove(sx, sy); invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                val v = vt?.apply { addMovement(e); computeCurrentVelocity(1000) }
                val vx = v?.xVelocity ?: 0f
                val vy = v?.yVelocity ?: 0f
                vt?.recycle(); vt = null
                val dx = e.x - downX
                val dy = e.y - downY
                when {
                    e.actionMasked == MotionEvent.ACTION_CANCEL -> if (dragging) fx().petFling(0f, 0f, now)
                    dragging -> fx().petFling(vx / stageS, vy / stageS, now)
                    moved && abs(dx) > 90 * stageS && abs(dx) > 1.5f * abs(dy) -> nextScreen(if (dx < 0) +1 else -1)
                    longFired -> Unit
                    !moved && now - lastTapAt < 300 -> { removeCallbacks(singleTap); lastTapAt = -1000L; replay() }
                    !moved -> { lastTapAt = now; tapX = sx; tapY = sy; postDelayed(singleTap, 300) }
                }
                if (dragging) invalidate()
                dragging = false
            }
        }
        return true
    }

    /** Rear screen asleep → no timers at all; awake → drift/sweep every 5 min (+ warm-up shuffle). */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        removeCallbacks(ambient); removeCallbacks(warm)
        if (visibility != VISIBLE) return
        postDelayed(ambient, 5 * 60_000L)
        if (shown == null && !signedOut) post(warm)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(warm); removeCallbacks(ambient); removeCallbacks(kick); removeCallbacks(singleTap); removeCallbacks(longPress)
        vt?.recycle(); vt = null
        sfx.release()
        super.onDetachedFromWindow()
    }

    // --------------------------------------------------------------- drawing

    private val kick = Runnable { invalidate() }
    private var reported = false

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glyphPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val plusPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint()
    private val haloShaders = HashMap<String, Shader>()
    private val cool = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.5f) })

    private var relayAt = -1000L
    private var signOd0 = 0f
    private var signAt = 0L
    private var lampAt = -1000L
    private var lampHold = 120L
    private var lampDecay = 400L

    private fun lamp(now: Long, hold: Long, decay: Long) { lampAt = now; lampHold = hold; lampDecay = decay }

    /** ±3px Lissajous offset from the wall clock (burn-in), periods 37 / 53 min. */
    private fun drift(): Pair<Int, Int> {
        val wall = System.currentTimeMillis()
        return (3 * sin(2 * PI * wall / (37 * 60_000.0))).roundToInt() to (3 * sin(2 * PI * wall / (53 * 60_000.0))).roundToInt()
    }
    private var drawnDrift = 0 to 0

    private fun isNight() = LocalTime.now().hour.let { it >= 23 || it < 7 } && !(debugPrefs?.getBoolean("day", false) ?: false)
    /** Debug builds: DebugReceiver `--ez day true` turns night mode off (filming at night). */
    private val debugPrefs = if (BuildConfig.DEBUG) ctx.getSharedPreferences("debug", Context.MODE_PRIVATE) else null

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val still = isStill()
        if (still && (timeline.isNotEmpty() || queue.isNotEmpty() || screen != Screen.TODAY || visibleTubes().any { it.active(now) })) shown?.let { settle(it); post { onNeedFrame?.invoke() } }
        runDue(now)
        val night = isNight()

        val cut = max(rootWindowInsets?.displayCutout?.safeInsetLeft ?: 0, debugCutoutPx)
        val availW = (width - cut).toFloat()
        val s = min(availW / 680f, height / 596f)
        val (driftX, driftY) = drift().also { drawnDrift = it }

        c.drawColor(Color.BLACK)
        c.save()
        stageX0 = cut + (availW - 680 * s) / 2 + driftX; stageY0 = (height - 596 * s) / 2 + driftY; stageS = s
        c.translate(stageX0, stageY0)
        c.scale(s, s)

        val dim = if (night || cooled) 0.4f else 1f
        val relay = when (val dt = now - relayAt) { in 0..59 -> 0.35f; in 60..89 -> 0.35f + 0.65f * (dt - 60) / 30f; else -> 1f }
        val gB = dim * relay
        val odCap = if (night) 0.4f else 1.4f
        glyphPaint.colorFilter = if (cooled) cool else null
        plusPaint.colorFilter = glyphPaint.colorFilter

        val tubes = visibleTubes()
        for (t in tubes) drawTube(c, t, now, gB, odCap, if (night || cooled) 0.6f else 1f, night)
        when (screen) {
            Screen.TODAY -> {
                val sOd = signOd0 * exp(-(now - signAt) / 1800f)
                drawSign(c, art.sign(Gas.NEON, true), 382f, 115f, adds[4].lvl * gB, min(sOd, odCap))
                drawSign(c, art.sign(Gas.ARGON, false), 382f, 249f, dels[4].lvl * gB, min(sOd, odCap))
                drawLabel(c, linesLabel, 404f, 28f, now, dim)
                drawLabel(c, weekLabel, 404f, 336f, now, dim)
                drawLabel(c, heroLabel, 26f, 508f, now, dim, right = 600f)
                drawLamp(c, now)
            }
            Screen.LONG -> {
                drawLabel(c, lifeLabel, 26f, 22f, now, dim)
                drawLabel(c, railLabel, 26f, 346f, now, dim)
                for (i in 0..6) {
                    val p = if (i == 6) dayToday else dayPaint
                    p.alpha = (255 * dim).toInt()
                    c.drawText(dayLetters[i], 24f + i * 92.83f + 37.5f, 515f, p)
                }
            }
            Screen.RECENT -> drawRecent(c, gB, dim)
            Screen.STREAK -> drawStreak(c, now, dim, night)
            Screen.WEEK -> drawWeek(c, now, gB, dim)
            Screen.BADGES -> drawBadges(c, now, dim)
            Screen.CATS -> drawCats(c, now, dim)
            Screen.OPEN -> drawOpen(c, now, dim)
        }
        fx?.draw(c, now)
        c.restore()

        removeCallbacks(kick)
        if (still) { if (reported) { reported = false; onAnimating?.invoke(false) }; return }
        // Frame scheduling: animate only while something moves; otherwise sleep until the next step.
        val animating = tubes.any { it.active(now) } || labelsActive(now) || now - lampAt < lampHold + lampDecay ||
            now - relayAt < 90 || signOd0 * exp(-(now - signAt) / 1800f) >= 0.02f || fx?.active(now) == true ||
            screenActive(now)
        val next = timeline.minOfOrNull { it.at }
        if (animating) postInvalidateOnAnimation() else if (next != null) postDelayed(kick, (next - now).coerceAtLeast(0))
        val busy = animating || (next != null && next - now < 1000)
        if (busy != reported) { reported = busy; onAnimating?.invoke(busy) }
    }

    private fun drawTube(c: Canvas, t: Tube, now: Long, gB: Float, odCap: Float, glassA: Float, night: Boolean) {
        t.tick(now)
        val face = t.faceAt(now)
        val a = t.alpha(now)
        val od = min(t.od(now), odCap)
        val g = art.glass(t.cls, t.gas)
        val gl = gB * t.lvl
        if (!night && t.halo > 0 && od > 0) {
            haloPaint.shader = haloShaders.getOrPut("${t.cls}/${t.gas}") {
                RadialGradient(0.5f * t.cls.w, 0.5f * t.cls.h, 0.62f * t.cls.h, t.gas.glow, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    .apply { setLocalMatrix(Matrix().apply { setScale(1.1f * t.cls.w / (0.62f * t.cls.h), 1f, 0.5f * t.cls.w, 0.5f * t.cls.h) }) }
            }
            haloPaint.alpha = (255 * (t.halo * od * gB).coerceIn(0f, 1f)).toInt()
            c.save(); c.translate(t.x, t.y)
            c.drawOval(-0.6f * t.cls.w, -0.12f * t.cls.h, 1.6f * t.cls.w, 1.12f * t.cls.h, haloPaint)
            c.restore()
        }
        bmpPaint.alpha = (255 * glassA).toInt()
        c.drawBitmap(g.base.bmp, t.x + g.base.dx, t.y + g.base.dy, bmpPaint)
        if (face != null) {
            bmpPaint.alpha = (255 * a * t.lvl * glassA).toInt()
            c.drawBitmap(g.lit.bmp, t.x + g.lit.dx, t.y + g.lit.dy, bmpPaint)
        }
        if (od > 0) {
            bmpPaint.alpha = (255 * min(od, 1f) * glassA).toInt()
            c.drawBitmap(g.hot.bmp, t.x + g.hot.dx, t.y + g.hot.dy, bmpPaint)
        }
        // 30ms afterglow of the previous cathode while cycling (SPEC §3.2 L7)
        val prev = t.prevFace
        val dt = now - t.switchAt
        if (prev != null && dt < 30) drawGlyph(c, t, prev, glyphPaint, 0.6f * (1 - dt / 30f) * gl)
        if (face == null) return
        drawGlyph(c, t, face, glyphPaint, gl * a)
        if (od > 0) {
            drawGlyph(c, t, face, plusPaint, 0.6f * min(od, 1f) * gl * a)
            corePaint.typeface = art.face(t.cls)
            corePaint.textSize = t.cls.text
            corePaint.color = if (now < t.whiteUntil) Color.WHITE else t.gas.hot
            corePaint.alpha = (255 * (min(od, 1f) * gl * a).coerceIn(0f, 1f)).toInt()
            c.drawText(face.toString(), t.x + art.textX(t.cls, face, corePaint), t.y + art.baseline(t.cls), corePaint)
        }
        if (t.comma && now >= t.cyEnd) drawGlyph(c, t, ',', glyphPaint, gl * a)
    }

    private fun drawGlyph(c: Canvas, t: Tube, ch: Char, p: Paint, alpha: Float) {
        if (alpha <= 0.004f) return
        val sp = art.glyph(t.cls, t.gas, ch)
        p.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
        c.drawBitmap(sp.bmp, t.x + sp.dx, t.y + sp.dy, p)
    }

    private fun drawSign(c: Canvas, sp: Sprite, cx: Float, cy: Float, alpha: Float, od: Float) {
        glyphPaint.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
        c.drawBitmap(sp.bmp, cx + sp.dx, cy + sp.dy, glyphPaint)
        if (od > 0.02f) {
            plusPaint.alpha = (255 * (0.6f * od * alpha).coerceIn(0f, 1f)).toInt()
            c.drawBitmap(sp.bmp, cx + sp.dx, cy + sp.dy, plusPaint)
        }
    }

    /** Departures board: when · repo, then title, newest first; the highlighted row's +/− is in the banks. */
    private fun drawRecent(c: Canvas, gB: Float, dim: Float) {
        drawSign(c, art.sign(Gas.NEON, true), 382f, 115f, radds[4].lvl * gB, 0f)
        drawSign(c, art.sign(Gas.ARGON, false), 382f, 249f, rdels[4].lvl * gB, 0f)
        val sel = recent.getOrNull(recentIdx)
        drawText(c, "THIS PR", true, 404f, 28f, dim, 656f)
        if (sel != null) drawText(c, "#${sel.number}", true, 404f, 336f, dim, 656f)
        if (recentFocus && sel != null) { drawFocus(c, sel, dim); return }
        drawText(c, "RECENT MERGES", false, 26f, 22f, dim, 372f)
        if (recent.isEmpty()) { drawText(c, "NO MERGES IN 7 DAYS", false, 26f, 64f, dim, 372f); return }
        recent.forEachIndexed { i, p ->
            val y = 64f + 86f * i
            val on = i == recentIdx
            drawText(c, "${ago(p.mergedAtMs)} · ${p.repo.substringAfter('/').uppercase()}", on, 26f, y, dim, 372f)
            val tp = if (on) titleHot else titlePaint
            tp.alpha = (255 * dim).toInt()
            val fit = TextUtils.ellipsize(p.title.replace(CONVENTIONAL, ""), tp, 346f, TextUtils.TruncateAt.END)
            c.drawText(fit, 0, fit.length, 26f, y + 46f, tp)
        }
    }

    /** Detail card for the focused PR: repo · number, the full title, then when / how long / how big / where. */
    private fun drawFocus(c: Canvas, p: Pr, dim: Float) {
        drawText(c, "SIZE · " + listOf("S", "M", "L", "XL")[tierOf(p.additions + p.deletions)], false, 404f, 366f, dim, 656f)
        drawCard(c, "${p.repo.uppercase()} · #${p.number}", p.title.replace(CONVENTIONAL, ""), details(p), dim)
    }

    /** Header (glowing silk), a big wrapped title (≤ 3 lines), detail lines, "TAP TO GO BACK". Used by every detail card. */
    private fun drawCard(c: Canvas, header: String, title: String, lines: List<String>, dim: Float, x: Float = 26f, w: Float = 346f) {
        drawText(c, header, true, x, 30f, dim, x + w)
        val lay = focusLayout?.takeIf { it.text == title && it.width == w.toInt() }
            ?: StaticLayout.Builder.obtain(title, 0, title.length, focusPaint, w.toInt())
                .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(4f, 1f).build().also { focusLayout = it }
        focusPaint.alpha = (255 * dim).toInt()
        c.save(); c.translate(x, 62f); lay.draw(c); c.restore()
        var y = 62f + lay.height + 30f
        detailPaint.alpha = (255 * dim).toInt()
        for (line in lines) {
            val fit = TextUtils.ellipsize(line, detailPaint, w, TextUtils.TruncateAt.MIDDLE)
            c.drawText(fit, 0, fit.length, x, y, detailPaint)
            y += 32f
        }
        drawText(c, "TAP TO GO BACK", false, x, 500f, dim, x + w)
    }

    private fun details(p: Pr): List<String> {
        val zone = ZoneId.systemDefault()
        val at = Instant.ofEpochMilli(p.mergedAtMs).atZone(zone)
        val days = java.time.temporal.ChronoUnit.DAYS.between(at.toLocalDate(), LocalDate.now(zone))
        val day = when (days) { 0L -> "TODAY"; 1L -> "YESTERDAY"; else -> at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase() }
        val out = mutableListOf("MERGED $day %02d:%02d · ${ago(p.mergedAtMs)}".format(at.hour, at.minute))
        if (p.createdAtMs > 0) {
            val m = (p.mergedAtMs - p.createdAtMs) / 60_000
            out += "OPEN " + when { m < 60 -> "${m}M"; m < 1440 -> "${m / 60}H ${m % 60}M"; else -> "${m / 1440}D ${m / 60 % 24}H" } + " BEFORE MERGE"
            out += "${p.files} ${if (p.files == 1) "FILE" else "FILES"} · ${p.commits} ${if (p.commits == 1) "COMMIT" else "COMMITS"}"
            out += "${p.reviews} ${if (p.reviews == 1) "REVIEW" else "REVIEWS"} · ${p.comments} ${if (p.comments == 1) "COMMENT" else "COMMENTS"}"
        }
        if (p.branch.isNotEmpty()) out += "${p.branch} → ${p.base}"
        if (p.genFiles > 0) out += "IGNORED ${p.genFiles} ${if (p.genFiles == 1) "FILE" else "FILES"} · %,d LINES".format(p.genAdds + p.genDels)
        return out
    }

    private fun openDetails(p: OpenPr) = listOfNotNull(
        if (p.autoMerge && p.status == OpenPr.Status.READY) "AUTO-MERGING" else p.status.text,
        "OPENED ${ago(p.createdAtMs)} · UPDATED ${ago(p.updatedAtMs)}",
        "REVIEW · " + when (p.review) {
            "APPROVED" -> "APPROVED"; "CHANGES_REQUESTED" -> "CHANGES REQUESTED"; "REVIEW_REQUIRED" -> "REQUIRED"
            else -> if (p.approvals > 0) "${p.approvals} ${if (p.approvals == 1) "APPROVAL" else "APPROVALS"}" else "NOT REQUIRED"
        },
        "CHECKS · " + when (p.checks) { "SUCCESS" -> "PASSING"; "FAILURE", "ERROR" -> "FAILED"; "PENDING", "EXPECTED" -> "RUNNING"; else -> "NONE" },
        if (p.autoMerge) "AUTO-MERGE ON" else null,
        if (p.mergeable == "CONFLICTING") "MERGE CONFLICT" else null,
        if (p.branch.isNotEmpty()) "${p.branch} → ${p.base}" else null,
    )

    /** Status board: what's ready, waiting and needs you; the top 5 (green, red, amber, drafts) as rows. */
    private fun drawOpen(c: Canvas, now: Long, dim: Float) {
        drawJewelLabel(c, "READY", OpenPr.Light.GREEN, 24f, dim)
        drawJewelLabel(c, "WAITING", OpenPr.Light.AMBER, 182f, dim)
        drawJewelLabel(c, "NEEDS YOU", OpenPr.Light.RED, 340f, dim)
        openRows.getOrNull(openFocus)?.let { drawCard(c, "${it.repo.substringAfter('/').uppercase()} · #${it.number}", it.title.replace(CONVENTIONAL, ""), openDetails(it), dim); return }
        drawText(c, "OPEN PRs · ${snap?.openCount ?: 0}", false, 26f, 22f, dim, 372f)
        if (openRows.isEmpty()) { drawText(c, "NO OPEN PRs", false, 26f, 64f, dim, 372f); return }
        openRows.forEachIndexed { i, p ->
            val y = 64f + 86f * i
            val hot = p.id == alertId
            val dt = now - alertAt
            // An alert row's jewel strikes (SPEC §8.1 flicker), then flares out and settles.
            val a = if (!hot) dim else if (dt < 0) 0f else if (dt < 220) dim * strikeCurve(dt) else dim
            if (hot && dt in 220..1500) drawJewel(c, 33f, y + 7f, p.status.light, dim * exp(-(dt - 220) / 400f), 6f + 14f * (1 - exp(-(dt - 220) / 300f)))
            drawJewel(c, 33f, y + 7f, p.status.light, a)
            val text = if (p.autoMerge && p.status == OpenPr.Status.READY) "AUTO-MERGING" else p.status.text
            drawText(c, "${p.repo.substringAfter('/').uppercase()} · #${p.number} · $text", hot, 48f, y, dim, 372f) // the jewel carries the status
            val tp = if (hot) titleHot else titlePaint
            tp.alpha = (255 * dim).toInt()
            val fit = TextUtils.ellipsize(p.title.replace(CONVENTIONAL, ""), tp, 346f, TextUtils.TruncateAt.END)
            c.drawText(fit, 0, fit.length, 26f, y + 46f, tp)
        }
    }

    private fun drawJewelLabel(c: Canvas, text: String, light: OpenPr.Light, top: Float, dim: Float) {
        drawJewel(c, 410f, top + 7f, light, dim)
        drawText(c, text, false, 424f, top, dim, 656f)
    }

    private val jewelGreen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC8FFC0.toInt(); setShadowLayer(12f, 0f, 0f, 0xFF22D46A.toInt()) }
    private val jewelRed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC2B8.toInt(); setShadowLayer(12f, 0f, 0f, 0xFFFF2A14.toInt()) }
    private val jewelAmber = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD2A0.toInt(); setShadowLayer(12f, 0f, 0f, Gas.NEON.glow) }
    private val jewelOff = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A1D15.toInt() }

    /** A panel jewel lamp: green / red / amber glow, or a dark unlit one. */
    private fun drawJewel(c: Canvas, x: Float, y: Float, light: OpenPr.Light, alpha: Float, r: Float = 6f) {
        if (alpha <= 0.004f) return
        val p = when (light) { OpenPr.Light.GREEN -> jewelGreen; OpenPr.Light.RED -> jewelRed; OpenPr.Light.AMBER -> jewelAmber; OpenPr.Light.OFF -> jewelOff }
        p.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
        c.drawCircle(x, y, r, p)
    }

    /** Streak in big tubes, best + today banks, the goal bargraph and this week's day jewels. */
    private fun drawStreak(c: Canvas, now: Long, dim: Float, night: Boolean) {
        drawText(c, "WORKDAY STREAK", false, 26f, 22f, dim, 372f)
        drawText(c, "BEST STREAK", false, 404f, 28f, dim, 656f)
        drawText(c, "TODAY", false, 404f, 186f, dim, 656f)
        val t = shown ?: return
        val met = t.prs >= goal
        drawText(c, if (met) "GOAL MET" + (if (t.prs > goal) " · +${t.prs - goal}" else "") else "TODAY'S GOAL · ${t.prs} / $goal",
            met, 26f, 346f, dim, 656f)
        drawBargraph(c, now, dim, night)
        val today = LocalDate.now()
        val monday = today.with(DayOfWeek.MONDAY)
        for (i in 0..6) {
            val d = monday.plusDays(i.toLong())
            val n = if (d > today) 0 else history[d] ?: 0
            val cx = 24f + i * 92.83f + 37.5f
            drawJewel(c, cx, 466f, if (n > 0) OpenPr.Light.AMBER else OpenPr.Light.OFF, dim)
            if (n >= goal) { ringPaint.alpha = (255 * dim).toInt(); c.drawCircle(cx, 466f, 11f, ringPaint) }
            val p = if (d == today) dayToday else dayPaint
            p.alpha = (255 * dim).toInt()
            c.drawText(d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), cx, 510f, p)
        }
    }

    private val barGlass = RectF(24f, 384f, 656f, 432f)
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.WHITE }
    private val barGhost = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; color = Gas.NEON.ghost }
    private val barLit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 9f; strokeCap = Paint.Cap.ROUND; color = Gas.NEON.core; setShadowLayer(14f, 0f, 0f, Gas.NEON.glow)
    }
    private val barHot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 10f; strokeCap = Paint.Cap.ROUND; color = Gas.NEON.hot; setShadowLayer(22f, 0f, 0f, Gas.NEON.glow)
    }
    private val barSurge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 30f; strokeCap = Paint.Cap.ROUND; color = Gas.NEON.glow; xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val tickPaint = Paint().apply { strokeWidth = 1.5f; color = LABEL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Gas.NEON.mid }

    /** IN-13-style bargraph tube: a neon column fills to today/goal; past the goal it runs white-hot. */
    private fun drawBargraph(c: Canvas, now: Long, dim: Float, night: Boolean) {
        glassPaint.alpha = (26 * dim).toInt()
        c.drawRoundRect(barGlass, 24f, 24f, glassPaint)
        val x0 = 48f; val x1 = 632f; val y = 408f
        barGhost.alpha = (255 * dim).toInt()
        c.drawLine(x0, y, x1, y, barGhost)
        tickPaint.alpha = (255 * dim).toInt()
        if (goal in 2..40) for (k in 1 until goal) {
            val x = x0 + (x1 - x0) * k / goal
            c.drawLine(x, 390f, x, 397f, tickPaint); c.drawLine(x, 419f, x, 426f, tickPaint)
        }
        val f = bar(now)
        if (f < 0.002f) return
        val p = if (f >= 1f) barHot else barLit
        p.alpha = (255 * dim).toInt()
        c.drawLine(x0, y, x0 + (x1 - x0) * min(f, 1f), y, p)
        val surge = if (now < surgeAt || night) 0f else exp(-(now - surgeAt) / 700f)
        if (surge > 0.02f) { barSurge.alpha = (160 * surge * dim).toInt(); c.drawLine(x0, y, x1, y, barSurge) }
    }

    /** The recap: big PR count, ± banks, a day-by-day strip, the best PR and a summary line. */
    private fun drawWeek(c: Canvas, now: Long, gB: Float, dim: Float) {
        val w = weekStats ?: return
        val end = w.start.plusDays(6)
        fun mon(d: LocalDate) = d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()
        val range = if (w.start.month == end.month) "${w.start.dayOfMonth}–${end.dayOfMonth} ${mon(end)}"
            else "${w.start.dayOfMonth} ${mon(w.start)}–${end.dayOfMonth} ${mon(end)}"
        drawText(c, "THIS WEEK · $range", false, 26f, 22f, dim, 372f)
        drawText(c, "LINES", false, 404f, 28f, dim, 656f)
        if (wprs.size == 2) { // three big tubes reach under the signs
            drawSign(c, art.sign(Gas.NEON, true), 382f, 115f, wadds[4].lvl * gB, 0f)
            drawSign(c, art.sign(Gas.ARGON, false), 382f, 249f, wdels[4].lvl * gB, 0f)
        }
        val todayIdx = LocalDate.now().dayOfWeek.value - 1
        for (i in 0..6) {
            val cx = 24f + i * 92.83f + 37.5f
            val n = w.perDay[i]
            val revealed = i <= recapDay && i <= todayIdx
            val lp = if (revealed && n > 0) dayToday else dayPaint
            lp.alpha = (255 * dim).toInt()
            c.drawText(w.start.plusDays(i.toLong()).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), cx, 365f, lp)
            if (!revealed) continue
            val np = if (n > 0) weekCountOn else weekCountOff
            np.alpha = (255 * dim).toInt()
            c.drawText(n.toString(), cx, 404f, np)
            if (n >= goal) drawJewel(c, cx, 424f, OpenPr.Light.AMBER, dim, 4f)
        }
        drawLabel(c, bestLabel, 26f, 446f, now, dim)
        val best = w.best
        if (best != null && bestLabel.text.isNotEmpty()) { // the title fades in with the BEST line above it
            titlePaint.alpha = (255 * dim * ((now - bestLabel.at).toFloat() / bestLabel.fade).coerceIn(0f, 1f)).toInt()
            val fit = TextUtils.ellipsize(best.title.replace(CONVENTIONAL, ""), titlePaint, 630f, TextUtils.TruncateAt.END)
            c.drawText(fit, 0, fit.length, 26f, 490f, titlePaint)
        }
        drawLabel(c, sumLabel, 26f, 508f, now, dim, right = 600f)
    }

    private val weekCountOn = labelPaint(26f).apply { color = Gas.NEON.core; textAlign = Paint.Align.CENTER; setShadowLayer(8f, 0f, 0f, 0xAAFF5200.toInt()) }
    private val weekCountOff = labelPaint(26f).apply { color = LABEL; textAlign = Paint.Align.CENTER }

    /** 4×2 shelf of badges: lit cathode icons for earned ones, ghost wire for the rest; a new one plays its unlock. */
    private fun drawBadges(c: Canvas, now: Long, dim: Float) {
        val ba = badgeArt
        badgeFocus?.let { b ->
            val n = book.count(b)
            ba.draw(c, b, 150f, 290f, 210f, n > 0, dim)
            fun day(d: LocalDate?) = d?.let { "${it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()} ${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()}" }
            drawCard(c, b.title, b.rule, listOfNotNull(if (n > 0) "EARNED ×$n" else "NOT EARNED YET",
                day(book.first(b))?.let { "FIRST · $it" }, day(book.last(b))?.let { "LAST · $it" }), dim, x = 290f, w = 366f)
            return
        }
        drawText(c, "BADGES · ${book.total} OF ${Badge.entries.size}", false, 26f, 22f, dim, 656f)
        Badge.entries.forEachIndexed { i, b ->
            val x0 = 24f + (i % 4) * 158f; val y0 = 52f + (i / 4) * 240f
            val cx = x0 + 79f; val cy = y0 + 90f
            val n = book.count(b)
            val t = (now - unlockAt).toFloat()
            val new = b == unlocking
            when {
                new && t < 0 -> ba.draw(c, b, cx, cy, 112f, false, dim)
                new && t < BadgeArt.UNLOCK_MS -> ba.drawUnlock(c, b, cx, cy, 112f, t, dim)
                else -> ba.draw(c, b, cx, cy, 112f, n > 0, dim)
            }
            val np = if (new) nameNew else if (n > 0) nameOn else nameOff
            np.alpha = (255 * dim).toInt()
            c.drawText(b.title, cx, y0 + 176f, np)
            if (n > 0) { badgeCount.alpha = (255 * dim).toInt(); c.drawText("×$n", cx, y0 + 204f, badgeCount) }
        }
    }

    private val nameOff = labelPaint(15f).apply { color = LABEL; letterSpacing = 0.15f; textAlign = Paint.Align.CENTER }
    private val nameOn = labelPaint(15f).apply { color = 0xFFA08872.toInt(); letterSpacing = 0.15f; textAlign = Paint.Align.CENTER }
    private val nameNew = labelPaint(15f).apply {
        color = EVENT; letterSpacing = 0.15f; textAlign = Paint.Align.CENTER; setShadowLayer(8f, 0f, 0f, 0xAAFF5200.toInt())
    }
    private val badgeCount = labelPaint(20f).apply { color = Gas.NEON.mid; textAlign = Paint.Align.CENTER }

    /** One sleeping cat per PR merged in the last 7 days (MergeFx draws and flies them). */
    private fun drawCats(c: Canvas, now: Long, dim: Float) {
        val n = snap?.prs?.size ?: 0
        val more = if (n > COLONY_MAX) " · +${n - COLONY_MAX} NOT SHOWN" else ""
        drawText(c, if (n == 0) "CAT COLONY · NO MERGES THIS WEEK" else "CAT COLONY · $n THIS WEEK$more", false, 26f, 22f, dim, 656f)
        fx?.drawColony(c, now, dim)
    }

    /** Per-screen animations that aren't tubes or labels. */
    private fun screenActive(now: Long) = when (screen) {
        Screen.STREAK -> now < barAt + BAR_MS || now < surgeAt + 2500
        Screen.BADGES -> unlocking != null && now < unlockAt + BadgeArt.UNLOCK_MS
        Screen.OPEN -> alertId != null && now < alertAt + 1500
        else -> false
    }

    private val focusPaint = labelPaint(24f).apply { color = Gas.NEON.core; setShadowLayer(8f, 0f, 0f, 0xAAFF5200.toInt()) }
    private val detailPaint = labelPaint(18f).apply { color = 0xFFA08872.toInt(); letterSpacing = 0.08f }

    private fun ago(ms: Long): String {
        val m = (System.currentTimeMillis() - ms) / 60_000
        return when { m < 1 -> "NOW"; m < 60 -> "${m}M AGO"; m < 1440 -> "${m / 60}H AGO"; else -> "${m / 1440}D AGO" }
    }

    private val titlePaint = labelPaint(21f).apply { color = 0xFF8A7360.toInt() }
    private val titleHot = labelPaint(21f).apply { color = Gas.NEON.core; setShadowLayer(8f, 0f, 0f, 0xAAFF5200.toInt()) }

    // --------------------------------------------------------------- labels + lamp

    private class Label(var text: String) {
        var event = false
        var prev = ""
        var prevEvent = false
        var at = -10_000L
        var fade = 150L
        fun active(t: Long) = t - at < fade
    }

    private val heroLabel = Label("PRs MERGED · TODAY")
    private val linesLabel = Label("LINES")
    private val weekLabel = Label("7 DAYS")
    private val lifeLabel = Label("ALL-TIME PRs MERGED")
    private val railLabel = Label("LAST 7 DAYS")

    private fun labelsActive(t: Long) = heroLabel.active(t) || linesLabel.active(t) || bestLabel.active(t) || sumLabel.active(t)

    private fun setLabel(l: Label, text: String, event: Boolean, now: Long, fade: Long = 150) {
        if (l.text == text && l.event == event) return
        l.prev = l.text; l.prevEvent = l.event
        l.text = text; l.event = event
        l.at = now; l.fade = fade
        invalidate()
    }

    private fun labelPaint(size: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = art.label; textSize = size
    }

    private val silk = labelPaint(17f).apply { color = LABEL; letterSpacing = 3.5f / 17f }
    private val eventPaint = labelPaint(17f).apply {
        color = EVENT; letterSpacing = 3.5f / 17f
        setShadowLayer(8f, 0f, 0f, 0xAAFF5200.toInt())
    }
    private val dayPaint = labelPaint(20f).apply { color = LABEL; textAlign = Paint.Align.CENTER }
    private val dayToday = labelPaint(20f).apply {
        color = Gas.NEON.mid; textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 0f, 0x99FF5200.toInt())
    }

    private fun drawLabel(c: Canvas, l: Label, x: Float, top: Float, now: Long, dim: Float, right: Float = 656f) {
        val p = if (l.fade <= 0) 1f else ((now - l.at).toFloat() / l.fade).coerceIn(0f, 1f)
        if (p < 1f) drawText(c, l.prev, l.prevEvent, x, top, (1 - p) * dim, right)
        drawText(c, l.text, l.event, x, top, p * dim, right)
    }

    private fun drawText(c: Canvas, text: String, event: Boolean, x: Float, top: Float, alpha: Float, right: Float) {
        if (alpha <= 0.004f || text.isEmpty()) return
        val p = if (event) eventPaint else silk
        p.alpha = (255 * alpha).toInt()
        val fit = TextUtils.ellipsize(text, p, right - x, TextUtils.TruncateAt.END)
        c.drawText(fit, 0, fit.length, x, top + 13, p)
    }

    private val lampLive = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD2A0.toInt(); setShadowLayer(12f, 0f, 0f, Gas.NEON.glow) }
    private val lampFlash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF6EC.toInt(); setShadowLayer(22f, 0f, 0f, Gas.NEON.glow) }
    private val lampOff = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A1D15.toInt() }

    private fun drawLamp(c: Canvas, now: Long) {
        if (signedOut || cooled || shown == null) { c.drawCircle(645f, 515f, 5f, lampOff); return }
        val dt = now - lampAt
        val f = when { dt < lampHold -> 1f; dt < lampHold + lampDecay -> 1f - (dt - lampHold).toFloat() / lampDecay; else -> 0f }
        lampLive.alpha = (255 * (1 - f)).toInt()
        c.drawCircle(645f, 515f, 5f, lampLive)
        if (f > 0) { lampFlash.alpha = (255 * f).toInt(); c.drawCircle(645f, 515f, 5f, lampFlash) }
    }

    private companion object {
        const val S = 0
        const val M = 1
        const val L = 2
        const val XL = 3
        const val STALE_MS = 10 * 60_000L
        const val DAY_MS = 86_400_000L
        const val BAR_MS = 700L // goal bargraph fill
        const val COLONY_MAX = 60
        /** The chime plays as a notification sound: notification volume, and DND mutes it too. */
        val NOTIFY: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        const val LABEL = 0xFF5E4C3E.toInt()
        const val EVENT = 0xFFFFB27A.toInt()
        val CONVENTIONAL = Regex("""^\w+(\([^)]*\))?!?:\s*""")
    }
}
