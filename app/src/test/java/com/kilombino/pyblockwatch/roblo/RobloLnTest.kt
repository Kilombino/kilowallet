package com.kilombino.pyblockwatch.roblo

import com.kilombino.pyblockwatch.ln.HoldUnknown
import com.kilombino.pyblockwatch.ln.LndError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A held Roblosats payment (bond, escrow) is followed until its outcome is known, never beyond. */
class RobloLnTest {
    @Test fun settledOrFinallyFailedEndsTheFollowUp() {
        assertFalse(RobloLn.keepFollowing(null))                                   // settled: preimage
        assertFalse(RobloLn.keepFollowing(LndError("the payment was cancelled")))   // bond returned
        assertFalse(RobloLn.keepFollowing(LndError("no route to the destination with enough liquidity")))
    }

    @Test fun anUnknownEndIsFollowedAgain() {
        assertTrue(RobloLn.keepFollowing(HoldUnknown("the payment ended without an answer")))  // node stopped
        assertTrue(RobloLn.keepFollowing(HoldUnknown("rpc error: transport is closing")))
    }

    @Test fun aPaymentTheNodeNeverSentIsNotWaitedFor() {
        assertFalse(RobloLn.keepFollowing(HoldUnknown("payment isn't initiated: not found")))
        assertFalse(RobloLn.keepFollowing(HoldUnknown("unknown payment hash")))
    }
}
