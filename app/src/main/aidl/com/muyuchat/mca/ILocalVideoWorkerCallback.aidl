package com.muyuchat.mca;

/**
 * Worker → owner events for one attempt (proposal §7.3). Stage events carry the attempt's
 * identity implicitly (the callback the owner registered for that attempt); the owner re-checks
 * attempt identity before writing any state, so a late or stale worker event can never move a
 * newer attempt.
 */
interface ILocalVideoWorkerCallback {

    /** A real pipeline stage. hasStep=false means no measured step progress (never fake numbers). */
    void onStage(in String stage, in long step, in long stepTotal, in boolean hasStep);

    /** Terminal outcome of the attempt: succeeded | failed | stopped. */
    void onOutcome(in String outcomeKind, in String errorCode, in String detail);
}
