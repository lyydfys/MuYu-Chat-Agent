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
 * The terminal record retains the typed error envelope that settled the attempt, so the snapshot
 * projection keeps the real failure observable after cleanup instead of unconditionally nulling it.
 */
internal fun TerminalDeliveryRecord.toRequestSnapshot(): RequestSnapshot = RequestSnapshot(
    identity = identity,
    state = finalState,
    terminal = true,
    terminalError = terminalError,
    registeredAtEpoch = identity.runtimeEpoch,
    registeredAtGeneration = identity.sessionGeneration
)
