package co.tryterra.merged

import android.app.Activity
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Fullscreen host for [MeterView]. Launches on the front display, then asks
 * Shizuku to move the task to the rear display (see
 * docs/rear-screen-routing.md). Polls GitHub only while visible.
 *
 * Always-on comes from the stock rear doze, not from us: ~16s after the rear
 * wakes, HyperOS starts subscreencenter's DozeService on display group 1 and the
 * panel drops to DOZE_SUSPEND (dim, self-refresh) — we stay RESUMED and our last
 * frame stays up at doze brightness, exactly like the stock face. Frames drawn
 * in DOZE_SUSPEND are dropped, so changes are pushed with a 500ms DRAW wake lock
 * (what DozeService itself does). The rear goes black only on DozeService's OFF
 * rules (pocket, dark room, 90s unlocked) — same as the stock face.
 * No FLAG_KEEP_SCREEN_ON: the rear is its own display group and ignores it.
 * See merged/design/REAR-AOD.md.
 */
class MeterActivity : Activity() {

    private lateinit var view: MeterView
    private lateinit var vault: Vault
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var polling = false
    private var fetching = false
    private var refetch = false
    private var moving = false
    private var lastFetchMs = 0L
    private var openAt = 0L
    private var openToken: String? = null
    private var last: Snapshot? = null
    private var pausedAt = 0L
    private lateinit var drawLock: PowerManager.WakeLock

