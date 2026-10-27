package com.kilombino.pyblockwatch.coinjoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Which unused addresses of another wallet a seat's mixed outputs take. */
class DestinationTest {
    private fun s(i: Int) = byteArrayOf(0, 20, i.toByte())

    @Test fun firstUnusedPastTheLastUsed() {
        // Used: 0, 1, 3 (2 was handed out and never paid). The outputs take 4, 5.
        val used = setOf(0, 1, 3)
        val got = Destination.pickUnused(2, 0, emptySet(), ::s) { it[2].toInt() in used }
        assertEquals(listOf(4, 5), got.map { it[2].toInt() })
    }

    @Test fun skipsWhatOtherSeatsHold() {
        val used = setOf(0)
        val held = setOf(s(1).toList(), s(2).toList())
        val got = Destination.pickUnused(3, 0, held, ::s) { it[2].toInt() in used }
        assertEquals(listOf(3, 4, 5), got.map { it[2].toInt() })
    }

    @Test fun findsUsedOnesPastTheStartingPoint() {
        // The other wallet moved on since we last looked: used up to 7, with gaps.
        val used = setOf(0, 2, 7)
        val got = Destination.pickUnused(1, 0, emptySet(), ::s) { it[2].toInt() in used }
        assertEquals(listOf(8), got.map { it[2].toInt() })
    }

    @Test fun aSmallerGapLimitIsRespected() {
        // A watch-only wallet scanning with a gap limit of 5: 3 held, 2 more fit, a 3rd would be out of sight.
        val held = (0 until 3).map { s(it).toList() }.toSet()
        assertEquals(listOf(3, 4), Destination.pickUnusedIndexes(2, 0, held, ::s, { false }, gap = 5))
        assertThrows(IllegalArgumentException::class.java) { Destination.pickUnusedIndexes(3, 0, held, ::s, { false }, gap = 5) }
    }

    @Test fun neverPastTheGap() {
        // 18 held by rounds still going: 2 more fit, a 3rd would be out of the wallet's sight.
        val held = (0 until 18).map { s(it).toList() }.toSet()
        assertEquals(2, Destination.pickUnused(2, 0, held, ::s) { false }.size)
        assertThrows(IllegalArgumentException::class.java) { Destination.pickUnused(3, 0, held, ::s) { false } }
    }
    @Test fun aRoundToTheWatchOnlyWalletLooksAtItWhenSentAndWhenConfirmed() {
        val sent = PoolSession.Event.Broadcast("p", "t")
        val confirmed = PoolSession.Event.Confirmed("p", "t")
        assertEquals(Destination.SENT_LOOK_DELAY_MS, Destination.watchLookAfter(sent, Destination.Kind.WATCH))
        assertEquals(0L, Destination.watchLookAfter(confirmed, Destination.Kind.WATCH))
        // Nothing reached it yet on the other steps.
        for (e in listOf(PoolSession.Event.Welcomed("p"), PoolSession.Event.Closing("p", 3), PoolSession.Event.SignNeeded("p", 3),
                         PoolSession.Event.Aborted("p", "x"), PoolSession.Event.Changed("p")))
            assertEquals(null, Destination.watchLookAfter(e, Destination.Kind.WATCH))
        // The hot wallet, Jam and a payment's recipients are not the watch-only wallet.
        for (k in listOf(Destination.Kind.HOT, Destination.Kind.JAM, Destination.Kind.PAY)) {
            assertEquals(null, Destination.watchLookAfter(sent, k))
            assertEquals(null, Destination.watchLookAfter(confirmed, k))
        }
    }
}
