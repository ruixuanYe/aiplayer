package com.aiplayercompanion.ai;

import com.aiplayercompanion.AIPlayerCompanionMod;
import com.aiplayercompanion.config.ModConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class LMStudioClient {
    private static final String SYSTEM_PROMPT = "你是一个 Minecraft AI 陪伴玩家。你的主人正在与你一起进行 Minecraft 生存游戏。你的回复应简短、自然、友好，不超过两句话。你不能声称完成了实际未完成的游戏操作。直接输出最终回复，不要输出推理过程。";
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public CompletableFuture<ChatResponse> chat(String message) {
        return chatWithSystemPrompt(SYSTEM_PROMPT, message, 0.6D);
    }

    public CompletableFuture<ChatResponse> chatWithSystemPrompt(String systemPrompt, String message, double temperature) {
        ModConfig config = ModConfig.get();
        if (!config.aiChatEnabled) {
            return CompletableFuture.completedFuture(ChatResponse.failed("chat disabled"));
        }

        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder()
                    .uri(URI.create(config.lmStudioApiUrl))
                    .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(createRequestBody(config.modelName, systemPrompt, message, temperature)));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(ChatResponse.failed("invalid api url"));
        }

        if (config.apiKey != null && !config.apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + config.apiKey);
        }

        return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return ChatResponse.failed("http " + response.statusCode() + ": " + extractErrorMessage(response.body()));
                    }
                    ChatResponse parsed = parseResponse(response.body());
                    if (!parsed.success() && "empty content".equals(parsed.errorMessage())) {
                        return retryPlainChat(systemPrompt, message).join();
                    }
                    return parsed;
                })
                .exceptionally(error -> {
                    AIPlayerCompanionMod.LOGGER.debug("LM Studio request failed: {}", error.toString());
                    return ChatResponse.failed("request failed");
                });
    }

    public CompletableFuture<Boolean> canConnect() {
        return chat("ping").thenApply(ChatResponse::success).exceptionally(error -> false);
    }

    private CompletableFuture<ChatResponse> retryPlainChat(String systemPrompt, String message) {
        ModConfig config = ModConfig.get();
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder()
                    .uri(URI.create(config.lmStudioApiUrl))
                    .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(createPlainRetryRequestBody(config.modelName, systemPrompt, message)));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(ChatResponse.failed("invalid api url"));
        }

        if (config.apiKey != null && !config.apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + config.apiKey);
        }

        return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return ChatResponse.failed("retry http " + response.statusCode() + ": " + extractErrorMessage(response.body()));
                    }
                    ChatResponse parsed = parseResponse(response.body());
                    if (!parsed.success() && "empty content".equals(parsed.errorMessage())) {
                        return ChatResponse.failed("empty content after retry");
                    }
                    return parsed;
                })
                .exceptionally(error -> ChatResponse.failed("retry request failed"));
    }

    private String createRequestBody(String modelName, String systemPrompt, String message, double temperature) {
        JsonObject root = new JsonObject();
        root.addProperty("model", modelName);
        root.addProperty("temperature", temperature);
        root.addProperty("max_tokens", ModConfig.get().maxTokens);
        if (ModConfig.get().disableThinking) {
            root.addProperty("enable_thinking", false);
            JsonObject chatTemplateKwargs = new JsonObject();
            chatTemplateKwargs.addProperty("enable_thinking", false);
            root.add("chat_template_kwargs", chatTemplateKwargs);
        }

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", systemPrompt == null || systemPrompt.isBlank() ? SYSTEM_PROMPT : systemPrompt);
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", ModConfig.get().disableThinking ? "/no_think\n" + (message == null ? "" : message) : (message == null ? "" : message));
        messages.add(user);
        root.add("messages", messages);
        return root.toString();
    }

    private String createPlainRetryRequestBody(String modelName, String systemPrompt, String message) {
        JsonObject root = new JsonObject();
        root.addProperty("model", modelName);
        root.addProperty("temperature", 0.3D);
        root.addProperty("max_tokens", Math.max(ModConfig.get().maxTokens, 256));
        root.addProperty("enable_thinking", false);
        JsonObject chatTemplateKwargs = new JsonObject();
        chatTemplateKwargs.addProperty("enable_thinking", false);
        root.add("chat_template_kwargs", chatTemplateKwargs);

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", systemPrompt == null || systemPrompt.isBlank() ? SYSTEM_PROMPT : systemPrompt);
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", "/no_think\n你现在必须只输出最终回复，不要解释，不要思考标签。玩家说：" + (message == null ? "" : message));
        messages.add(user);
        root.add("messages", messages);
        return root.toString();
    }

    private ChatResponse parseResponse(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return ChatResponse.failed("missing choices");
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null) {
                return ChatResponse.failed("missing content");
            }
            String content = getString(message, "content");
            if ((content == null || content.isBlank()) && message.has("reasoning_content")) {
                content = getString(message, "reasoning_content");
            }
            if (content == null || content.isBlank()) {
                return ChatResponse.failed("empty content");
            }
            return ChatResponse.ok(cleanModelText(content));
        } catch (Exception e) {
            AIPlayerCompanionMod.LOGGER.debug("Invalid LM Studio response JSON: {}", e.toString());
            return ChatResponse.failed("invalid response");
        }
    }

    private String getString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private String cleanModelText(String text) {
        String cleaned = text == null ? "" : text.strip();
        cleaned = cleaned.replaceAll("(?is)<think>.*?</think>", "").strip();
        cleaned = cleaned.replace("/no_think", "").strip();
        return cleaned;
    }

    private String extractErrorMessage(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject error = root.getAsJsonObject("error");
            if (error != null && error.has("message")) {
                String message = error.get("message").getAsString();
                return message.length() > 180 ? message.substring(0, 180) + "..." : message;
            }
        } catch (Exception ignored) {
        }
        if (body == null || body.isBlank()) {
            return "empty error body";
        }
        String cleaned = body.replace('\n', ' ').replace('\r', ' ').strip();
        return cleaned.length() > 180 ? cleaned.substring(0, 180) + "..." : cleaned;
    }
}
