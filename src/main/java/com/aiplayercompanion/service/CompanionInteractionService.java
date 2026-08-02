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
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        if (handleInventoryIntent(player, normalized)) {
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
        LM_STUDIO_CLIENT.chatWithSystemPrompt(ACTION_SYSTEM_PROMPT, contentWithBotContext(player, content), 0.2D)
                .thenAccept(response -> player.getServer().execute(() -> handleActionResponse(player, content, response)));
    }

    private static boolean handleInventoryIntent(ServerPlayerEntity player, String content) {
        Optional<AIPlayerBot> maybeBot = AIPlayerBotManager.findOwnedBot(player);
        if (maybeBot.isEmpty()) {
            return false;
        }
        String normalized = content == null ? "" : content.strip().toLowerCase(Locale.ROOT);
        if (isInventoryQuestion(normalized)) {
            player.sendMessage(Text.literal(ModelNameUtil.companionName() + "：").formatted(Formatting.AQUA)
                    .append(Text.literal(inventorySummary(maybeBot.get())).formatted(Formatting.WHITE)), false);
            CompanionLog.player(player, "INVENTORY", "listed bot inventory");
            return true;
        }
        if (!isGiveItemRequest(normalized)) {
            return false;
        }
        giveRequestedItem(player, maybeBot.get(), normalized);
        return true;
    }

    private static boolean isInventoryQuestion(String content) {
        return (content.contains("背包") || content.contains("身上") || content.contains("物品") || content.contains("装备"))
                && (content.contains("有什么") || content.contains("有什么东西") || content.contains("有啥") || content.contains("列表") || content.contains("看一下") || content.contains("看看"));
    }

    private static boolean isGiveItemRequest(String content) {
        return (content.contains("给我") || content.contains("丢给我") || content.contains("扔给我") || content.contains("拿给我") || content.contains("把"))
                && !(content.contains("跟随") || content.contains("过来") || content.contains("等待") || content.contains("停下"));
    }

    private static void giveRequestedItem(ServerPlayerEntity player, AIPlayerBot bot, String request) {
        Optional<Integer> slot = findRequestedItemSlot(bot, request);
        if (slot.isEmpty()) {
            player.sendMessage(Text.literal(ModelNameUtil.companionName() + "：我没找到你要的东西。").formatted(Formatting.YELLOW), false);
            CompanionLog.player(player, "INVENTORY", "give failed: item not found");
            return;
        }

        ItemStack stack = bot.getInventory().removeStack(slot.get());
        if (stack.isEmpty()) {
            player.sendMessage(Text.literal(ModelNameUtil.companionName() + "：这个槽位是空的。").formatted(Formatting.YELLOW), false);
            return;
        }
        ItemEntity dropped = bot.dropItem(stack, false);
        if (dropped != null) {
            dropped.setPickupDelay(0);
            Vec3d target = player.getEyePos().subtract(bot.getEyePos()).normalize().multiply(0.35D);
            dropped.setVelocity(target.x, 0.2D, target.z);
        } else {
            player.giveItemStack(stack);
        }
        bot.getInventory().markDirty();
        player.sendMessage(Text.literal(ModelNameUtil.companionName() + "：给你，" + stack.getName().getString() + "。").formatted(Formatting.AQUA), false);
        CompanionLog.player(player, "INVENTORY", "gave requested item");
    }

    private static Optional<Integer> findRequestedItemSlot(AIPlayerBot bot, String request) {
        int bestSlot = -1;
        int bestScore = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            int score = requestMatchScore(request, stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot >= 0 ? Optional.of(bestSlot) : Optional.empty();
    }

    private static int requestMatchScore(String request, ItemStack stack) {
        String name = normalizeText(stack.getName().getString());
        String id = normalizeText(Registries.ITEM.getId(stack.getItem()).toString());
        String path = normalizeText(Registries.ITEM.getId(stack.getItem()).getPath());
        String normalizedRequest = normalizeText(request);
        if (!name.isBlank() && normalizedRequest.contains(name)) {
            return 100 + name.length();
        }
        if (!path.isBlank() && normalizedRequest.contains(path)) {
            return 90 + path.length();
        }
        if (!id.isBlank() && normalizedRequest.contains(id)) {
            return 80 + id.length();
        }
        return chineseAliasScore(normalizedRequest, path);
    }

    private static int chineseAliasScore(String request, String itemPath) {
        int material = 0;
        if (request.contains("下界合金") && itemPath.contains("netherite")) material = 60;
        else if (request.contains("钻石") && itemPath.contains("diamond")) material = 55;
        else if (request.contains("铁") && itemPath.contains("iron")) material = 50;
        else if (request.contains("石") && itemPath.contains("stone")) material = 45;
        else if (request.contains("金") && itemPath.contains("golden")) material = 40;
        else if ((request.contains("木") || request.contains("木头")) && itemPath.contains("wooden")) material = 35;
        else if (request.contains("锁链") && itemPath.contains("chainmail")) material = 35;
        else if (request.contains("皮革") && itemPath.contains("leather")) material = 30;

        int type = 0;
        if ((request.contains("剑") || request.contains("武器")) && itemPath.contains("sword")) type = 30;
        else if ((request.contains("斧") || request.contains("斧头")) && itemPath.contains("axe")) type = 28;
        else if ((request.contains("头盔") || request.contains("帽子")) && itemPath.contains("helmet")) type = 25;
        else if ((request.contains("胸甲") || request.contains("衣服")) && itemPath.contains("chestplate")) type = 25;
        else if ((request.contains("护腿") || request.contains("裤子")) && itemPath.contains("leggings")) type = 25;
        else if ((request.contains("靴") || request.contains("鞋")) && itemPath.contains("boots")) type = 25;

        if (material > 0 && type > 0) {
            return material + type;
        }
        if (type > 0 && !request.contains("钻石") && !request.contains("铁") && !request.contains("金") && !request.contains("石") && !request.contains("下界合金")) {
            return type;
        }
        return 0;
    }

    private static String inventorySummary(AIPlayerBot bot) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            String name = stack.getName().getString();
            counts.put(name, counts.getOrDefault(name, 0) + stack.getCount());
        }
        if (counts.isEmpty()) {
            return "我背包里现在是空的。";
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((name, count) -> parts.add(name + " x" + count));
        return "我现在有：" + String.join("，", parts) + "。";
    }

    private static String contentWithBotContext(ServerPlayerEntity player, String content) {
        Optional<AIPlayerBot> maybeBot = AIPlayerBotManager.findOwnedBot(player);
        if (maybeBot.isEmpty()) {
            return content;
        }
        return content + "\n\n[本地游戏状态]\nBot 背包：" + inventorySummary(maybeBot.get())
                + "\n注意：你可以根据这个摘要聊天说明自己有什么，但不要声称已经丢出物品；丢物品由本地规则执行。";
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace("minecraft:", "")
                .replace("_", "")
                .replace(" ", "")
                .replace("-", "")
                .strip();
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
