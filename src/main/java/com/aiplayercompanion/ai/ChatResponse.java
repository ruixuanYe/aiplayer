package com.aiplayercompanion.ai;

public record ChatResponse(boolean success, String content, String errorMessage) {
    public static ChatResponse ok(String content) {
        return new ChatResponse(true, content == null ? "" : content.strip(), "");
    }

    public static ChatResponse failed(String errorMessage) {
        return new ChatResponse(false, "", errorMessage == null ? "unknown error" : errorMessage);
    }
}
