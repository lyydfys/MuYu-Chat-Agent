package com.muyuchat.api.local;

import com.muyuchat.api.local.ITokenCallback;

interface ILocalLlmService {
    String getLoadedModelJson();
    String getParamsJson();
    String getDeviceProfileJson();
    String getAgentRecommendationJson(String requestJson);
    void startChat(String sessionId, String requestJson, ITokenCallback callback);
    void runBenchmark(String requestJson, ITokenCallback callback);
    void stop(String sessionId);
    String getMetricsJson();
}
