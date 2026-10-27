package co.tryterra.merged

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class BadgeRulesTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 10, 30) // a Friday
    private var n = 0

    private fun ms(t: String) = LocalDateTime.parse(t).atZone(zone).toInstant().toEpochMilli()
    private fun pr(merged: String, adds: Int = 10, dels: Int = 5, openSec: Long = 3600) =
        Pr(id = "pr${n++}", repo = "o/r", number = n, title = "t", additions = adds, deletions = dels,
            mergedAtMs = ms(merged), createdAtMs = if (openSec < 0) 0 else ms(merged) - openSec * 1000, genChecked = true)

    private fun keys(prs: List<Pr>, goal: Int = 99, streak: Int = 0, start: LocalDate? = null, life: Int = 0, prev: Int? = null) =
        BadgeBook.awards(prs, zone, goal, streak, start, life, prev, today).map { it.key }

    @Test fun netNegative() {
        assertEquals(listOf("NET_NEGATIVE:2026-10-26"), keys(listOf(pr("2026-10-26T12:00", 10, 4), pr("2026-10-26T13:00", 0, 20))))
        assertEquals(emptyList<String>(), keys(listOf(pr("2026-10-26T12:00", 7, 7))))
        assertEquals(emptyList<String>(), keys(listOf(pr("2026-10-26T12:00", 0, 0))))
    }

    @Test fun lineRulesWaitForGeneratedCheck() {
        val big = listOf(pr("2026-10-26T12:00", 9_000, 2_000), pr("2026-10-26T13:00", 0, 1).copy(genChecked = false))
        assertEquals(emptyList<String>(), keys(big))
    }

    @Test fun earlyBird() {
        assertEquals(emptyList<String>(), keys(listOf(pr("2026-10-26T04:59"), pr("2026-10-26T09:00"))))
        assertEquals(listOf("EARLY_BIRD:2026-10-26"), keys(listOf(pr("2026-10-26T05:00"), pr("2026-10-26T08:59"))))
    }

    @Test fun tenK() {
        assertEquals(emptyList<String>(), keys(listOf(pr("2026-10-26T12:00", 9_000, 999))))
        assertEquals(listOf("TEN_K:2026-10-26"), keys(listOf(pr("2026-10-26T12:00", 6_000, 0), pr("2026-10-26T13:00", 4_000, 0))))
    }

    @Test fun friday() {
        assertEquals(emptyList<String>(), keys(listOf(pr("2026-10-30T16:59"), pr("2026-10-29T18:00"))))
        assertEquals(listOf("FRIDAY:2026-10-30"), keys(listOf(pr("2026-10-30T17:00"))))
    }

    @Test fun speedrun() {
        assertEquals(listOf("SPEEDRUN:2026-10-26"), keys(listOf(pr("2026-10-26T12:00", openSec = 59))))
        assertEquals(emptyList<String>(), keys(listOf(pr("2026-10-26T12:00", openSec = 60), pr("2026-10-26T13:00", openSec = -1))))
    }

    @Test fun goal() {
        val day = List(3) { pr("2026-10-27T1$it:00") }
        assertEquals(listOf("GOAL:2026-10-27"), keys(day, goal = 3))
        assertEquals(emptyList<String>(), keys(day, goal = 4))
    }

    @Test fun onARoll() {
        val start = LocalDate.of(2026, 10, 26)
        assertEquals(emptyList<String>(), keys(emptyList(), streak = 4, start = start))
        assertEquals(listOf("ON_A_ROLL:2026-10-26"), keys(emptyList(), streak = 5, start = start))
        assertEquals(today, BadgeBook.awards(emptyList(), zone, 99, 7, start, 0, null, today).single().day)
    }

    @Test fun century() {
        assertEquals(emptyList<String>(), keys(emptyList(), life = 500, prev = null))
        assertEquals(listOf("CENTURY:500"), keys(emptyList(), life = 500, prev = 499))
        assertEquals(emptyList<String>(), keys(emptyList(), life = 512, prev = 500))
        assertEquals(listOf("CENTURY:500"), keys(emptyList(), life = 512, prev = 399))
    }

    @Test fun localDays() {
        // 02:30 UTC on the 27th is 22:30 on the 26th in New York: same local day as the 12:00 merge.
        val late = Pr("x", "o/r", 1, "t", 0, 30, LocalDateTime.parse("2026-10-27T02:30").atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(), genChecked = true)
        assertEquals(listOf("NET_NEGATIVE:2026-10-26", "GOAL:2026-10-26"), keys(listOf(pr("2026-10-26T12:00", 20, 0), late), goal = 2))
    }

    @Test fun progressLines() {
        val now = LocalDateTime.parse("2026-10-30T14:00") // Friday afternoon
        val todays = listOf(pr("2026-10-30T10:00", 3000, 1210), pr("2026-10-30T11:00", openSec = 120))
        fun prog(b: Badge, streak: Int = 3, rolled: Boolean = false, life: Int = 563) =
            BadgeBook.progress(b, todays, todays, 10, streak, rolled, life, now)
        assertEquals("TODAY 2 / 10" to "2/10", prog(Badge.GOAL))
        assertEquals("TODAY 4,225 / 10,000 LINES" to "4.2K/10K", prog(Badge.TEN_K))
        assertEquals("STREAK 3 / 5" to "3/5", prog(Badge.ON_A_ROLL))
        assertEquals("EARNED THIS RUN" to null, prog(Badge.ON_A_ROLL, streak = 7, rolled = true))
        assertEquals("37 PRs TO #600" to "37 TO GO", prog(Badge.CENTURY))
        assertEquals("1 PR TO #600", prog(Badge.CENTURY, life = 599).first)
        assertEquals("NEXT CHANCE · FRI FROM 5 PM", prog(Badge.FRIDAY).first)
        assertEquals("FASTEST THIS WEEK · 2 MIN", prog(Badge.SPEEDRUN).first)
        assertEquals("NEXT CHANCE · TOMORROW 5 AM", prog(Badge.EARLY_BIRD).first)
    }
}
