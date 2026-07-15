package com.muyuchat.mca;

import com.muyuchat.mca.ISdxlImagePhaseWorkerCallback;

/** One disposable process owns exactly one SDXL QNN runtime profile. */
interface ISdxlImagePhaseWorker {
    boolean execute(String requestJson, ISdxlImagePhaseWorkerCallback callback);
    boolean cancel(String requestId);
}
