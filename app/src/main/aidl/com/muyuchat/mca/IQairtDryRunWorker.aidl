package com.muyuchat.mca;

import com.muyuchat.mca.IQairtDryRunWorkerCallback;

/**
 * Runs a bounded QAIRT create/generate/destroy certification in the dedicated
 * :qairt_smoke process.  The product process only receives serialized events;
 * it never attempts an unverified QAIRT handle itself.
 */
interface IQairtDryRunWorker {
    boolean start(String requestJson, IQairtDryRunWorkerCallback callback);
    boolean cancel(String requestId);
}
