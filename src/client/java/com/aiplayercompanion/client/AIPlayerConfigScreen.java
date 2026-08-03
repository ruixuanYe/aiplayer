package com.aiplayercompanion.client;

import com.aiplayercompanion.ai.LMStudioClient;
import com.aiplayercompanion.config.AIPlayerCleanConfig;
import com.aiplayercompanion.util.BotNameUtil;
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
    private static final int MAX_CONTENT_WIDTH = 460;
    private static final int ROW_BUTTON_WIDTH = 38;
    private static final int GAP = 5;
    private static final int FIELD_HEIGHT = 20;
    private static final int ROW = 82;
    private static final int TOP = 54;
    private static final int BOTTOM = 42;

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
        int y = TOP + 18 - scroll;
        addField("\u0041\u0050\u0049 \u5730\u5740",
                "LM Studio \u672c\u5730\u670d\u52a1\u5730\u5740",
                "http://127.0.0.1:1234/v1/chat/completions", config.apiUrl, 500, y, text -> config.apiUrl = normalizeApiUrl(text),
                (field, result) -> {
                    config.apiUrl = normalizeApiUrl(field.getText());
                    field.setText(config.apiUrl);
                    testConnection(result);
                });
        y += ROW;
        addField("\u6a21\u578b ID",
                "LM Studio \u5f53\u524d\u52a0\u8f7d\u7684\u6a21\u578b ID",
                "local-model", config.modelName, 200, y, text -> config.modelName = text.trim(),
                (field, result) -> {
                    config.modelName = field.getText().trim();
                    if (config.modelName.isBlank()) {
                        result.setMessage(Text.literal("\u65e0\u6548\uff1a\u6a21\u578b ID \u4e0d\u80fd\u4e3a\u7a7a"));
                    } else {
                        testConnection(result);
                    }
                });
        y += ROW;
        addField("API Key / Token",
                "LM Studio \u53ef\u7559\u7a7a",
                "\u53ef\u7559\u7a7a", config.apiKey, 500, y, text -> config.apiKey = text,
                (field, result) -> {
                    config.apiKey = field.getText();
                    testConnection(result);
                });
        y += ROW;
        addField("\u8d85\u65f6\u79d2\u6570",
                "\u6a21\u578b\u56de\u590d\u6700\u591a\u7b49\u5f85\u65f6\u95f4",
                "15", String.valueOf(config.timeoutSeconds), 3, y, text -> {
            try {
                config.timeoutSeconds = Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                config.timeoutSeconds = 15;
            }
        }, (field, result) -> {
            try {
                int value = Integer.parseInt(field.getText().trim());
                if (value < 1 || value > 120) {
                    result.setMessage(Text.literal("\u65e0\u6548\uff1a\u8bf7\u586b 1-120"));
                    return;
                }
                config.timeoutSeconds = value;
                testConnection(result);
            } catch (NumberFormatException ignored) {
                result.setMessage(Text.literal("\u65e0\u6548\uff1a\u53ea\u80fd\u586b\u6570\u5b57"));
            }
        });
        y += ROW;

        CheckboxWidget enabled = CheckboxWidget.builder(Text.literal("\u542f\u7528 AI \u804a\u5929"), textRenderer)
                .pos(centerX - contentWidth() / 2, y)
                .checked(aiChatEnabled)
                .callback((checkbox, checked) -> {
                    aiChatEnabled = checked;
                    AIPlayerCleanConfig.get().aiChatEnabled = checked;
                })
                .build();
        enabled.visible = isRowVisible(y);
        addDrawableChild(enabled);
        y += ROW / 2;

        ButtonWidget syncName = ButtonWidget.builder(Text.literal("\u540c\u6b65 AIPlayer \u540d\u79f0"), button -> {
            AIPlayerCleanConfig current = AIPlayerCleanConfig.get();
            current.botName = BotNameUtil.deriveFromModel(current.modelName);
            current.autoNameFromModel = true;
            current.clearBinding();
            AIPlayerCleanConfig.save();
            status = "\u5df2\u8986\u76d6\uff1a" + current.botName + "\uff0c\u4e0b\u6b21\u53ec\u5524\u751f\u6548";
        }).dimensions(centerX - contentWidth() / 2, y, contentWidth(), FIELD_HEIGHT).build();
        syncName.visible = isRowVisible(y);
        addDrawableChild(syncName);

        addDrawableChild(ButtonWidget.builder(Text.literal("\u4fdd\u5b58\u8fd4\u56de"), button -> {
            AIPlayerCleanConfig.get().aiChatEnabled = aiChatEnabled;
            AIPlayerCleanConfig.save();
            close();
        }).dimensions(centerX - 104, height - 28, 96, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("\u5b8c\u6210"), button -> close())
                .dimensions(centerX + 8, height - 28, 96, 20)
                .build());
    }

    private void addField(String label, String description, String placeholder, String value, int maxLength, int y, FieldSetter setter, FieldTester tester) {
        int contentWidth = contentWidth();
        int inputWidth = inputWidth();
        int x = width / 2 - contentWidth / 2;
        boolean visible = isRowVisible(y);

        LabelWidget labelWidget = new LabelWidget(x, y, contentWidth, 10, Text.literal(label), 0xFFFFFFFF);
        labelWidget.visible = visible;
        addDrawableChild(labelWidget);

        LabelWidget descriptionWidget = new LabelWidget(x, y + 12, contentWidth, 10, Text.literal(description), 0xFFA8B0C0);
        descriptionWidget.visible = visible;
        addDrawableChild(descriptionWidget);

        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y + 29, inputWidth, FIELD_HEIGHT, Text.literal(label));
        field.setMaxLength(maxLength);
        field.setText(value == null ? "" : value);
        field.setPlaceholder(Text.literal(placeholder));
        field.setChangedListener(setter::set);
        field.visible = visible;
        addDrawableChild(field);

        LabelWidget result = new LabelWidget(x, y + 52, contentWidth, 10, Text.literal(""), 0xFFFFFF55);
        result.visible = visible;
        addDrawableChild(result);

        ButtonWidget saveButton = ButtonWidget.builder(Text.literal("\u5b58"), button -> {
            setter.set(field.getText());
            AIPlayerCleanConfig.get().aiChatEnabled = aiChatEnabled;
            AIPlayerCleanConfig.save();
            result.setMessage(Text.literal("\u5df2\u4fdd\u5b58"));
        }).dimensions(x + inputWidth + GAP, y + 29, ROW_BUTTON_WIDTH, FIELD_HEIGHT).build();
        saveButton.visible = visible;
        addDrawableChild(saveButton);

        ButtonWidget testButton = ButtonWidget.builder(Text.literal("\u6d4b"), button -> {
            setter.set(field.getText());
            AIPlayerCleanConfig.get().aiChatEnabled = aiChatEnabled;
            AIPlayerCleanConfig.save();
            tester.test(field, result);
        }).dimensions(x + inputWidth + GAP + ROW_BUTTON_WIDTH + GAP, y + 29, ROW_BUTTON_WIDTH, FIELD_HEIGHT).build();
        testButton.visible = visible;
        addDrawableChild(testButton);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int panelWidth = Math.min(width - 12, contentWidth() + 30);
        int panelX = width / 2 - panelWidth / 2;
        context.fill(0, 0, width, height, 0xD0101010);
        context.fill(panelX + 2, TOP + 2, panelX + panelWidth + 2, height - BOTTOM + 2, 0x80101010);
        context.fill(panelX, TOP, panelX + panelWidth, height - BOTTOM, 0xD0181C24);
        context.fill(panelX, TOP, panelX + panelWidth, TOP + 2, 0xFF566078);
        context.fill(panelX, height - BOTTOM - 2, panelX + panelWidth, height - BOTTOM, 0xFF10141C);
        context.drawBorder(panelX, TOP, panelWidth, height - TOP - BOTTOM, 0xFF465064);

        context.drawTextWithShadow(textRenderer, title, width / 2 - textRenderer.getWidth(title) / 2, 18, 0xFFFFFF);
        Text subtitle = Text.literal("LM Studio / API");
        context.drawTextWithShadow(textRenderer, subtitle, width / 2 - textRenderer.getWidth(subtitle) / 2, 38, 0xA0A0A0);

        if (!status.isBlank()) {
            String visible = status.length() > 36 ? status.substring(0, 36) : status;
            context.drawTextWithShadow(textRenderer, Text.literal(visible), width / 2 - contentWidth() / 2, height - 40, 0xFFFF55);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int contentHeight = ROW * 4 + ROW / 2 + 30;
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
        return y + FIELD_HEIGHT + 52 > TOP && y < height - BOTTOM - 8;
    }

    private int contentWidth() {
        return Math.max(240, Math.min(MAX_CONTENT_WIDTH, width - 36));
    }

    private int inputWidth() {
        return Math.max(130, contentWidth() - ROW_BUTTON_WIDTH * 2 - GAP * 2);
    }

    private void testConnection(LabelWidget result) {
        result.setMessage(Text.literal("\u6d4b\u8bd5\u4e2d..."));
        AIPlayerCleanConfig.save();
        LMStudioClient.test().thenAccept(response -> MinecraftClient.getInstance().execute(() ->
                result.setMessage(Text.literal(response.ok() ? "\u6709\u6548\uff1a\u8fde\u63a5\u901a\u8fc7" : "\u65e0\u6548\uff1a" + shortText(response.error())))));
    }

    private String normalizeApiUrl(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) {
            return "http://127.0.0.1:1234/v1/chat/completions";
        }
        if (!text.endsWith("/v1/chat/completions")) {
            text = text.replaceAll("/+$", "") + "/v1/chat/completions";
        }
        return text;
    }

    private String shortText(String text) {
        if (text == null || text.isBlank()) {
            return "\u672a\u77e5\u9519\u8bef";
        }
        return text.length() > 34 ? text.substring(0, 34) : text;
    }

    private interface FieldSetter {
        void set(String value);
    }

    private interface FieldTester {
        void test(TextFieldWidget field, LabelWidget result);
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
