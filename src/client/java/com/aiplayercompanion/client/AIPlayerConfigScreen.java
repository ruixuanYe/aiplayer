package com.aiplayercompanion.client;

import com.aiplayercompanion.ai.LMStudioClient;
import com.aiplayercompanion.config.AIPlayerCleanConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public final class AIPlayerConfigScreen extends Screen {
    private static final int FIELD_WIDTH = 360;
    private static final int FIELD_HEIGHT = 20;
    private static final int ROW = 46;

    private final Screen parent;
    private final List<FieldRow> fields = new ArrayList<>();
    private int scroll;
    private boolean aiChatEnabled;
    private String status = "";

    public AIPlayerConfigScreen(Screen parent) {
        super(Text.literal("AIPlayer Companion"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        fields.clear();
        AIPlayerCleanConfig config = AIPlayerCleanConfig.get();
        aiChatEnabled = config.aiChatEnabled;

        int centerX = width / 2;
        int y = 74 - scroll;
        addField("API 地址", config.apiUrl, 500, y, text -> config.apiUrl = text);
        y += ROW;
        addField("模型名称", config.modelName, 200, y, text -> config.modelName = text);
        y += ROW;
        addField("API Key / Token", config.apiKey, 500, y, text -> config.apiKey = text);
        y += ROW;
        addField("超时秒数", String.valueOf(config.timeoutSeconds), 3, y, text -> {
            try {
                config.timeoutSeconds = Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                config.timeoutSeconds = 15;
            }
        });
        y += ROW;
        addField("AI 系统提示词", config.systemPrompt, 1000, y, text -> config.systemPrompt = text);
        y += ROW;

        CheckboxWidget enabled = CheckboxWidget.builder(Text.literal("启用 AI 聊天"), textRenderer)
                .pos(centerX - FIELD_WIDTH / 2, y)
                .checked(aiChatEnabled)
                .callback((checkbox, checked) -> {
                    aiChatEnabled = checked;
                    AIPlayerCleanConfig.get().aiChatEnabled = checked;
                })
                .build();
        enabled.visible = isRowVisible(y);
        addDrawableChild(enabled);

        addDrawableChild(ButtonWidget.builder(Text.literal("保存"), button -> {
            AIPlayerCleanConfig.get().aiChatEnabled = aiChatEnabled;
            AIPlayerCleanConfig.save();
            status = "已保存";
        }).dimensions(centerX - 185, height - 32, 80, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("测试连接"), button -> {
            AIPlayerCleanConfig.get().aiChatEnabled = aiChatEnabled;
            AIPlayerCleanConfig.save();
            status = "测试中...";
            LMStudioClient.test().thenAccept(response -> MinecraftClient.getInstance().execute(() -> {
                status = response.ok() ? "连接成功：" + response.content() : "连接失败：" + response.error();
            }));
        }).dimensions(centerX - 95, height - 32, 110, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("完成"), button -> close())
                .dimensions(centerX + 25, height - 32, 80, 20)
                .build());
    }

    private void addField(String label, String value, int maxLength, int y, FieldSetter setter) {
        int x = width / 2 - FIELD_WIDTH / 2;
        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y + 14, FIELD_WIDTH, FIELD_HEIGHT, Text.literal(label));
        field.setMaxLength(maxLength);
        field.setText(value == null ? "" : value);
        field.setChangedListener(setter::set);
        field.visible = isRowVisible(y);
        fields.add(new FieldRow(label, field, y));
        addDrawableChild(field);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.drawTextWithShadow(textRenderer, title, width / 2 - textRenderer.getWidth(title) / 2, 18, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("本地模型连接、聊天触发和 API 设置"), width / 2 - 110, 40, 0xA0A0A0);

        for (FieldRow row : fields) {
            if (isRowVisible(row.y())) {
                context.drawTextWithShadow(textRenderer, row.label(), row.field().getX(), row.y(), 0xD8D8D8);
            }
        }

        if (!status.isBlank()) {
            String visible = status.length() > 80 ? status.substring(0, 80) : status;
            context.drawTextWithShadow(textRenderer, Text.literal(visible), width / 2 - FIELD_WIDTH / 2, height - 52, 0xFFFF55);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxScroll = Math.max(0, ROW * 6 - (height - 120));
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (verticalAmount * 18)));
        clearAndInit();
        return true;
    }

    @Override
    public void close() {
        AIPlayerCleanConfig.get().aiChatEnabled = aiChatEnabled;
        AIPlayerCleanConfig.save();
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private boolean isRowVisible(int y) {
        return y > 52 && y < height - 58;
    }

    private record FieldRow(String label, TextFieldWidget field, int y) {
    }

    private interface FieldSetter {
        void set(String value);
    }
}
