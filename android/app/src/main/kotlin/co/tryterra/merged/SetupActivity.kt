package co.tryterra.merged

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/**
 * Front-screen setup: GitHub sign-in (Device Flow, or paste a token), Shizuku grant for rear-screen
 * routing, daily goal, quiet hours, and starting the meter. Each problem says what's wrong and offers
 * the one button that fixes it (UX-PASS #15). After Start it stays up, pointing you at the back.
 */
class SetupActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var vault: Vault
    private lateinit var account: TextView
    private lateinit var code: TextView
    private lateinit var shizuku: TextView
    private lateinit var shizukuButton: Button
    private lateinit var start: Button
    private lateinit var started: TextView
    @Volatile private var signInRun = 0
    @Volatile private var poller: Thread? = null
    @Volatile private var checked: String? = null // the token whose login we've confirmed with GitHub

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Token may be on screen (paste field) — keep it out of screenshots/Recents.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        vault = Vault(this)
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun header(t: String) = root.addView(TextView(this).apply {
            text = t
            textSize = 13f
            letterSpacing = 0.12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad, 0, dp(6))
        })
        fun note(t: String) = root.addView(TextView(this).apply { text = t; alpha = 0.7f })
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { onClick() }
            root.addView(this)
        }
        fun hours(value: Int, onChange: (Int) -> Unit) = NumberPicker(this).apply {
            minValue = 0; maxValue = 23; this.value = value
            setFormatter { "%02d:00".format(it) }
            setOnValueChangedListener { _, _, n -> onChange(n) }
        }

        root.addView(TextView(this).apply { text = "Merged"; textSize = 32f; setTypeface(typeface, Typeface.BOLD) })
        note("Your merged PRs, on the back of your phone.")

        header("GITHUB")
        account = TextView(this).also { root.addView(it) }
        code = TextView(this).apply {
            textSize = 34f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            visibility = TextView.GONE
            root.addView(this)
        }
        if (BuildConfig.GITHUB_CLIENT_ID.isNotEmpty()) button("Sign in with GitHub") { signIn() }
        val tokenField = EditText(this).apply {
            hint = "…or paste a token (classic PAT, repo scope)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            root.addView(this)
        }
        button("Save token") {
            val t = tokenField.text.toString().trim()
            if (t.isEmpty()) return@button
            vault.token = t
            Snapshot.clear(this) // unverified until the meter's next fetch
            tokenField.text.clear()
            refresh()
        }
        button("Sign out") { vault.token = null; vault.expired = false; Snapshot.clear(this); refresh() }

        header("REAR SCREEN")
        shizuku = TextView(this).also { root.addView(it) }
        shizukuButton = button("Grant Shizuku permission") {
            when (RearRouter.status()) {
                RearRouter.Status.SHIZUKU_OFF -> packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
                    ?.let { startActivity(it) } ?: startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                RearRouter.Status.NO_PERMISSION -> Shizuku.requestPermission(REQ_SHIZUKU)
                RearRouter.Status.READY -> Unit
            }
            refresh()
        }

        header("DAILY GOAL")
        note("Merged PRs a day that count as goal met.")
        root.addView(NumberPicker(this).apply {
            minValue = 1
            maxValue = 30
            value = Progress.goal(this@SetupActivity)
            wrapSelectorWheel = false
            // The meter re-reads the goal on its next publish; poke so that's now.
            setOnValueChangedListener { _, _, n -> Progress.setGoal(this@SetupActivity, n); MeterActivity.poke?.invoke() }
        })

        header("QUIET HOURS")
        note("No cats, no sounds, slower polling. From, until (the same hour: never quiet).")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL; root.addView(this) }
        row.addView(hours(Progress.quietStart(this)) { Progress.setQuiet(this, it, Progress.quietEnd(this)); MeterActivity.poke?.invoke() })
        row.addView(TextView(this).apply { text = "  →  "; textSize = 20f })
        row.addView(hours(Progress.quietEnd(this)) { Progress.setQuiet(this, Progress.quietStart(this), it); MeterActivity.poke?.invoke() })

        header("ON THE BACK")
        note(LEGEND)

        header("GO")
        start = button("Start Merged") {
            // A meter already running on the rear screen just re-fetches; don't stack a second one.
            MeterActivity.poke?.invoke()
                ?: startActivity(Intent(this, MeterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            started.visibility = TextView.VISIBLE // stay up: the meter is on the back now, not here
        }
        started = TextView(this).apply {
            text = "On the back screen ✓ — flip the phone and double-tap it."
            setPadding(0, dp(8), 0, 0)
            visibility = TextView.GONE
            root.addView(this)
        }

        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(root) }) // clear the status bar (edge-to-edge)
        Shizuku.addRequestPermissionResultListener(shizukuListener)
        refresh()
    }

    /**
     * HyperOS freezes us while you approve in the browser, so the poll loop
     * stalls; coming back wakes it to poll right away instead of mid-sleep.
     */
    override fun onResume() { super.onResume(); poller?.interrupt(); refresh() }

    override fun onDestroy() {
        signInRun++ // stops any polling loop
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
        super.onDestroy()
    }

    private fun refresh() {
        val token = vault.token
        val snap = Snapshot.load(this)
        account.text = when {
            token == null && vault.expired -> "GitHub rejected the token — sign in again."
            token == null -> "Not signed in."
            snap != null && snap.login.isNotEmpty() -> "Signed in as @${snap.login} ✓"
            else -> "Checking the token…"
        }
        start.isEnabled = token != null
        if (token != null && (snap == null || snap.login.isEmpty()) && checked != token) verify(token)
        shizuku.text = when (RearRouter.status()) {
            RearRouter.Status.READY -> "Shizuku ready ✓ — Merged moves itself to the rear screen."
            RearRouter.Status.NO_PERMISSION -> "Shizuku is running but hasn't authorized Merged."
            RearRouter.Status.SHIZUKU_OFF -> "Shizuku isn't running (it stops on reboot). Without it Merged shows on this screen."
        }
        shizukuButton.text = when (RearRouter.status()) {
            RearRouter.Status.SHIZUKU_OFF -> "Open Shizuku"
            RearRouter.Status.NO_PERMISSION -> "Grant Shizuku permission"
            RearRouter.Status.READY -> "Shizuku ready"
        }
        shizukuButton.isEnabled = RearRouter.status() != RearRouter.Status.READY
    }

    /** Ask GitHub whose token this is, off the main thread ("Signed in as @…", or that it was rejected). */
    private fun verify(token: String) {
        checked = token
        thread {
            val r = runCatching { GitHub.viewer(token) }
            main.post {
                if (isDestroyed || vault.token != token) return@post
                r.onSuccess { account.text = "Signed in as @$it ✓" }
                    .onFailure { e ->
                        if (e is GitHub.AuthError) { vault.token = null; vault.expired = true; refresh() }
                        else account.text = "Token saved ✓ (couldn't reach GitHub to check it)"
                    }
            }
        }
    }

    private fun signIn() {
        val run = ++signInRun
        val clientId = BuildConfig.GITHUB_CLIENT_ID
        code.visibility = TextView.VISIBLE
        code.text = "…"
        poller = thread {
            val dc = runCatching { GitHub.requestDeviceCode(clientId) }.getOrElse { e ->
                main.post { if (run == signInRun) { code.text = "Couldn't reach GitHub\n${e.message}"; code.textSize = 16f } }
                return@thread
            }
            if (run != signInRun) return@thread
            main.post {
                if (run != signInRun || isDestroyed) return@post
                code.textSize = 34f
                code.text = dc.userCode
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("GitHub code", dc.userCode))
                account.text = "Code copied — paste it on the GitHub page that just opened, approve, then come back here."
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dc.verificationUri)))
            }
            var interval = dc.intervalSec
            while (run == signInRun && System.currentTimeMillis() < dc.expiresAtMs) {
                try { Thread.sleep(interval * 1000L) } catch (_: InterruptedException) {}
                if (run != signInRun) return@thread
                when (val p = runCatching { GitHub.pollToken(clientId, dc.deviceCode) }
                    .getOrDefault(GitHub.Poll.Pending)) {
                    GitHub.Poll.Pending -> Unit
                    GitHub.Poll.SlowDown -> interval += 5
                    is GitHub.Poll.Token -> {
                        vault.token = p.token
                        Snapshot.clear(this)
                        main.post { code.visibility = TextView.GONE; refresh() }
                        return@thread
                    }
                    is GitHub.Poll.Failed -> {
                        main.post { code.text = p.reason; code.textSize = 16f }
                        return@thread
                    }
                }
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_SHIZUKU = 4242
        const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        /** The gesture grammar (UX-PASS §4), in the words the rear's hints use. */
        val LEGEND = """
            Swipe left or right: the next screen. Tap: the next screen, or whatever you tap (a row, a badge, a count).
            Tap the big count on Today: replay the last merge. Tap a flying cat during a merge: pop it.
            Hold, then drag: a pet cat follows your finger (fling it: fireworks). On the cat screen: a laser dot.
            Cats: tap one to meet its PR (again for the details), rub one back and forth, tap the header to wake them.
            Hold still for 1.5 s: this screen.
        """.trimIndent()
    }
}
