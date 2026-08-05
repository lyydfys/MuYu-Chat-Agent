package com.muyuchat.mca;

import android.os.ParcelFileDescriptor;

/**
 * Process boundary for ordinary local text runtimes.  The protocol deliberately
 * carries begin requests through a read-only file descriptor so large prompts
 * never approach Binder's transaction-size limit.
 */
interface ILocalChatWorker {
    void initRuntime(String runtimeName, String nativeLibDir);
    int loadModel(String runtimeName, String modelPath, String paramsJson);
    void unloadModel();
    int beginCompletion(in ParcelFileDescriptor requestPayload);
    int beginCompletionWithPrefixCache(in ParcelFileDescriptor requestPayload);
    String getPrefillProgressJson();
    void resetPrefillProgress();
    String generateNextChunk();
    void invalidateConversationContext();
    void requestStop();
    boolean requestStopIfActive();
    String getRuntimeStatsJson();
    oneway void shutdown();
}
