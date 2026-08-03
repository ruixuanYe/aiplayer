package com.aiplayercompanion.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class AIPlayerMenuScreen extends Screen {
    private static final int BUTTON_WIDTH = 140;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 8;

    private final Screen parent;
    private Page page = Page.MAIN;

    public AIPlayerMenuScreen(Screen parent) {
        super(Text.literal("AIPlayer"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearChildren();
        int centerX = width / 2;
        int y = Math.max(58, height / 2 - 50);

        if (page == Page.MAIN) {
            addMenuButton(centerX, y, "AIPlayer", button -> {
                page = Page.AIPLAYER;
                rebuild();
            });
            y += BUTTON_HEIGHT + GAP;
            addMenuButton(centerX, y, "行为", button -> {
                page = Page.BEHAVIOR;
                rebuild();
            });
        } else if (page == Page.AIPLAYER) {
            addMenuButton(centerX, y, "召唤", button -> runCommandAndClose("aiplayer spawn"));
            y += BUTTON_HEIGHT + GAP;
            addMenuButton(centerX, y, "删除", button -> runCommandAndClose("aiplayer remove"));
        } else if (page == Page.BEHAVIOR) {
            addMenuButton(centerX, y, "跟随", button -> runCommandAndClose("aiplayer follow"));
            y += BUTTON_HEIGHT + GAP;
            addMenuButton(centerX, y, "等待", button -> runCommandAndClose("aiplayer stop"));
            y += BUTTON_HEIGHT + GAP;
            addMenuButton(centerX, y, "传送", button -> runCommandAndClose("aiplayer teleport"));
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("返回"), button -> back())
                .dimensions(centerX - BUTTON_WIDTH / 2, height - 34, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
    }

    private void addMenuButton(int centerX, int y, String label, ButtonWidget.PressAction action) {
        addDrawableChild(ButtonWidget.builder(Text.literal(label), action)
                .dimensions(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
    }

    private void back() {
        if (page == Page.MAIN) {
            close();
            return;
        }
        page = Page.MAIN;
        rebuild();
    }

    private void runCommandAndClose(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatCommand(command);
        }
        close();
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xA0101010);
        int panelWidth = 190;
        int panelHeight = page == Page.BEHAVIOR ? 160 : 130;
        int panelX = width / 2 - panelWidth / 2;
        int panelY = Math.max(36, height / 2 - panelHeight / 2);
        context.fill(panelX + 2, panelY + 2, panelX + panelWidth + 2, panelY + panelHeight + 2, 0x70101010);
        context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xD0181C24);
        context.drawBorder(panelX, panelY, panelWidth, panelHeight, 0xFF556070);

        Text heading = switch (page) {
            case MAIN -> Text.literal("AIPlayer 菜单");
            case AIPLAYER -> Text.literal("AIPlayer");
            case BEHAVIOR -> Text.literal("行为");
        };
        context.drawTextWithShadow(textRenderer, heading, width / 2 - textRenderer.getWidth(heading) / 2, panelY + 12, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    private enum Page {
        MAIN,
        AIPLAYER,
        BEHAVIOR
    }
}