    private val pollTick = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, pollMs(last))
        }
    }
    private val moveToRear = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        val moved = RearRouter.moveToRear(this)
        moving = false
        // Move failed: stay on the front screen and poll here.
        if (!moved && polling) { main.removeCallbacks(pollTick); main.post(pollTick) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = Vault(this)
        if (vault.token == null && !vault.expired && demoMode() == null) {
            startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        // Draw under the rear camera island; MeterView keeps content out of it via insets.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        view = MeterView(this)
        // DRAW_WAKE_LOCK (0x80, @hide constant, WAKE_LOCK permission only): lifts a
        // dozing display from DOZE_SUSPEND to DOZE so one frame can land.
        drawLock = getSystemService(PowerManager::class.java)
            .newWakeLock(DRAW_WAKE_LOCK, "merged:push").apply { setReferenceCounted(false) }
        view.onNeedFrame = { pushFrame() }
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        // Battery: idle frames are static, so ask the panel for its slowest mode
        // and only speed up while a celebration is actually animating.
        setRefreshRate(fast = false)
        view.onAnimating = { setRefreshRate(it) }
        view.debugCutoutPx = debugPrefs()?.getInt("cutout", 0) ?: 0
        Snapshot.load(this)?.let { last = it; lastFetchMs = it.fetchedAtMs; view.show(it, emptyList()) }
        if (vault.token == null && vault.expired) view.tokenExpired()
        view.onSetup = {
            // Setup belongs on the front screen, in its own task (taskAffinity="").
            startActivity(
                Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
            )
        }
        setContentView(view)
        // Staying on the front screen (no Shizuku, e.g. the emulator): hide the bars, or the navigation bar covers
        // the bottom of the stage. The rear display has none, and the front instance that moves there skips this.
        if (display?.displayId == Display.DEFAULT_DISPLAY && RearRouter.status() != RearRouter.Status.READY) window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Just landed on the rear (the move recreates us): claim the rear-screen slot once.
        // Twice: Xiaomi's rear launcher restarts a few seconds after the move and takes the slot back.
        if (display?.displayId != Display.DEFAULT_DISPLAY) for (ms in longArrayOf(500, 4_000)) main.postDelayed({ pokeRear() }, ms)
        // Already on the rear display after the move recreates us — don't loop.
        // While the move is pending, this front-screen instance doesn't fetch:
        // the rear instance does, so catch-up merges celebrate where you'll see them.
        if (display?.displayId == 0 && RearRouter.status() == RearRouter.Status.READY) {
            moving = true
            main.postDelayed(moveToRear, 250)
        }
    }

    override fun onResume() {
        super.onResume()
        // After a reboot the meter can start on the front (Shizuku wasn't up yet); once it is, a resume moves it over.
        if (display?.displayId == Display.DEFAULT_DISPLAY && !moving && RearRouter.status() == RearRouter.Status.READY) {
            moving = true
            main.postDelayed(moveToRear, 250)
        }
        if (pausedAt > 0 && System.currentTimeMillis() - pausedAt > 30 * 60_000L) view.wake()
        // Back from OFF into doze: DozeService only allows ~300ms to draw, which a
        // thawing process can miss — push our own frame so the rear isn't left black.
        if (view.isStill()) pushFrame()
        poke = { main.post { view.debugCutoutPx = debugPrefs()?.getInt("cutout", 0) ?: 0; refresh() } }
        if (BuildConfig.DEBUG) {
            inject = { s -> main.post { publish(s) } }
            debugCmd = { cmd -> main.post { view.debug(cmd) } }
        }
        polling = true
        val ms = pollMs(last)
        val due = (lastFetchMs + ms - System.currentTimeMillis()).coerceIn(0, ms)
        main.postDelayed(pollTick, if (moving) 2_000 else due)
    }

    override fun onPause() {
        pausedAt = System.currentTimeMillis()
        poke = null
        inject = null
        debugCmd = null
        polling = false
        main.removeCallbacks(pollTick)
        super.onPause()
    }

    /**
     * Panel state changed (doze ↔ on): redraw, since frames drawn while it was suspended were dropped.
     * Leaving ON (dimming): home to Today, settled — Greeze may freeze us before DOZE_SUSPEND arrives (UX-PASS F17).
     * Coming on: the 10-second window starts — roll the day, catch up if the last fetch is over a minute old
     * (locked or not), and re-register as the rear's foreground app so HyperOS doesn't freeze us mid-touch.
     */
    private var pokedAt = 0L
    private var displayEventAt = 0L
    private var shownState = Display.STATE_UNKNOWN
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(id: Int) {
            val d = display ?: return
            if (!::view.isInitialized || id != d.displayId) return
            val was = shownState
            shownState = d.state
            if (d.state == Display.STATE_ON && was != Display.STATE_ON) {
                view.rearOn()
                if (polling && System.currentTimeMillis() - lastFetchMs > 60_000) { main.removeCallbacks(pollTick); main.post(pollTick) }
            } else if (d.state != Display.STATE_ON) view.rearOff()
            view.invalidate()
            // Frozen while the rear dozes, we never see it go dim — only that it's on again after a quiet gap.
            val now = System.currentTimeMillis()
            if (d.displayId != Display.DEFAULT_DISPLAY && d.state == Display.STATE_ON && now - displayEventAt > 5_000) pokeRear()
            displayEventAt = now
        }
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
    }

    /**
     * Resume RearPokeActivity for a frame on the rear (see its doc). HyperOS only allows it while
     * unlocked; once registered, the slot survives locking and rear doze. At most every 2 s.
     */
    private fun pokeRear() {
        val now = System.currentTimeMillis()
        if (now - pokedAt < 2_000 || isFinishing || getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        pokedAt = now
        // From the app context (no source activity, no launch display) HyperOS skips its rear-display
        // allowlist; the default affinity drops it into our task, which is already on the rear.
        runCatching {
            applicationContext.startActivity(Intent(applicationContext, RearPokeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
            .onFailure { Log.w(TAG, "rear poke", it) }
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        if (::drawLock.isInitialized && drawLock.isHeld) drawLock.release()
        main.removeCallbacks(moveToRear)
        io.shutdownNow()
        super.onDestroy()
    }

    private fun refresh() {
        if (demoMode() != null) { // debug: canned data, merges injected by DebugReceiver
            Snapshot.load(this)?.let { publish(it) }
            return
        }
        val token = vault.token ?: return if (vault.expired && last != null) view.tokenExpired() else view.signedOut()
        if (fetching) return
        fetching = true
        val prev = last
        // The open-PR search is a second ~4 s request: every 5 min is plenty for the status lights.
        val withOpen = token != openToken || System.currentTimeMillis() - openAt >= OPEN_MS // a new account fetches its own
        io.execute {
            if (!polling) { main.post { fetching = false }; return@execute } // went invisible while queued
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val state = getSharedPreferences("state", MODE_PRIVATE)
            // 1. Merged PRs: what the face shows, published before the slower open search and the backfill (UX-PASS F15).
            val snap = runCatching {
                val f = GitHub.fetchMerged(
                    token,
                    since = today.minusDays(WINDOW_DAYS - 1).atStartOfDay(zone).toOffsetDateTime(),
                    yearStart = today.withDayOfYear(1).atStartOfDay(zone).toOffsetDateTime(),
                )
                val prs = withoutGenerated(token, f.prs)
                check(demoMode() == null) { "demo mode" } // switched to demo mid-fetch: don't wipe its seeded state
                claim(state, f.login) // before recording, or publish's account switch would wipe the backfill
                val same = prev?.login == f.login
                Snapshot(f.login, prs, f.windowCount, f.ytd, f.lifetime, System.currentTimeMillis(),
                    prev?.open.orEmpty().takeIf { same }.orEmpty(), if (same) prev?.openCount ?: 0 else 0)
            }.getOrElse { e -> main.post { fetching = false; failed(token, e) }; return@execute }
            deliver(token, snap, open = false)
            // 2. Open PRs every 5 min; in between, one cheap call re-checks just the PRs we saw failing (UX-PASS #19).
            val opened = if (withOpen) runCatching { GitHub.fetchOpen(token) }.onFailure { Log.w(TAG, "open PRs", it) }.getOrNull() else null
            val watch = snap.open.filter { it.failing }.map { it.id }
            val checks = if (opened == null && watch.isNotEmpty()) runCatching { GitHub.checks(token, watch) }
                .onFailure { Log.w(TAG, "checks", it) }.getOrNull() else null
            val next = when {
                opened != null -> snap.copy(open = opened.first, openCount = opened.second)
                checks != null && snap.open.any { p -> checks[p.id]?.let { it != p.checks } == true } ->
                    snap.copy(open = snap.open.map { p -> checks[p.id]?.let { p.copy(checks = it) } ?: p })
                else -> snap
            }
            if (next !== snap) deliver(token, next, open = opened != null)
            // 3. The streak's history, a few weeks a poll: republish only when it learned something.
            val learned = runCatching { demoMode() == null && backfill(token, state, snap.prs, today, zone, prev?.fetchedAtMs) }.getOrDefault(false)
            if (learned) deliver(token, next, open = false)
            main.post { fetching = false; if (refetch) { refetch = false; if (polling) refresh() } }
        }
    }

    /** Main thread: save and show a poll's result — unless the token changed mid-request (then fetch theirs next). */
    private fun deliver(token: String, snap: Snapshot, open: Boolean) = main.post {
        if (isDestroyed || demoMode() != null) return@post
        if (vault.token != token) { refetch = true; return@post }
        lastFetchMs = snap.fetchedAtMs
        if (open) { openAt = snap.fetchedAtMs; openToken = token }
        Snapshot.save(this, snap)
        publish(snap)
    }

    private fun failed(token: String, e: Throwable) {
        if (isDestroyed || demoMode() != null) return
        if (vault.token != token) { if (polling) refresh(); return } // a new sign-in mid-request: fetch theirs
        Log.w(TAG, "fetch failed", e)
        // A rejected token keeps the numbers (cooled, TOKEN EXPIRED); a tap on the rear opens Setup.
        if (e is GitHub.AuthError) { vault.token = null; vault.expired = true; view.tokenExpired() } else view.markStale()
    }

    /**
     * Take generated-file lines out of each PR's counts: carried over from the last snapshot (merged PRs never
     * change), else fetched once. A failed fetch leaves those PRs unchecked, so the next poll retries them.
     */
    private fun withoutGenerated(token: String, prs: List<Pr>): List<Pr> {
        val old = Snapshot.load(this)?.prs.orEmpty().filter { it.genChecked }.associateBy { it.id }
        val gen = runCatching { GitHub.generated(token, prs.map { it.id }.filter { it !in old }) }
            .onFailure { Log.w(TAG, "generated files", it) }.getOrDefault(emptyMap())
        fun Pr.minus(a: Int, d: Int, n: Int) = copy(additions = (additions - a).coerceAtLeast(0),
            deletions = (deletions - d).coerceAtLeast(0), genAdds = a, genDels = d, genFiles = n, genChecked = true)
        return prs.map { p ->
            old[p.id]?.let { p.minus(it.genAdds, it.genDels, it.genFiles) }
                ?: gen[p.id]?.let { p.minus(it.adds, it.dels, it.files) } ?: p
        }
    }

    /**
     * Record the window's per-day counts, then walk the streak back week by week until a weekday without a
     * merge ends it (52 weeks at most, 8 per poll). Once the history knows that day, later polls make no extra calls.
     * A failure just stops here; the next poll picks it up.
     */
    private fun backfill(token: String, state: SharedPreferences, prs: List<Pr>, today: LocalDate, zone: ZoneId, lastFetchMs: Long?): Boolean {
        val window = (0 until WINDOW_DAYS).associate { today.minusDays(it) to 0 } +
            prs.groupingBy { Instant.ofEpochMilli(it.mergedAtMs).atZone(zone).toLocalDate() }.eachCount()
        var history = Progress.record(state, window, today)
        // The last poll's day was recorded mid-day; once it's out of the window it never gets corrected, so re-fetch it.
        lastFetchMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }?.takeIf { it < today.minusDays(WINDOW_DAYS - 1) }
            ?.let { history = Progress.forget(state, it) }
        var learned = false
        runCatching {
            repeat(8) { // ≤ 8 weeks a poll (~1 s each)
                val e = Progress.backfillBefore(history, today) ?: return@runCatching
                history = Progress.record(state, GitHub.mergedDays(token, e.minusDays(7), e.minusDays(1), zone), today)
                learned = true
            }
        }.onFailure { Log.w(TAG, "backfill", it) }
        return learned
    }

    /** Different account → forget the old one's seen set, daily bests, history and badges. */
    private fun claim(prefs: SharedPreferences, login: String) {
        if (prefs.getString("login", null) != login) prefs.edit().clear().putString("login", login).commit()
    }

    /**
     * Polls only while visible (onResume..onPause), so a sleeping rear screen costs no radio.
     * 45 s while a merge looks imminent (auto-merge on, or a PR that went ready in the last day) or a PR that failed
     * in the last day is being watched for a fix (UX-PASS #19); 15 min in quiet hours; else 2 min.
     */
    private fun pollMs(s: Snapshot?): Long = when {
        Progress.quiet(Progress.quietStart(this), Progress.quietEnd(this), LocalTime.now().hour) -> 15 * 60_000L
        s?.open.orEmpty().any { it.autoMerge || (it.failing || it.status == OpenPr.Status.READY) && System.currentTimeMillis() - it.updatedAtMs < 86_400_000L } -> 45_000L
        else -> 120_000L
    }

    /**
     * Diff against the PR ids we've already shown. First run (nothing seen yet)
     * celebrates nothing; afterwards only PRs merged in the last [FRESH_MS]
     * count as fresh, so reopening after a weekend doesn't fire a backlog.
     */
    private fun publish(snap: Snapshot) {
        val prefs = getSharedPreferences("state", MODE_PRIVATE)
        claim(prefs, snap.login)
        last = snap
        val seen = prefs.getStringSet("seen", null)
        val now = System.currentTimeMillis()
        val fresh = if (seen == null) emptyList() else snap.prs
            .filter { it.id !in seen && now - it.mergedAtMs < FRESH_MS }
            .sortedBy { it.mergedAtMs }
        prefs.edit().putStringSet("seen", snap.prs.mapTo(HashSet()) { it.id }).apply()
        view.show(snap, fresh)
    }

    /** Get one frame onto a dozing (DOZE_SUSPEND) rear panel. */
    private fun pushFrame() {
        runCatching { drawLock.acquire(500) }.onFailure { Log.w(TAG, "draw lock", it) }
        main.postDelayed({ if (!isDestroyed) view.invalidate() }, 50)
    }

    /** Slowest refresh mode at the current resolution when idle; ~60Hz while animating. */
    private fun setRefreshRate(fast: Boolean) {
        val d = display ?: return
        val same = d.supportedModes.filter {
            it.physicalWidth == d.mode.physicalWidth && it.physicalHeight == d.mode.physicalHeight
        }
        val pick = if (fast) same.minByOrNull { abs(it.refreshRate - 60f) } else same.minByOrNull { it.refreshRate }
        pick ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = pick.modeId }
    }

    private fun debugPrefs() =
        if (BuildConfig.DEBUG) getSharedPreferences("debug", MODE_PRIVATE) else null

    private fun demoMode(): String? = debugPrefs()?.getString("demo", null)

    companion object {
        private const val TAG = "Merged"
        private const val FRESH_MS = 6 * 3_600_000L
        private const val OPEN_MS = 5 * 60_000L
        const val WINDOW_DAYS = 7L
        private const val DRAW_WAKE_LOCK = 0x80 // PowerManager.DRAW_WAKE_LOCK (@hide)

        /** Set while a meter is resumed: re-fetch now (Setup after sign-in, DebugReceiver). */
        @Volatile var poke: (() -> Unit)? = null

        /** Debug builds, while resumed: show this snapshot as if a poll had returned it (fake merges). */
        @Volatile var inject: ((Snapshot) -> Unit)? = null

        /** Debug builds, while resumed: hand a command to the view (DebugReceiver `--es show …`). */
        @Volatile var debugCmd: ((String) -> Unit)? = null
    }
}
