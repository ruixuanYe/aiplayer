package com.aiplayercompanion.ai;

public record ChatResponse(boolean ok, String content, String error) {
    public static ChatResponse ok(String content) {
        return new ChatResponse(true, content, "");
    }

    public static ChatResponse error(String error) {
        return new ChatResponse(false, "", error);
    }
}
