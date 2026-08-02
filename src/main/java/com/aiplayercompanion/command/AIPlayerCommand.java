package com.aiplayercompanion.command;

import com.aiplayercompanion.ai.ChatResponse;
import com.aiplayercompanion.ai.LMStudioClient;
import com.aiplayercompanion.AIPlayerCompanionMod;
import com.aiplayercompanion.bot.AIPlayerBot;
import com.aiplayercompanion.bot.AIPlayerBotManager;
import com.aiplayercompanion.bot.BotInventoryView;
import com.aiplayercompanion.config.ModConfig;
import com.aiplayercompanion.entity.AIPlayerEntity;
import com.aiplayercompanion.service.AIPlayerManager;
import com.aiplayercompanion.service.CompanionLog;
import com.aiplayercompanion.service.CompanionInteractionService;
import com.aiplayercompanion.util.ModelNameUtil;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class AIPlayerCommand {
    private static final LMStudioClient LM_STUDIO_CLIENT = new LMStudioClient();

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(literal("aiplayer")
                .requires(source -> source.hasPermissionLevel(0))
                .then(literal("spawn").executes(context -> spawn(context.getSource())))
                .then(literal("follow").executes(context -> setState(context.getSource(), AIPlayerEntity.CompanionState.FOLLOWING, "AI 伙伴开始跟随你。")))
                .then(literal("stop").executes(context -> setState(context.getSource(), AIPlayerEntity.CompanionState.WAITING, "AI 伙伴正在原地等待。")))
                .then(literal("come").executes(context -> come(context.getSource())))
                .then(literal("remove").executes(context -> remove(context.getSource())))
                .then(literal("menu").executes(context -> menu(context.getSource())))
                .then(literal("inventory").executes(context -> inventory(context.getSource())))
                .then(literal("rename-auto").executes(context -> renameAuto(context.getSource())))
                .then(literal("status").executes(context -> status(context.getSource())))
                .then(literal("behavior")
                        .then(literal("combat").executes(context -> toggleBehavior(context.getSource(), "combat")))
                        .then(literal("protect").executes(context -> toggleBehavior(context.getSource(), "protect")))
                        .then(literal("pickup").executes(context -> toggleBehavior(context.getSource(), "pickup")))
                        .then(literal("equip").executes(context -> toggleBehavior(context.getSource(), "equip")))
                        .then(literal("weapon").executes(context -> toggleBehavior(context.getSource(), "weapon"))))
                .then(literal("config")
                        .then(literal("show").executes(context -> configShow(context.getSource())))
                        .then(literal("api").then(argument("url", StringArgumentType.greedyString()).executes(context -> configApi(
                                context.getSource(),
                                StringArgumentType.getString(context, "url")
                        ))))
                        .then(literal("model").then(argument("model", StringArgumentType.greedyString()).executes(context -> configModel(
                                context.getSource(),
                                StringArgumentType.getString(context, "model")
                        ))))
                        .then(literal("apikey").then(argument("key", StringArgumentType.greedyString()).executes(context -> configApiKey(
                                context.getSource(),
                                StringArgumentType.getString(context, "key")
                        ))))
                        .then(literal("apikey-clear").executes(context -> configApiKey(context.getSource(), "")))
                        .then(literal("chat")
                                .then(literal("on").executes(context -> configChat(context.getSource(), true)))
                                .then(literal("off").executes(context -> configChat(context.getSource(), false))))
                        .then(literal("timeout").then(argument("seconds", IntegerArgumentType.integer(1, 120)).executes(context -> configTimeout(
                                context.getSource(),
                                IntegerArgumentType.getInteger(context, "seconds")
                        ))))
                        .then(literal("max_tokens").then(argument("tokens", IntegerArgumentType.integer(32, 4096)).executes(context -> configMaxTokens(
                                context.getSource(),
                                IntegerArgumentType.getInteger(context, "tokens")
                        ))))
                        .then(literal("test").executes(context -> configTest(context.getSource()))))
                .then(literal("debug")
                        .then(literal("on").executes(context -> debug(context.getSource(), true)))
                        .then(literal("off").executes(context -> debug(context.getSource(), false))))
                .then(literal("logs").executes(context -> logs(context.getSource(), 10))
                        .then(argument("count", IntegerArgumentType.integer(1, 50)).executes(context -> logs(
                                context.getSource(),
                                IntegerArgumentType.getInteger(context, "count")
                        ))))
                .then(literal("chat").then(argument("content", StringArgumentType.greedyString()).executes(context -> chat(
                        context.getSource(),
                        StringArgumentType.getString(context, "content")
                ))))));
    }

    private static int spawn(ServerCommandSource source) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        if (usesPlayerBot()) {
            try {
                if (AIPlayerBotManager.findOwnedBot(player).isPresent()) {
                    player.sendMessage(Text.literal("你已经拥有一个 AI 玩家伙伴了。").formatted(Formatting.YELLOW), false);
                    return 0;
                }
                if (AIPlayerManager.findOwnedCompanion(player).isPresent()) {
                    player.sendMessage(Text.literal("检测到旧 NPC 伙伴仍在世界中；已继续生成新的玩家 Bot。").formatted(Formatting.YELLOW), false);
                }
                AIPlayerBotManager.spawnFor(player);
                player.sendMessage(Text.literal("AI 玩家伙伴已生成。它现在是真正的服务端玩家实体。").formatted(Formatting.GREEN), false);
                return 1;
            } catch (Throwable throwable) {
                AIPlayerCompanionMod.LOGGER.error("Failed to spawn AI player bot", throwable);
                player.sendMessage(Text.literal("AI 玩家 Bot 生成失败，详细原因已写入 latest.log。").formatted(Formatting.RED), false);
                return 0;
            }
        }
        if (AIPlayerManager.findOwnedCompanion(player).isPresent()) {
            source.sendFeedback(() -> Text.literal("你已经拥有一个 AI 伙伴了。").formatted(Formatting.YELLOW), false);
            CompanionLog.player(player, "COMMAND", "/aiplayer spawn blocked: companion already exists");
            return 0;
        }
        AIPlayerManager.spawnFor(player);
        CompanionLog.player(player, "COMMAND", "/aiplayer spawn -> created companion");
        source.sendFeedback(() -> Text.literal("AI 伙伴已生成。").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int menu(ServerCommandSource source) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        CompanionInteractionService.openScreenOrFallbackMenu(player);
        return 1;
    }

    private static int inventory(ServerCommandSource source) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        Optional<AIPlayerBot> bot = AIPlayerBotManager.findOwnedBot(player);
        if (bot.isEmpty()) {
            source.sendFeedback(() -> Text.literal("没有找到你的 AI 玩家 Bot。").formatted(Formatting.RED), false);
            return 0;
        }
        AIPlayerBot entity = bot.get();
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, p) -> GenericContainerScreenHandler.createGeneric9x6(syncId, inventory, new BotInventoryView(entity)),
                Text.literal(entity.getName().getString() + " 背包")
        ));
        CompanionLog.player(player, "INVENTORY", "opened bot inventory screen");
        return 1;
    }

    private static int renameAuto(ServerCommandSource source) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        if (usesPlayerBot()) {
            ModConfig.get().autoNameFromModel = true;
            ModConfig.save();
            source.sendFeedback(() -> Text.literal("已启用按模型自动命名。玩家 Bot 的原版名字需要删除后重新生成才会改变。").formatted(Formatting.GREEN), false);
            return 1;
        }
        Optional<AIPlayerEntity> companion = AIPlayerManager.findOwnedCompanion(player);
        if (companion.isEmpty()) {
            source.sendFeedback(() -> Text.literal("没有找到你的 AI 伙伴。").formatted(Formatting.RED), false);
            return 0;
        }
        ModConfig.get().autoNameFromModel = true;
        ModConfig.save();
        companion.get().refreshDisplayNameFromConfig();
        source.sendFeedback(() -> Text.literal("AI 伙伴已按模型自动命名为：" + ModelNameUtil.companionName()).formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int configShow(ServerCommandSource source) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        ModConfig config = ModConfig.get();
        String apiKeyStatus = config.apiKey == null || config.apiKey.isBlank() ? "未设置" : "已设置";
        String text = "AI API 配置：\n"
                + "API 地址：" + config.lmStudioApiUrl + "\n"
                + "模型：" + config.modelName + "\n"
                + "显示名：" + ModelNameUtil.companionName() + "\n"
                + "Max Tokens：" + config.maxTokens + "\n"
                + "超时：" + config.requestTimeoutSeconds + " 秒\n"
                + "聊天：" + (config.aiChatEnabled ? "开启" : "关闭") + "\n"
                + "API Key：" + apiKeyStatus;
        source.sendFeedback(() -> Text.literal(text).formatted(Formatting.AQUA), false);
        CompanionLog.player(player, "CONFIG", "show api config");
        return 1;
    }

    private static int toggleBehavior(ServerCommandSource source, String key) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        ModConfig config = ModConfig.get();
        String label;
        boolean enabled;
        switch (key) {
            case "combat" -> {
                config.botAutoCombat = !config.botAutoCombat;
                enabled = config.botAutoCombat;
                label = "主动攻击";
            }
            case "protect" -> {
                config.botProtectOwner = !config.botProtectOwner;
                enabled = config.botProtectOwner;
                label = "保护主人";
            }
            case "pickup" -> {
                config.botAutoPickup = !config.botAutoPickup;
                enabled = config.botAutoPickup;
                label = "自动拾取";
            }
            case "equip" -> {
                config.botAutoEquip = !config.botAutoEquip;
                enabled = config.botAutoEquip;
                label = "自动装备";
            }
            case "weapon" -> {
                config.botAutoWeapon = !config.botAutoWeapon;
                enabled = config.botAutoWeapon;
                label = "自动选武器";
            }
            default -> {
                return 0;
            }
        }
        ModConfig.save();
        CompanionLog.player(player, "BEHAVIOR", key + " -> " + enabled);
        source.sendFeedback(() -> Text.literal(label + "：" + (enabled ? "开启" : "关闭")).formatted(enabled ? Formatting.GREEN : Formatting.YELLOW), false);
        return 1;
    }

    private static int configApi(ServerCommandSource source, String url) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        String normalized = normalizeApiUrl(url);
        if (normalized.isBlank()) {
            source.sendFeedback(() -> Text.literal("API 地址不能为空。").formatted(Formatting.YELLOW), false);
            return 0;
        }
        ModConfig.get().lmStudioApiUrl = normalized;
        ModConfig.get().aiChatEnabled = true;
        ModConfig.save();
        CompanionLog.player(player, "CONFIG", "api url -> " + normalized);
        source.sendFeedback(() -> Text.literal("AI API 地址已保存并立即生效：\n" + normalized).formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int configModel(ServerCommandSource source, String model) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        String normalized = model == null ? "" : model.strip();
        if (normalized.isBlank()) {
            source.sendFeedback(() -> Text.literal("模型名称不能为空。").formatted(Formatting.YELLOW), false);
            return 0;
        }
        ModConfig.get().modelName = normalized;
        if (ModConfig.get().autoNameFromModel) {
            ModConfig.get().aiName = ModelNameUtil.displayNameFromModel(normalized);
        }
        ModConfig.save();
        CompanionLog.player(player, "CONFIG", "model -> " + normalized);
        source.sendFeedback(() -> Text.literal("模型名称已保存并立即生效：" + normalized).formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int configApiKey(ServerCommandSource source, String key) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        String normalized = key == null ? "" : key.strip();
        ModConfig.get().apiKey = normalized;
        ModConfig.save();
        CompanionLog.player(player, "CONFIG", normalized.isBlank() ? "api key cleared" : "api key updated");
        source.sendFeedback(() -> Text.literal(normalized.isBlank() ? "API Key 已清空。" : "API Key 已保存并立即生效。").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int configChat(ServerCommandSource source, boolean enabled) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        ModConfig.get().aiChatEnabled = enabled;
        ModConfig.save();
        CompanionLog.player(player, "CONFIG", "chat enabled -> " + enabled);
        source.sendFeedback(() -> Text.literal(enabled ? "AI 聊天已开启。" : "AI 聊天已关闭。").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int configTimeout(ServerCommandSource source, int seconds) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        ModConfig.get().requestTimeoutSeconds = seconds;
        ModConfig.save();
        CompanionLog.player(player, "CONFIG", "timeout seconds -> " + seconds);
        source.sendFeedback(() -> Text.literal("请求超时已设置为 " + seconds + " 秒。").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int configMaxTokens(ServerCommandSource source, int tokens) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        ModConfig.get().maxTokens = tokens;
        ModConfig.save();
        CompanionLog.player(player, "CONFIG", "max tokens -> " + tokens);
        source.sendFeedback(() -> Text.literal("最大输出 Token 已设置为 " + tokens + "。").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int configTest(ServerCommandSource source) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        source.sendFeedback(() -> Text.literal("正在测试 AI API 连接...").formatted(Formatting.GRAY), false);
        CompanionLog.player(player, "CONFIG", "api test started");
        LM_STUDIO_CLIENT.canConnect().thenAccept(connected -> source.getServer().execute(() -> {
            CompanionLog.player(player, "CONFIG", "api test -> " + (connected ? "connected" : "failed"));
            source.sendFeedback(() -> Text.literal(connected ? "AI API 测试成功，可以聊天。" : "AI API 测试失败，请检查地址、模型名或 API Key。")
                    .formatted(connected ? Formatting.GREEN : Formatting.YELLOW), false);
        }));
        return 1;
    }

    private static String normalizeApiUrl(String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.isBlank()) {
            return "";
        }
        if (url.endsWith("/v1/chat/completions")) {
            return url;
        }
        if (url.endsWith("/v1")) {
            return url + "/chat/completions";
        }
        return url + "/v1/chat/completions";
    }

    private static int setState(ServerCommandSource source, AIPlayerEntity.CompanionState state, String message) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        if (usesPlayerBot()) {
            Optional<AIPlayerBot> bot = AIPlayerBotManager.findOwnedBot(player);
            if (bot.isEmpty()) {
                source.sendFeedback(() -> Text.literal("没有找到你的 AI 玩家伙伴。").formatted(Formatting.RED), false);
                return 0;
            }
            AIPlayerBot.BotState botState = state == AIPlayerEntity.CompanionState.FOLLOWING
                    ? AIPlayerBot.BotState.FOLLOWING
                    : AIPlayerBot.BotState.WAITING;
            AIPlayerBotManager.setState(player, botState);
            source.sendFeedback(() -> Text.literal(message).formatted(Formatting.GREEN), false);
            return 1;
        }
        Optional<AIPlayerEntity> companion = AIPlayerManager.findOwnedCompanion(player);
        if (companion.isEmpty()) {
            source.sendFeedback(() -> Text.literal("没有找到你的 AI 伙伴。").formatted(Formatting.RED), false);
            CompanionLog.player(player, "COMMAND", "set state " + state + " failed: companion not found");
            return 0;
        }
        companion.get().setCompanionState(state);
        companion.get().sendOwnerFeedback(state == AIPlayerEntity.CompanionState.FOLLOWING ? "好，我跟着你。" : "好，我在这里等你。");
        CompanionLog.player(player, "STATE", "companion state -> " + state);
        source.sendFeedback(() -> Text.literal(message).formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int come(ServerCommandSource source) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        if (usesPlayerBot()) {
            Optional<AIPlayerBot> bot = AIPlayerBotManager.findOwnedBot(player);
            if (bot.isEmpty()) {
                source.sendFeedback(() -> Text.literal("没有找到你的 AI 玩家伙伴。").formatted(Formatting.RED), false);
                return 0;
            }
            AIPlayerBotManager.setState(player, AIPlayerBot.BotState.FOLLOWING);
            AIPlayerBotManager.teleportNearOwner(player, bot.get(), false);
            source.sendFeedback(() -> Text.literal("AI 玩家伙伴已来到你附近。").formatted(Formatting.GREEN), false);
            return 1;
        }
        Optional<AIPlayerEntity> companion = AIPlayerManager.findOwnedCompanion(player);
        if (companion.isEmpty()) {
            source.sendFeedback(() -> Text.literal("没有找到你的 AI 伙伴。").formatted(Formatting.RED), false);
            CompanionLog.player(player, "COMMAND", "/aiplayer come failed: companion not found");
            return 0;
        }
        AIPlayerEntity entity = companion.get();
        entity.setCompanionState(AIPlayerEntity.CompanionState.FOLLOWING);
        entity.sendOwnerFeedback("我来了。");
        if (entity.getWorld() == player.getWorld()) {
            entity.getNavigation().stop();
            entity.getNavigation().startMovingTo(player, 1.15D);
            CompanionLog.player(player, "PATH", "come: recalculated path to owner");
        } else {
            CompanionLog.player(player, "PATH", "come: owner and companion are in different dimensions");
        }
        source.sendFeedback(() -> Text.literal("AI 伙伴正在过来。").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int remove(ServerCommandSource source) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        if (usesPlayerBot()) {
            Optional<AIPlayerBot> bot = AIPlayerBotManager.findOwnedBot(player);
            if (bot.isEmpty()) {
                source.sendFeedback(() -> Text.literal("没有找到你的 AI 玩家伙伴。").formatted(Formatting.RED), false);
                return 0;
            }
            AIPlayerBotManager.remove(player, bot.get());
            source.sendFeedback(() -> Text.literal("AI 玩家伙伴已删除。").formatted(Formatting.GREEN), false);
            return 1;
        }
        Optional<AIPlayerEntity> companion = AIPlayerManager.findOwnedCompanion(player);
        if (companion.isEmpty()) {
            source.sendFeedback(() -> Text.literal("没有找到你的 AI 伙伴。").formatted(Formatting.RED), false);
            CompanionLog.player(player, "COMMAND", "/aiplayer remove failed: companion not found");
            return 0;
        }
        companion.get().discard();
        CompanionLog.player(player, "COMMAND", "/aiplayer remove -> companion removed");
        source.sendFeedback(() -> Text.literal("AI 伙伴已删除。").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int status(ServerCommandSource source) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        if (usesPlayerBot()) {
            Optional<AIPlayerBot> bot = AIPlayerBotManager.findOwnedBot(player);
            if (bot.isEmpty()) {
                source.sendFeedback(() -> Text.literal("没有找到你的 AI 玩家伙伴。").formatted(Formatting.RED), false);
                return 0;
            }
            AIPlayerBot entity = bot.get();
            String distance = entity.getWorld() == player.getWorld()
                    ? String.format(Locale.ROOT, "%.1f", Math.sqrt(entity.squaredDistanceTo(player)))
                    : "不同维度";
            String status = String.format(Locale.ROOT,
                    "AI 类型：PLAYER_BOT\nAI 名称：%s\n状态：%s\n生命值：%.1f / %.1f\n坐标：%.1f %.1f %.1f\n与主人距离：%s\n背包：原版玩家背包\n原版 /tp：可用\nLM Studio：检测中...",
                    entity.getName().getString(),
                    entity.getBotState().name(),
                    entity.getHealth(),
                    entity.getMaxHealth(),
                    entity.getX(),
                    entity.getY(),
                    entity.getZ(),
                    distance
            );
            source.sendFeedback(() -> Text.literal(status).formatted(Formatting.AQUA), false);
            LM_STUDIO_CLIENT.canConnect().thenAccept(connected -> source.getServer().execute(() ->
                    source.sendFeedback(() -> Text.literal("LM Studio：" + (connected ? "可连接" : "不可连接")).formatted(connected ? Formatting.GREEN : Formatting.YELLOW), false)
            ));
            return 1;
        }
        Optional<AIPlayerEntity> companion = AIPlayerManager.findOwnedCompanion(player);
        if (companion.isEmpty()) {
            source.sendFeedback(() -> Text.literal("没有找到你的 AI 伙伴。").formatted(Formatting.RED), false);
            CompanionLog.player(player, "STATUS", "status failed: companion not found");
            return 0;
        }

        AIPlayerEntity entity = companion.get();
        String dimensionNote = entity.getWorld() == player.getWorld() ? "" : "（不同维度）";
        double distance = entity.getWorld() == player.getWorld() ? Math.sqrt(entity.squaredDistanceTo(player)) : -1.0D;
        String status = String.format(Locale.ROOT,
                "AI 名称：%s\n状态：%s\n生命值：%.1f / %.1f\n坐标：%.1f %.1f %.1f %s\n与主人距离：%s\nLM Studio：检测中...\n日志文件：%s",
                entity.getName().getString(),
                entity.getCompanionState().name(),
                entity.getHealth(),
                entity.getMaxHealth(),
                entity.getX(),
                entity.getY(),
                entity.getZ(),
                dimensionNote,
                distance < 0 ? "不同维度" : String.format(Locale.ROOT, "%.1f", distance),
                CompanionLog.logPath()
        );
        source.sendFeedback(() -> Text.literal(status).formatted(Formatting.AQUA), false);
        CompanionLog.player(player, "STATUS", "status requested");

        LM_STUDIO_CLIENT.canConnect().thenAccept(connected -> source.getServer().execute(() -> {
            CompanionLog.player(player, "LM_STUDIO", "connection check -> " + (connected ? "connected" : "unavailable"));
            source.sendFeedback(() -> Text.literal("LM Studio：" + (connected ? "可连接" : "不可连接")).formatted(connected ? Formatting.GREEN : Formatting.YELLOW), false);
        }));
        return 1;
    }

    private static int debug(ServerCommandSource source, boolean enabled) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        ModConfig.get().enableOwnerDebugMessages = enabled;
        ModConfig.save();
        CompanionLog.player(player, "DEBUG", "owner debug messages -> " + enabled);
        source.sendFeedback(() -> Text.literal(enabled ? "AI 调试聊天日志已开启。" : "AI 调试聊天日志已关闭。").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int logs(ServerCommandSource source, int count) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        List<String> lines = CompanionLog.readLastLines(count);
        source.sendFeedback(() -> Text.literal("最近 AI 日志（文件：" + CompanionLog.logPath() + "）：").formatted(Formatting.AQUA), false);
        for (String line : lines) {
            source.sendFeedback(() -> Text.literal(line).formatted(Formatting.GRAY), false);
        }
        CompanionLog.player(player, "LOGS", "read last " + count + " log lines");
        return 1;
    }

    private static int chat(ServerCommandSource source, String content) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        if (content == null || content.isBlank()) {
            source.sendFeedback(() -> Text.literal("聊天内容不能为空。").formatted(Formatting.YELLOW), false);
            CompanionLog.player(player, "CHAT", "empty chat command ignored");
            return 0;
        }

        String normalized = content.strip();
        CompanionLog.player(player, "CHAT_IN", ModConfig.get().logChatContent ? normalized : "<hidden by config>");
        if (CompanionInteractionService.isFollowCommand(normalized)) {
            CompanionLog.player(player, "LOCAL_INTENT", "follow keyword matched");
            return setState(source, AIPlayerEntity.CompanionState.FOLLOWING, "AI 伙伴开始跟随你。");
        }
        if (CompanionInteractionService.isWaitCommand(normalized)) {
            CompanionLog.player(player, "LOCAL_INTENT", "wait keyword matched");
            return setState(source, AIPlayerEntity.CompanionState.WAITING, "AI 伙伴正在原地等待。");
        }
        if (!ModConfig.get().aiChatEnabled) {
            source.sendFeedback(() -> Text.literal("AI 聊天已在配置中关闭。").formatted(Formatting.YELLOW), false);
            CompanionLog.player(player, "LM_STUDIO", "chat skipped: disabled by config");
            return 0;
        }

        CompanionInteractionService.requestChat(player, normalized, true);
        return 1;
    }

    private static ServerPlayerEntity requirePlayer(ServerCommandSource source) {
        try {
            return source.getPlayerOrThrow();
        } catch (Exception e) {
            source.sendError(Text.literal("该命令只能由玩家执行。"));
            return null;
        }
    }

    private static boolean usesPlayerBot() {
        return "PLAYER_BOT".equals(ModConfig.get().companionMode);
    }
}
