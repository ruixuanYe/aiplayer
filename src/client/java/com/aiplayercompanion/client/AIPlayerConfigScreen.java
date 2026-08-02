package com.aiplayercompanion.client;

import com.aiplayercompanion.ai.LMStudioClient;
import com.aiplayercompanion.config.ModConfig;
import com.aiplayercompanion.util.ModelNameUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

public class AIPlayerConfigScreen extends Screen {
    private static final int FIELD_HEIGHT = 20;
    private static final int ROW_GAP = 42;
    private static final int TOGGLE_GAP = 26;
    private static final int BG = 0xE0101014;
    private static final int PANEL = 0xD01B1D24;
    private static final int PANEL_BORDER = 0xFF3B4152;
    private static final int TITLE = 0xFFFFFFFF;
    private static final int LABEL = 0xFFE4E7EF;
    private static final int HINT = 0xFFAAB0C0;
    private static final int MUTED = 0xFF7D8496;
    private static final int OK = 0xFF74E083;
    private static final int WARN = 0xFFFFD166;
    private static final int ERROR = 0xFFFF6B6B;

    private final Screen parent;
    private final LMStudioClient testClient = new LMStudioClient();
    private final List<FormRow> rows = new ArrayList<>();

    private TextFieldWidget apiUrlField;
    private TextFieldWidget modelField;
    private TextFieldWidget apiKeyField;
    private TextFieldWidget timeoutField;
    private TextFieldWidget maxTokensField;
    private TextFieldWidget aiNameField;
    private TextFieldWidget wakeWordsField;
    private ButtonWidget chatToggleButton;
    private ButtonWidget chatListenerToggleButton;
    private ButtonWidget autoNameToggleButton;
    private ButtonWidget debugToggleButton;
    private ButtonWidget fileLogToggleButton;
    private ButtonWidget logContentToggleButton;
    private ButtonWidget disableThinkingToggleButton;

    private String statusMessage = "普通聊天会直接发送给 AI；动作命令需要触发词。";
    private int statusColor = HINT;
    private int scrollOffset;
    private int contentHeight;

    protected AIPlayerConfigScreen(Screen parent) {
        super(Text.literal("AIPlayer Companion 设置"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rows.clear();
        scrollOffset = MathHelper.clamp(scrollOffset, 0, maxScroll());
        ModConfig config = ModConfig.get();

        apiUrlField = field("API 地址", config.lmStudioApiUrl, 300);
        modelField = field("模型 ID", config.modelName, 200);
        apiKeyField = field("API Key / Token", config.apiKey, 300);
        timeoutField = field("请求超时秒数", String.valueOf(config.requestTimeoutSeconds), 4);
        maxTokensField = field("Max Tokens", String.valueOf(config.maxTokens), 4);
        aiNameField = field("AI 显示名", config.aiName, 80);
        wakeWordsField = field("动作触发词", config.wakeWords, 160);

        addButtonRow(ButtonWidget.builder(Text.literal("LM Studio 模板"), button -> {
            apiUrlField.setText("http://192.168.0.237:1234/v1/chat/completions");
            status("已填入局域网模板，保存前请确认 IP。", HINT);
        }).build(), ButtonWidget.builder(Text.literal("清空 Key"), button -> {
            apiKeyField.setText("");
            status("API Key 已清空，点击保存后生效。", HINT);
        }).build());

        autoNameToggleButton = toggle("自动命名", config.autoNameFromModel, button -> {
            ModConfig.get().autoNameFromModel = !ModConfig.get().autoNameFromModel;
            if (ModConfig.get().autoNameFromModel) {
                aiNameField.setText(ModelNameUtil.displayNameFromModel(modelField.getText()));
            }
            refreshToggleLabels();
        });
        chatToggleButton = toggle("AI 聊天", config.aiChatEnabled, button -> {
            ModConfig.get().aiChatEnabled = !ModConfig.get().aiChatEnabled;
            refreshToggleLabels();
        });
        chatListenerToggleButton = toggle("监听聊天", config.enableChatListener, button -> {
            ModConfig.get().enableChatListener = !ModConfig.get().enableChatListener;
            refreshToggleLabels();
        });
        disableThinkingToggleButton = toggle("关思考", config.disableThinking, button -> {
            ModConfig.get().disableThinking = !ModConfig.get().disableThinking;
            refreshToggleLabels();
        });
        fileLogToggleButton = toggle("文件日志", config.enableFileLog, button -> {
            ModConfig.get().enableFileLog = !ModConfig.get().enableFileLog;
            refreshToggleLabels();
        });
        debugToggleButton = toggle("聊天调试", config.enableOwnerDebugMessages, button -> {
            ModConfig.get().enableOwnerDebugMessages = !ModConfig.get().enableOwnerDebugMessages;
            refreshToggleLabels();
        });
        logContentToggleButton = toggle("记录聊天内容", config.logChatContent, button -> {
            ModConfig.get().logChatContent = !ModConfig.get().logChatContent;
            refreshToggleLabels();
        });

        addButtonRow(autoNameToggleButton, chatToggleButton);
        addButtonRow(chatListenerToggleButton, disableThinkingToggleButton);
        addButtonRow(fileLogToggleButton, debugToggleButton);
        addFullButtonRow(logContentToggleButton);
        addFullButtonRow(ButtonWidget.builder(Text.literal("按模型生成名称"), button -> {
            aiNameField.setText(ModelNameUtil.displayNameFromModel(modelField.getText()));
            ModConfig.get().autoNameFromModel = true;
            refreshToggleLabels();
            status("已根据模型生成名称，保存后生效。", HINT);
        }).build());

        int footerY = this.height - 28;
        int footerWidth = Math.min(540, this.width - 32);
        int footerX = (this.width - footerWidth) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("保存"), button -> saveConfig())
                .dimensions(footerX, footerY, 86, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("测试连接"), button -> testConnection())
                .dimensions(footerX + 96, footerY, 100, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("完成"), button -> close())
                .dimensions(footerX + footerWidth - 86, footerY, 86, 20)
                .build());

        refreshToggleLabels();
        updateWidgetPositions();
    }

