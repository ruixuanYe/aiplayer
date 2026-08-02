package com.aiplayercompanion.network;

import com.aiplayercompanion.AIPlayerCompanionMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record OpenCompanionMenuPayload() implements CustomPayload {
    public static final OpenCompanionMenuPayload INSTANCE = new OpenCompanionMenuPayload();
    public static final CustomPayload.Id<OpenCompanionMenuPayload> ID = new CustomPayload.Id<>(
            Identifier.of(AIPlayerCompanionMod.MOD_ID, "open_companion_menu")
    );
    public static final PacketCodec<RegistryByteBuf, OpenCompanionMenuPayload> CODEC = PacketCodec.unit(INSTANCE);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
