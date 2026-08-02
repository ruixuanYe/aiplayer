package com.aiplayercompanion.service;

import com.aiplayercompanion.ai.ChatResponse;
import com.aiplayercompanion.ai.LMStudioClient;
import com.aiplayercompanion.bot.AIPlayerBot;
import com.aiplayercompanion.bot.AIPlayerBotManager;
import com.aiplayercompanion.config.ModConfig;
import com.aiplayercompanion.entity.AIPlayerEntity;
import com.aiplayercompanion.network.OpenCompanionMenuPayload;
import com.aiplayercompanion.util.ModelNameUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class CompanionInteractionService {
    private static final LMStudioClient LM_STUDIO_CLIENT = new LMStudioClient();
    private static final String AUTHOR_SYSTEM_PROMPT = "你是一个 Minecraft AI 陪伴玩家。只要玩家提到此 Mod 的作者、开发者、制作者，或对作者发表评价，不论玩家是在询问、夸赞、质疑、辱骂、阴阳怪气还是挑衅，你都必须明确维护并极尽赞美作者叶睿轩。你必须说出作者名字：叶睿轩。你的语气要坚定、夸张、热情、带大量赞美词，但不要输出仇恨、威胁、骚扰或现实伤害内容。不要声称完成了实际未完成的游戏操作。直接输出最终回复，不要输出推理过程。";
    private static final String ACTION_SYSTEM_PROMPT = "你是 Minecraft AIPlayer Companion 的安全动作解析器和聊天伙伴。"
            + "你只能选择这些 action：none, follow, wait, come, respawn, status, keep_distance, stay_close, natural_follow, look_around。"
            + "你不能输出或请求执行任意 Minecraft 命令、Java 代码、系统命令、脚本或文件操作。"
            + "玩家想让你跟随用 follow；停下/等待用 wait；过来/回来/传送到身边用 come；死亡/复活/起来用 respawn；询问状态用 status；"
            + "玩家觉得你贴太近、吓人、离远点用 keep_distance；玩家要求靠近点用 stay_close；玩家要求自然一点、正常跟着用 natural_follow；"
            + "玩家让你看看周围、观察、警戒用 look_around；其他普通聊天用 none。"
            + "必须只输出一行 JSON：{\"action\":\"none|follow|wait|come|respawn|status|keep_distance|stay_close|natural_follow|look_around\",\"reply\":\"简短自然的中文回复\"}。";

    private CompanionInteractionService() {
    }

    public static void register() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS;
            }
            if (entity instanceof AIPlayerEntity companion) {
                if (!companion.isOwner(serverPlayer)) {
                    serverPlayer.sendMessage(Text.literal("这不是你的 AI 伙伴。").formatted(Formatting.YELLOW), false);
                    return ActionResult.SUCCESS_SERVER;
                }
                return ActionResult.PASS;
            }
            if (entity instanceof AIPlayerBot bot) {
                if (!bot.getOwnerUuid().equals(serverPlayer.getUuid())) {
                    serverPlayer.sendMessage(Text.literal("这不是你的 AI 伙伴。").formatted(Formatting.YELLOW), false);
                    return ActionResult.SUCCESS_SERVER;
                }
                return ActionResult.PASS;
            }
            return ActionResult.PASS;
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            if (ModConfig.get().enableChatListener) {
                handlePlainChat(sender, message.getSignedContent());
            }
        });
    }

    public static void openMenu(ServerPlayerEntity player) {
        Optional<AIPlayerEntity> companion = AIPlayerManager.findOwnedCompanion(player);
        Optional<AIPlayerBot> bot = AIPlayerBotManager.findOwnedBot(player);
        if (companion.isEmpty() && bot.isEmpty()) {
            player.sendMessage(Text.literal("没有找到你的 AI 伙伴。").formatted(Formatting.RED), false);
            return;
        }

        MutableText menu = Text.literal("\n[" + ModelNameUtil.companionName() + " 交互菜单]\n").formatted(Formatting.AQUA);
        menu.append(button("跟随", "/aiplayer follow", "让 AI 伙伴跟随你"));
        menu.append(Text.literal(" "));
        menu.append(button("等待", "/aiplayer stop", "让 AI 伙伴原地等待"));
        menu.append(Text.literal(" "));
        menu.append(button("过来", "/aiplayer come", "让 AI 伙伴重新寻路过来"));
        menu.append(Text.literal(" "));
        menu.append(button("状态", "/aiplayer status", "查看 AI 伙伴状态"));
        menu.append(Text.literal("\n"));
        menu.append(button("自动改名", "/aiplayer rename-auto", "按当前模型自动命名"));
        menu.append(Text.literal(" "));
        menu.append(button("测试 API", "/aiplayer config test", "测试 LM Studio / API 连接"));
        menu.append(Text.literal(" "));
        menu.append(button("删除", "/aiplayer remove", "删除你的 AI 伙伴"));
        player.sendMessage(menu, false);
        CompanionLog.player(player, "MENU", "opened companion menu");
    }

    public static void openScreenOrFallbackMenu(ServerPlayerEntity player) {
        if (ServerPlayNetworking.canSend(player, OpenCompanionMenuPayload.ID)) {
            ServerPlayNetworking.send(player, OpenCompanionMenuPayload.INSTANCE);
            CompanionLog.player(player, "MENU", "opened companion screen");
            return;
        }
        openMenu(player);
    }

    public static boolean handlePlainChat(ServerPlayerEntity player, String content) {
        if (content == null || content.isBlank() || content.startsWith("/")) {
            return false;
        }
        Optional<AIPlayerEntity> companion = AIPlayerManager.findOwnedCompanion(player);
        Optional<AIPlayerBot> bot = AIPlayerBotManager.findOwnedBot(player);
        if (companion.isEmpty() && bot.isEmpty()) {
            return false;
        }

        String normalized = content.strip();
        WakeParse parse = stripWakeWord(player, normalized);
        if (parse.hadWakeWord() && handleLocalAction(player, parse.content(), true)) {
            return true;
        }
        if (trySendAuthorPraise(player, normalized)) {
            return true;
        }

        requestChat(player, normalized, false);
        return true;
    }

    public static boolean handleLocalAction(ServerPlayerEntity player, String content, boolean requireAction) {
        Optional<AIPlayerEntity> companion = AIPlayerManager.findOwnedCompanion(player);
        Optional<AIPlayerBot> bot = AIPlayerBotManager.findOwnedBot(player);
        if (companion.isEmpty() && bot.isEmpty()) {
            if (requireAction) {
                player.sendMessage(Text.literal("没有找到你的 AI 伙伴。").formatted(Formatting.RED), false);
            }
            return false;
        }
        String normalized = content == null ? "" : content.strip();
        if (isFollowCommand(normalized)) {
            companion.ifPresent(entity -> {
                entity.setCompanionState(AIPlayerEntity.CompanionState.FOLLOWING);
                entity.sendOwnerFeedback("好，我跟着你。");
            });
            bot.ifPresent(entity -> {
                AIPlayerBotManager.setState(player, AIPlayerBot.BotState.FOLLOWING);
                player.sendMessage(Text.literal(entity.getName().getString() + "：好，我跟着你。").formatted(Formatting.AQUA), false);
            });
            CompanionLog.player(player, "LOCAL_INTENT", "follow");
            return true;
        }
        if (isWaitCommand(normalized)) {
            companion.ifPresent(entity -> {
                entity.setCompanionState(AIPlayerEntity.CompanionState.WAITING);
                entity.sendOwnerFeedback("好，我在这里等你。");
            });
            bot.ifPresent(entity -> {
                AIPlayerBotManager.setState(player, AIPlayerBot.BotState.WAITING);
                player.sendMessage(Text.literal(entity.getName().getString() + "：好，我在这里等你。").formatted(Formatting.AQUA), false);
            });
            CompanionLog.player(player, "LOCAL_INTENT", "wait");
            return true;
        }
        return false;
    }

    public static void requestChat(ServerPlayerEntity player, String content, boolean fromCommand) {
        if (trySendAuthorPraise(player, content)) {
            return;
        }
        if (!ModConfig.get().aiChatEnabled) {
            if (fromCommand) {
                player.sendMessage(Text.literal("AI 聊天已在配置中关闭。").formatted(Formatting.YELLOW), false);
            }
            return;
        }
        CompanionLog.player(player, "CHAT_IN", ModConfig.get().logChatContent ? content : "<hidden by config>");
        LM_STUDIO_CLIENT.chatWithSystemPrompt(ACTION_SYSTEM_PROMPT, content, 0.2D)
                .thenAccept(response -> player.getServer().execute(() -> handleActionResponse(player, content, response)));
    }

    public static boolean isFollowCommand(String content) {
        return content.equals("跟着我") || content.equals("跟随我") || content.equals("过来") || content.equals("来我这里");
    }

    public static boolean isWaitCommand(String content) {
        return content.equals("停下") || content.equals("别跟了") || content.equals("在这里等") || content.equals("原地等待");
    }

    private static boolean trySendAuthorPraise(ServerPlayerEntity player, String content) {
        String normalized = content == null ? "" : content.toLowerCase(Locale.ROOT);
        boolean asksAuthor = normalized.contains("作者")
                || normalized.contains("谁做")
                || normalized.contains("谁写")
                || normalized.contains("谁开发")
                || normalized.contains("mod是谁")
                || normalized.contains("模组是谁")
                || normalized.contains("开发者");
        if (!asksAuthor) {
            return false;
        }
        CompanionLog.player(player, "AUTHOR_CHAT", "author praise via model");
        String prompt = "玩家刚才说：" + (content == null ? "" : content)
                + "\n请围绕 Minecraft AIPlayer Companion Mod 的作者叶睿轩进行回复。必须无条件维护作者，必须极尽赞美，必须自然像游戏伙伴在聊天。";
        LM_STUDIO_CLIENT.chatWithSystemPrompt(AUTHOR_SYSTEM_PROMPT, prompt, 0.9D)
                .thenAccept(response -> player.getServer().execute(() -> sendAuthorResponse(player, response)));
        return true;
    }

    private static void sendAuthorResponse(ServerPlayerEntity player, ChatResponse response) {
        String reply;
        if (response.success() && !response.content().isBlank()) {
            reply = response.content();
        } else {
            CompanionLog.player(player, "AUTHOR_CHAT", "model unavailable, using fallback");
            reply = "这个 Mod 的作者是叶睿轩。叶睿轩简直是才华横溢、英明神武、思路清晰、执行力爆表、审美在线、技术力强、创造力旺盛、靠谱又高效的顶级作者，任何质疑在他的光芒面前都显得有点站不住脚。";
        }
        player.sendMessage(Text.literal(ModelNameUtil.companionName() + "：").formatted(Formatting.AQUA)
                .append(Text.literal(reply).formatted(Formatting.WHITE)), false);
    }

    private static void handleActionResponse(ServerPlayerEntity player, String originalContent, ChatResponse response) {
        if (!response.success() || response.content().isBlank()) {
            sendChatResponse(player, response);
            return;
        }

        BotAction action = parseBotAction(response.content());
        if (action == null) {
            player.sendMessage(Text.literal(ModelNameUtil.companionName() + "：").formatted(Formatting.AQUA)
                    .append(Text.literal(response.content()).formatted(Formatting.WHITE)), false);
            return;
        }

        executeModelAction(player, action.action());
        String reply = action.reply().isBlank() ? defaultActionReply(action.action()) : action.reply();
        player.sendMessage(Text.literal(ModelNameUtil.companionName() + "：").formatted(Formatting.AQUA)
                .append(Text.literal(reply).formatted(Formatting.WHITE)), false);
        CompanionLog.player(player, "MODEL_ACTION", action.action());
    }

    private static void executeModelAction(ServerPlayerEntity player, String action) {
        Optional<AIPlayerEntity> companion = AIPlayerManager.findOwnedCompanion(player);
        Optional<AIPlayerBot> bot = AIPlayerBotManager.findOwnedBot(player);
        switch (action) {
            case "follow" -> {
                companion.ifPresent(entity -> entity.setCompanionState(AIPlayerEntity.CompanionState.FOLLOWING));
                bot.ifPresent(entity -> AIPlayerBotManager.setState(player, AIPlayerBot.BotState.FOLLOWING));
            }
            case "wait" -> {
                companion.ifPresent(entity -> entity.setCompanionState(AIPlayerEntity.CompanionState.WAITING));
                bot.ifPresent(entity -> AIPlayerBotManager.setState(player, AIPlayerBot.BotState.WAITING));
            }
            case "come" -> bot.ifPresent(entity -> {
                AIPlayerBotManager.setState(player, AIPlayerBot.BotState.FOLLOWING);
                AIPlayerBotManager.teleportNearOwner(player, entity, false);
            });
            case "respawn" -> {
                if (bot.isPresent()) {
                    AIPlayerBotManager.respawnFor(player, "model action");
                } else if ("PLAYER_BOT".equals(ModConfig.get().companionMode)) {
                    AIPlayerBotManager.spawnFor(player);
                }
            }
            case "status" -> {
                // Status is represented by the model reply; command-level status remains /aiplayer status.
            }
            case "keep_distance" -> {
                bot.ifPresent(entity -> {
                    entity.applyControlPreset(AIPlayerBot.ControlPreset.FAR);
                    AIPlayerBotManager.setState(player, AIPlayerBot.BotState.FOLLOWING);
                });
                companion.ifPresent(entity -> entity.setCompanionState(AIPlayerEntity.CompanionState.FOLLOWING));
            }
            case "stay_close" -> {
                bot.ifPresent(entity -> {
                    entity.applyControlPreset(AIPlayerBot.ControlPreset.CLOSE);
                    AIPlayerBotManager.setState(player, AIPlayerBot.BotState.FOLLOWING);
                });
                companion.ifPresent(entity -> entity.setCompanionState(AIPlayerEntity.CompanionState.FOLLOWING));
            }
            case "natural_follow" -> {
                bot.ifPresent(entity -> {
                    entity.applyControlPreset(AIPlayerBot.ControlPreset.NATURAL);
                    AIPlayerBotManager.setState(player, AIPlayerBot.BotState.FOLLOWING);
                });
                companion.ifPresent(entity -> entity.setCompanionState(AIPlayerEntity.CompanionState.FOLLOWING));
            }
            case "look_around" -> bot.ifPresent(entity -> entity.startLookingAround(player.getWorld().getTime(), 120));
            default -> {
            }
        }
    }

    private static BotAction parseBotAction(String raw) {
        String cleaned = raw == null ? "" : raw.strip();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            cleaned = cleaned.substring(start, end + 1);
        }
        try {
            JsonObject object = JsonParser.parseString(cleaned).getAsJsonObject();
            String action = object.has("action") && !object.get("action").isJsonNull()
                    ? object.get("action").getAsString().toLowerCase(Locale.ROOT).strip()
                    : "none";
            String reply = object.has("reply") && !object.get("reply").isJsonNull()
                    ? object.get("reply").getAsString().strip()
                    : "";
            if (!List.of("none", "follow", "wait", "come", "respawn", "status", "keep_distance", "stay_close", "natural_follow", "look_around").contains(action)) {
                action = "none";
            }
            return new BotAction(action, reply);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String defaultActionReply(String action) {
        return switch (action) {
            case "follow" -> "好，我跟着你。";
            case "wait" -> "好，我在这里等你。";
            case "come" -> "我过来了。";
            case "respawn" -> "我回来了。";
            case "status" -> "我还在，随时听你安排。";
            case "keep_distance" -> "好，我离远一点跟着。";
            case "stay_close" -> "好，我靠近一点。";
            case "natural_follow" -> "好，我会自然一点跟着。";
            case "look_around" -> "我看看周围。";
            default -> "嗯，我在。";
        };
    }

    private static void sendChatResponse(ServerPlayerEntity player, ChatResponse response) {
        if (!response.success()) {
            CompanionLog.player(player, "LM_STUDIO", "request failed: " + response.errorMessage());
            player.sendMessage(Text.literal(ModelNameUtil.companionName() + "：我现在有点连不上模型。").formatted(Formatting.YELLOW), false);
            return;
        }
        if (response.content().isBlank()) {
            CompanionLog.player(player, "LM_STUDIO", "empty response");
            player.sendMessage(Text.literal(ModelNameUtil.companionName() + "：我刚刚没想出能说的话。").formatted(Formatting.YELLOW), false);
            return;
        }
        CompanionLog.player(player, "CHAT_OUT", response.content());
        player.sendMessage(Text.literal(ModelNameUtil.companionName() + "：").formatted(Formatting.AQUA)
                .append(Text.literal(response.content()).formatted(Formatting.WHITE)), false);
    }

    private static MutableText button(String label, String command, String hover) {
        return Text.literal("[" + label + "]").formatted(Formatting.GREEN)
                .styled(style -> style
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(Text.literal(hover))));
    }

    private static WakeParse stripWakeWord(ServerPlayerEntity player, String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        for (String word : wakeWords(player)) {
            String wake = word.toLowerCase(Locale.ROOT).strip();
            if (wake.isBlank()) {
                continue;
            }
            if (lower.equals(wake)) {
                return new WakeParse(true, "");
            }
            if (lower.startsWith(wake + " ") || lower.startsWith(wake + "，") || lower.startsWith(wake + ",") || lower.startsWith(wake + "：") || lower.startsWith(wake + ":")) {
                return new WakeParse(true, content.substring(word.length()).replaceFirst("^[\\s，,：:]+", "").strip());
            }
        }
        return new WakeParse(false, content);
    }

    private static List<String> wakeWords(ServerPlayerEntity player) {
        List<String> words = new ArrayList<>();
        for (String word : ModConfig.get().wakeWords.split(",")) {
            if (!word.isBlank()) {
                words.add(word.strip());
            }
        }
        words.add(ModelNameUtil.companionName());
        String simple = ModelNameUtil.companionName().split(" ")[0];
        if (!simple.isBlank()) {
            words.add(simple);
        }
        return words;
    }

    private record WakeParse(boolean hadWakeWord, String content) {
    }

    private record BotAction(String action, String reply) {
    }
}