    private TextFieldWidget field(String label, String text, int maxLength) {
        TextFieldWidget widget = new TextFieldWidget(this.textRenderer, 0, 0, 10, FIELD_HEIGHT, Text.literal(label));
        widget.setMaxLength(maxLength);
        widget.setText(text == null ? "" : text);
        widget.setPlaceholder(Text.literal(label));
        addDrawableChild(widget);
        rows.add(new FormRow(label, widget, ROW_GAP));
        return widget;
    }

    private ButtonWidget toggle(String label, boolean enabled, ButtonWidget.PressAction action) {
        return ButtonWidget.builder(toggleText(label, enabled), action).build();
    }

    private void addButtonRow(ButtonWidget left, ButtonWidget right) {
        addDrawableChild(left);
        addDrawableChild(right);
        rows.add(new FormRow("", left, right, TOGGLE_GAP));
    }

    private void addFullButtonRow(ButtonWidget button) {
        addDrawableChild(button);
        rows.add(new FormRow("", button, null, TOGGLE_GAP));
    }

    private void updateWidgetPositions() {
        int formWidth = Math.min(540, this.width - 32);
        int formX = (this.width - formWidth) / 2;
        int y = 62 - scrollOffset;
        int viewportTop = viewportTop();
        int viewportBottom = viewportBottom();

        for (FormRow row : rows) {
            int widgetY = row.label().isEmpty() ? y : y + 12;
            boolean visible = widgetY + FIELD_HEIGHT >= viewportTop && y <= viewportBottom;
            if (row.left() instanceof TextFieldWidget) {
                row.left().setX(formX);
                row.left().setY(widgetY);
                row.left().setWidth(formWidth);
            } else {
                int gap = 10;
                int half = (formWidth - gap) / 2;
                row.left().setX(formX);
                row.left().setY(widgetY);
                row.left().setWidth(row.right() == null ? formWidth : half);
                if (row.right() != null) {
                    row.right().setX(formX + half + gap);
                    row.right().setY(widgetY);
                    row.right().setWidth(half);
                }
            }
            row.left().visible = visible;
            row.left().active = visible;
            if (row.right() != null) {
                row.right().visible = visible;
                row.right().active = visible;
            }
            y += row.height();
        }
        contentHeight = y + scrollOffset - 62;
        scrollOffset = MathHelper.clamp(scrollOffset, 0, maxScroll());
    }

    private void saveConfig() {
        int timeout;
        int maxTokens;
        try {
            timeout = Integer.parseInt(timeoutField.getText().strip());
            maxTokens = Integer.parseInt(maxTokensField.getText().strip());
        } catch (NumberFormatException e) {
            status("超时和 Max Tokens 必须是数字。", ERROR);
            return;
        }
        if (timeout < 1 || timeout > 120) {
            status("请求超时范围是 1 到 120 秒。", ERROR);
            return;
        }
        if (maxTokens < 32 || maxTokens > 4096) {
            status("Max Tokens 范围是 32 到 4096。", ERROR);
            return;
        }

        ModConfig config = ModConfig.get();
        config.lmStudioApiUrl = normalizeApiUrl(apiUrlField.getText());
        config.modelName = modelField.getText().strip();
        config.apiKey = apiKeyField.getText().strip();
        config.requestTimeoutSeconds = timeout;
        config.maxTokens = maxTokens;
        config.aiName = aiNameField.getText().isBlank() ? "AI Companion" : aiNameField.getText().strip();
        config.wakeWords = wakeWordsField.getText().isBlank() ? "AI,ai,伙伴,aiplayer" : wakeWordsField.getText().strip();
        if (config.autoNameFromModel) {
            config.aiName = ModelNameUtil.displayNameFromModel(config.modelName);
            aiNameField.setText(config.aiName);
        }
        ModConfig.save();

        apiUrlField.setText(config.lmStudioApiUrl);
        status("配置已保存，立即生效。", OK);
    }

