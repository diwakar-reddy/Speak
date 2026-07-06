package com.apps.dsimpletools.speak.speaker

/**
 * Pure session-level resolution of held short segments — no Android/native dependencies, fully
 * unit-testable.
 *
 * Short segments (< [SpeakerPolicy.MIN_GATED_SEGMENT_SEC]) cannot be gated by embedding similarity
 * (see [SpeakerReason.BYPASS]), so [SherpaAsrEngine][com.apps.dsimpletools.speak.asr.SherpaAsrEngine]
 * decodes them and HOLDS their text. At session finish this policy decides whether that held text
 * should be included in the final transcript, using the outcomes of the long segments — which
 * CAM++ *can* gate reliably — as the signal for who was speaking:
 *
 *  - >= 1 long segment ACCEPTED as the owner  -> the owner was clearly present: include the held
 *    short segments (a brief foreign interjection during an owner session can ride along — an
 *    accepted trade-off).
 *  - NO long segments at all (e.g. a single-word dictation "much")  -> a deliberate tap by the
 *    owner: include the held short segments. This is the reported-bug case the old threshold
 *    relaxation could never fix.
 *  - Long segments present but ALL rejected (sustained foreign speech)  -> drop the held short
 *    segments too; nobody the gate trusts was speaking.
 */
object SessionGatePolicy {

    /**
     * Whether held short segments should be included in the final transcript.
     *
     * @param longAccepted number of long (gated) segments accepted as the owner this session.
     * @param longRejected number of long (gated) segments rejected as foreign this session.
     */
    fun includeHeldShortSegments(longAccepted: Int, longRejected: Int): Boolean =
        longAccepted > 0 || longRejected == 0
}
