package com.muyuchat.api.local;

interface ITokenCallback {
    void onChunk(String sessionId, String text);
    void onDone(String sessionId);
    void onError(String sessionId, String message);
}
