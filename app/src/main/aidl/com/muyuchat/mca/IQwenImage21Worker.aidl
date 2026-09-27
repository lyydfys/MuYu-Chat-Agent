package com.muyuchat.mca;

import com.muyuchat.mca.IQwenImage21WorkerCallback;

interface IQwenImage21Worker {
    void load(String requestJson, IQwenImage21WorkerCallback callback);
    void generate(String requestJson, IQwenImage21WorkerCallback callback);
    boolean cancel(String requestId);
    void unload(String requestId, IQwenImage21WorkerCallback callback);
    String status();
}
