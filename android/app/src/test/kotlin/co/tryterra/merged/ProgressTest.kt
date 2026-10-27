package co.tryterra.merged

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

// October 2026: Mon 12, Mon 19, Mon 26 (UK clocks go back Sun 25); US clocks go back Sun 1 Nov.
class ProgressTest {
    private fun d(s: String) = LocalDate.parse("2026-$s")
    private fun h(vararg p: Pair<String, Int>) = p.associate { d(it.first) to it.second }
    /** Every day in [from, to] with [n] merges. */
    private fun run(from: LocalDate, to: LocalDate, n: Int = 1) =
        generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.associateWith { n }

    @Test fun weekendInsideRun() {
        val days = h("10-28" to 2, "10-27" to 1, "10-26" to 3, "10-25" to 0, "10-24" to 0, "10-23" to 4, "10-22" to 1, "10-21" to 0)
        assertEquals(5, Progress.streak(days, d("10-28")))
        assertEquals(d("10-22"), Progress.streakStart(days, d("10-28")))
    }

    @Test fun weekendMergesCount() {
        val days = h("10-26" to 1, "10-25" to 2, "10-24" to 1, "10-23" to 1, "10-22" to 0)
        assertEquals(4, Progress.streak(days, d("10-26")))
        assertEquals(d("10-23"), Progress.streakStart(days, d("10-26")))
    }

    @Test fun missingWeekendSkipped() {
        val days = h("10-26" to 1, "10-23" to 1, "10-22" to 0) // Sat/Sun absent
        assertEquals(2, Progress.streak(days, d("10-26")))
    }

    @Test fun todayWithZeroDoesNotBreak() {
        val days = h("10-27" to 0, "10-26" to 2, "10-25" to 0, "10-24" to 0, "10-23" to 1, "10-22" to 0)
        assertEquals(2, Progress.streak(days, d("10-27")))
        assertEquals(d("10-23"), Progress.streakStart(days, d("10-27")))
        assertEquals(2, Progress.streak(days - d("10-27"), d("10-27"))) // today missing too
        assertEquals(0, Progress.streak(h("10-27" to 0, "10-26" to 0), d("10-27")))
        assertNull(Progress.streakStart(h("10-27" to 0, "10-26" to 0), d("10-27")))
    }

    @Test fun saturdayTodayWithZero() {
        val days = h("10-24" to 0, "10-23" to 1, "10-22" to 1, "10-21" to 0)
        assertEquals(2, Progress.streak(days, d("10-24")))
    }

    @Test fun weekdayGapEnds() {
        assertEquals(1, Progress.streak(h("10-28" to 1, "10-27" to 0, "10-26" to 5), d("10-28")))
    }

    @Test fun missingHistory() {
        assertEquals(2, Progress.streak(h("10-28" to 1, "10-27" to 1, "10-23" to 9), d("10-28"))) // Mon 26 missing
        assertEquals(0, Progress.streak(emptyMap(), d("10-28")))
        assertNull(Progress.streakStart(emptyMap(), d("10-28")))
        assertEquals(0, Progress.bestStreak(emptyMap(), d("10-28")))
    }

    @Test fun bestStreak() {
        // Old run Mon 12 – Fri 16 + Sun 18 merge = 6; broken Mon 19; current Tue 20 .. Tue 27 with a dead weekend = 6.
        val days = run(d("10-12"), d("10-16")) + h("10-17" to 0, "10-18" to 1, "10-19" to 0) +
            run(d("10-20"), d("10-23")) + h("10-24" to 0, "10-25" to 0, "10-26" to 1, "10-27" to 1)
        assertEquals(6, Progress.bestStreak(days, d("10-27")))
        assertEquals(6, Progress.streak(days, d("10-27")))
        // Current run grows past the old one; today's 0 doesn't break it.
        val more = days + h("10-28" to 2, "10-29" to 0)
        assertEquals(7, Progress.bestStreak(more, d("10-29")))
        assertEquals(7, Progress.streak(more, d("10-29")))
        // A missing weekday inside the history breaks a run.
        assertEquals(4, Progress.bestStreak(days - d("10-14") + h("10-26" to 0), d("10-27")))
    }

    @Test fun backfillEmpty() {
        assertEquals(d("10-26"), Progress.backfillBefore(emptyMap(), d("10-26")))
        assertEquals(d("10-26"), Progress.backfillBefore(h("10-20" to 1), d("10-26"))) // today and yesterday unknown
    }

    @Test fun backfillReachesWindowStart() {
        val window = run(d("10-20"), d("10-26"))
        assertEquals(d("10-20"), Progress.backfillBefore(window, d("10-26")))
        // Fetched week before: all merged → keep going; a dead Wednesday → done.
        assertEquals(d("10-13"), Progress.backfillBefore(window + run(d("10-13"), d("10-19")), d("10-26")))
        assertNull(Progress.backfillBefore(window + run(d("10-13"), d("10-19")) + h("10-14" to 0), d("10-26")))
    }

