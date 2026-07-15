package com.muyuchat.mca;

interface ILocalImageWorkerCallback {
    void onProgress(String payloadJson);
    void onComplete(String payloadJson);
    void onError(String payloadJson);
}
