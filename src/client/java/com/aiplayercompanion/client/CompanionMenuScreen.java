package com.aiplayercompanion.client;

import com.aiplayercompanion.config.ModConfig;
import com.aiplayercompanion.util.ModelNameUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class CompanionMenuScreen extends Screen {
    private static final int WIDTH = 300;
    private static final int HEIGHT = 248;
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
        int buttonWidth = 128;
        int left = x + 16;
        int right = x + WIDTH - 16 - buttonWidth;
        int row = y + 48;

        addCommandButton(left, row, buttonWidth, "跟随", "aiplayer follow");
        addCommandButton(right, row, buttonWidth, "等待", "aiplayer stop");
        row += 24;
        addCommandButton(left, row, buttonWidth, "来这里", "aiplayer come");
        addCommandButton(right, row, buttonWidth, "状态", "aiplayer status");
        row += 30;

        ModConfig config = ModConfig.get();
        addCommandButton(left, row, buttonWidth, toggleLabel("主动攻击", config.botAutoCombat), "aiplayer behavior combat", false);
        addCommandButton(right, row, buttonWidth, toggleLabel("自动拾取", config.botAutoPickup), "aiplayer behavior pickup", false);
        row += 24;
        addCommandButton(left, row, buttonWidth, toggleLabel("自动装备", config.botAutoEquip), "aiplayer behavior equip", false);
        addCommandButton(right, row, buttonWidth, toggleLabel("自动选武器", config.botAutoWeapon), "aiplayer behavior weapon", false);
        row += 30;

        addCommandButton(left, row, buttonWidth, "自然命名", "aiplayer rename-auto");
        addCommandButton(right, row, buttonWidth, "测试 API", "aiplayer config test");
        row += 24;
        addDrawableChild(ButtonWidget.builder(Text.literal("打开设置"), button -> MinecraftClient.getInstance().setScreen(new AIPlayerConfigScreen(this)))
                .dimensions(left, row, buttonWidth, 20)
                .build());
        addCommandButton(right, row, buttonWidth, "删除伙伴", "aiplayer remove");
        row += 32;
        addDrawableChild(ButtonWidget.builder(Text.literal("关闭"), button -> close())
                .dimensions(x + WIDTH / 2 - 45, row, 90, 20)
                .build());
    }

    private String toggleLabel(String label, boolean enabled) {
        return (enabled ? "✓ " : "□ ") + label;
    }

    private void addCommandButton(int x, int y, int width, String label, String command) {
        addCommandButton(x, y, width, label, command, true);
    }

    private void addCommandButton(int x, int y, int width, String label, String command, boolean closeAfterClick) {
        addDrawableChild(ButtonWidget.builder(Text.literal(label), button -> {
            if (client != null && client.getNetworkHandler() != null) {
                client.getNetworkHandler().sendChatCommand(command);
                if (closeAfterClick) {
                    close();
                } else {
                    this.clearChildren();
                    init();
                }
            }
        }).dimensions(x, y, width, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int x = this.width / 2 - WIDTH / 2;
        int y = this.height / 2 - HEIGHT / 2;
        context.fill(x, y, x + WIDTH, y + HEIGHT, PANEL);
        context.drawBorder(x, y, WIDTH, HEIGHT, BORDER);
        context.drawCenteredTextWithShadow(this.textRenderer, ModelNameUtil.companionName(), this.width / 2, y + 12, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, "G 键打开，右键不再弹出菜单", this.width / 2, y + 27, HINT);
        context.drawCenteredTextWithShadow(this.textRenderer, "行为开关", this.width / 2, y + 99, HINT);
        super.render(context, mouseX, mouseY, delta);
    }
}
