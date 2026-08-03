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
        addField("\u0041\u0050\u0049 \u5730\u5740", config.apiUrl, 500, y, text -> config.apiUrl = text);
        y += ROW;
        addField("\u6a21\u578b\u540d\u79f0", config.modelName, 200, y, text -> config.modelName = text);
        y += ROW;
        addField("API Key / Token", config.apiKey, 500, y, text -> config.apiKey = text);
        y += ROW;
        addField("\u8d85\u65f6\u79d2\u6570", String.valueOf(config.timeoutSeconds), 3, y, text -> {
            try {
                config.timeoutSeconds = Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                config.timeoutSeconds = 15;
            }
        });
        y += ROW;
        addField("\u0041\u0049 \u7cfb\u7edf\u63d0\u793a\u8bcd", config.systemPrompt, 1000, y, text -> config.systemPrompt = text);
        y += ROW;

        CheckboxWidget enabled = CheckboxWidget.builder(Text.literal("\u542f\u7528 AI \u804a\u5929"), textRenderer)
                .pos(centerX - FIELD_WIDTH / 2, y)
                .checked(aiChatEnabled)
                .callback((checkbox, checked) -> {
                    aiChatEnabled = checked;
                    AIPlayerCleanConfig.get().aiChatEnabled = checked;
                })
                .build();
        enabled.visible = isRowVisible(y);
        addDrawableChild(enabled);

        addDrawableChild(ButtonWidget.builder(Text.literal("\u4fdd\u5b58"), button -> {
            AIPlayerCleanConfig.get().aiChatEnabled = aiChatEnabled;
            AIPlayerCleanConfig.save();
            status = "\u5df2\u4fdd\u5b58";
        }).dimensions(centerX - 185, height - 32, 80, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("\u6d4b\u8bd5\u8fde\u63a5"), button -> {
            AIPlayerCleanConfig.get().aiChatEnabled = aiChatEnabled;
            AIPlayerCleanConfig.save();
            status = "\u6d4b\u8bd5\u4e2d...";
            LMStudioClient.test().thenAccept(response -> MinecraftClient.getInstance().execute(() ->
                    status = response.ok()
                            ? "\u8fde\u63a5\u6210\u529f\uff1a" + response.content()
                            : "\u8fde\u63a5\u5931\u8d25\uff1a" + response.error()));
        }).dimensions(centerX - 95, height - 32, 110, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("\u5b8c\u6210"), button -> close())
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
        context.fill(0, 0, width, height, 0xD0101010);
        context.fill(width / 2 - 220, 58, width / 2 + 220, height - 44, 0xCC181C24);
        context.drawBorder(width / 2 - 220, 58, 440, height - 102, 0xFF3A4254);
        context.drawTextWithShadow(textRenderer, title, width / 2 - textRenderer.getWidth(title) / 2, 18, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("\u672c\u5730\u6a21\u578b\u8fde\u63a5\u548c API \u8bbe\u7f6e"), width / 2 - 88, 40, 0xA0A0A0);

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