    @Test fun backfillKnownEnd() {
        assertNull(Progress.backfillBefore(h("10-26" to 1, "10-25" to 0, "10-24" to 0, "10-23" to 0), d("10-26")))
        assertNull(Progress.backfillBefore(h("10-27" to 0, "10-26" to 0), d("10-27"))) // ended yesterday
    }

    @Test fun backfillUnknownWeekend() {
        // Sat/Sun count when merged, so missing ones are unknown too.
        assertEquals(d("10-26"), Progress.backfillBefore(h("10-26" to 1), d("10-26")))
        assertEquals(d("10-24"), Progress.backfillBefore(h("10-26" to 1, "10-25" to 0, "10-24" to 0), d("10-26")))
    }

    @Test fun backfillGapAndLimit() {
        val split = run(d("10-20"), d("10-26")) + run(d("10-06"), d("10-12"))
        assertEquals(d("10-20"), Progress.backfillBefore(split, d("10-26")))
        val today = d("10-26")
        assertEquals(today.minusDays(363), Progress.backfillBefore(run(today.minusDays(363), today), today))
        assertNull(Progress.backfillBefore(run(today.minusDays(364), today), today))
    }

    private fun at(s: String, z: ZoneId) = LocalDateTime.parse(s).atZone(z).toInstant().toEpochMilli()
    private fun pr(n: Int, adds: Int, dels: Int, ms: Long) = Pr("id$n", "terra/api", n, "t$n", adds, dels, ms)

    @Test fun weekOnMondayAfterUkDst() {
        val ldn = ZoneId.of("Europe/London")
        val prs = listOf(
            pr(1, 500, 500, at("2026-10-25T23:30", ldn)), // Sunday, previous week
            pr(2, 10, 5, at("2026-10-26T00:10", ldn)),
            pr(3, 40, 2, at("2026-10-26T15:00", ldn)),
        )
        val w = Progress.week(prs, d("10-26"), ldn, 2)
        assertEquals(d("10-26"), w.start)
        assertArrayEquals(intArrayOf(2, 0, 0, 0, 0, 0, 0), w.perDay)
        assertEquals(2, w.prs); assertEquals(50, w.adds); assertEquals(7, w.dels)
        assertEquals(1, w.goalDays); assertEquals(1, w.workdaysSoFar)
        assertEquals(3, w.best?.number)
    }

    @Test fun weekOnSundayAcrossUsDst() {
        val ny = ZoneId.of("America/New_York") // Sun 1 Nov is 25 h long
        val prs = listOf(
            pr(1, 1, 1, at("2026-10-25T23:59", ny)), // previous week
            pr(2, 1, 1, at("2026-10-26T00:00:30", ny)),
            pr(3, 100, 0, at("2026-10-31T12:00", ny)),
            pr(4, 1, 1, at("2026-11-01T00:30", ny)), // EDT
            pr(5, 1, 1, at("2026-11-01T23:30", ny)), // EST
        )
        val w = Progress.week(prs, LocalDate.parse("2026-11-01"), ny, 2)
        assertEquals(d("10-26"), w.start)
        assertArrayEquals(intArrayOf(1, 0, 0, 0, 0, 1, 2), w.perDay)
        assertEquals(4, w.prs); assertEquals(1, w.goalDays); assertEquals(6, w.workdaysSoFar) // Sun met the goal: it counts
        assertEquals(3, w.best?.number)
    }

    @Test fun weekBucketsInLocalZone() {
        // 00:30 BST on Sun 25 is Sat 24 in UTC.
        val ldn = ZoneId.of("Europe/London")
        val ms = at("2026-10-25T00:30", ldn)
        assertArrayEquals(intArrayOf(0, 0, 0, 0, 0, 0, 1), Progress.week(listOf(pr(1, 1, 1, ms)), d("10-25"), ldn, 10).perDay)
        assertArrayEquals(intArrayOf(0, 0, 0, 0, 0, 1, 0), Progress.week(listOf(pr(1, 1, 1, ms)), d("10-25"), ZoneId.of("UTC"), 10).perDay)
    }

    @Test fun weekMidweekEmpty() {
        val w = Progress.week(emptyList(), d("10-28"), ZoneId.of("UTC"), 10)
        assertEquals(d("10-26"), w.start)
        assertEquals(0, w.prs); assertEquals(0, w.goalDays); assertEquals(3, w.workdaysSoFar)
        assertNull(w.best)
        // A PR stamped after today (clock skew) is not this week's.
        val future = pr(1, 1, 1, at("2026-10-29T10:00", ZoneId.of("UTC")))
        assertEquals(0, Progress.week(listOf(future), d("10-28"), ZoneId.of("UTC"), 10).prs)
    }
}
