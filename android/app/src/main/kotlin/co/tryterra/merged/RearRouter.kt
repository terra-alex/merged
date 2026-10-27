package co.tryterra.merged

import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Moves our own Activity onto the rear screen (display 1) via Shizuku.
 *
 * Mechanism (see `docs/rear-screen-routing.md`), run as uid=shell:
 *   am force-stop com.xiaomi.subscreencenter
 *   am display move-stack <taskId> <displayId>
 *
 * Moving an existing task (rather than starting on display 1) avoids the MIUI
 * start-time package allowlist; going through `am` instead of a raw binder
 * transaction code survives OTAs that renumber IActivityTaskManager.
 *
 * Shizuku.newProcess is package-private in Shizuku 13, so we call it via
 * reflection — same pattern MRSS uses internally for its TaskService.
 */
object RearRouter {
    const val DISPLAY_REAR = 1
    private const val TAG = "Merged.RearRouter"

    enum class Status { READY, SHIZUKU_OFF, NO_PERMISSION }

    fun status(): Status = try {
        when {
            !Shizuku.pingBinder() -> Status.SHIZUKU_OFF
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Status.NO_PERMISSION
            else -> Status.READY
        }
    } catch (_: Throwable) {
        Status.SHIZUKU_OFF
    }

    fun moveToRear(activity: Activity, displayId: Int = DISPLAY_REAR): Boolean {
        if (status() != Status.READY) {
            Log.w(TAG, "not ready: ${status()}")
            return false
        }
        val taskId = activity.taskId
        require(taskId > 0) { "invalid taskId=$taskId" }
        require(displayId in 0..8) { "invalid displayId=$displayId" }
        return try {
            // Step 1: kick SubScreenLauncher off display 1. Otherwise it stays
            // the top SECONDARY_HOME activity and our task is paused/hidden
            // immediately after the move. MRSS does the same thing in
            // ChargingService.disableSubScreenLauncher() before its move.
            //
            // We pass argv directly (no `sh -c`) so the args are never
            // re-parsed as a shell string — no injection sink.
            val killExit = shizukuExec(arrayOf("am", "force-stop", "com.xiaomi.subscreencenter"))
            Log.i(TAG, "force-stop subscreencenter → exit=$killExit")

            // Step 2: move our task. `am display move-stack` calls
            // moveRootTaskToDisplay by name. Don't use the raw
            // `service call activity_task 50`: on HyperOS OS3.0.319 that code
            // became resizeTask (moveRootTaskToDisplay is 51) — it "succeeds"
            // and silently does nothing.
            val moveExit = shizukuExec(arrayOf(
                "am", "display", "move-stack", taskId.toString(), displayId.toString(),
            ))
            Log.i(TAG, "am display move-stack (task=$taskId display=$displayId) → exit=$moveExit")
            moveExit == 0
        } catch (t: Throwable) {
            Log.w(TAG, "shizuku exec failed", t)
            false
        }
    }

    /** Run `cmd` as uid=shell via Shizuku. Returns the process exit code. */
    private fun shizukuExec(cmd: Array<String>): Int {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        val process = method.invoke(null, cmd, null, null)!!
        return process.javaClass.getMethod("waitFor").invoke(process) as Int
    }
}
