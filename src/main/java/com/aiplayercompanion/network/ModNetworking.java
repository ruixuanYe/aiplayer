package com.aiplayercompanion.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class ModNetworking {
    private ModNetworking() {
    }

    public static void registerCommon() {
        PayloadTypeRegistry.playS2C().register(OpenCompanionMenuPayload.ID, OpenCompanionMenuPayload.CODEC);
    }
}
