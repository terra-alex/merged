package co.tryterra.merged

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min

/** Daily goal, the persisted per-day history, workday streak and the week recap. V2.md §2, §5. */
object Progress {
    const val DEFAULT_GOAL = 10
    private const val KEEP_DAYS = 400L
    private const val BACKFILL_DAYS = 364L // 52 weekly rounds

    // "settings", not "state": the goal survives account switches.
    private fun settings(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun goal(ctx: Context): Int = settings(ctx).getInt("goal", DEFAULT_GOAL).coerceIn(1, 99)
    fun setGoal(ctx: Context, n: Int) { settings(ctx).edit().putInt("goal", n.coerceIn(1, 99)).apply() }

    // Quiet hours (UX-PASS #16): no cats, no sounds, no shows, slow polling. Whole local hours.
    fun quietStart(ctx: Context): Int = settings(ctx).getInt("quietStart", 23)
    fun quietEnd(ctx: Context): Int = settings(ctx).getInt("quietEnd", 7)
    fun setQuiet(ctx: Context, start: Int, end: Int) { settings(ctx).edit().putInt("quietStart", start).putInt("quietEnd", end).apply() }

    /** [hour] in [start, end), wrapping midnight; start == end: never quiet. */
    fun quiet(start: Int, end: Int, hour: Int) = when {
        start == end -> false
        start < end -> hour in start until end
        else -> hour >= start || hour < end
    }

    /** Per-day merged-PR counts, prefs "state" key "daily" (JSON ISO date -> count). Kept 400 days. */
    fun history(prefs: SharedPreferences): Map<LocalDate, Int> {
        val j = runCatching { JSONObject(prefs.getString("daily", "{}")!!) }.getOrDefault(JSONObject())
        return j.keys().asSequence().mapNotNull { k -> runCatching { LocalDate.parse(k) to j.getInt(k) }.getOrNull() }.toMap()
    }

    /** Merge in counts (max(old, new) per day — counts only grow), trim to 400 days, save. Returns the merged map. */
    fun record(prefs: SharedPreferences, days: Map<LocalDate, Int>, today: LocalDate): Map<LocalDate, Int> {
        val m = history(prefs).toMutableMap()
        days.forEach { (d, n) -> m[d] = max(m[d] ?: 0, n) }
        val cutoff = today.minusDays(KEEP_DAYS)
        m.keys.removeAll { it < cutoff }
        prefs.edit().putString("daily", JSONObject(m.mapKeys { it.key.toString() }).toString()).apply()
        return m
    }

    /** Make [day] unknown again (its count was taken mid-day), so the backfill re-fetches it. Returns the history. */
    fun forget(prefs: SharedPreferences, day: LocalDate): Map<LocalDate, Int> {
        val m = history(prefs).toMutableMap()
        if (m.remove(day) != null) prefs.edit().putString("daily", JSONObject(m.mapKeys { it.key.toString() }).toString()).apply()
        return m
    }

    private fun weekend(d: LocalDate) = d.dayOfWeek >= DayOfWeek.SATURDAY

    /** Consecutive days with >= 1 merge counting back from today. Sat/Sun with 0 never break it (skipped, not counted);
     *  today with 0 doesn't break it (not counted). A weekday with 0 (or missing from [days]) ends it. */
    fun streak(days: Map<LocalDate, Int>, today: LocalDate): Int {
        val s = streakStart(days, today) ?: return 0
        return days.count { (d, n) -> n > 0 && d >= s && d <= today }
    }

    /** First day of the current run, null if the streak is 0. */
    fun streakStart(days: Map<LocalDate, Int>, today: LocalDate): LocalDate? {
        var start: LocalDate? = null
        var d = today
        while (true) {
            if ((days[d] ?: 0) > 0) start = d else if (d != today && !weekend(d)) return start
            d = d.minusDays(1)
        }
    }

    /** Longest run in the history (incl. the current one), same rules as [streak]. */
    fun bestStreak(days: Map<LocalDate, Int>, today: LocalDate): Int {
        var d = days.keys.minByOrNull { it.toEpochDay() } ?: return 0
        var best = 0
        var run = 0
        while (d <= today) {
            if ((days[d] ?: 0) > 0) best = max(best, ++run) else if (d != today && !weekend(d)) run = 0
            d = d.plusDays(1)
        }
        return best
    }

    /** A day missing from the history is UNKNOWN (not 0). Returns the earliest known day E when the current run
     *  reaches E without ending (so the week before E must be fetched), or today when the history is empty;
     *  null when the run's ending weekday is known or E is >= 364 days back. */
    fun backfillBefore(days: Map<LocalDate, Int>, today: LocalDate): LocalDate? {
        var d = today
        while (true) {
            if (d != today) {
                val n = days[d] ?: return d.plusDays(1).takeIf { ChronoUnit.DAYS.between(it, today) < BACKFILL_DAYS }
                if (n == 0 && !weekend(d)) return null
            }
            d = d.minusDays(1)
        }
    }

    /** Best day before [today] (today is still running); 0 when the history has none. */
    fun bestDay(days: Map<LocalDate, Int>, today: LocalDate): Int = days.filterKeys { it < today }.values.maxOrNull() ?: 0

    /** Best Monday-to-Sunday week in the history, this one included. */
    fun bestWeek(days: Map<LocalDate, Int>): Int =
        days.entries.groupBy { it.key.with(DayOfWeek.MONDAY) }.values.maxOfOrNull { w -> w.sumOf { it.value } } ?: 0

    /** Last week's count for each weekday Mon..Sun (days the history doesn't know read 0). */
    fun lastWeek(days: Map<LocalDate, Int>, today: LocalDate): IntArray {
        val mon = today.with(DayOfWeek.MONDAY).minusDays(7)
        return IntArray(7) { days[mon.plusDays(it.toLong())] ?: 0 }
    }

    /** Biggest PR (lines changed) and fastest merge (whole minutes from opened to merged) seen since [since] (install). */
    class Records(val biggest: Int, val fastestMin: Int?, val since: LocalDate?)

    fun records(prefs: SharedPreferences): Records {
        val j = runCatching { JSONObject(prefs.getString("records", "{}")!!) }.getOrDefault(JSONObject())
        return Records(j.optInt("biggest"), if (j.has("fastest")) j.getInt("fastest") else null,
            j.optString("since").takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
    }

    /**
     * Fold [prs] into the persisted records (prefs "state" key "records"); returns them. Only PRs whose generated files
     * were taken out count for the biggest: a failed check would make a lockfile a record forever.
     */
    fun recordPrs(prefs: SharedPreferences, prs: List<Pr>, today: LocalDate = LocalDate.now()): Records {
        val r = records(prefs)
        val big = max(r.biggest, prs.filter { it.genChecked }.maxOfOrNull { it.additions + it.deletions } ?: 0)
        val fast = (listOfNotNull(r.fastestMin) + prs.filter { it.createdAtMs > 0 }.map { minutes(it) }).minOrNull()
        val since = r.since ?: today
        if (big != r.biggest || fast != r.fastestMin || r.since == null)
            prefs.edit().putString("records", JSONObject().put("biggest", big).put("since", since.toString())
                .apply { if (fast != null) put("fastest", fast) }.toString()).apply()
        return Records(big, fast, since)
    }

    fun minutes(p: Pr) = ((p.mergedAtMs - p.createdAtMs) / 60_000).toInt().coerceAtLeast(0)

    class Week(
        val start: LocalDate,            // Monday of this week
        val perDay: IntArray,            // 7 counts Mon..Sun (future days 0)
        val prs: Int, val adds: Int, val dels: Int,   // lines exclude generated files (Pr fields already do)
        val goalDays: Int,               // days this week with count >= goal
        val workdaysSoFar: Int,          // weekdays before today, today once it met the goal, weekend days that met it
        val best: Pr?,                   // max additions+deletions this week
    )

    fun week(prs: List<Pr>, today: LocalDate, zone: ZoneId, goal: Int): Week {
        val start = today.minusDays(today.dayOfWeek.value - 1L)
        val perDay = IntArray(7)
        val mine = ArrayList<Pr>()
        for (p in prs) {
            val d = Instant.ofEpochMilli(p.mergedAtMs).atZone(zone).toLocalDate()
            if (d < start || d > today) continue
            mine += p
            perDay[d.dayOfWeek.value - 1]++
        }
        return Week(start, perDay, mine.size, mine.sumOf { it.additions }, mine.sumOf { it.deletions },
            // Today joins the denominator once it meets the goal (so Tuesday morning doesn't read as a miss), and a weekend
            // day only if it met the goal, so it reads e.g. 6 / 6, never 6 / 5.
            perDay.count { it >= goal }, today.dayOfWeek.value.let { dow ->
                min(dow - 1, 5) + (if (dow <= 5 && perDay[dow - 1] >= goal) 1 else 0) + perDay.drop(5).count { it >= goal }
            },
            mine.maxByOrNull { it.additions + it.deletions })
    }
}
