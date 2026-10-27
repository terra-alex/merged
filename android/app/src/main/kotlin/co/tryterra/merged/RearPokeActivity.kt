package co.tryterra.merged

import android.app.Activity

/**
 * Invisible, resumes for one frame on the rear, then finishes. HyperOS's freezer (Greeze) keeps
 * the app registered as the rear screen's foreground app unfrozen while the rear is on, but it
 * only re-registers on a foreground *package change* on display 1. Merged stays RESUMED through
 * rear doze, so after Xiaomi's rear launcher takes the slot it never gets it back, and is frozen
 * 5 s after every wake (touches then queue up into an ANR). Resuming this after the rear wakes
 * hands the slot back. See merged/design/REAR-AOD.md.
 */
class RearPokeActivity : Activity() {
    override fun onResume() {
        super.onResume()
        window.decorView.post { finish(); overridePendingTransition(0, 0) }
    }
}
