package com.aiplayercompanion.ai;

import com.aiplayercompanion.config.AIPlayerCleanConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class LMStudioClient {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private LMStudioClient() {
    }

    public static CompletableFuture<ChatResponse> test() {
        return chat("请回复：连接成功");
    }

    public static CompletableFuture<ChatResponse> chat(String userText) {
        AIPlayerCleanConfig config = AIPlayerCleanConfig.get();
        if (!config.aiChatEnabled) {
            return CompletableFuture.completedFuture(ChatResponse.error("AI 聊天未启用"));
        }
        if (config.apiUrl == null || config.apiUrl.isBlank()) {
            return CompletableFuture.completedFuture(ChatResponse.error("API 地址为空"));
        }
        if (config.modelName == null || config.modelName.isBlank()) {
            return CompletableFuture.completedFuture(ChatResponse.error("模型名称为空"));
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", config.modelName);
        body.addProperty("temperature", 0.7D);
        body.addProperty("stream", false);

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", config.systemPrompt);
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userText);
        messages.add(user);
        body.add("messages", messages);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(config.apiUrl))
                .timeout(Duration.ofSeconds(config.timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
        if (config.apiKey != null && !config.apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + config.apiKey);
        }

        return HTTP.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parse(response.statusCode(), response.body()))
                .exceptionally(error -> ChatResponse.error("LM Studio 请求失败：" + safeError(error)));
    }

    private static ChatResponse parse(int statusCode, String body) {
        if (statusCode < 200 || statusCode >= 300) {
            return ChatResponse.error("HTTP " + statusCode);
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return ChatResponse.error("empty response");
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null || !message.has("content")) {
                return ChatResponse.error("missing message content");
            }
            String content = message.get("content").getAsString().trim();
            if (content.isBlank()) {
                return ChatResponse.error("empty response");
            }
            return ChatResponse.ok(content);
        } catch (RuntimeException e) {
            return ChatResponse.error("JSON 解析失败：" + safeError(e));
        }
    }

    private static String safeError(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message.length() > 120 ? message.substring(0, 120) : message;
    }
}
