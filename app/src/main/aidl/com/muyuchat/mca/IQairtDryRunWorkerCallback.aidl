package com.muyuchat.mca;

interface IQairtDryRunWorkerCallback {
    void onProgress(String payloadJson);
    void onComplete(String payloadJson);
    void onError(String payloadJson);
}
