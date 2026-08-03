package com.aiplayercompanion.fakeplayer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.Packet;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

public final class AIPlayerClientConnection extends ClientConnection {
    private static final SocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 0);

    public AIPlayerClientConnection() {
        super(NetworkSide.SERVERBOUND);
        installBackingChannel();
    }

    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener callbacks) {
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener callbacks, boolean flush) {
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public boolean isChannelAbsent() {
        return false;
    }

    @Override
    public SocketAddress getAddress() {
        return ADDRESS;
    }

    @Override
    public String getAddressAsString(boolean useHostname) {
        return "aiplayer-fake";
    }

    private void installBackingChannel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        for (Field field : ClientConnection.class.getDeclaredFields()) {
            if (Channel.class.isAssignableFrom(field.getType())) {
                setField(field, channel);
            } else if (SocketAddress.class.isAssignableFrom(field.getType())) {
                setField(field, ADDRESS);
            }
        }
    }

    private void setField(Field field, Object value) {
        try {
            field.setAccessible(true);
            field.set(this, value);
        } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
        }
    }
}
