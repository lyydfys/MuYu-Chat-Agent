package com.muyuchat.mca;

interface IQwenImage21WorkerCallback {
    void onProgress(String payloadJson);
    void onComplete(String payloadJson);
    void onError(String payloadJson);
}
