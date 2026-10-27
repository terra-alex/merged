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
 * Front-screen setup: GitHub sign-in (Device Flow, or paste a token), Shizuku
 * grant for rear-screen routing, and a button to start the meter.
 */
class SetupActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var vault: Vault
    private lateinit var account: TextView
    private lateinit var code: TextView
    private lateinit var shizuku: TextView
    @Volatile private var signInRun = 0
    @Volatile private var poller: Thread? = null

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
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { onClick() }
            root.addView(this)
        }

        root.addView(TextView(this).apply { text = "Merged"; textSize = 32f; setTypeface(typeface, Typeface.BOLD) })
        root.addView(TextView(this).apply { text = "Your merged PRs, on the back of your phone."; alpha = 0.7f })

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
        button("Sign out") { vault.token = null; Snapshot.clear(this); refresh() }

        header("REAR SCREEN")
        shizuku = TextView(this).also { root.addView(it) }
        button("Grant Shizuku permission") {
            if (runCatching { Shizuku.pingBinder() }.getOrDefault(false) &&
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
            ) Shizuku.requestPermission(REQ_SHIZUKU)
            refresh()
        }

        header("DAILY GOAL")
        root.addView(TextView(this).apply { text = "Merged PRs a day that count as goal met."; alpha = 0.7f })
        root.addView(NumberPicker(this).apply {
            minValue = 1
            maxValue = 30
            value = Progress.goal(this@SetupActivity)
            wrapSelectorWheel = false
            // The meter re-reads the goal on its next publish; poke so that's now.
            setOnValueChangedListener { _, _, n -> Progress.setGoal(this@SetupActivity, n); MeterActivity.poke?.invoke() }
        })

        header("GO")
        button("Start Merged") {
            // A meter already running on the rear screen just re-fetches; don't stack a second one.
            MeterActivity.poke?.invoke()
                ?: startActivity(Intent(this, MeterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
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
        val snap = Snapshot.load(this)
        account.text = when {
            vault.token == null -> "Not signed in."
            snap != null && snap.login.isNotEmpty() -> "Signed in as @${snap.login} ✓"
            else -> "Token saved ✓"
        }
        shizuku.text = when (RearRouter.status()) {
            RearRouter.Status.READY -> "Shizuku ready ✓ — Merged will move itself to the rear screen."
            RearRouter.Status.NO_PERMISSION -> "Shizuku is running but hasn't authorized Merged — tap Grant."
            RearRouter.Status.SHIZUKU_OFF -> "Shizuku isn't running. Without it Merged shows on this screen."
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
    }
}