    private void testConnection() {
        saveConfig();
        if (statusColor == ERROR) {
            return;
        }
        status("正在测试连接...", HINT);
        testClient.chat("ping").thenAccept(response -> MinecraftClient.getInstance().execute(() -> {
            if (response.success()) {
                status("连接成功，模型返回正常。", OK);
            } else {
                status("连接失败：" + response.errorMessage(), WARN);
            }
        }));
    }

    private void refreshToggleLabels() {
        ModConfig config = ModConfig.get();
        if (chatToggleButton != null) chatToggleButton.setMessage(toggleText("AI 聊天", config.aiChatEnabled));
        if (chatListenerToggleButton != null) chatListenerToggleButton.setMessage(toggleText("监听聊天", config.enableChatListener));
        if (autoNameToggleButton != null) autoNameToggleButton.setMessage(toggleText("自动命名", config.autoNameFromModel));
        if (debugToggleButton != null) debugToggleButton.setMessage(toggleText("聊天调试", config.enableOwnerDebugMessages));
        if (fileLogToggleButton != null) fileLogToggleButton.setMessage(toggleText("文件日志", config.enableFileLog));
        if (logContentToggleButton != null) logContentToggleButton.setMessage(toggleText("记录聊天内容", config.logChatContent));
        if (disableThinkingToggleButton != null) disableThinkingToggleButton.setMessage(toggleText("关思考", config.disableThinking));
    }

    private Text toggleText(String label, boolean enabled) {
        return Text.literal((enabled ? "✓ " : "  ") + label);
    }

    private void status(String message, int color) {
        this.statusMessage = message;
        this.statusColor = color;
    }

    private String normalizeApiUrl(String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.isBlank()) return "http://127.0.0.1:1234/v1/chat/completions";
        if (url.endsWith("/v1/chat/completions")) return url;
        if (url.endsWith("/v1")) return url + "/chat/completions";
        return url + "/v1/chat/completions";
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        updateWidgetPositions();
        context.fill(0, 0, this.width, this.height, BG);
        context.drawCenteredTextWithShadow(this.textRenderer, "AIPlayer Companion", this.width / 2, 14, TITLE);
        context.drawCenteredTextWithShadow(this.textRenderer, "本地模型连接、聊天触发和伙伴行为设置", this.width / 2, 28, HINT);

        int formWidth = Math.min(540, this.width - 32);
        int formX = (this.width - formWidth) / 2;
        int top = viewportTop();
        int bottom = viewportBottom();
        context.fill(formX - 10, top - 8, formX + formWidth + 10, bottom + 8, PANEL);
        context.drawBorder(formX - 10, top - 8, formWidth + 20, bottom - top + 16, PANEL_BORDER);

        context.enableScissor(formX - 12, top - 6, formX + formWidth + 12, bottom + 6);
        int y = 62 - scrollOffset;
        for (FormRow row : rows) {
            if (!row.label().isEmpty() && y >= top - 18 && y <= bottom) {
                context.drawTextWithShadow(this.textRenderer, row.label(), formX, y, LABEL);
            }
            y += row.height();
        }
        super.render(context, mouseX, mouseY, delta);
        context.disableScissor();

        if (maxScroll() > 0) {
            drawScrollbar(context, formX + formWidth + 5, top, bottom);
        }
        context.drawTextWithShadow(this.textRenderer, statusMessage, formX, this.height - 42, statusColor);
        context.drawTextWithShadow(this.textRenderer, "滚轮上下滚动", formX + formWidth - 78, this.height - 42, MUTED);
    }

    private void drawScrollbar(DrawContext context, int x, int top, int bottom) {
        int height = bottom - top;
        int maxScroll = maxScroll();
        int thumbHeight = Math.max(18, height * height / Math.max(height, contentHeight));
        int thumbY = top + (height - thumbHeight) * scrollOffset / maxScroll;
        context.fill(x, top, x + 3, bottom, 0x8043485A);
        context.fill(x, thumbY, x + 3, thumbY + thumbHeight, 0xFFE4E7EF);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseY >= viewportTop() && mouseY <= viewportBottom()) {
            scrollOffset = MathHelper.clamp(scrollOffset - (int) (verticalAmount * 22.0D), 0, maxScroll());
            updateWidgetPositions();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private int viewportTop() {
        return 52;
    }

    private int viewportBottom() {
        return Math.max(viewportTop() + 60, this.height - 50);
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (viewportBottom() - viewportTop()));
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    private record FormRow(String label, ClickableWidget left, ClickableWidget right, int height) {
        private FormRow(String label, ClickableWidget left, int height) {
            this(label, left, null, height);
        }
    }
}
