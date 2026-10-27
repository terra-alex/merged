package co.tryterra.merged

import android.app.KeyguardManager
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
import android.os.SystemClock
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
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * "Neon Tally" — a Nixie-tube counter for today's merged PRs and lines.
 * The instrument: merged/design/SPEC.md. Screens, gestures, the cats and the merge timing: merged/design/UX-PASS.md.
 *
 * Battery contract: at rest nothing animates and no frame is drawn. Frames run
 * only while something moves, via postInvalidateOnAnimation; future timeline
 * steps use one postDelayed kick.
 */
class MeterView(ctx: Context) : View(ctx) {

    var debugCutoutPx = 0
        set(v) { field = v; invalidate() }
    /** Open Setup on the front screen (the activity starts it; we say so on the rear). */
    var onSetup: (() -> Unit)? = null
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

    /** Fully on — not dimmed, dozing or off. Only then does a merge play live, or a show start. */
    fun isAwake() = display?.state == Display.STATE_ON

    private fun frame() { if (isStill()) onNeedFrame?.invoke() else invalidate() }

    /**
     * Drop any moment or show in flight and snap every tube to [t]: the doze face is always Today. Shows that didn't get
     * to play wait in the ledger (a NEW badge, a parked alert).
     */
    private fun settle(t: Totals) {
        if (inShow(SystemClock.uptimeMillis())) cutShow()
        (listOfNotNull(plannedShow) + shows).forEach(::park)
        queue.clear(); timeline.clear(); momentUntil = 0; nextSlot = 0; pendingPlay = null
        shows.clear(); showUntil = 0; plannedShow = null; showToken++
        noteUntil = 0; peekUntil = 0; tagged = -1
        fx?.clear(); stopRub()
        leave(); screen = Screen.TODAY
        hero.forEach { it.lvl = 1f }
        applyInstant(t, fade = 0)
    }

    private val art = NixieArt.get(ctx.assets)
    /** Merge celebration (cats, then fire) and the colony; baked on first use, not at launch. */
    private var fx: MergeFx? = null
    private fun fx() = fx ?: MergeFx(art).also { f -> fx = f; f.onPounce = { feel.feel(Feel.Kind.LAND, 1) } }
    private val prefs = ctx.getSharedPreferences("state", Context.MODE_PRIVATE)
    /** One-time gesture hints (UX-PASS #14); its own file, so an account switch doesn't re-teach them. */
    private val hints = ctx.getSharedPreferences("hints", Context.MODE_PRIVATE)
    private val book = BadgeBook(prefs)
    /** Badge icons; baked the first time the Badges screen shows. */
    private val badgeArt = BadgeArt.get(art)
    private val feel = Feel(this)
    private val rnd = java.util.Random()

