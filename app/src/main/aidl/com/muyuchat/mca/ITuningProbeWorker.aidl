package com.muyuchat.mca;

import com.muyuchat.mca.ITuningProbeWorkerCallback;

/** Executes one persisted load-bound tuning probe in the disposable :tuning process. */
interface ITuningProbeWorker {
    boolean start(String requestJson, ITuningProbeWorkerCallback callback);
    boolean cancel(String requestJson);
}
