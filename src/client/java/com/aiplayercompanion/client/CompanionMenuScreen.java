package com.aiplayercompanion.client;

import com.aiplayercompanion.util.ModelNameUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class CompanionMenuScreen extends Screen {
    private static final int WIDTH = 260;
    private static final int HEIGHT = 178;
    private static final int PANEL = 0xE0181A22;
    private static final int BORDER = 0xFF4A5268;
    private static final int HINT = 0xFFAAB0C0;

    public CompanionMenuScreen() {
        super(Text.literal(ModelNameUtil.companionName()));
    }

    @Override
    protected void init() {
        int x = this.width / 2 - WIDTH / 2;
        int y = this.height / 2 - HEIGHT / 2;
        int buttonWidth = 108;
        int left = x + 16;
        int right = x + WIDTH - 16 - buttonWidth;
        int row = y + 52;

        addCommandButton(left, row, buttonWidth, "跟随", "aiplayer follow");
        addCommandButton(right, row, buttonWidth, "等待", "aiplayer stop");
        row += 26;
        addCommandButton(left, row, buttonWidth, "来这里", "aiplayer come");
        addCommandButton(right, row, buttonWidth, "状态", "aiplayer status");
        row += 26;
        addCommandButton(left, row, buttonWidth, "自动改名", "aiplayer rename-auto");
        addCommandButton(right, row, buttonWidth, "测试 API", "aiplayer config test");
        row += 26;
        addDrawableChild(ButtonWidget.builder(Text.literal("打开设置"), button -> MinecraftClient.getInstance().setScreen(new AIPlayerConfigScreen(this)))
                .dimensions(left, row, buttonWidth, 20)
                .build());
        addCommandButton(right, row, buttonWidth, "删除", "aiplayer remove");
        row += 30;
        addDrawableChild(ButtonWidget.builder(Text.literal("关闭"), button -> close())
                .dimensions(x + WIDTH / 2 - 45, row, 90, 20)
                .build());
    }

    private void addCommandButton(int x, int y, int width, String label, String command) {
        addDrawableChild(ButtonWidget.builder(Text.literal(label), button -> {
            if (client != null && client.getNetworkHandler() != null) {
                client.getNetworkHandler().sendChatCommand(command);
                close();
            }
        }).dimensions(x, y, width, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int x = this.width / 2 - WIDTH / 2;
        int y = this.height / 2 - HEIGHT / 2;
        context.fill(x, y, x + WIDTH, y + HEIGHT, PANEL);
        context.drawBorder(x, y, WIDTH, HEIGHT, BORDER);
        context.drawCenteredTextWithShadow(this.textRenderer, ModelNameUtil.companionName(), this.width / 2, y + 14, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, "AI 伙伴交互菜单", this.width / 2, y + 29, HINT);
        super.render(context, mouseX, mouseY, delta);
    }
}
