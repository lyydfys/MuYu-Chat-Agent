package com.muyuchat.mca.facade

import com.muyuchat.core.engine.runtime.RequestSnapshot
import com.muyuchat.core.engine.runtime.TerminalDeliveryRecord

/**
 * Read model shared by the chat and image facades.
 *
 * A settled attempt releases its live registry entry as part of terminal cleanup, but the bounded
 * outcome record is retained by the callback router. Projecting that record back onto the public
 * [RequestSnapshot] shape keeps the existing "query the state of a request" contract answering after
 * cleanup instead of returning null, without making the live registry index retain finished work.
 *
 * The projection is deliberately lossy in one field: [TerminalDeliveryRecord] carries no error
 * envelope, so `terminalError` is null. That matches the behaviour before cleanup existed -- the
 * settlement paths that reach here never passed an error envelope either.
 */
internal fun TerminalDeliveryRecord.toRequestSnapshot(): RequestSnapshot = RequestSnapshot(
    identity = identity,
    state = finalState,
    terminal = true,
    terminalError = null,
    registeredAtEpoch = identity.runtimeEpoch,
    registeredAtGeneration = identity.sessionGeneration
)
