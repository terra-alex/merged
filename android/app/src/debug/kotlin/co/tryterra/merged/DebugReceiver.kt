package co.tryterra.merged

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Debug-build-only test hooks, driven from the host. The manifest guards the
 * receiver with android.permission.DUMP, which adb's shell uid holds and
 * third-party apps can't get — so only `adb shell am broadcast` reaches it.
 *
 *   adb shell am broadcast -f 32 -a co.tryterra.merged.DEBUG -p co.tryterra.merged \
 *     --es token "$(gh auth token)"      # real data
 *     --es demo big|quiet|zero|off       # canned data, no network
 *     --ei merge 1200                    # fake a PR merging now (+1200 lines)
 *     --ei cutout 296                    # emulator: pretend the camera covers the left 296px
 *     --ez day true|false                # no night mode (filming after 23:00)
 *     --es show <cmd>                    # hand a command to the resumed MeterView (MeterView.debug)
 *     --ei goal 12                       # daily goal
 *     --es badges reset                  # forget every earned badge
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        intent.getStringExtra("token")?.let { Vault(context).token = it }
        intent.getStringExtra("demo")?.let { mode ->
            if (mode == "off") {
                prefs.edit().remove("demo").apply()
                Snapshot.clear(context)
            } else {
                prefs.edit().putString("demo", mode).apply()
                val snap = demo(mode, System.currentTimeMillis())
                Snapshot.save(context, snap)
                seed(context, snap)
            }
        }
        intent.getStringExtra("show")?.let { MeterActivity.debugCmd?.invoke(it) }
        if (intent.hasExtra("goal")) Progress.setGoal(context, intent.getIntExtra("goal", Progress.DEFAULT_GOAL))
        if (intent.getStringExtra("badges") == "reset") BadgeBook(context.getSharedPreferences("state", Context.MODE_PRIVATE)).reset()
        if (intent.hasExtra("cutout")) prefs.edit().putInt("cutout", intent.getIntExtra("cutout", 0)).apply()
        if (intent.hasExtra("day")) prefs.edit().putBoolean("day", intent.getBooleanExtra("day", false)).apply()
        if (intent.hasExtra("merge")) {
            val snap = Snapshot.load(context)
            if (snap != null) {
                val now = System.currentTimeMillis()
                val adds = intent.getIntExtra("merge", 120)
                val pr = Pr("demo-$now", "acme/api", 3000 + (now % 997).toInt(),
                    "feat: ship the thing", adds, adds / 5, now, createdAtMs = now - 25 * 60_000L, genChecked = true)
                val faked = snap.copy(prs = listOf(pr) + snap.prs,
                    windowCount = snap.windowCount + 1, ytd = snap.ytd + 1,
                    lifetime = snap.lifetime + 1, fetchedAtMs = now)
                Snapshot.save(context, faked)
                // Straight to the view: a refresh would re-fetch GitHub and drop the fake PR.
                // The next real poll rolls the numbers back to the truth.
                MeterActivity.inject?.invoke(faked)
            }
        }
        Log.i("Merged.Debug", "applied ${intent.extras?.keySet()}")
        // A refresh would re-publish under a `show` command and cut it short.
        if (!intent.hasExtra("merge") && !intent.hasExtra("show")) MeterActivity.poke?.invoke()
    }

    companion object {
        const val PREFS = "debug"

        /** Canned days shaped like real usage (heavy-tailed PR sizes). */
        fun demo(mode: String, now: Long): Snapshot {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            // additions per PR merged today; deletions ≈ 15%
            val todaySizes = when (mode) {
                "big" -> listOf(17795, 1991, 1253, 979, 471, 399, 383, 291, 245, 206, 190, 190,
                    124, 87, 75, 70, 49, 17, 15, 14, 6, 5, 3, 2)
                "quiet" -> listOf(71, 15)
                else -> emptyList()
            }
            // PR counts for the 6 previous days, oldest first
            val past = when (mode) {
                "big" -> listOf(3, 0, 4, 16, 28, 13)
                "quiet" -> listOf(0, 2, 3, 1, 0, 3)
                else -> listOf(1, 0, 2, 3, 1, 2)
            }
            val prs = ArrayList<Pr>()
            val startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayMs = 86_400_000L
            val elapsed = (now - startOfToday).coerceAtLeast(60_000)
            // "big" starts its day with an early-bird merge at 08:15 and spreads the rest up to now.
            val early = (startOfToday + 8 * 3_600_000L + 15 * 60_000L).takeIf { mode == "big" && it < now }
            todaySizes.forEachIndexed { i, a ->
                val t = if (early != null) now - (now - early) * i / todaySizes.lastIndex
                    else now - elapsed * i / todaySizes.size.coerceAtLeast(1)
                val p = details(Pr("d-t-$i", if (i % 3 == 0) "acme/infra" else "acme/api", 2600 + i,
                    TITLES[i % TITLES.size], a, a * 15 / 100, t), i)
                // The Recent card's "IGNORED 2 GENERATED FILES" line.
                prs += if (i == 0 && mode == "big") p.copy(genAdds = 12034, genDels = 310, genFiles = 2, genChecked = true) else p.copy(genChecked = true)
            }
            past.forEachIndexed { d, n ->
                val dayStart = startOfToday - (past.size - d) * dayMs
                repeat(n) { i ->
                    val a = listOf(103, 15, 399, 1239, 40, 220, 8, 760)[(d * 7 + i) % 8]
                    // Day 2 is a clean-up day: more lines deleted than added.
                    prs += details(Pr("d-$d-$i", "acme/api", 2000 + d * 50 + i, TITLES[(d + i) % TITLES.size],
                        if (d == 2) a / 6 else a, if (d == 2) a else a / 6, dayStart + dayMs * (i + 1) / (n + 1), genChecked = true), d * 7 + i)
                }
            }
            prs.sortByDescending { it.mergedAtMs }
            val ytd = if (mode == "big") 1810 else 412
            val open = open(now)
            return Snapshot("demo", prs, prs.size, ytd, ytd + 578, now, open, open.size)
        }

        private val TITLES = listOf(
            "feat(webhooks): retry failed deliveries with backoff", "fix(auth): refresh tokens before they expire",
            "chore(deps): bump the go toolchain", "refactor(router): split the v2 handlers by provider",
            "perf(db): batch the daily rollup inserts", "fix: typo in the error message",
            "feat(api): paginate the activity export", "test: cover the sleep-score edge cases",
        )

        /** Recent-card details; seeds 5 and 9 are under-a-minute speedruns (opened → merged). */
        private fun details(p: Pr, k: Int) = p.copy(
            createdAtMs = p.mergedAtMs - if (k == 5 || k == 9) 30_000L + k * 2_000 else (k % 7 + 1) * 3_100_000L,
            files = 1 + p.additions / 60, commits = 1 + k % 4, reviews = k % 3, comments = k * 5 % 7,
            branch = listOf("feat/webhook-retries", "fix/token-refresh", "chore/bump-go", "refactor/split-router")[k % 4],
            base = "main",
        )

        /** One or more open PRs in every status. */
        private fun open(now: Long): List<OpenPr> {
            fun pr(n: Int, title: String, ageH: Long, updatedH: Long, branch: String, repo: String = "acme/api",
                   draft: Boolean = false, review: String = "REVIEW_REQUIRED", approvals: Int = 0, checks: String = "SUCCESS",
                   mergeable: String = "MERGEABLE", auto: Boolean = false) =
                OpenPr("o-$n", repo, n, title, now - ageH * 3_600_000L, now - updatedH * 3_600_000L - 60_000L * (n % 50),
                    draft, review, approvals, checks, mergeable, auto, branch, "main")
            return listOf(
                pr(2711, "feat(webhooks): sign every delivery", 26, 1, "feat/webhook-signing", review = "APPROVED", approvals = 1),
                pr(388, "chore(deps): bump the go toolchain to 1.25", 5, 0, "chore/go-1.25", "acme/infra", review = "", approvals = 1, auto = true),
                pr(2706, "fix(auth): refresh tokens before they expire", 30, 2, "fix/token-refresh", checks = "FAILURE"),
                pr(2690, "refactor(router): split the v2 handlers by provider", 75, 5, "refactor/split-router", mergeable = "CONFLICTING"),
                pr(2702, "feat(api): paginate the activity export", 50, 3, "feat/export-pages", review = "CHANGES_REQUESTED"),
                pr(2714, "perf(db): batch the daily rollup inserts", 2, 0, "perf/rollup-batch", checks = "PENDING"),
                pr(391, "ci: cache the docker layers between runs", 8, 4, "ci/docker-cache", "acme/infra"),
                pr(2699, "docs: how webhook signing works", 100, 20, "docs/webhook-signing", review = "", checks = ""),
                pr(2620, "feat(sdk): typed client for the routes API", 400, 48, "feat/routes-sdk", draft = true, review = "", checks = ""),
            )
        }

        /**
         * The STREAK screen reads the per-day history, which demo mode never fetches: seed it with the demo's
         * own days plus five weeks of weekday merges before them. Claims "state" for the demo login first,
         * as publish would (and so switching demos doesn't carry the last one's counts).
         */
        private fun seed(context: Context, snap: Snapshot) {
            val state = context.getSharedPreferences("state", Context.MODE_PRIVATE)
            state.edit().clear().putString("login", snap.login).commit()
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val days = HashMap<LocalDate, Int>()
            for (i in 0L..40L) today.minusDays(i).let { d ->
                days[d] = if (i < 7 || d.dayOfWeek >= DayOfWeek.SATURDAY) 0 else 3 + (i * 5 % 11).toInt()
            }
            snap.prs.forEach { days.merge(Instant.ofEpochMilli(it.mergedAtMs).atZone(zone).toLocalDate(), 1, Int::plus) }
            Progress.record(state, days, today)
        }
    }
}
