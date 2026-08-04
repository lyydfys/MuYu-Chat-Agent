package com.muyuchat.mca;

import com.muyuchat.mca.ILocalImageWorkerCallback;

interface ILocalImageWorker {
    void begin(String requestJson);
    boolean cancel(String requestJson);
    boolean cancelAndExit(String requestJson);
    boolean generate(String requestJson, ILocalImageWorkerCallback callback);
    boolean upscale(String requestJson, ILocalImageWorkerCallback callback);
}
