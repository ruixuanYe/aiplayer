package com.aiplayercompanion.client;

import com.aiplayercompanion.ai.LMStudioClient;
import com.aiplayercompanion.config.AIPlayerCleanConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public final class AIPlayerConfigScreen extends Screen {
    private static final int MAX_FIELD_WIDTH = 360;
    private static final int FIELD_HEIGHT = 20;
    private static final int ROW = 70;
    private static final int TOP = 58;
    private static final int BOTTOM = 44;

    private final Screen parent;
    private int scroll;
    private boolean aiChatEnabled;
    private String status = "";

    public AIPlayerConfigScreen(Screen parent) {
        super(Text.literal("AIPlayer Companion"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        AIPlayerCleanConfig config = AIPlayerCleanConfig.get();
        aiChatEnabled = config.aiChatEnabled;

        int centerX = width / 2;
        int fieldWidth = fieldWidth();
        int y = TOP + 18 - scroll;
        addField("\u0041\u0050\u0049 \u5730\u5740",
                "LM Studio \u672c\u5730\u670d\u52a1\u5730\u5740",
                "http://127.0.0.1:1234/v1/chat/completions", config.apiUrl, 500, y, text -> config.apiUrl = text);
        y += ROW;
        addField("\u6a21\u578b ID",
                "LM Studio \u5f53\u524d\u52a0\u8f7d\u7684\u6a21\u578b ID",
                "local-model", config.modelName, 200, y, text -> config.modelName = text);
        y += ROW;
        addField("API Key / Token",
                "LM Studio \u53ef\u7559\u7a7a",
                "\u53ef\u7559\u7a7a", config.apiKey, 500, y, text -> config.apiKey = text);
        y += ROW;
        addField("\u8d85\u65f6\u79d2\u6570",
                "\u6a21\u578b\u56de\u590d\u6700\u591a\u7b49\u5f85\u65f6\u95f4",
                "15", String.valueOf(config.timeoutSeconds), 3, y, text -> {
            try {
                config.timeoutSeconds = Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                config.timeoutSeconds = 15;
            }
        });
        y += ROW;

        CheckboxWidget enabled = CheckboxWidget.builder(Text.literal("\u542f\u7528 AI \u804a\u5929"), textRenderer)
                .pos(centerX - fieldWidth / 2, y)
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
        }).dimensions(centerX - 135, height - 28, 80, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("\u6d4b\u8bd5\u8fde\u63a5"), button -> {
            AIPlayerCleanConfig.get().aiChatEnabled = aiChatEnabled;
            AIPlayerCleanConfig.save();
            status = "\u6d4b\u8bd5\u4e2d...";
            LMStudioClient.test().thenAccept(response -> MinecraftClient.getInstance().execute(() ->
                    status = response.ok()
                            ? "\u8fde\u63a5\u6210\u529f\uff1a" + response.content()
                            : "\u8fde\u63a5\u5931\u8d25\uff1a" + response.error()));
        }).dimensions(centerX - 45, height - 28, 110, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("\u5b8c\u6210"), button -> close())
                .dimensions(centerX + 75, height - 28, 80, 20)
                .build());
    }

    private void addField(String label, String description, String placeholder, String value, int maxLength, int y, FieldSetter setter) {
        int fieldWidth = fieldWidth();
        int x = width / 2 - fieldWidth / 2;
        boolean visible = isRowVisible(y);

        LabelWidget labelWidget = new LabelWidget(x, y, fieldWidth, 10, Text.literal(label), 0xFFFFFFFF);
        labelWidget.visible = visible;
        addDrawableChild(labelWidget);

        LabelWidget descriptionWidget = new LabelWidget(x, y + 12, fieldWidth, 10, Text.literal(description), 0xFFA8B0C0);
        descriptionWidget.visible = visible;
        addDrawableChild(descriptionWidget);

        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y + 29, fieldWidth, FIELD_HEIGHT, Text.literal(label));
        field.setMaxLength(maxLength);
        field.setText(value == null ? "" : value);
        field.setPlaceholder(Text.literal(placeholder));
        field.setChangedListener(setter::set);
        field.visible = visible;
        addDrawableChild(field);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int panelWidth = Math.min(width - 16, fieldWidth() + 40);
        int panelX = width / 2 - panelWidth / 2;
        context.fill(0, 0, width, height, 0xD0101010);
        context.fill(panelX, TOP, panelX + panelWidth, height - BOTTOM, 0xCC181C24);
        context.drawBorder(panelX, TOP, panelWidth, height - TOP - BOTTOM, 0xFF3A4254);

        context.drawTextWithShadow(textRenderer, title, width / 2 - textRenderer.getWidth(title) / 2, 18, 0xFFFFFF);
        Text subtitle = Text.literal("LM Studio / API");
        context.drawTextWithShadow(textRenderer, subtitle, width / 2 - textRenderer.getWidth(subtitle) / 2, 38, 0xA0A0A0);

        if (!status.isBlank()) {
            String visible = status.length() > 36 ? status.substring(0, 36) : status;
            context.drawTextWithShadow(textRenderer, Text.literal(visible), width / 2 - fieldWidth() / 2, height - 40, 0xFFFF55);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int contentHeight = ROW * 4 + 28;
        int visibleHeight = Math.max(80, height - TOP - BOTTOM - 16);
        int maxScroll = Math.max(0, contentHeight - visibleHeight);
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
        return y + FIELD_HEIGHT + 29 > TOP && y < height - BOTTOM - 6;
    }

    private int fieldWidth() {
        return Math.max(180, Math.min(MAX_FIELD_WIDTH, width - 48));
    }

    private interface FieldSetter {
        void set(String value);
    }

    private final class LabelWidget extends ClickableWidget {
        private final int color;

        private LabelWidget(int x, int y, int width, int height, Text message, int color) {
            super(x, y, width, height, message);
            this.color = color;
            this.active = false;
        }

        @Override
        protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            context.drawTextWithShadow(textRenderer, getMessage(), getX(), getY(), color);
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        }
    }
}
