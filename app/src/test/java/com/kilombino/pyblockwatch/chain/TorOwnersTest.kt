package com.kilombino.pyblockwatch.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Tor ownership contract ([Tor.acquire]/[Tor.release]) behind the ROBLO tab. */
class TorOwnersTest {
    @Test fun theLastOwnerToLeaveStopsTor() {
        val o = TorOwners()
        o.acquire("roblo"); o.acquire("other")
        assertFalse(o.release("roblo"))      // "other" still needs it
        assertTrue(o.release("other"))       // nobody left
        assertTrue(o.mayStop())
    }

    @Test fun theSameOwnerTwiceCountsOnce() {
        val o = TorOwners()
        o.acquire("roblo"); o.acquire("roblo")
        assertTrue(o.release("roblo"))
        assertEquals(emptySet<String>(), o.holders())
    }

    @Test fun releasingWhatWasNeverHeldStopsNothing() {
        val o = TorOwners()
        o.acquire("roblo")
        assertFalse(o.release("nobody"))
        assertEquals(setOf("roblo"), o.holders())
    }

    /** Existing users: Tor started the old way (a .onion node, Electrum, Lightning…) stays up for good. */
    @Test fun startedTheOldWayItIsNeverStoppedByARelease() {
        val o = TorOwners()
        o.keepForever()
        o.acquire("roblo")
        assertFalse(o.release("roblo"))
        assertFalse(o.mayStop())
        // …and when ROBLO came first and the old way later, the same.
        val p = TorOwners()
        p.acquire("roblo"); p.keepForever()
        assertFalse(p.release("roblo"))
    }

    @Test fun anExplicitStopIsRefusedWhileSomebodyHoldsTor() {
        val o = TorOwners()
        o.keepForever(); o.acquire("roblo")
        assertFalse(o.stopAllowed())
        o.release("roblo")
        assertTrue(o.stopAllowed())          // nobody holds it: stop, and the old way's hold ends
        assertTrue(o.mayStop())
    }

    @Test fun ownersFromManyThreads() {
        val o = TorOwners()
        val threads = (0 until 8).map { i -> Thread { repeat(1000) { o.acquire("t$i"); o.release("t$i") } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertTrue(o.holders().isEmpty())
        assertTrue(o.mayStop())
    }
}
