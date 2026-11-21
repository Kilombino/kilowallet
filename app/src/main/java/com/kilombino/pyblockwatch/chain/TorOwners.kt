package com.kilombino.pyblockwatch.chain

/**
 * The bookkeeping behind [Tor.acquire]/[Tor.release]: a set of named owners, and whether Tor was
 * started the old way ([Tor.start]: up until the process ends). Pure, so it is tested off-device,
 * and shared with the desktop's own Tor object (its tor of the Tor Project instead of Arti).
 */
internal class TorOwners {
    private val held = LinkedHashSet<String>()
    private var forever = false

    /** [owner] holds Tor (twice by the same name counts once). */
    @Synchronized fun acquire(owner: String) { held.add(owner) }

    /** [owner] lets go; true when that was the last owner and Tor may stop now. */
    @Synchronized fun release(owner: String): Boolean = held.remove(owner) && mayStop()

    /** Started the old way: never stopped by a release. */
    @Synchronized fun keepForever() { forever = true }

    /** No owner left and nothing started it the old way. */
    @Synchronized fun mayStop(): Boolean = held.isEmpty() && !forever

    /** [Tor.stop] is refused while anybody holds Tor; an explicit stop ends the old way's hold. */
    @Synchronized fun stopAllowed(): Boolean = if (held.isEmpty()) { forever = false; true } else false

    @Synchronized fun holders(): Set<String> = held.toSet()
}
