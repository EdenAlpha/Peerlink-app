package com.peerlink.app.service

import kotlin.math.abs

/**
 * Semi-automatic Home/Away suggestion — pure decision logic, no state.
 *
 * The rule was proven against every recorded match: whoever CREATES the room
 * is always Home, never Away (checked against all side locks: evening = the
 * host made the rooms and locked Home, morning = the brother made them and
 * locked Home). The room creator reaches the game's own matchmaking first —
 * it was already waiting in the lobby — so its first STUN binding request to
 * Konami's matchmaking servers is observed earlier than the joiner's. Each
 * phone timestamps its own first call, the two timestamps cross over the
 * match-control channel, and both phones compute the same answer.
 *
 * This object only decides, so unit tests pin the behaviour:
 *  - no timestamp → no suggestion (plain manual H/A stays available),
 *  - gaps below MIN_GAP_MS are clock skew or an ambiguous start — never guess,
 *  - gaps above MAX_GAP_MS are times from an older room — never guess,
 *  - a hold on the card (human disagrees) flips the answer exactly once,
 *    no matter which side held.
 */
object SideSuggestion {

    /** Below this the two wall clocks may simply be skewed; stay silent. */
    const val MIN_GAP_MS = 45_000L

    /** Above this the timestamps belong to different rooms; stay silent. */
    const val MAX_GAP_MS = 30L * 60_000L

    /**
     * @param localFirstMs wall-clock time of this phone's first matchmaking STUN
     * @param peerFirstMs  wall-clock time of the peer's, as sent by the peer
     * @param swapped      true after a hold on the suggestion card
     * @return the side THIS phone should play, or null for no suggestion
     */
    fun compute(
        localFirstMs: Long,
        peerFirstMs: Long,
        swapped: Boolean,
    ): MatchControlChannel.Side? {
        if (localFirstMs <= 0L || peerFirstMs <= 0L) return null
        val gap = abs(localFirstMs - peerFirstMs)
        if (gap < MIN_GAP_MS || gap > MAX_GAP_MS) return null
        // The earlier caller waited in the lobby first: it created the room.
        var side = if (localFirstMs < peerFirstMs) {
            MatchControlChannel.Side.HOME
        } else {
            MatchControlChannel.Side.AWAY
        }
        if (swapped) side = side.opposite()
        return side
    }
}
