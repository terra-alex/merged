package co.tryterra.merged

import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Every sound and buzz Merged makes (design/UX-PASS.md §5). Sounds go through one [quiet] gate (ringer, DND,
 * quiet hours); touch answers go through [feel]. Frequency sets loudness: S merges and touches only tick,
 * M/L/XL merges chime, badges get a sparkle.
 */
class Feel(private val view: View) {

    enum class Kind { TICK, POP, LAND, CONFIRM, LONG_PRESS }

    private val ctx = view.context
    private val vibrator = ctx.getSystemService(Vibrator::class.java)
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(NOTIFY).build()
    private val chimeSmall = pool.load(ctx, R.raw.merge_small, 1)
    private val chimeBig = pool.load(ctx, R.raw.merge_big, 1)
    private val mewId = pool.load(ctx, R.raw.mew, 1)
    private val sparkleId = pool.load(ctx, R.raw.sparkle, 1)
    private var lastFeel = 0L
    private var chimeUntil = 0L
    private var lastMew = -10_000L

    /** Silent, DND or quiet hours: no sounds and no merge buzz. */
    fun quiet(night: Boolean) = night || audio.ringerMode == AudioManager.RINGER_MODE_SILENT ||
        nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL

    private fun sound(night: Boolean) = !quiet(night) && audio.ringerMode == AudioManager.RINGER_MODE_NORMAL

    /**
     * A real merge (never a replay): S is a 15 ms tick and no sound; M two pulses and the small chime; L three and the
     * arpeggio; XL adds a long pulse. Plain vibrate: HyperOS drops app vibrations tagged notification/ringtone ("not
     * vibrating for ringtones or notify for MIUI"), hence the ringer/DND checks in [quiet]. We're the rear display's
     * resumed activity, so this counts as foreground even with the front screen in use.
     */
    fun merge(tier: Int, night: Boolean) {
        if (quiet(night)) return
        if (vibrator?.hasVibrator() == true) {
            if (tier == 0) vibrator.vibrate(VibrationEffect.createOneShot(15, 70))
            else {
                val pulses = if (tier == 1) 2 else 3
                val timings = ArrayList<Long>().apply { add(0); repeat(pulses) { if (it > 0) add(90); add(45) }; if (tier == 3) { add(140); add(320) } }
                val amps = IntArray(timings.size) { if (it % 2 == 0) 0 else if (tier == 3 && it == timings.lastIndex) 200 else 255 }
                vibrator.vibrate(VibrationEffect.createWaveform(timings.toLongArray(), amps, -1))
            }
        }
        if (tier > 0 && sound(night)) chime(if (tier >= 2) chimeBig else chimeSmall, if (tier >= 2) 1050 else 450)
    }

    /** The small chime alone (a failing PR going green). */
    fun ding(night: Boolean) { if (sound(night)) chime(chimeSmall, 450) }

    /** Badge unlock: a sparkle over a soft C7, plus a confirm click. */
    fun sparkle(night: Boolean) { feel(Kind.CONFIRM); if (sound(night)) chime(sparkleId, 600) }

    private fun chime(id: Int, ms: Long) {
        pool.play(id, 1f, 1f, 1, 0, 1f)
        chimeUntil = SystemClock.uptimeMillis() + ms
    }

    /** A cat's mew, pitched by its PR's size (small PRs squeak), detuned ±3 % by [seed]. At most one per 2 s, never over a chime. */
    fun mew(tier: Int, seed: Int, night: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!sound(night) || now < chimeUntil || now - lastMew < 2000) return
        lastMew = now
        val detune = 1f + .06f * (MergeFx.rnd(seed, 3, 91) - .5f)
        pool.play(mewId, .8f, .8f, 0, 0, (floatArrayOf(1.5f, 1.2f, 1f, .8f)[tier.coerceIn(0, 3)] * detune).coerceIn(.5f, 2f))
    }

    /** Answer a touch: at most one per 120 ms. Ticks and clicks follow the system's touch-feedback setting. */
    fun feel(kind: Kind, tier: Int = 1) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFeel < 120) return
        lastFeel = now
        when (kind) {
            Kind.TICK -> view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            Kind.CONFIRM -> view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            Kind.LONG_PRESS -> view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Kind.POP -> touch(VibrationEffect.createOneShot(20, 255))
            Kind.LAND -> touch(VibrationEffect.createOneShot(25, intArrayOf(60, 100, 140, 180)[tier.coerceIn(0, 3)]))
        }
    }

    /** A soft purr while a cat is being rubbed: 14 ms on, 26 ms off, until [stopPurr]. */
    fun purr() = touch(VibrationEffect.createWaveform(longArrayOf(0, 14, 26), intArrayOf(0, 60, 0), 1))

    fun stopPurr() { vibrator?.cancel() }

    private fun touch(e: VibrationEffect) {
        if (vibrator?.hasVibrator() != true) return
        if (Build.VERSION.SDK_INT >= 33) vibrator.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        else vibrator.vibrate(e)
    }

    fun release() { stopPurr(); pool.release() }

    private companion object {
        /** Chimes play as notification sounds: notification volume, and DND mutes them too. */
        val NOTIFY: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    }
}
