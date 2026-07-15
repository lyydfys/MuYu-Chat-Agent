package com.muyuchat.mca;

interface ISdxlImagePhaseWorkerCallback {
    void onProgress(String payloadJson);
    void onComplete(String payloadJson);
    void onError(String payloadJson);
}
