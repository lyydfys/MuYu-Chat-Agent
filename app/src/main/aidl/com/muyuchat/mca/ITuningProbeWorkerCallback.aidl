package com.muyuchat.mca;

interface ITuningProbeWorkerCallback {
    void onProgress(String payloadJson);
    void onComplete(String payloadJson);
    void onError(String payloadJson);
}
