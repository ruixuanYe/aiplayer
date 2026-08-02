package com.aiplayercompanion.client;

import com.aiplayercompanion.config.ModConfig;
import com.aiplayercompanion.util.ModelNameUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class CompanionMenuScreen extends Screen {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 214;
    private static final int PANEL = 0xE0181A22;
    private static final int BORDER = 0xFF4A5268;
    private static final int HINT = 0xFFAAB0C0;

    private Page page = Page.HOME;

    public CompanionMenuScreen() {
        super(Text.literal(ModelNameUtil.companionName()));
    }

    @Override
    protected void init() {
        int x = this.width / 2 - WIDTH / 2;
        int y = this.height / 2 - HEIGHT / 2;
        int left = x + 20;
        int right = x + 164;
        int buttonWidth = 136;
        int row = y + 54;

        if (page == Page.HOME) {
            addPageButton(left, row, buttonWidth, "AIPlayer", Page.PLAYER);
            addPageButton(right, row, buttonWidth, "行为模式", Page.MOVEMENT);
            row += 26;
            addPageButton(left, row, buttonWidth, "战斗选项", Page.COMBAT);
            addPageButton(right, row, buttonWidth, "装备", Page.EQUIPMENT);
            row += 34;
            addDrawableChild(ButtonWidget.builder(Text.literal("Mod 设置"), button -> MinecraftClient.getInstance().setScreen(new AIPlayerConfigScreen(this)))
                    .dimensions(left, row, buttonWidth, 20)
                    .build());
            addCommandButton(right, row, buttonWidth, "测试 API", "aiplayer config test", true);
            row += 34;
            addCloseButton(x, row);
            return;
        }

        switch (page) {
            case PLAYER -> {
                addCommandButton(left, row, buttonWidth, "召唤", "aiplayer spawn", true);
                addCommandButton(right, row, buttonWidth, "删除", "aiplayer remove", true);
                row += 26;
                addCommandButton(left, row, buttonWidth, "状态", "aiplayer status", true);
                addCommandButton(right, row, buttonWidth, "自动命名", "aiplayer rename-auto", true);
            }
            case MOVEMENT -> {
                addCommandButton(left, row, buttonWidth, "跟随", "aiplayer follow", true);
                addCommandButton(right, row, buttonWidth, "等待", "aiplayer stop", true);
                row += 26;
                addCommandButton(left, row, buttonWidth, "传送过来", "aiplayer come", true);
            }
            case COMBAT -> {
                ModConfig config = ModConfig.get();
                addCommandButton(left, row, buttonWidth, toggleLabel("主动攻击", config.botAutoCombat), "aiplayer behavior combat", false);
                addCommandButton(right, row, buttonWidth, toggleLabel("保护主人", config.botProtectOwner), "aiplayer behavior protect", false);
            }
            case EQUIPMENT -> {
                ModConfig config = ModConfig.get();
                addCommandButton(left, row, buttonWidth, toggleLabel("自动拾取", config.botAutoPickup), "aiplayer behavior pickup", false);
                addCommandButton(right, row, buttonWidth, toggleLabel("自动装备", config.botAutoEquip), "aiplayer behavior equip", false);
                row += 26;
                addCommandButton(left, row, buttonWidth, toggleLabel("自动选武器", config.botAutoWeapon), "aiplayer behavior weapon", false);
                addCommandButton(right, row, buttonWidth, "打开背包", "aiplayer inventory", true);
            }
            default -> {
            }
        }

        row = y + HEIGHT - 34;
        addDrawableChild(ButtonWidget.builder(Text.literal("返回"), button -> {
            page = Page.HOME;
            rebuild();
        }).dimensions(left, row, buttonWidth, 20).build());
        addCloseButton(x, row);
    }

    private String toggleLabel(String label, boolean enabled) {
        return (enabled ? "✓ " : "□ ") + label;
    }

    private void addPageButton(int x, int y, int width, String label, Page target) {
        addDrawableChild(ButtonWidget.builder(Text.literal(label), button -> {
            page = target;
            rebuild();
        }).dimensions(x, y, width, 20).build());
    }

    private void addCommandButton(int x, int y, int width, String label, String command, boolean closeAfterClick) {
        addDrawableChild(ButtonWidget.builder(Text.literal(label), button -> {
            if (client != null && client.getNetworkHandler() != null) {
                client.getNetworkHandler().sendChatCommand(command);
                if (closeAfterClick) {
                    close();
                } else {
                    rebuild();
                }
            }
        }).dimensions(x, y, width, 20).build());
    }

    private void addCloseButton(int panelX, int y) {
        addDrawableChild(ButtonWidget.builder(Text.literal("关闭"), button -> close())
                .dimensions(panelX + WIDTH - 20 - 136, y, 136, 20)
                .build());
    }

    private void rebuild() {
        clearChildren();
        init();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int x = this.width / 2 - WIDTH / 2;
        int y = this.height / 2 - HEIGHT / 2;
        context.fill(x, y, x + WIDTH, y + HEIGHT, PANEL);
        context.drawBorder(x, y, WIDTH, HEIGHT, BORDER);
        context.drawCenteredTextWithShadow(this.textRenderer, ModelNameUtil.companionName(), this.width / 2, y + 12, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, subtitle(), this.width / 2, y + 29, HINT);
        super.render(context, mouseX, mouseY, delta);
    }

    private String subtitle() {
        return switch (page) {
            case HOME -> "G 键菜单";
            case PLAYER -> "召唤和删除";
            case MOVEMENT -> "跟随、等待和传送";
            case COMBAT -> "战斗开关";
            case EQUIPMENT -> "拾取、装备和背包";
        };
    }

    private enum Page {
        HOME,
        PLAYER,
        MOVEMENT,
        COMBAT,
        EQUIPMENT
    }
}