    private fun tick() = feel.feel(Feel.Kind.TICK)

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
        if (dt < 0) return 0f
        for (i in 1 until k.size) if (dt < k[i]) return v[i - 1] + (v[i] - v[i - 1]) * (dt - k[i - 1]) / (k[i] - k[i - 1])
        return 1f
    }

    // --------------------------------------------------------------- layout (SPEC §4)

    private var hero = heroTubes(2)
    private val adds = List(5) { Tube(404f + 51 * it, 56f, Cls.SMALL, Gas.NEON, commaSlot = it == 1) }
    private val dels = List(5) { Tube(404f + 51 * it, 190f, Cls.SMALL, Gas.ARGON, 0.9f, commaSlot = it == 1) }
    private val week = List(3) { Tube(506f + 51 * it, 362f, Cls.SMALL, Gas.NEON, 0.6f) }
    private var life = lifeTubes(4)
    // ALL-TIME's records row (UX-PASS #18): best day · best week · biggest PR (lines) · fastest merge (minutes).
    private val recDay = List(2) { Tube(24f + 39 * it, 372f, Cls.RAIL, Gas.NEON) }
    private val recWeek = List(3) { Tube(157f + 39 * it, 372f, Cls.RAIL, Gas.NEON) }
    private val recBig = List(4) { Tube(329f + 39 * it, 372f, Cls.RAIL, Gas.NEON, commaSlot = it == 0) }
    private val recFast = List(3) { Tube(540f + 39 * it, 372f, Cls.RAIL, Gas.NEON) }

    private fun heroTubes(n: Int) = if (n == 2) List(2) { Tube(24f + 160 * it, 28f, Cls.HERO, Gas.NEON) }
        else List(3) { Tube(24f + 114 * it, 28f, Cls.HERO3, Gas.NEON) }

    private fun lifeTubes(n: Int) = if (n == 4) List(4) { Tube(24f + 156 * it, 46f, Cls.LIFE, Gas.NEON, commaSlot = it == 0) }
        else List(5) { Tube(24f + 124 * it, 46f, Cls.LIFE5, Gas.NEON, commaSlot = it == 1) }

    /**
     * The swipe loop, in this order (UX-PASS §3): back in time to the left of Today's right-hand neighbours, pending
     * work one swipe right of Today (OPEN is last, so it wraps round next to it).
     */
    private enum class Screen { TODAY, RECENT, STREAK, WEEK, LONG, BADGES, CATS, OPEN }
    private var screen = Screen.TODAY
    private var screenAt = -10_000L // when the current screen came up (its text strikes in after its tubes start)

    private fun visibleTubes(): List<Tube> = when (screen) {
        Screen.TODAY -> hero + adds + dels + week
        Screen.LONG -> life + recDay + recWeek + recBig + recFast
        Screen.RECENT -> radds + rdels
        Screen.STREAK -> stk + bestBank + toGoBank
        Screen.WEEK -> wprs + wadds + wdels
        Screen.OPEN -> oReady + oWait + oNeeds
        Screen.BADGES, Screen.CATS -> emptyList()
    }

    /** Big counters on Streak / Week: 2 LIFE tubes, or 3 narrower ones from 100 (clear of the ± signs at x 382). */
    private fun bigTubes(n: Int) = if (n == 2) List(2) { Tube(24f + 156 * it, 46f, Cls.LIFE, Gas.NEON) }
        else List(3) { Tube(24f + 122 * it, 46f, Cls.LIFE5, Gas.NEON) }

    private var stk = bigTubes(2)
    private val bestBank = List(3) { Tube(506f + 51 * it, 56f, Cls.SMALL, Gas.NEON, 0.6f) }
    private val toGoBank = List(2) { Tube(557f + 51 * it, 214f, Cls.SMALL, Gas.NEON) }
    private var wprs = bigTubes(2)
    private val wadds = List(5) { Tube(404f + 51 * it, 56f, Cls.SMALL, Gas.NEON, commaSlot = it == 1) }
    private val wdels = List(5) { Tube(404f + 51 * it, 190f, Cls.SMALL, Gas.ARGON, 0.9f, commaSlot = it == 1) }
    private val oReady = List(2) { Tube(557f + 51 * it, 52f, Cls.SMALL, Gas.NEON) }
    private val oWait = List(2) { Tube(557f + 51 * it, 210f, Cls.SMALL, Gas.NEON, 0.6f) }
    private val oNeeds = List(2) { Tube(557f + 51 * it, 368f, Cls.SMALL, Gas.NEON) } // neon: argon means deletions everywhere else

    // Recent PRs: a departures board of the last 5 merges; the banks cycle through each one's +/−.
    private val radds = List(5) { Tube(404f + 51 * it, 56f, Cls.SMALL, Gas.NEON, commaSlot = it == 1) }
    private val rdels = List(5) { Tube(404f + 51 * it, 190f, Cls.SMALL, Gas.ARGON, 0.9f, commaSlot = it == 1) }
    private var recent: List<Pr> = emptyList()
    private var recentIdx = 0
    private var recentFocus = false // a tapped PR's detail card is up (no auto-advance)
    private var recentCycle = 0     // token for the current auto-advance loop
    private var focusLayout: StaticLayout? = null
    private var cardAt = -10_000L   // a detail card strikes in

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

    private fun blankBank(tubes: List<Tube>) = tubes.forEach { it.set(null, false) }

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
    private var expired = false
    private var offline = false
    private var cooled = false
    /** The rear is dimmed: the hero label names the day ("· TUE"), so a face left up past midnight can't say TODAY. */
    private var dozeLabel = false
    private var bestDay = 0
    private var records = Progress.Records(0, null, null)
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

    private var history: Map<LocalDate, Int> = emptyMap()
    private var goal = Progress.DEFAULT_GOAL
    private var streak = 0
    private var awakeMs = AWAKE_MS
    private var quietStart = 23
    private var quietEnd = 7

    /** Goal and quiet hours, cached: isNight() runs every frame. */
    private fun readSettings() {
        // How long the rear stays on after a wake or a touch (HyperOS Settings › rear screen; 10 s by default).
        awakeMs = runCatching { android.provider.Settings.System.getInt(context.contentResolver, "subscreen_display_time") }
            .getOrDefault(AWAKE_MS.toInt()).toLong().coerceIn(5_000L, 600_000L)
        goal = Progress.goal(context)
        quietStart = Progress.quietStart(context); quietEnd = Progress.quietEnd(context)
    }

    private fun tierOf(churn: Int) = when { churn < 100 -> S; churn < 1239 -> M; churn < 5000 -> L; else -> XL }
    private fun tierOf(p: Pr) = tierOf(p.additions + p.deletions)

    /** PRs merged since this Monday (the colony and WEEK count the calendar week). */
    private fun weekPrs(): List<Pr> = snap?.prs?.let { ps -> val m = startOf(LocalDate.now().with(DayOfWeek.MONDAY)); ps.filter { it.mergedAtMs >= m } }.orEmpty()

    /** Open PRs, minus any already in the merged list (the open search is up to 5 min older than the merged one). */
    private fun openOf(s: Snapshot?): List<OpenPr> {
        s ?: return emptyList()
        val merged = s.prs.mapTo(HashSet()) { it.id }
        return s.open.filter { it.id !in merged }
    }

    private fun coatOf(p: Pr) = MergeFx.coat(p, MergeFx.rank(weekPrs()), coatDay())

    /** The day the coats are for (debug builds can switch the seasonal ones off: DebugReceiver `--ez season false`). */
    private fun coatDay(): LocalDate = LocalDate.now().let { if (debugPrefs?.getBoolean("noSeason", false) == true) it.withMonth(6) else it }

    /** Halloween week: the kittens and the paw wear pumpkin too. */
    private fun pumpkinWeek() = coatDay().let { it.monthValue == 10 && it.dayOfMonth >= 25 }

    /** Called by the activity after every successful poll (and with cached data at launch). */
    fun show(s: Snapshot, fresh: List<Pr>) {
        val now = SystemClock.uptimeMillis()
        val prev = snap
        snap = s
        signedOut = false; expired = false; offline = false
        cooled = System.currentTimeMillis() - s.fetchedAtMs > STALE_MS
        readSettings()
        val t = totals(s)
        val today = LocalDate.now()
        history = Progress.record(prefs, t.days.withIndex().associate { (i, v) -> today.minusDays(6L - i) to v }, today)
        bestDay = Progress.bestDay(history, today)
        val recBefore = Progress.records(prefs)
        records = Progress.recordPrs(prefs, s.prs)
        dayLetters = (0..6).map { today.minusDays(6L - it).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }
        contentDescription = "${t.prs} PRs merged today, ${t.adds} lines added, ${t.dels} removed"
        streak = Progress.streak(history, today)
        val virgin = !prefs.contains("badges") // first evaluation awards the window silently
        val earned = book.evaluate(s, ZoneId.systemDefault(), goal, streak, Progress.streakStart(history, today),
            prev?.takeIf { it.login == s.login }?.lifetime)
            .filter { !virgin && it.day == today && it.badge != Badge.GOAL }.map { it.badge }.distinct()
        applyLong(t)
        val night = isNight()
        val old = shown
        // One moment for whatever landed since the last poll: before = the totals without these merges (SPEC §8.4).
        val before = fresh.fold(t) { acc, p -> acc.minus(p) }
        val e = if (fresh.isEmpty() || old == null) null else event(before, t, fresh, news(before, t, fresh, recBefore))
        // Nobody's looking (no moment at doze brightness), or its numbers can't land before the rear dims: buzz now
        // (it's what makes you flip the phone) and park it — the next wake replays it, or a cat peeks.
        if (!isAwake() || e != null && deadline() - now < 300 + NUMBERS_MS[e.tier]) {
            if (e != null) {
                feel.merge(e.tier, night)
                if (!night) prefs.edit().putString("missedId", fresh.last().id).putLong("missedAt", System.currentTimeMillis()).apply()
            }
            queueShows(prev, s, earned, goalHit = false, merge = false, now = now) // parks (or ledgers) what it finds
            // While dozing every push takes a wake lock: only when something on the face changed.
            if (t != old || prev?.fetchedAtMs != s.fetchedAtMs || e != null) { shown = t; settle(t); frame() }
            return
        }
        lamp(now, hold = 120, decay = 400)
        invalidate()

        shown = t
        if (old == null) { // cold start: lay out, then strike the lit tubes in (SPEC §9)
            hero.forEach { it.lvl = 1f }
            applyInstant(t)
            setLabel(heroLabel, heroLabelText(t), false, now, 0)
            strikeIn(now, 40)
            return
        }
        refreshScreen(prev, now)
        if (fresh.isEmpty()) {
            if (t != old) plainRoll(t, now)
            applyDayState(t, now)
            queueShows(prev, s, earned, goalHit = false, merge = false, now = now)
            return
        }
        e ?: return
        queue.add(e)
        queueShows(prev, s, earned, e.goal, merge = true, now = now)
        if (now >= nextSlot && pendingPlay == null) playNext(now) // a flyby already up: this one follows its moment
        invalidate()
    }

    /** What the label says once the numbers land (UX-PASS §6 "label hold"): the goal, else a record broken. */
    private fun news(before: Totals, after: Totals, prs: List<Pr>, rec: Progress.Records): String? {
        if (before.prs < goal && after.prs >= goal) return "GOAL MET · ${after.prs}/$goal"
        val since = rec.since ?: return null
        if (ChronoUnit.DAYS.between(since, LocalDate.now()) < 28) return null // a since-install record breaks every day at first
        val big = prs.filter { it.genChecked }.maxOfOrNull { it.additions + it.deletions } ?: 0 // lockfile lines don't make records
        val fast = prs.filter { it.createdAtMs > 0 }.minOfOrNull { Progress.minutes(it) }
        return when {
            bestDay >= 5 && before.prs <= bestDay && after.prs > bestDay -> "NEW RECORD · BEST DAY ${after.prs}"
            rec.biggest > 0 && big > rec.biggest -> "NEW RECORD · BIGGEST PR %,d LINES".format(big)
            rec.fastestMin != null && fast != null && fast < rec.fastestMin -> "NEW RECORD · FASTEST MERGE $fast MIN"
            else -> null
        }
    }

    fun signedOut() {
        if (signedOut) return // every poll lands here while signed out: one frame (and wake lock), not one per poll
        signedOut = true; expired = false
        snap = null; shown = null
        queue.clear(); timeline.clear(); momentUntil = 0; nextSlot = 0; pendingPlay = null
        shows.clear(); showUntil = 0; plannedShow = null
        fx?.clear()
        removeCallbacks(warm)
        (hero + adds + dels + week + life + recDay + recWeek + recBig + recFast).forEach { it.set(null, false) }
        leave(); screen = Screen.TODAY
        setLabel(heroLabel, heroLabelText(null), true, SystemClock.uptimeMillis(), 0)
        frame()
    }

    /** GitHub rejected the token: keep the last numbers, cooled, under TOKEN EXPIRED. Any tap opens Setup. */
    fun tokenExpired() {
        if (expired) return
        expired = true; cooled = true
        shown?.let { settle(it) }
        setLabel(heroLabel, heroLabelText(shown), true, SystemClock.uptimeMillis(), 0)
        frame()
    }

    fun markStale() {
        val s = snap
        if (s != null && shown != null && totals(s) != shown) show(s, emptyList()) // day rolled over while offline
        if (s == null && !signedOut) { // offline, nothing cached: stop warming and say so
            removeCallbacks(warm); hero.forEach { it.set(null, false) }
            offline = true
            setLabel(heroLabel, heroLabelText(null), true, SystemClock.uptimeMillis(), 0)
        }
        val was = cooled
        cooled = expired || s == null || System.currentTimeMillis() - s.fetchedAtMs > STALE_MS
        if (cooled != was || s == null) frame()
    }

    /** Screen back on after a long dark spell: replay the cold-start strike-in. */
    fun wake() { if (shown != null && !isStill()) { strikeIn(SystemClock.uptimeMillis(), 40); invalidate() } }

    // --------------------------------------------------------------- the rear's 10-second window (UX-PASS §6)

    private var onAt = 0L         // uptime the rear last came fully on
    private var touchAt = -100_000L
    private var debugUntil = 0L   // debug shows get a minute

    /**
     * When the rear dims: 10 s after it woke or was last touched (HyperOS subscreen_display_time), less a margin.
     * Nothing new starts that can't finish by then. On the front display (no Shizuku) there's no such window.
     */
    private fun deadline(): Long = if (display?.displayId == Display.DEFAULT_DISPLAY || neverDims()) Long.MAX_VALUE / 4
        else max(max(onAt, touchAt) + awakeMs - 500, debugUntil)

    /** Developer option "Stay awake" while plugged into a source that's charging: the rear never dims, so there's no window. */
    private fun neverDims(): Boolean {
        val stay = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0)
        if (stay == 0) return false
        val plugged = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return stay and plugged != 0 // both are bitmasks of AC / USB / wireless / dock
    }

    /** The rear just came fully on (woken, or back from dim): the window starts; anything missed gets its chance. */
    fun rearOn() {
        val now = SystemClock.uptimeMillis()
        onAt = now
        if (snap != null) markStale() // rolls the day if midnight passed while frozen (no data yet: still connecting, not offline)
        val t = shown ?: return
        if (dozeLabel) { dozeLabel = false; setLabel(heroLabel, heroLabelText(t), false, now, 0); invalidate() }
        if (isNight() || inMoment(now) || inShow(now)) return
        val missedAt = prefs.getLong("missedAt", 0L)
        if (missedAt > 0) { // a merge that landed while nobody was looking: replay it if it's fresh, else a cat peeks (until midnight)
            if (Instant.ofEpochMilli(missedAt).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()) {
                if (System.currentTimeMillis() - missedAt < 120_000) replay(now, news = true) else peek(now)
                return
            }
            seenMissed()
        }
        ledgerShow(merge = false)?.let { shows += it } // a NEW badge (say, earned in quiet hours), else a parked alert
        if (shows.isEmpty() && recapDue() && deadline() - now >= 7500) shows += recapShow()
        kickShows(now)
    }

    /** The rear dimmed, dozed or went off: Today, settled; whatever didn't play waits in the ledger. */
    fun rearOff() {
        val t = shown ?: return
        val now = SystemClock.uptimeMillis()
        val busy = screen != Screen.TODAY || inMoment(now) || inShow(now) || shows.isNotEmpty() || plannedShow != null ||
            noteOn(now) || now < peekUntil || cardOpen()
        if (dozeLabel && !busy) return
        dozeLabel = true
        if (busy) settle(t) else setLabel(heroLabel, heroLabelText(t), false, now, 0)
        frame()
    }

    // --------------------------------------------------------------- ledger (unseen news, prefs "state")

    private fun seenMissed() { if (prefs.contains("missedAt")) prefs.edit().remove("missedAt").remove("missedId").apply() }

    private fun newBadges(): Set<String> = prefs.getStringSet("newBadges", emptySet()).orEmpty()
    private fun ledgerBadge(b: Badge) { prefs.edit().putStringSet("newBadges", newBadges() + b.name).apply() }
    private fun unledger(b: Badge) { if (b.name in newBadges()) prefs.edit().putStringSet("newBadges", newBadges() - b.name).apply() }

    /** Alerts and rescues that couldn't play (newest last, at most 3); replayed on a later wake if still true. */
    private val parked = ArrayList<Show>()

    private fun park(sh: Show) {
        when (sh.kind) {
            Kind.BADGE -> sh.badge?.let(::ledgerBadge)
            Kind.ALERT, Kind.RESCUE -> { parked.removeAll { it.key == sh.key }; parked += sh; while (parked.size > 3) parked.removeAt(0) }
            Kind.GOAL, Kind.RECAP -> Unit // the label said GOAL MET; an unplayed recap stays due
        }
    }

    private fun stillTrue(sh: Show): Boolean {
        val open = openOf(snap)
        return when (sh.kind) {
            Kind.ALERT -> open.any { "${it.id}:${it.status.light}" == sh.key }
            Kind.RESCUE -> open.any { it.id == sh.key && it.checks == "SUCCESS" }
            else -> true
        }
    }

    private fun takeParked(): Show? {
        parked.removeAll { !stillTrue(it) }
        return parked.removeLastOrNull()?.also { it.merge = false }
    }

    /** The first unseen thing as a show: a NEW badge's unlock, else a parked alert or rescue. [merge]: it follows a replay. */
    private fun ledgerShow(merge: Boolean = true): Show? = newBadges().firstNotNullOfOrNull { n -> Badge.entries.find { it.name == n } }
        ?.let { unlockShow(it, merge) } ?: takeParked()?.also { it.merge = merge }

    private fun applyInstant(t: Totals, fade: Long = 150) {
        if (hero.size != (if (t.prs >= 100) 3 else 2)) hero = heroTubes(if (t.prs >= 100) 3 else 2)
        setBank(hero, t.prs); setBank(adds, t.adds, kM = true); setBank(dels, t.dels, kM = true); setBank(week, t.week)
        applyLong(t)
        applyDayState(t, SystemClock.uptimeMillis(), fade)
    }

    /** ALL-TIME: the odometer and the records row ("since" the history's first day). */
    private fun applyLong(t: Totals) {
        if (life.size != (if (t.lifetime >= 10_000) 5 else 4)) life = lifeTubes(if (t.lifetime >= 10_000) 5 else 4)
        setBank(life, t.lifetime)
        setBank(recDay, max(bestDay, t.prs))
        setBank(recWeek, Progress.bestWeek(history))
        if (records.biggest > 0) setBank(recBig, records.biggest, kM = true) else blankBank(recBig)
        records.fastestMin?.let { setBank(recFast, it) } ?: blankBank(recFast)
    }

    /** Net-negative day + personal-best glow. SPEC §6.4. */
    private fun applyDayState(t: Totals, now: Long, fade: Long = 150) {
        val netNeg = t.dels > t.adds && t.dels > 0
        adds.forEach { it.lvl = if (netNeg) 0.75f else 1f }
        dels.forEach { it.lvl = if (netNeg) 1f else 0.9f }
        val hot = t.prs > bestDay && bestDay >= 5
        hero.forEach { it.odFloor = if (hot) 0.25f else 0f }
        if (now >= momentUntil) {
            setLabel(linesLabel, if (netNeg) "LINES · NET NEGATIVE" else "LINES", false, now, fade)
            setLabel(heroLabel, heroLabelText(t), statusText() != null, now, fade)
        }
    }

    /** Label precedence (UX-PASS #15): SIGNED OUT > TOKEN EXPIRED > OFFLINE (no cache) > CONNECTING > the count. */
    private fun statusText(): String? = when {
        signedOut -> "SIGNED OUT · TAP TO SIGN IN"
        expired -> "TOKEN EXPIRED · TAP FOR SETUP"
        shown == null && offline -> "NO CONNECTION · RETRYING"
        shown == null -> "CONNECTING TO GITHUB"
        else -> null
    }

    /** "PRs MERGED · TODAY" (or the day's name while dozing), "· BEST" past the best day. */
    private fun heroLabelText(t: Totals?): String = statusText() ?: run {
        val day = if (dozeLabel) LocalDate.now().dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase() else "TODAY"
        "PRs MERGED · $day" + if (t != null && t.prs > bestDay && bestDay >= 5) " · BEST" else ""
    }

    /** Numbers moved without a fresh merge (late-found PR, midnight): roll quietly. */
    private fun plainRoll(t: Totals, now: Long) {
        rollHero(t.prs, now, 6, 0f, 0f, sympathetic = false)
        rollBank(adds, t.adds, now, kM = true)
        rollBank(dels, t.dels, now + 150, kM = true)
        rollBank(week, t.week, now, steps = { 6 })
    }

    // --------------------------------------------------------------- merge moments (SPEC §8, UX-PASS §6)

    private class Event(
        val before: Totals, val after: Totals, val pa: Int, val pd: Int, val tier: Int,
        val repo: String, val title: String, val n: Int, val century: Int?, val goal: Boolean,
        val news: String?, val coat: Int, val seed: Int, val replay: Boolean,
    )

    private val queue = ArrayDeque<Event>()
    private var nextSlot = 0L
    private var momentUntil = 0L
    private var lastMomentAt = -1_000_000L
    private var pendingPlay: Step? = null // the flyby is up; the strike is scheduled (pops pull it in)

    private fun inMoment(now: Long) = queue.isNotEmpty() || pendingPlay != null || now < momentUntil
    private fun inShow(now: Long) = now < showUntil

    private fun event(before: Totals, after: Totals, prs: List<Pr>, news: String? = null, replay: Boolean = false): Event {
        var tier = tierOf(prs.maxOf { it.additions + it.deletions })
        val century = if (after.lifetime / 100 > before.lifetime / 100) after.lifetime / 100 * 100 else null
        if (century != null) tier = XL
        if (bestDay >= 5 && before.prs <= bestDay && after.prs > bestDay) tier = max(tier, L)
        val goalHit = before.prs < goal && after.prs >= goal // hitting the daily goal is a big one too
        if (goalHit) tier = max(tier, L)
        val p = prs.last()
        return Event(before, after, prs.sumOf { it.additions }, prs.sumOf { it.deletions }, tier,
            p.repo.substringAfter('/'), p.title.replace(CONVENTIONAL, ""), prs.size, century, goalHit,
            news, coatOf(p), p.id.hashCode(), replay)
    }

    /**
     * Fit the moment (and at most one show) into what's left of the rear's window (UX-PASS §6): first an alert or rescue
     * is parked, then the flyby shrinks (to 1.2 s), then the goal surge goes, then the flyby, then a badge is parked
     * (NEW on its cell). The numbers are never cut. S merges have no flyby: the digit lands in ~300 ms.
     */
    private fun playNext(now: Long) {
        val e = queue.removeFirstOrNull() ?: return runShows(now)
        val night = isNight()
        var show = plannedShow
        plannedShow = null
        val numbers = 300 + NUMBERS_MS[e.tier]
        val avail = deadline() - now
        fun rest() = numbers + (show?.let { HOLD_SHOW_MS + it.ms } ?: 0L)
        var fly = if (night || e.tier == S) 0L else if (show != null) min(FLY_MS[e.tier], 3000L) else FLY_MS[e.tier]
        if (fly + rest() > avail && show != null && (show.kind == Kind.ALERT || show.kind == Kind.RESCUE)) { park(show); show = null }
        if (fly > 0 && fly + rest() > avail) fly = max(1200L, avail - rest())
        if (fly + rest() > avail && show?.kind == Kind.GOAL) show = null
        if (fly + rest() > avail) fly = 0
        if (fly + rest() > avail && show != null) { park(show); show = null }
        show?.let { shows.addFirst(it) }
        if (inShow(now)) cutShow()
        if (screen != Screen.TODAY) { leave(); screen = Screen.TODAY; screenAt = now; strikeIn(now, 30) } // a merge cuts straight back to Today
        val lead = if (fly > 0) fx().cats(now, e.tier, e.coat, fly) else 0L
        if (lead > 0) feel.mew(e.tier, e.seed, night)
        val showFollows = show != null
        pendingPlay = at(now + lead) { t -> pendingPlay = null; play(e, t, showFollows) }
    }

    /** A popped flyby cat bursts early: the strike follows the last cat out, not the timetable. */
    private fun pullStrike(now: Long) {
        val step = pendingPlay ?: return
        val t = max(now, fx?.strikeAt() ?: return)
        if (t >= step.at) return
        timeline.remove(step)
        pendingPlay = at(t, step.run)
    }

    private fun play(e: Event, t0: Long, showFollows: Boolean) {
        val night = isNight()
        val big = e.tier >= L
        if (screen != Screen.TODAY) { leave(); screen = Screen.TODAY; screenAt = t0 } // a merge cuts straight back to Today
        val numbers = 300 + NUMBERS_MS[e.tier]
        momentUntil = t0 + if (showFollows) numbers + HOLD_SHOW_MS else max(numbers + HOLD_SHOW_MS, min(MOMENT_MS[e.tier], deadline() - t0))
        nextSlot = momentUntil
        at(nextSlot) { playNext(it) }
        lastMomentAt = t0
        startFire(e, t0, night)
        lamp(t0, hold = 400, decay = 300)
        if (e.tier > S && !night) relayAt = t0
        if (!e.replay) feel.merge(e.tier, night) // the buzz and chime land with the strike, not at detection
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
        e.news?.let { n -> at(t0 + numbers) { now -> setLabel(heroLabel, n, true, now) } }
        at(t0 + numbers) { if (isAwake()) seenMissed() } // the latest merge has landed in front of you
        at(momentUntil) { now -> if (lastMomentAt == t0) settleMoment(e, now) }
        if (e.tier == S) {
            at(t0 + 150) { now ->
                rollBank(adds, e.after.adds, now, kM = true, od = 0.6f)
                rollBank(dels, e.after.dels, now + 150, kM = true, od = 0.6f)
                rollBank(week, e.after.week, now + 300, steps = { 6 }, od = 0.6f)
            }
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

    private fun at(time: Long, run: (Long) -> Unit): Step { val s = Step(time, run); timeline += s; invalidate(); return s }

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

    /** Strike the lit tubes in, [gap] apart, left to right — or right to left ([fromRight]: the screen came in from the right). */
    private fun strikeIn(now: Long, gap: Long, tubes: List<Tube> = visibleTubes(), fromRight: Boolean = false) {
        val lit = tubes.filter { it.ch != null }.sortedWith(compareBy({ it.x }, { it.y }))
        (if (fromRight) lit.reversed() else lit).forEachIndexed { i, t -> t.strike(now + i * gap) }
    }

    /**
     * Next (+1) or previous (−1) screen: the new one strikes in 120 ms later, its wave travelling the way the finger went,
     * and its text after it (UX-PASS #13).
     */
    private fun nextScreen(dir: Int, now: Long) {
        val all = Screen.entries
        leave()
        screen = all[(screen.ordinal + dir + all.size) % all.size]
        screenAt = now
        enter(now, visit = true)
        strikeIn(now + 120, 25, fromRight = dir > 0)
        tick()
        frame()
    }

    private fun goHome(now: Long) { leave(); screen = Screen.TODAY; screenAt = now; strikeIn(now, 30); frame() }

    /** Screen-specific setup as a screen comes up; [visit]: you swiped or tapped here (not a show or a data refresh). */
    private fun enter(now: Long, visit: Boolean = false) {
        when (screen) {
            Screen.RECENT -> startRecent(now)
            Screen.STREAK -> enterStreak(now, surge = false)
            Screen.WEEK -> { startRecap(now); if (visit && recapDue()) recapShow().done() } // you came to see it: it's seen
            Screen.OPEN -> enterOpen(now)
            Screen.CATS -> enterCats(now, visit)
            Screen.BADGES -> if (visit) playNewBadges(now)
            Screen.TODAY, Screen.LONG -> Unit
        }
    }

    /** Drop the screen's transient state (cards, filters, a recap or cat flight in progress). */
    private fun leave() {
        if (rescueId != null || peekUntil > 0) { fx?.unpeek(); peekUntil = 0 } // a rescue's paw, the missed-merge peek
        recentFocus = false; openFocus = -1; badgeFocus = null; unlocking = emptySet(); visitNew = emptySet()
        alertId = null; rescueId = null; openFilter = null; openPage = 0
        catCard = null; kittenCard = null; tagged = -1; taggedKitten = -1; noteUntil = 0
        recapToken++
        stopRub()
        if (screen == Screen.CATS) fx?.colony(emptyList())
    }

    /** New data while a screen other than Today is up: keep its numbers current. */
    private fun refreshScreen(prev: Snapshot?, now: Long) {
        if (inMoment(now) || inShow(now)) return
        when (screen) {
            Screen.STREAK -> enterStreak(now, surge = false)
            Screen.OPEN -> if (openFocus < 0) enterOpen(now)
            Screen.CATS -> if (prev?.prs?.map { it.id } != snap?.prs?.map { it.id } ||
                readyOf(prev).map { it.id } != readyOf(snap).map { it.id }) { if (!cardOpen()) enterCats(now, visit = false) }
            else -> Unit
        }
    }

    private fun cardOpen() = recentFocus || openFocus >= 0 || badgeFocus != null || catCard != null || kittenCard != null

    private fun closeCard(now: Long) {
        if (recentFocus) { recentFocus = false; cycleRecent(now) }
        openFocus = -1; badgeFocus = null; catCard = null; kittenCard = null
        tick(); frame()
        kickShows(now)
    }

    // --------------------------------------------------------------- showcases (design/V2.md, UX-PASS §6)
    // After a merge, or when a poll brings news, the screen cuts away for a few seconds (a badge unlock, the goal surge,
    // a failing PR rescued, an open PR turning green/red, the Friday recap), then back to Today. At most one per merge.

    private enum class Kind { BADGE, GOAL, RESCUE, ALERT, RECAP }

    /** [merge]: follows a merge moment (starts right after it); otherwise it waits 3 s after the last touch and for any card. */
    private class Show(val screen: Screen, val ms: Long, val kind: Kind, var merge: Boolean, val key: String?,
                       val badge: Badge? = null, val start: (Long) -> Unit, val done: () -> Unit = {})

    private val shows = ArrayDeque<Show>()
    private var playing: Show? = null // the show on screen now
    private var plannedShow: Show? = null // chosen for the merge being detected; playNext fits it in or parks it
    private var showUntil = 0L
    private var showToken = 0
    private val rescued = HashMap<String, Long>() // open PR id → when it was rescued (its row says RESCUED for an hour)

    /** Open PRs whose checks went from failing to passing since the last snapshot (UX-PASS #19). */
    private fun rescues(prev: Snapshot?, s: Snapshot): List<OpenPr> {
        val was = prev?.open?.associateBy { it.id } ?: return emptyList()
        return openOf(s).filter { p -> was[p.id]?.failing == true && p.checks == "SUCCESS" }
    }

    /** Pick the rarest news for the one slot: badge > goal > rescue > alert. The rest go to the ledger. */
    private fun queueShows(prev: Snapshot?, s: Snapshot, earned: List<Badge>, goalHit: Boolean, merge: Boolean, now: Long) {
        val res = rescues(prev, s)
        res.forEach { rescued[it.id] = System.currentTimeMillis() }
        val alert = prev?.let { pv ->
            val was = pv.open.associate { it.id to it.status.light }
            openOf(s).filter { p -> res.none { it.id == p.id } && was[p.id].let { it != null && it != p.status.light } &&
                p.status.light.let { it == OpenPr.Light.GREEN || it == OpenPr.Light.RED } }.minByOrNull { it.status.light.ordinal }
        }
        if (isNight()) { earned.forEach(::ledgerBadge); return } // quiet hours: NEW on the cells, the rest is dropped
        val cands = earned.map { unlockShow(it, merge) } + listOfNotNull(if (goalHit) goalShow() else null) +
            res.take(1).map { rescueShow(it, merge) } + listOfNotNull(alert?.let { alertShow(it, merge) })
        if (!isAwake()) { cands.forEach(::park); return } // seen on a later wake
        cands.drop(1).forEach(::park)
        val pick = cands.firstOrNull() ?: return
        if (merge) { plannedShow?.let(::park); plannedShow = pick } else { shows += pick; kickShows(now) }
    }

    private fun unlockShow(b: Badge, merge: Boolean) = Show(Screen.BADGES, BADGE_SHOW_MS, Kind.BADGE, merge, b.name, b, { t ->
        unlocking = setOf(b); unlockAt = t + 150
        at(unlockAt) { feel.sparkle(isNight()) }
    }) { unledger(b) }

    private fun goalShow() = Show(Screen.STREAK, 2500, Kind.GOAL, true, null, start = { t -> enterStreak(t, surge = true) })

    private fun alertShow(p: OpenPr, merge: Boolean) = Show(Screen.OPEN, 3000, Kind.ALERT, merge, "${p.id}:${p.status.light}",
        start = { t -> alertId = p.id; alertAt = t + 200; enterOpen(t) })

    /** A cat reaches in from under the island and bats the red jewel; it flips green with a ding (UX-PASS #19). */
    private fun rescueShow(p: OpenPr, merge: Boolean) = Show(Screen.OPEN, 2500, Kind.RESCUE, merge, p.id, start = { t ->
        rescueId = p.id; rescueAt = t + 700; enterOpen(t)
        fx().peek(t, 71f, -24f, 1300L, if (pumpkinWeek()) MergeFx.PUMPKIN else 0)
        at(rescueAt) { if (rescueId == p.id) { feel.ding(isNight()); feel.feel(Feel.Kind.CONFIRM) } }
    })

    private fun recapShow() = Show(Screen.WEEK, recapMs() + 2200, Kind.RECAP, false, null, start = { t -> startRecap(t) }) {
        prefs.edit().putString("recapWeek", LocalDate.now().with(DayOfWeek.MONDAY).toString()).apply() // seen only once it finished
    }

    /** Fri from 17:00, or the weekend, once per week (marked when it finishes), if anything merged this week. */
    private fun recapDue(): Boolean {
        val s = snap ?: return false
        val now = LocalDateTime.now()
        val d = now.dayOfWeek
        if (!(d == DayOfWeek.FRIDAY && now.hour >= 17 || d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY)) return false
        val monday = now.toLocalDate().with(DayOfWeek.MONDAY)
        return prefs.getString("recapWeek", null) != monday.toString() && s.prs.any { it.mergedAtMs >= startOf(monday) }
    }

    private fun kickShows(now: Long) { if (!inMoment(now) && now >= showUntil && shows.isNotEmpty()) runShows(now) }

    /** Play the next show if it can finish before the rear dims; the last one hands back to Today. */
    private fun runShows(now: Long) {
        if (inMoment(now) || now < showUntil) return
        val sh = shows.firstOrNull() ?: return
        if (isNight() || !isAwake() || now + sh.ms > deadline()) { endChain(); return }
        if (!sh.merge && cardOpen()) return // closeCard() kicks it
        if (!sh.merge && now - touchAt < 3000) { at(touchAt + 3000) { runShows(it) }; return }
        if (now + sh.ms > deadline()) { endChain(); return }
        shows.removeFirst()
        leave()
        playing = sh
        screen = sh.screen; screenAt = now
        sh.start(now)
        strikeIn(now, 30)
        showUntil = now + sh.ms
        val token = ++showToken
        at(showUntil) { t ->
            if (token != showToken) return@at
            showUntil = 0; playing = null
            sh.done()
            if (shows.isNotEmpty()) runShows(t)
            if (showUntil == 0L && screen != Screen.TODAY) goHome(t)
        }
        frame()
    }

    /** The show on screen is cut short (a merge, the dim): it waits in the ledger and isn't marked seen. */
    private fun cutShow() {
        playing?.let(::park); playing = null
        showUntil = 0; showToken++
    }

    /** Stop the show chain: what's left waits in the ledger. [skipped]: you tapped or swiped it away (a recap counts as seen). */
    private fun endChain(skipped: Boolean = false) {
        if (skipped) playing?.takeIf { it.kind == Kind.RECAP }?.done?.invoke()
        playing = null
        shows.forEach(::park); shows.clear()
        showUntil = 0; showToken++
    }

    /**
     * Debug builds (DebugReceiver `--es show …`): goal | badge:NAME | badge:all | open | rescue | recap | cats | arrive |
     * peek | missed | rearon | rearoff | hints | screen:NAME | buzz:S…XL. Each opens a fresh minute-long window.
     */
    fun debug(cmd: String) {
        val now = SystemClock.uptimeMillis()
        val s = snap ?: return
        onAt = now; debugUntil = now + 60_000
        when {
            cmd == "goal" -> shows += goalShow()
            cmd == "badge:all" -> Badge.entries.forEach { shows += unlockShow(it, true) }
            cmd.startsWith("badge:") -> shows += unlockShow(Badge.valueOf(cmd.substringAfter(':')), true)
            cmd == "open" -> openOf(s).minByOrNull { it.status.light.ordinal }?.let { shows += alertShow(it, true) }
            cmd == "rescue" -> openOf(s).firstOrNull()?.let { rescued[it.id] = System.currentTimeMillis(); shows += rescueShow(it, true) }
            cmd == "recap" -> shows += recapShow().also { it.merge = true }
            cmd.startsWith("buzz:") -> feel.merge(listOf("S", "M", "L", "XL").indexOf(cmd.substringAfter(':')).coerceAtLeast(0), false)
            cmd == "peek" -> peek(now)
            cmd == "missed" -> { prefs.edit().putLong("missedAt", System.currentTimeMillis() - 5 * 60_000L).apply(); rearOn() }
            cmd == "rearon" -> rearOn()
            cmd == "rearoff" -> rearOff()
            cmd == "hints" -> hints.edit().clear().apply()
            cmd == "arrive" -> { prefs.edit().putLong("catsSeenAt", 0L).apply(); goTo(Screen.CATS, now) }
            cmd == "cats" -> { goTo(Screen.CATS, now); fx().wake(now) }
            cmd.startsWith("screen:") -> goTo(Screen.valueOf(cmd.substringAfter(':')), now)
        }
        kickShows(now)
    }

    private fun goTo(sc: Screen, now: Long) {
        if (inMoment(now) || inShow(now)) return
        leave(); screen = sc; screenAt = now; enter(now, visit = true); strikeIn(now, 30); frame()
    }

    // --------------------------------------------------------------- streak + goal

    private var barFrom = 0f
    private var barTo = 0f
    private var barAt = -10_000L
    private var surgeAt = -10_000L
    private var bestStreak = 0

    private fun enterStreak(now: Long, surge: Boolean) {
        val t = shown ?: return
        val today = LocalDate.now()
        val n = if (streak >= 100) 3 else 2
        if (stk.size != n) stk = bigTubes(n)
        bestStreak = Progress.bestStreak(history, today)
        setBank(stk, streak); setBank(bestBank, bestStreak); setBank(toGoBank, abs(goal - t.prs))
        bestBank.forEach { it.lvl = if (streak > 0 && streak >= bestStreak) 1f else 0.6f } // matching your best lights it up
        // The bar fills up as the screen arrives; the goal-hitting merge surges it from the last PR's mark.
        barFrom = if (surge) max(0f, (t.prs - 1) / goal.toFloat()) else 0f
        barTo = t.prs / goal.toFloat(); barAt = now + 250
        surgeAt = if (surge) barAt + BAR_MS else -10_000L
        if (surge && !isNight()) { // the bargraph's cat rockets out of the end of the tube (UX-PASS #9)
            val v = snap?.prs?.maxByOrNull { it.mergedAtMs }?.let(::coatOf) ?: 0
            at(surgeAt) { if (screen == Screen.STREAK) { fx().rocket(it, 48f, 408f, v); feel.feel(Feel.Kind.CONFIRM) } }
        }
    }

    private fun bar(now: Long): Float {
        val u = ((now - barAt) / BAR_MS.toFloat()).coerceIn(0f, 1f)
        return barFrom + (barTo - barFrom) * (1 - (1 - u) * (1 - u) * (1 - u))
    }

    // --------------------------------------------------------------- week recap

    private var weekStats: Progress.Week? = null
    private var lastWeek = IntArray(7)
    private var recapDay = 6 // day columns revealed so far (Mon = 0)
    private var recapToken = 0
    private val bestLabel = Label("")
    private val sumLabel = Label("")
    private val vsLabel = Label("")

    private fun recapMs() = 350L + 350L * LocalDate.now().dayOfWeek.value + 900

    /**
     * Banks from zero, then day by day the day lights and the tubes roll up to the running totals. Columns not yet
     * revealed show last week's count for that day in ghost wire; this week's strikes over it (UX-PASS #20).
     */
    private fun startRecap(now: Long) {
        val s = snap ?: return
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val w = Progress.week(s.prs, today, zone, goal).also { weekStats = it }
        lastWeek = Progress.lastWeek(history, today)
        val n = if (w.prs >= 100) 3 else 2
        if (wprs.size != n) wprs = bigTubes(n)
        setBank(wprs, 0); setBank(wadds, 0, kM = true); setBank(wdels, 0, kM = true)
        recapDay = -1
        setLabel(bestLabel, "", false, now, 0); setLabel(sumLabel, "", false, now, 0); setLabel(vsLabel, "", false, now, 0)
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
        at(end + 500) { tt -> if (token == recapToken) { setLabel(sumLabel, summaryText(w), false, tt, 400); setLabel(vsLabel, vsText(w), false, tt, 400) } }
    }

    private fun bestText(w: Progress.Week) = w.best?.let { "BEST · ${it.repo.substringAfter('/').uppercase()} #${it.number} · +%,d −%,d".format(it.additions, it.deletions) }
        ?: "NO MERGES YET THIS WEEK"

    private fun summaryText(w: Progress.Week): String {
        val badges = book.earnedSince(w.start).size
        return "STREAK $streak · GOAL ${w.goalDays} / ${w.workdaysSoFar} DAYS · $badges ${if (badges == 1) "BADGE" else "BADGES"}"
    }

    /** This week so far against last week up to the same weekday. */
    private fun vsText(w: Progress.Week): String {
        val upTo = LocalDate.now().dayOfWeek.value
        val diff = w.perDay.take(upTo).sum() - lastWeek.take(upTo).sum()
        return when { diff > 0 -> "+$diff VS LAST WEEK"; diff < 0 -> "−${-diff} VS LAST WEEK"; else -> "SAME AS LAST WEEK" }
    }

    // --------------------------------------------------------------- open PRs

    private var openRows: List<OpenPr> = emptyList()
    private var openFocus = -1 // row whose detail card is up
    private var openFilter: OpenPr.Light? = null // a tapped count: just that colour's rows, 5 a page
    private var openPage = 0
    private var openPool = 0
    private var alertId: String? = null
    private var alertAt = -10_000L
    private var rescueId: String? = null
    private var rescueAt = -10_000L

    private fun enterOpen(now: Long) {
        val open = openOf(snap)
        val sorted = open.sortedWith(compareBy({ it.status.light.ordinal }, { -it.updatedAtMs }))
        val first = sorted.firstOrNull { it.id == alertId || it.id == rescueId } // a show's row is always on the board, first
        val pool = (listOfNotNull(first) + sorted.filter { it !== first }).filter { openFilter == null || it.status.light == openFilter }
        openPool = pool.size
        if (openPage * 5 >= pool.size) openPage = 0 // a poll shrank the filtered list under this page
        openRows = pool.drop(openPage * 5).take(5)
        openFocus = -1
        setBank(oReady, open.count { it.status.light == OpenPr.Light.GREEN })
        setBank(oWait, open.count { it.status.light == OpenPr.Light.AMBER })
        setBank(oNeeds, open.count { it.status.light == OpenPr.Light.RED })
        focusLayout = null
    }

    /** Rows open their card; a count filters the rows to its colour (again: the next 5; past the end: all); elsewhere: next. */
    private fun tapOpen(sx: Float, sy: Float, now: Long) {
        if (sy < 52f) { nextScreen(+1, now); return }
        if (sx > 392f) {
            val band = when { sy < 182f -> OpenPr.Light.GREEN; sy < 340f -> OpenPr.Light.AMBER; else -> OpenPr.Light.RED }
            if (openFilter != band) { openFilter = band; openPage = 0 }
            else if ((openPage + 1) * 5 < openPool) openPage++
            else { openFilter = null; openPage = 0 }
            enterOpen(now); tick(); frame(); return
        }
        val row = floor((sy - 52f) / 86f).toInt()
        if (row !in openRows.indices) { nextScreen(+1, now); return }
        openFocus = row; focusLayout = null; cardAt = now; learn("openRow")
        tick(); frame()
    }

    // --------------------------------------------------------------- badges

    private var badgeFocus: Badge? = null
    private var unlocking: Set<Badge> = emptySet()
    private var visitNew: Set<Badge> = emptySet() // NEW badges playing their unlock on this visit
    private var unlockAt = -10_000L

    /** Unlocks that didn't get to play (NEW) play in their cells when you visit, then they're seen. */
    private fun playNewBadges(now: Long) {
        val nb = newBadges().mapNotNull { n -> Badge.entries.find { it.name == n } }.toSet()
        if (nb.isEmpty()) return
        unlocking = nb; visitNew = nb; unlockAt = now + 250
        at(unlockAt) { feel.sparkle(isNight()) }
        prefs.edit().remove("newBadges").apply()
    }

    private fun tapBadges(sx: Float, sy: Float, now: Long) {
        val col = floor((sx - 24f) / 158f).toInt(); val row = floor((sy - 52f) / 240f).toInt()
        val b = Badge.entries.getOrNull(row * 4 + col)
        if (sy < 52f || col !in 0..3 || row !in 0..1 || b == null) { nextScreen(+1, now); return }
        badgeFocus = b; focusLayout = null; cardAt = now; learn("badge")
        tick(); frame()
    }

    private fun badgeProgress(b: Badge): Pair<String, String?> {
        val today = LocalDate.now()
        val todays = snap?.prs?.filter { it.mergedAtMs >= startOf(today) }.orEmpty()
        return BadgeBook.progress(b, todays, weekPrs(), goal, streak, book.rolled(Progress.streakStart(history, today)),
            shown?.lifetime ?: 0, LocalDateTime.now())
    }

    // --------------------------------------------------------------- cats (UX-PASS §5)

    private class Kitten(val x: Float, val pr: OpenPr)

    private var colonyCats: List<MergeFx.Sleeper> = emptyList()
    private var kittens: List<Kitten> = emptyList()
    private var lastShelf: Map<String, Float> = emptyMap() // kitten x by PR id at the last visit (a merged one arrives from there)
    private var tagged = -1          // colony index whose tag is up (tap it again for its card)
    private var pokedAt = 0L
    private var taggedKitten = -1
    private var catCard: Pr? = null
    private var kittenCard: OpenPr? = null
    private val mewed = HashSet<Int>() // one mew per cat per visit

    /** READY (or auto-merging) open PRs: kittens on the shelf above the pile. */
    private fun readyOf(s: Snapshot?) = openOf(s).filter { it.status == OpenPr.Status.READY || it.autoMerge }.take(5)

    /**
     * Lay out this week's colony (and the shelf). On a [visit], cats merged since the last one come home: up to 3 fly in
     * (from the shelf if they were kittens), up to 6 more look up; their neighbours look up as they land.
     */
    private fun enterCats(now: Long, visit: Boolean) {
        val prs = weekPrs()
        kittens = readyOf(snap).mapIndexed { k, p -> Kitten(628f - 62f * k, p) }
        val rank = MergeFx.rank(prs)
        val today = coatDay()
        colonyCats = MergeFx.pile(prs, COLONY_MAX, if (kittens.isEmpty()) 52f else 116f) { MergeFx.coat(it, rank, today) }
        val f = fx()
        f.colony(colonyCats)
        mewed.clear(); tagged = -1; taggedKitten = -1
        if (!visit) return
        val seenAt = prefs.getLong("catsSeenAt", -1L)
        prefs.edit().putLong("catsSeenAt", System.currentTimeMillis()).apply()
        val shelf = lastShelf
        lastShelf = kittens.associate { it.pr.id to it.x }
        if (seenAt < 0 || isNight() || !isAwake()) return
        val fresh = colonyCats.indices.filter { (colonyCats[it].pr?.mergedAtMs ?: 0) > seenAt }.reversed() // newest first
        val home = fresh.take(3)
        val land = f.arrive(home.map { i ->
            val x0 = colonyCats[i].pr?.id?.let { shelf[it] }
            Triple(i, x0 ?: -120f, if (x0 != null) KITTEN_Y else 120f + 300f * MergeFx.rnd(colonyCats[i].seed, 5, 5))
        }, now)
        val pile = colonyCats
        if (home.isNotEmpty()) at(now + land) { t ->
            if (colonyCats !== pile || screen != Screen.CATS) return@at // rebuilt mid-flight, or you left: those flights are gone
            feel.feel(Feel.Kind.LAND, home.maxOf { tierOf(pile[it].pr ?: return@maxOf 0) })
            home.flatMap { f.neighbours(it, 2) }.distinct().forEach { j -> f.stir(j, MergeFx.HEAD_UP, t, t + 600) }
            at(t + 600) { invalidate() }
        }
        fresh.drop(3).take(6).forEachIndexed { k, i ->
            val s = now + 300 + 220L * k
            f.stir(i, MergeFx.HEAD_UP, s, s + 500)
            at(s) { invalidate() }; at(s + 500) { invalidate() }
        }
    }

    /**
     * Header strip: wake the newest. A cat or kitten within 60 px: poke it (it hops, sits up, mews; its tag fills the
     * header); the same one again while tagged: its card. Nothing in range: wake the newest. Never navigates.
     */
    private fun tapCats(sx: Float, sy: Float, now: Long) {
        val f = fx()
        if (sy < 52f) { learn("wakeAll"); wakeAll(now); return }
        if (f.busy(now)) { tick(); return }
        val i = f.nearest(sx, sy, 60f, now)
        val k = kittens.indices.filter { hypot(kittens[it].x - sx, KITTEN_Y - sy) < 60f }.minByOrNull { hypot(kittens[it].x - sx, KITTEN_Y - sy) }
            ?.takeIf { kk -> f.sleeper(i)?.let { h -> hypot(h.x - sx, h.y - sy) } ?.let { hypot(kittens[kk].x - sx, KITTEN_Y - sy) < it } ?: true } ?: -1
        if (k >= 0) {
            val p = kittens[k].pr
            if (taggedKitten == k && noteOn(now)) { kittenCard = p; focusLayout = null; cardAt = now; noteUntil = 0; tick(); frame(); return }
            taggedKitten = k; tagged = -1; f.unpoke()
            note("${statusWord(p)} · #${p.number} · ${p.repo.substringAfter('/').uppercase()}", MergeFx.POKE_MS, now)
            tick(); frame(); return
        }
        val h = f.sleeper(i)
        val p = h?.pr
        if (h == null || p == null) { wakeAll(now); return }
        if (tagged == i && noteOn(now)) { catCard = p; focusLayout = null; cardAt = now; noteUntil = 0; f.unpoke(); tick(); frame(); return }
        learn("poke")
        tagged = i; taggedKitten = -1
        val rare = when { h.variant == MergeFx.GOLD -> "GOLD CAT · "; h.ghost -> "GHOST CAT · "; else -> "" }
        note(rare + "${p.repo.substringAfter('/').uppercase()} · #${p.number} · +%,d −%,d · ${ago(p.mergedAtMs)}".format(p.additions, p.deletions),
            MergeFx.POKE_MS, now)
        val night = isNight()
        f.poke(i, now, hop = !night)
        pokedAt = now
        at(now + MergeFx.POKE_MS) { if (tagged == i && pokedAt == now) { tagged = -1; fx?.unpoke() }; invalidate() }
        tick()
        if (!night && mewed.add(i)) feel.mew(tierOf(p), p.id.hashCode(), night)
        frame()
    }

    /** The newest 6 wake and fly a calm lap; one LAND as the first touches down. Asleep at night (and says so). */
    private fun wakeAll(now: Long) {
        if (isNight()) { note("CATS ASLEEP TILL %02d:00".format(quietEnd), 2000, now); tick(); return }
        val land = fx().wake(now, 6)
        tick()
        if (land > 0) at(now + land) { if (screen == Screen.CATS) feel.feel(Feel.Kind.LAND, 1) }
        invalidate()
    }

    // --------------------------------------------------------------- recent

    private fun startRecent(now: Long) {
        recent = snap?.prs?.sortedByDescending { it.mergedAtMs }?.take(5).orEmpty()
        recentIdx = 0
        recentFocus = false
        showRecent(now, instant = true)
        cycleRecent(now)
    }

    /** Advance the highlight every 2.6 s while the list (not a focused card) is up, one lap, then rest on the newest. */
    private fun cycleRecent(now: Long) {
        val token = ++recentCycle
        fun tick(t: Long) {
            if (screen != Screen.RECENT || recentFocus || token != recentCycle || recent.size < 2) return
            recentIdx = (recentIdx + 1) % recent.size
            showRecent(t, instant = false)
            if (recentIdx != 0) at(t + 2600) { tick(it) } // ponytail: no 20 s return any more, so the lap ends it (0 fps after)
        }
        at(now + 2600) { tick(it) }
    }

    /** A row opens that PR's detail card; anywhere else goes to the next screen. */
    private fun tapRecent(sx: Float, sy: Float, now: Long) {
        val row = floor((sy - 52f) / 86f).toInt()
        if (sx > 392f || sy < 52f || row !in recent.indices) { nextScreen(+1, now); return }
        recentFocus = true; focusLayout = null; cardAt = now; learn("row")
        if (row != recentIdx) { recentIdx = row; showRecent(now, instant = false) }
        tick(); frame()
    }

    private fun showRecent(now: Long, instant: Boolean) {
        val p = recent.getOrNull(recentIdx)
        val a = p?.additions ?: 0
        val d = p?.deletions ?: 0
        if (instant) { setBank(radds, a, kM = true); setBank(rdels, d, kM = true) }
        else { rollBank(radds, a, now, kM = true, od = 0.5f); rollBank(rdels, d, now + 120, kM = true, od = 0.5f) }
    }

    // --------------------------------------------------------------- replay, peek

    /** Celebrate the most recent merge again (Today rewinds to just before it); [news]: then the first unseen thing. */
    private fun replay(now: Long, news: Boolean = false) {
        val t = shown ?: return
        val p = snap?.prs?.maxByOrNull { it.mergedAtMs } ?: return
        if (inMoment(now) || inShow(now) || isStill()) return
        val b = t.minus(p)
        val n = if (b.prs >= 100) 3 else 2
        if (hero.size != n) hero = heroTubes(n)
        setBank(hero, b.prs); setBank(adds, b.adds, kM = true); setBank(dels, b.dels, kM = true); setBank(week, b.week)
        leave(); screen = Screen.TODAY; screenAt = now
        queue.add(event(b, t, listOf(p), if (news && b.prs < goal && t.prs >= goal) "GOAL MET · ${t.prs}/$goal" else null, replay = true))
        if (news) plannedShow = ledgerShow()
        playNext(now)
        invalidate()
    }

    private var peekUntil = 0L

    /** A merge you missed (UX-PASS #8): a cat peeks out from under the island by the digits; tap it to replay. */
    private fun peek(now: Long) {
        val p = snap?.prs?.maxByOrNull { it.mergedAtMs } ?: return
        if (isNight()) return
        peekUntil = now + PEEK_MS
        fx().peek(now, 470f, -6f, PEEK_MS, coatOf(p))
        note("TAP THE CAT · REPLAY", PEEK_MS, now)
        invalidate()
    }

    /** No snapshot yet: hero tubes shuffle dimly (clearly not a value). Never show 0 before data. */
    private val warm = object : Runnable {
        override fun run() {
            if (shown != null || signedOut || offline || isStill()) return
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
            snap?.let { cooled = expired || System.currentTimeMillis() - it.fetchedAtMs > STALE_MS }
            if (++ticks % 3 == 0 && shown != null && !isStill() && now - lastMomentAt > 120_000 && now >= momentUntil) {
                visibleTubes().filter { it.ch != null }.forEach { t ->
                    at(now + ((t.x + t.cls.w / 2) / 680f * 900).toLong()) { tt -> t.to(tt, t.ch, t.comma, 10, 30, force = true) }
                }
            }
            if (drift() != drawnDrift) frame() // pixel drift (burn-in) — pushed to the panel even while dozing
            postDelayed(this, 5 * 60_000L)
        }
    }

    // --------------------------------------------------------------- touch (UX-PASS §4)
    // Only reaches us while the rear is awake: once it dims, HyperOS swallows touches until a double-tap wakes it
    // (and a double-tap on the awake rear sleeps it, so Merged has no double-tap gestures).
    //
    // Layers, first match wins: a merge moment (tap a flying cat: pop; swipe: settle and move), a show (tap: Today;
    // swipe: move on), a card (tap: close), signed out / token expired (tap: Setup), then the screen's own map.
    // A pause decides: moving within 250 ms of touchdown is a swipe (or, on CATS, a rub once it reverses near a cat);
    // resting 250 ms then moving is a hold-drag (the pet cat; on CATS the laser dot); still for 1.5 s is Setup.

    private enum class Mode { TAP, SWIPE, PET, LASER, RUB }

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private var mode = Mode.TAP
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastSX = 0f
    private var lastSY = 0f
    private var popped = false
    private var longFired = false
    private var vt: VelocityTracker? = null
    private var rubCat = -1   // the colony cat under the finger at touchdown
    private var rubDir = 0
    private var rubExt = 0f
    private var rubPath = 0f
    private var rubAt = 0L
    private var purring = false
    private val longPress = Runnable {
        val now = SystemClock.uptimeMillis()
        if (mode == Mode.TAP && !inMoment(now) && !inShow(now) && !cardOpen()) { longFired = true; openSetup(now) }
    }
    private val purrPause = Runnable { if (purring) { feel.stopPurr(); purring = false } }

    // Stage transform from the last frame, to map touches into stage px.
    private var stageX0 = 0f
    private var stageY0 = 0f
    private var stageS = 1f

    override fun performClick(): Boolean { super.performClick(); nextScreen(+1, SystemClock.uptimeMillis()); return true }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        val sx = (e.x - stageX0) / stageS
        val sy = (e.y - stageY0) / stageS
        if (BuildConfig.DEBUG && e.actionMasked != MotionEvent.ACTION_MOVE) android.util.Log.d("Merged.Touch",
            "${MotionEvent.actionToString(e.actionMasked)} still=${isStill()} moment=${inMoment(now)} show=${inShow(now)} mode=$mode")
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchAt = now; downAt = now; downX = e.x; downY = e.y; lastSX = sx; lastSY = sy
                mode = Mode.TAP; longFired = false; popped = false
                vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) }
                postDelayed(longPress, LONG_MS)
                if (inMoment(now) && fx?.pop(sx, sy, now) == true) { // a flying cat, hit: it bursts right there
                    popped = true; feel.feel(Feel.Kind.POP); pullStrike(now); invalidate()
                }
                rubCat = if (screen == Screen.CATS && shown != null && !cardOpen() && !inShow(now)) fx?.nearest(sx, sy, 70f, now) ?: -1 else -1
                rubDir = 0; rubExt = sx; rubPath = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                touchAt = now
                if (longFired) return true
                if (mode == Mode.TAP && hypot(e.x - downX, e.y - downY) > slop) {
                    removeCallbacks(longPress)
                    mode = when {
                        now - downAt < HOLD_MS || !canHold(now) -> Mode.SWIPE
                        screen == Screen.CATS -> Mode.LASER.also { fx().laserDown(sx, sy, now); tick() }
                        else -> Mode.PET.also { fx().petDown(sx, sy, now); tick() }
                    }
                }
                when (mode) {
                    Mode.PET -> { fx().petMove(sx, sy); invalidate() }
                    Mode.LASER -> { fx().laserMove(sx, sy, now); invalidate() }
                    Mode.SWIPE -> if (rubCat >= 0) rubTrack(sx, sy, now)
                    Mode.RUB -> rubMove(sx, sy, now)
                    Mode.TAP -> Unit
                }
                lastSX = sx; lastSY = sy
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                val v = vt?.apply { addMovement(e); computeCurrentVelocity(1000) }
                val vx = v?.xVelocity ?: 0f
                val vy = v?.yVelocity ?: 0f
                vt?.recycle(); vt = null
                val cancel = e.actionMasked == MotionEvent.ACTION_CANCEL
                val dx = e.x - downX
                val dy = e.y - downY
                when (mode) {
                    Mode.RUB -> stopRub()
                    Mode.PET -> { // set down slowly: it flies home under the island; flung: it bursts
                        val burst = fx().petFling(if (cancel) 0f else vx / stageS, if (cancel) 0f else vy / stageS, now)
                        feel.feel(if (burst) Feel.Kind.POP else Feel.Kind.TICK); invalidate()
                    }
                    Mode.LASER -> { val land = fx().laserUp(now); if (land > 0) at(now + land) { feel.feel(Feel.Kind.LAND, 1) }; invalidate() }
                    Mode.SWIPE -> if (!cancel && abs(dx) > 90 * stageS && abs(dx) > 1.5f * abs(dy)) swipe(if (dx < 0) +1 else -1, now)
                    Mode.TAP -> if (!cancel && !longFired && !popped) tap(sx, sy, now)
                }
                mode = Mode.TAP
            }
        }
        return true
    }

    /** Hold-then-drag plays (the pet, the laser) only on a quiet instrument by day, awake, with no card up. */
    private fun canHold(now: Long) = shown != null && !inMoment(now) && !inShow(now) && !isNight() && !isStill() && !cardOpen()

    private fun swipe(dir: Int, now: Long) {
        val t = shown ?: return
        if (inMoment(now)) settle(t) // the numbers land at once, then you move
        else if (inShow(now)) endChain(skipped = true) // move on from the show's screen
        nextScreen(dir, now)
    }

    private fun tap(sx: Float, sy: Float, now: Long) {
        if (signedOut || expired) { openSetup(now); return }
        if (shown == null) { if (offline) openSetup(now) else tick(); return } // still connecting: nothing to act on yet
        if (inMoment(now)) { lamp(now, hold = 120, decay = 400); frame(); return } // refused (a hit on a flying cat popped it at DOWN)
        if (inShow(now)) { endChain(skipped = true); goHome(now); tick(); return }
        if (cardOpen()) { closeCard(now); return }
        when (screen) {
            Screen.TODAY -> tapToday(sx, sy, now)
            Screen.RECENT -> tapRecent(sx, sy, now)
            Screen.OPEN -> tapOpen(sx, sy, now)
            Screen.BADGES -> tapBadges(sx, sy, now)
            Screen.CATS -> tapCats(sx, sy, now)
            else -> nextScreen(+1, now)
        }
    }

    /**
     * Tap the count (the hero tubes): replay the last merge. Not a double-tap: on the rear, HyperOS keeps that for
     * itself (it sleeps an awake rear and wakes a dim one). The peeking cat replays too; anywhere else, the next screen.
     */
    private fun tapToday(sx: Float, sy: Float, now: Long) {
        if (now < peekUntil && sx < 160f && sy > 400f) {
            fx?.unpeek(); peekUntil = 0; noteUntil = 0
            tick(); replay(now, news = true); return
        }
        if (sx < 360f && sy < 490f) { learn("tapReplay"); tick(); replay(now) } else nextScreen(+1, now)
    }

    /** Setup opens on the front screen (often face down): say so on the rear. */
    private fun openSetup(now: Long) {
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        note(if (locked) "UNLOCK YOUR PHONE FOR SETUP" else "SETUP · ON YOUR PHONE", 3000, now)
        feel.feel(Feel.Kind.LONG_PRESS)
        onSetup?.invoke()
    }

    /** Moving on CATS: a direction reversal within 70 px of the cat under the finger turns the swipe into a rub. */
    private fun rubTrack(sx: Float, sy: Float, now: Long) {
        if (rubDir == 0) { if (abs(sx - rubExt) > 12f) { rubDir = if (sx > rubExt) 1 else -1; rubExt = sx }; return }
        if ((sx - rubExt) * rubDir > 0) { rubExt = sx; return }
        if (abs(sx - rubExt) < 12f) return
        val h = fx?.sleeper(rubCat) ?: return
        if (hypot(sx - h.x, sy - h.y) > 70f) { rubDir = -rubDir; rubExt = sx; return }
        mode = Mode.RUB; rubAt = now; rubPath = 0f
        learn("rub")
        fx().rub(rubCat)
        feel.purr(); purring = true
        postDelayed(purrPause, 150)
        invalidate()
    }

    /** Rubbing: a heart every ~150 px (not at night), the purr while the finger moves (paused after 150 ms still; 4 s at most). */
    private fun rubMove(sx: Float, sy: Float, now: Long) {
        rubPath += hypot(sx - lastSX, sy - lastSY)
        if (rubPath >= 150f) { rubPath -= 150f; if (!isNight()) { fx().heart(rubCat, now); invalidate() } }
        removeCallbacks(purrPause)
        if (now - rubAt < 4000) { if (!purring) { feel.purr(); purring = true }; postDelayed(purrPause, 150) }
        else if (purring) { feel.stopPurr(); purring = false }
    }

    private fun stopRub() {
        removeCallbacks(purrPause)
        if (purring) { feel.stopPurr(); purring = false }
        if (mode == Mode.RUB) { fx?.rub(-1); mode = Mode.TAP; invalidate() }
    }

    // --------------------------------------------------------------- hints (UX-PASS #14)

    private fun learned(k: String) = hints.getBoolean(k, false)
    private fun learn(k: String) { if (!learned(k)) hints.edit().putBoolean(k, true).apply() }
    /** Hints only while the rear is fully on and nothing is playing. */
    private fun hinting(now: Long) = isAwake() && !inShow(now) && !inMoment(now)

    /** Rear screen asleep → no timers at all; awake → drift/sweep every 5 min (+ warm-up shuffle). */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        removeCallbacks(ambient); removeCallbacks(warm)
        if (visibility != VISIBLE) return
        readSettings()
        postDelayed(ambient, 5 * 60_000L)
        if (shown == null && !signedOut) post(warm)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isAwake()) onAt = SystemClock.uptimeMillis()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(warm); removeCallbacks(ambient); removeCallbacks(kick); removeCallbacks(longPress)
        removeCallbacks(purrPause)
        vt?.recycle(); vt = null
        feel.release()
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

    /** Quiet hours (Setup; default 23:00–07:00): no cats, no sounds, no shows. */
    private fun isNight() = Progress.quiet(quietStart, quietEnd, LocalTime.now().hour) && !(debugPrefs?.getBoolean("day", false) ?: false)
    /** Debug builds: DebugReceiver `--ez day true` turns night mode off (filming at night). */
    private val debugPrefs = if (BuildConfig.DEBUG) ctx.getSharedPreferences("debug", Context.MODE_PRIVATE) else null

    /** A new screen's text strikes in with its tubes (UX-PASS #13). */
    private fun enterA(now: Long) = if (now - screenAt > 400) 1f else strikeCurve(now - screenAt - 120)

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
        val sd = dim * enterA(now)
        val relay = when (val dt = now - relayAt) { in 0..59 -> 0.35f; in 60..89 -> 0.35f + 0.65f * (dt - 60) / 30f; else -> 1f }
        val gB = dim * relay
        val odCap = if (night) 0.4f else 1.4f
        glyphPaint.colorFilter = if (cooled) cool else null
        plusPaint.colorFilter = glyphPaint.colorFilter

        val tubes = visibleTubes()
        for (t in tubes) drawTube(c, t, now, gB, odCap, if (night || cooled) 0.6f else 1f, night)
        when (screen) {
            Screen.TODAY -> drawToday(c, now, dim, gB, odCap)
            Screen.LONG -> drawLong(c, now, sd)
            Screen.RECENT -> drawRecent(c, now, gB, sd)
            Screen.STREAK -> drawStreak(c, now, sd, night)
            Screen.WEEK -> drawWeek(c, now, gB, sd)
            Screen.BADGES -> drawBadges(c, now, sd)
            Screen.CATS -> drawCats(c, now, sd)
            Screen.OPEN -> drawOpen(c, now, sd)
        }
        if (screen != Screen.TODAY) drawPips(c, sd)
        fx?.draw(c, now)
        c.restore()

        removeCallbacks(kick)
        if (still) { if (reported) { reported = false; onAnimating?.invoke(false) }; return }
        // Frame scheduling: animate only while something moves; otherwise sleep until the next step.
        val animating = tubes.any { it.active(now) } || labelsActive(now) || (screen == Screen.TODAY && now - lampAt < lampHold + lampDecay) ||
            now - relayAt < 90 || signOd0 * exp(-(now - signAt) / 1800f) >= 0.02f || fx?.active(now) == true ||
            screenActive(now) || now - screenAt < 400 || now - cardAt < 220
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

    /** Today: signs, labels, the hero label (or a note), the as-of stamp and the live lamp. */
    private fun drawToday(c: Canvas, now: Long, dim: Float, gB: Float, odCap: Float) {
        val sOd = signOd0 * exp(-(now - signAt) / 1800f)
        drawSign(c, art.sign(Gas.NEON, true), 382f, 115f, adds[4].lvl * gB, min(sOd, odCap))
        drawSign(c, art.sign(Gas.ARGON, false), 382f, 249f, dels[4].lvl * gB, min(sOd, odCap))
        drawLabel(c, linesLabel, 404f, 28f, now, dim)
        drawLabel(c, weekLabel, 404f, 336f, now, dim)
        val hint = !learned("tapReplay") && hinting(now) && !heroLabel.active(now) && heroLabel.text == heroLabelText(shown)
        when {
            noteOn(now) -> drawText(c, note, true, 26f, 508f, dim, 548f)
            hint -> drawText(c, "PRs MERGED · TAP TO REPLAY", false, 26f, 508f, dim, 548f)
            else -> drawLabel(c, heroLabel, 26f, 508f, now, dim, right = 548f)
        }
        snap?.takeIf { shown != null }?.let { // as of the last fetch: only changes when a poll lands (0 extra frames)
            val at = Instant.ofEpochMilli(it.fetchedAtMs).atZone(ZoneId.systemDefault())
            stampPaint.alpha = (255 * max(dim, .6f)).toInt()
            c.drawText("%02d:%02d".format(at.hour, at.minute), 630f, 523f, stampPaint)
        }
        drawLamp(c, now)
    }

    /** ALL-TIME: the odometer, and the records row under it. */
    private fun drawLong(c: Canvas, now: Long, dim: Float) {
        header(c, "ALL-TIME PRs MERGED", now, dim)
        val since = history.keys.minOrNull()?.let { "${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()}" }
        drawText(c, if (since != null) "RECORDS · SINCE $since" else "RECORDS", false, 26f, 346f, dim, 656f)
        groupPaint.alpha = (255 * dim).toInt()
        c.drawText("BEST DAY", 61.5f, 512f, groupPaint)
        c.drawText("BEST WEEK", 214f, 512f, groupPaint)
        c.drawText("BIGGEST PR", 405.5f, 512f, groupPaint)
        c.drawText("FASTEST · MIN", 597f, 512f, groupPaint)
    }

    /** A screen's header: its name and the one number it explains — or, for up to 4 s, a note (a tag, a confirmation). */
    private fun header(c: Canvas, text: String, now: Long, dim: Float, right: Float = 656f) {
        if (noteOn(now)) drawText(c, note, true, 26f, 22f, dim, 656f) else drawText(c, text, false, 26f, 22f, dim, right)
    }

    /** Departures board: when · repo, then title, newest first; the highlighted row's +/− is in the banks. */
    private fun drawRecent(c: Canvas, now: Long, gB: Float, dim: Float) {
        drawSign(c, art.sign(Gas.NEON, true), 382f, 115f, radds[4].lvl * gB, 0f)
        drawSign(c, art.sign(Gas.ARGON, false), 382f, 249f, rdels[4].lvl * gB, 0f)
        val sel = recent.getOrNull(recentIdx)
        drawText(c, "THIS PR", true, 404f, 28f, dim, 656f)
        if (sel != null) drawText(c, "#${sel.number}", true, 404f, 336f, dim, 656f)
        if (recentFocus && sel != null) { drawFocus(c, sel, now, dim); return }
        header(c, if (!learned("row") && hinting(now)) "RECENT MERGES · TAP A ROW" else "RECENT MERGES", now, dim, 372f)
        if (recent.isEmpty()) { drawText(c, "NO MERGES IN 7 DAYS", false, 26f, 64f, dim, 372f); return }
        recent.forEachIndexed { i, p ->
            val y = 64f + 86f * i
            val on = i == recentIdx
            drawInfo(c, "${ago(p.mergedAtMs)} · ${p.repo.substringAfter('/').uppercase()}", on, 26f, y, dim, 356f)
            val tp = if (on) titleHot else titlePaint
            tp.alpha = (255 * dim).toInt()
            val fit = TextUtils.ellipsize(p.title.replace(CONVENTIONAL, ""), tp, 330f, TextUtils.TruncateAt.END)
            c.drawText(fit, 0, fit.length, 26f, y + 48f, tp)
        }
    }

    /** Detail card for the focused PR: repo · number, the full title, then when / how long / how big / where. */
    private fun drawFocus(c: Canvas, p: Pr, now: Long, dim: Float) {
        drawText(c, "SIZE · " + listOf("S", "M", "L", "XL")[tierOf(p)], false, 404f, 366f, dim, 656f)
        drawCard(c, "${p.repo.uppercase()} · #${p.number}", p.title.replace(CONVENTIONAL, ""), details(p), now, dim)
    }

    /** Header (glowing silk), a big wrapped title (≤ 3 lines), detail lines, "TAP TO CLOSE"; it strikes in. Every detail card. */
    private fun drawCard(c: Canvas, header: String, title: String, lines: List<String>, now: Long, dim: Float,
                         x: Float = 26f, w: Float = 346f, accent: String? = null) {
        val d = dim * strikeCurve(now - cardAt)
        drawText(c, header, true, x, 30f, d, x + w)
        val lay = focusLayout?.takeIf { it.text == title && it.width == w.toInt() }
            ?: StaticLayout.Builder.obtain(title, 0, title.length, focusPaint, w.toInt())
                .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(4f, 1f).build().also { focusLayout = it }
        focusPaint.alpha = (255 * d).toInt()
        c.save(); c.translate(x, 62f); lay.draw(c); c.restore()
        var y = 62f + lay.height + 34f
        if (accent != null) { drawInfo(c, accent, true, x, y - 17f, d, w); y += 34f }
        detailPaint.alpha = (255 * d).toInt()
        for (line in lines) {
            if (y > 488f) break
            val fit = TextUtils.ellipsize(line, detailPaint, w, TextUtils.TruncateAt.MIDDLE)
            c.drawText(fit, 0, fit.length, x, y, detailPaint)
            y += 34f
        }
        drawText(c, "TAP TO CLOSE", false, x, 510f, d, x + w)
    }

    private fun details(p: Pr): List<String> {
        val zone = ZoneId.systemDefault()
        val at = Instant.ofEpochMilli(p.mergedAtMs).atZone(zone)
        val days = ChronoUnit.DAYS.between(at.toLocalDate(), LocalDate.now(zone))
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

    /** The row's first word: RESCUED for an hour after its checks went green, AUTO when it'll merge itself. */
    private fun statusWord(p: OpenPr): String = when {
        rescued[p.id]?.let { System.currentTimeMillis() - it < 3_600_000L } == true && p.checks == "SUCCESS" -> "RESCUED"
        p.autoMerge && p.status == OpenPr.Status.READY -> "AUTO"
        else -> p.status.word
    }

    /** Status board: ready, waiting and needs-you counts (tap one to list just those), the top 5 rows. */
    private fun drawOpen(c: Canvas, now: Long, dim: Float) {
        drawJewelLabel(c, "READY", OpenPr.Light.GREEN, 24f, dim, openFilter == OpenPr.Light.GREEN)
        drawJewelLabel(c, "WAITING", OpenPr.Light.AMBER, 182f, dim, openFilter == OpenPr.Light.AMBER)
        drawJewelLabel(c, "NEEDS YOU", OpenPr.Light.RED, 340f, dim, openFilter == OpenPr.Light.RED)
        openRows.getOrNull(openFocus)?.let {
            drawCard(c, "${it.repo.substringAfter('/').uppercase()} · #${it.number}", it.title.replace(CONVENTIONAL, ""), openDetails(it), now, dim)
            return
        }
        val open = openOf(snap)
        val lit = open.count { it.status.light != OpenPr.Light.OFF }
        val title = openFilter?.let { f ->
            val name = when (f) { OpenPr.Light.GREEN -> "READY"; OpenPr.Light.AMBER -> "WAITING"; else -> "NEEDS YOU" }
            val pages = (openPool + 4) / 5
            "$name · $openPool" + if (pages > 1) " · ${openPage + 1}/$pages" else ""
        } ?: if (!learned("openRow") && hinting(now) && open.isNotEmpty()) "OPEN PRs · TAP A ROW" else "OPEN PRs · $lit"
        header(c, title, now, dim, 372f)
        if (openRows.isEmpty()) { drawText(c, "NO OPEN PRs", false, 26f, 64f, dim, 372f); return }
        openRows.forEachIndexed { i, p ->
            val y = 64f + 86f * i
            val hot = p.id == alertId || p.id == rescueId
            val dt = now - alertAt
            // An alert row's jewel strikes (SPEC §8.1 flicker), then flares out and settles; a rescue flips red → green.
            val light = if (p.id == rescueId && now < rescueAt) OpenPr.Light.RED else p.status.light
            val rt = now - rescueAt
            val a = when {
                p.id == alertId -> if (dt < 0) 0f else if (dt < 220) dim * strikeCurve(dt) else dim
                p.id == rescueId && rt in 0..219 -> dim * strikeCurve(rt)
                else -> dim
            }
            if (p.id == alertId && dt in 220..1500) drawJewel(c, 33f, y + 7f, light, dim * exp(-(dt - 220) / 400f), 6f + 14f * (1 - exp(-(dt - 220) / 300f)))
            if (p.id == rescueId && rt in 0..1500) drawJewel(c, 33f, y + 7f, light, dim * exp(-rt / 400f), 6f + 16f * (1 - exp(-rt / 300f)))
            drawJewel(c, 33f, y + 7f, light, a)
            val word = if (p.id == rescueId && now < rescueAt) OpenPr.Status.FAILING.word else statusWord(p)
            drawInfo(c, "$word · #${p.number} · ${p.repo.substringAfter('/').uppercase()}", hot, 48f, y, dim, 324f) // status first
            val tp = if (hot) titleHot else titlePaint
            tp.alpha = (255 * dim).toInt()
            val fit = TextUtils.ellipsize(p.title.replace(CONVENTIONAL, ""), tp, 330f, TextUtils.TruncateAt.END)
            c.drawText(fit, 0, fit.length, 26f, y + 48f, tp)
        }
        val drafts = open.count { it.status == OpenPr.Status.DRAFT }
        val older = ((snap?.openCount ?: 0) - (snap?.open?.size ?: 0)).coerceAtLeast(0)
        val foot = listOfNotNull(if (drafts > 0) "+$drafts ${if (drafts == 1) "DRAFT" else "DRAFTS"}" else null, if (older > 0) "+$older OLDER" else null)
        if (openFilter == null && foot.isNotEmpty()) drawText(c, foot.joinToString(" · "), false, 26f, 520f, dim, 372f)
    }

    private fun drawJewelLabel(c: Canvas, text: String, light: OpenPr.Light, top: Float, dim: Float, on: Boolean) {
        drawJewel(c, 410f, top + 7f, light, dim)
        drawText(c, text, on, 424f, top, dim, 656f)
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

    /** Streak in big tubes, the best streak, how many to go (or over) today, and the goal bargraph. */
    private fun drawStreak(c: Canvas, now: Long, dim: Float, night: Boolean) {
        header(c, "WORKDAY STREAK", now, dim, 372f)
        val t = shown ?: return
        if (streak > 0 && streak >= bestStreak) drawText(c, "BEST STREAK · NOW", true, 404f, 28f, dim, 656f)
        else drawText(c, history.keys.minOrNull()?.let { "BEST · SINCE ${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()}" }
            ?: "BEST STREAK", false, 404f, 28f, dim, 656f)
        drawText(c, if (t.prs < goal) "TO GO" else "OVER GOAL", false, 404f, 186f, dim, 656f)
        val met = t.prs >= goal
        drawText(c, if (met) "GOAL MET" + (if (t.prs > goal) " · +${t.prs - goal}" else "") else "TODAY'S GOAL · ${t.prs} / $goal",
            met, 26f, 346f, dim, 656f)
        drawBargraph(c, now, dim, night)
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

    /**
     * The week: big PR count, ± banks, a Mon–Sun strip (orange letter = today; a dot = merged; a ring = goal met;
     * unrevealed days show last week's count in ghost wire), the best PR, a summary, and this week against last.
     */
    private fun drawWeek(c: Canvas, now: Long, gB: Float, dim: Float) {
        val w = weekStats ?: return
        val end = w.start.plusDays(6)
        fun mon(d: LocalDate) = d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()
        val range = if (w.start.month == end.month) "${w.start.dayOfMonth}–${end.dayOfMonth} ${mon(end)}"
            else "${w.start.dayOfMonth} ${mon(w.start)}–${end.dayOfMonth} ${mon(end)}"
        header(c, "THIS WEEK · $range", now, dim, 372f)
        drawText(c, "LINES", false, 404f, 28f, dim, 656f)
        if (wprs.size == 2) { // three big tubes reach under the signs
            drawSign(c, art.sign(Gas.NEON, true), 382f, 115f, wadds[4].lvl * gB, 0f)
            drawSign(c, art.sign(Gas.ARGON, false), 382f, 249f, wdels[4].lvl * gB, 0f)
        }
        val todayIdx = LocalDate.now().dayOfWeek.value - 1
        for (i in 0..6) {
            val cx = 24f + i * 92.83f + 37.5f
            val n = w.perDay[i]
            val lp = if (i == todayIdx) dayToday else dayPaint
            lp.alpha = (255 * dim).toInt()
            c.drawText(w.start.plusDays(i.toLong()).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), cx, 365f, lp)
            val revealed = i <= recapDay && i <= todayIdx
            if (!revealed) {
                ghostCount.alpha = (255 * dim).toInt()
                c.drawText(lastWeek[i].toString(), cx, 400f, ghostCount)
                continue
            }
            val np = if (n > 0) weekCountOn else weekCountOff
            np.alpha = (255 * dim).toInt()
            c.drawText(n.toString(), cx, 400f, np)
            if (n > 0) drawJewel(c, cx, 425f, OpenPr.Light.AMBER, dim, 4f)
            if (n >= goal) { ringPaint.alpha = (255 * dim).toInt(); c.drawCircle(cx, 425f, 9f, ringPaint) }
        }
        drawLabel(c, bestLabel, 26f, 446f, now, dim)
        val best = w.best
        if (best != null && bestLabel.text.isNotEmpty()) { // the title fades in with the BEST line above it
            titlePaint.alpha = (255 * dim * ((now - bestLabel.at).toFloat() / bestLabel.fade).coerceIn(0f, 1f)).toInt()
            val fit = TextUtils.ellipsize(best.title.replace(CONVENTIONAL, ""), titlePaint, 630f, TextUtils.TruncateAt.END)
            c.drawText(fit, 0, fit.length, 26f, 490f, titlePaint)
        }
        drawLabel(c, sumLabel, 26f, 508f, now, dim, right = 656f)
        drawLabel(c, vsLabel, 26f, 536f, now, dim, right = 656f)
    }

    private val weekCountOn = labelPaint(26f).apply { color = Gas.NEON.core; textAlign = Paint.Align.CENTER; setShadowLayer(8f, 0f, 0f, 0xAAFF5200.toInt()) }
    private val weekCountOff = labelPaint(26f).apply { color = LABEL; textAlign = Paint.Align.CENTER }
    /** Last week's count in an unrevealed column: unlit wire, not a value yet. */
    private val ghostCount = labelPaint(22f).apply {
        color = Gas.NEON.ghost; textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE; strokeWidth = 1.2f
    }

    /** 4×2 shelf of badges: lit cathode icons for earned ones, ghost wire for the rest (with how close); NEW ones unlock in place. */
    private fun drawBadges(c: Canvas, now: Long, dim: Float) {
        val ba = badgeArt
        badgeFocus?.let { b ->
            val n = book.count(b)
            ba.draw(c, b, 150f, 290f, 210f, n > 0, dim)
            fun day(d: LocalDate?) = d?.let { "${it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()} ${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()}" }
            drawCard(c, b.title, b.rule, listOfNotNull(if (n > 0) "EARNED ×$n" else "NOT EARNED YET",
                day(book.first(b))?.let { "FIRST · $it" }, day(book.last(b))?.let { "LAST · $it" }), now, dim, x = 290f, w = 366f,
                accent = badgeProgress(b).first)
            return
        }
        header(c, if (!learned("badge") && hinting(now)) "BADGES · TAP A BADGE" else "BADGES · ${book.total} OF ${Badge.entries.size}", now, dim)
        Badge.entries.forEachIndexed { i, b ->
            val x0 = 24f + (i % 4) * 158f; val y0 = 52f + (i / 4) * 240f
            val cx = x0 + 79f; val cy = y0 + 90f
            val n = book.count(b)
            val t = (now - unlockAt).toFloat()
            val new = b in unlocking
            when {
                new && t < 0 -> ba.draw(c, b, cx, cy, 112f, false, dim)
                new && t < BadgeArt.UNLOCK_MS -> ba.drawUnlock(c, b, cx, cy, 112f, t, dim)
                else -> ba.draw(c, b, cx, cy, 112f, n > 0, dim)
            }
            val np = if (new) nameNew else if (n > 0) nameOn else nameOff
            np.alpha = (255 * dim).toInt()
            c.drawText(b.title, cx, y0 + 176f, np)
            when {
                b in visitNew -> { nameNew.alpha = (255 * dim).toInt(); c.drawText("NEW", cx, y0 + 204f, nameNew) }
                n > 0 -> { badgeCount.alpha = (255 * dim).toInt(); c.drawText("×$n", cx, y0 + 204f, badgeCount) }
                else -> badgeProgress(b).second?.let { badgeNear.alpha = (255 * dim).toInt(); c.drawText(it, cx, y0 + 202f, badgeNear) }
            }
        }
    }

    private val nameOff = labelPaint(18f).apply { color = LABEL; letterSpacing = 0.08f; textAlign = Paint.Align.CENTER }
    private val nameOn = labelPaint(18f).apply { color = 0xFFA08872.toInt(); letterSpacing = 0.08f; textAlign = Paint.Align.CENTER }
    private val nameNew = labelPaint(18f).apply {
        color = EVENT; letterSpacing = 0.08f; textAlign = Paint.Align.CENTER; setShadowLayer(8f, 0f, 0f, 0xAAFF5200.toInt())
    }
    private val badgeCount = labelPaint(20f).apply { color = Gas.NEON.mid; textAlign = Paint.Align.CENTER }
    private val badgeNear = labelPaint(16f).apply { color = LABEL; textAlign = Paint.Align.CENTER }

    /** This week as cats (MergeFx draws and flies them), READY kittens on a shelf above, or a poked cat's card. */
    private fun drawCats(c: Canvas, now: Long, dim: Float) {
        catCard?.let { p ->
            drawCard(c, "${p.repo.uppercase()} · #${p.number}", p.title.replace(CONVENTIONAL, ""), details(p), now, dim, w = 630f)
            return
        }
        kittenCard?.let { p ->
            drawCard(c, "${p.repo.substringAfter('/').uppercase()} · #${p.number}", p.title.replace(CONVENTIONAL, ""), openDetails(p), now, dim, w = 630f)
            return
        }
        val n = weekPrs().size
        val more = if (n > COLONY_MAX) " · +${n - COLONY_MAX} NOT SHOWN" else ""
        val text = when {
            n == 0 -> "CAT COLONY · NO MERGES THIS WEEK"
            !learned("poke") && hinting(now) -> "CAT COLONY · TAP A CAT · RUB IT"
            !learned("wakeAll") && hinting(now) -> "CAT COLONY · TAP HERE · WAKE ALL"
            else -> "CAT COLONY · $n THIS WEEK$more"
        }
        header(c, text, now, dim)
        if (kittens.isNotEmpty()) { // the shelf: READY open PRs as kittens, a green lamp under each
            shelfPaint.alpha = (90 * dim).toInt()
            c.drawLine(kittens.last().x - 34f, 104f, 652f, 104f, shelfPaint)
            val f = fx()
            kittens.forEach { k ->
                f.drawSit(c, k.x, KITTEN_Y, .45f, if (pumpkinWeek()) MergeFx.PUMPKIN else 0, true, dim)
                drawJewel(c, k.x, 111f, OpenPr.Light.GREEN, dim, 4f)
            }
        }
        fx?.drawColony(c, now, dim)
    }

    private val shelfPaint = Paint().apply { strokeWidth = 2f; color = 0xFF8A7360.toInt() }

    /** Position pips (UX-PASS #14): one per screen, the current one lit; never on Today, never a tap target. */
    private fun drawPips(c: Canvas, dim: Float) {
        for (sc in Screen.entries) {
            val p = if (sc == screen) pipOn else pipOff
            p.alpha = (255 * dim).toInt()
            c.drawCircle(30f + 14f * sc.ordinal, 568f, 3f, p)
        }
    }

    private val pipOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Gas.NEON.core; setShadowLayer(6f, 0f, 0f, Gas.NEON.glow) }
    private val pipOff = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A2C22.toInt() }

    /** Per-screen animations that aren't tubes or labels. */
    private fun screenActive(now: Long) = when (screen) {
        Screen.STREAK -> now < barAt + BAR_MS || now < surgeAt + 2500
        Screen.BADGES -> unlocking.isNotEmpty() && now < unlockAt + BadgeArt.UNLOCK_MS
        Screen.OPEN -> (alertId != null && now < alertAt + 1500) || (rescueId != null && now < rescueAt + 1500)
        else -> false
    }

    private val focusPaint = labelPaint(28f).apply { color = Gas.NEON.core; setShadowLayer(8f, 0f, 0f, 0xAAFF5200.toInt()) }
    private val detailPaint = labelPaint(22f).apply { color = 0xFFA08872.toInt(); letterSpacing = 0.06f }

    private fun ago(ms: Long): String {
        val m = (System.currentTimeMillis() - ms) / 60_000
        return when { m < 1 -> "NOW"; m < 60 -> "${m}M AGO"; m < 1440 -> "${m / 60}H AGO"; else -> "${m / 1440}D AGO" }
    }

    private val titlePaint = labelPaint(24f).apply { color = 0xFF8A7360.toInt() }
    private val titleHot = labelPaint(24f).apply { color = Gas.NEON.core; setShadowLayer(8f, 0f, 0f, 0xAAFF5200.toInt()) }

    // --------------------------------------------------------------- labels, notes + lamp
    // Two text tiers (UX-PASS §3): ornament (silk, 17 px, screen headers only) and information (≥ 19 px, ≥ 4.5:1).

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
    private val weekLabel = Label("LAST 7 DAYS")

    private fun labelsActive(t: Long) = heroLabel.active(t) || linesLabel.active(t) || bestLabel.active(t) || sumLabel.active(t) || vsLabel.active(t)

    private fun setLabel(l: Label, text: String, event: Boolean, now: Long, fade: Long = 150) {
        if (l.text == text && l.event == event) return
        l.prev = l.text; l.prevEvent = l.event
        l.text = text; l.event = event
        l.at = now; l.fade = fade
        invalidate()
    }

    /** A transient line (a cat's tag, "SETUP · ON YOUR PHONE", a peek's hint) in place of the header, or Today's hero label. */
    private var note = ""
    private var noteUntil = 0L
    private fun noteOn(now: Long) = now < noteUntil

    private fun note(text: String, ms: Long, now: Long) {
        note = text; noteUntil = now + ms
        at(noteUntil) { invalidate() }
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
    private val metaPaint = labelPaint(19f).apply { color = 0xFF8A7360.toInt(); letterSpacing = 0.08f }
    private val metaHot = labelPaint(19f).apply { color = EVENT; letterSpacing = 0.08f; setShadowLayer(8f, 0f, 0f, 0xAAFF5200.toInt()) }
    private val stampPaint = labelPaint(19f).apply { color = 0xFF8A7360.toInt(); letterSpacing = 0.08f; textAlign = Paint.Align.RIGHT }
    private val groupPaint = labelPaint(15f).apply { color = 0xFF8A7360.toInt(); letterSpacing = 0.08f; textAlign = Paint.Align.CENTER }
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

    /** Information-tier line ([w] px wide), brighter than the silk headers; [hot] in the event colour. */
    private fun drawInfo(c: Canvas, text: String, hot: Boolean, x: Float, top: Float, alpha: Float, w: Float) {
        if (alpha <= 0.004f || text.isEmpty()) return
        val p = if (hot) metaHot else metaPaint
        p.alpha = (255 * max(alpha, if (alpha > 0.3f) 0.6f else alpha)).toInt() // dims less than ornament
        val fit = TextUtils.ellipsize(text, p, w, TextUtils.TruncateAt.END)
        c.drawText(fit, 0, fit.length, x, top + 15, p)
    }

    private val lampLive = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD2A0.toInt(); setShadowLayer(12f, 0f, 0f, Gas.NEON.glow) }
    private val lampFlash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF6EC.toInt(); setShadowLayer(22f, 0f, 0f, Gas.NEON.glow) }
    private val lampOff = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A1D15.toInt() }

    private fun drawLamp(c: Canvas, now: Long) {
        val dt = now - lampAt
        val f = when { dt < lampHold -> 1f; dt < lampHold + lampDecay -> 1f - (dt - lampHold).toFloat() / lampDecay; else -> 0f }
        if (signedOut || expired || cooled || shown == null) { // off — but a refused tap still blinks it
            c.drawCircle(645f, 515f, 5f, lampOff)
            if (f > 0) { lampFlash.alpha = (255 * f).toInt(); c.drawCircle(645f, 515f, 5f, lampFlash) }
            return
        }
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
        const val KITTEN_Y = 88f
        /** Fallback for HyperOS subscreen_display_time (read in readSettings): the rear dims this long after a wake or touch. */
        const val AWAKE_MS = 10_000L
        const val LONG_MS = 1500L // still this long: Setup
        const val HOLD_MS = 250L  // rest this long before moving: a hold-drag (pet, laser), not a swipe
        const val PEEK_MS = 3000L
        const val BADGE_SHOW_MS = 3000L
        /** After the strike, until the numbers have landed (S M L XL). */
        val NUMBERS_MS = longArrayOf(600, 2900, 4000, 4000)
        /** A flyby's longest natural lead (S has none). */
        val FLY_MS = longArrayOf(0, 3300, 3900, 5200)
        /** How long a moment holds its label when nothing follows. */
        val MOMENT_MS = longArrayOf(3000, 6000, 6000, 6000)
        const val HOLD_SHOW_MS = 800L
        const val LABEL = 0xFF5E4C3E.toInt()
        const val EVENT = 0xFFFFB27A.toInt()
        val CONVENTIONAL = Regex("""^\w+(\([^)]*\))?!?:\s*""")
    }
}
