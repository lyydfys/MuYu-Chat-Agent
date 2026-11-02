package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.runtime.workflow.WorkflowCheckpointSink
import com.muyuchat.core.agent.runtime.workflow.WorkflowCheckpointSource

/** One frame's durable checkpoint pair (load + CAS save). */
interface WorkflowCheckpointStore : WorkflowCheckpointSource, WorkflowCheckpointSink

/**
 * Creates the checkpoint store for one frame key.
 *
 * The workflow service already derives a task-root key plus a legacy run-id key per execution; this
 * factory lets the composition root choose the durable backend (Room in production, files in tests
 * and for legacy import) without teaching the service about SQLite or files.
 */
fun interface WorkflowCheckpointStoreFactory {
    fun create(key: String, legacyKey: String?): WorkflowCheckpointStore
}
