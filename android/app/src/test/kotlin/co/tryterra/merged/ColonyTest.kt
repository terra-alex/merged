package co.tryterra.merged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

/** MergeFx.pile geometry: pure Kotlin, no Android. */
class ColonyTest {
    private val churns = intArrayOf(12, 40, 90, 300, 800, 2000, 7000)

    private fun prs(n: Int, churn: (Int) -> Int = { churns[(it * 5 + it / 3) % churns.size] }) = List(n) { i ->
        Pr("PR_$i", "terra/${listOf("api", "app", "docs", "infra", "web")[i % 5]}", i, "t", churn(i) * 2 / 3, churn(i) - churn(i) * 2 / 3, 1_000_000L * i)
    }

    private fun box(s: MergeFx.Sleeper) = floatArrayOf(
        s.x - MergeFx.SLEEP_W * s.scale / 2, s.y - MergeFx.SLEEP_H * s.scale / 2,
        s.x + MergeFx.SLEEP_W * s.scale / 2, s.y + MergeFx.SLEEP_H * s.scale / 2)

    private fun key(p: List<MergeFx.Sleeper>) = p.map { listOf(it.x, it.y, it.scale, it.flip, it.variant, it.seed, it.zzz) }

    @Test fun fitsTheStageWithoutOverlaps() {
        for (n in listOf(0, 1, 5, 23, 40, 60, 61, 150)) {
            val p = MergeFx.pile(prs(n))
            assertEquals(minOf(n, 60), p.size)
            val b = p.map(::box)
            for (r in b) {
                assertTrue("n=$n ${r.toList()}", r[0] >= 24f && r[2] <= 656f && r[1] >= 52f && r[3] <= 572f)
                if (r[2] > 579f && r[1] < 101f) assertTrue("top corner n=$n", hypot(r[2] - 579f, r[1] - 101f) <= 101f)
                if (r[2] > 579f && r[3] > 495f) assertTrue("bottom corner n=$n", hypot(r[2] - 579f, r[3] - 495f) <= 101f)
            }
            for (i in b.indices) for (j in i + 1 until b.size) {
                val ox = minOf(b[i][2], b[j][2]) - maxOf(b[i][0], b[j][0])
                val oy = minOf(b[i][3], b[j][3]) - maxOf(b[i][1], b[j][1])
                assertTrue("n=$n cats $i/$j overlap $ox×$oy", ox <= .5f || oy <= .5f)
            }
        }
    }

    @Test fun deterministicAndOrderFree() {
        val a = prs(40)
        assertEquals(key(MergeFx.pile(a)), key(MergeFx.pile(a)))
        assertEquals(key(MergeFx.pile(a)), key(MergeFx.pile(a.shuffled(Random(7)))))
    }

    @Test fun oldestAtTheBottomNewestKept() {
        val p = MergeFx.pile(prs(150).shuffled(Random(3)))
        assertEquals((90 until 150).map { "PR_$it".hashCode() }, p.map { it.seed })
        val b = p.map(::box)
        for (i in b.indices) for (j in i + 1 until b.size) // a newer cat is never under an older one it overlaps
            if (minOf(b[i][2], b[j][2]) - maxOf(b[i][0], b[j][0]) > .5f) assertTrue("cats $i/$j", b[j][3] <= b[i][1] + .01f)
        assertTrue(b.take(5).all { it[3] > 552f }) // the oldest lie on the floor (lift <= 3 px)
        assertTrue(b.takeLast(5).all { it[3] < 500f })
    }

    @Test fun tiersAndCrowding() {
        val few = MergeFx.pile(prs(4) { intArrayOf(50, 500, 2000, 9000)[it] })
        assertEquals(listOf(.62f, .78f, .95f, 1.15f), few.map { it.scale })
        val crowd = MergeFx.pile(prs(60) { 9000 }) // 60 XL cats can't fit at full size
        assertTrue(crowd.all { it.scale < 1.15f && it.scale == crowd[0].scale })
        assertTrue(crowd.minOf { box(it)[1] } >= 52f)
        assertTrue(MergeFx.pile(prs(60)).count { it.flip } in 6..36)
        assertTrue(MergeFx.pile(prs(60)).count { it.zzz } in 8..36)
        assertTrue(MergeFx.pile(prs(60)).map { it.variant }.toSet().all { it in 0..3 })
    }
}
