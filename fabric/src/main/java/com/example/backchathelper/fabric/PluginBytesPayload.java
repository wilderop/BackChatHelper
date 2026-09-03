package com.example.backchathelper.fabric;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

record PluginBytesPayload(CustomPacketPayload.Type<PluginBytesPayload> kind, byte[] data)
        implements CustomPacketPayload {

    static final class TypeHolder {
        final CustomPacketPayload.Type<PluginBytesPayload> type;
        final StreamCodec<FriendlyByteBuf, PluginBytesPayload> codec;

        private TypeHolder(CustomPacketPayload.Type<PluginBytesPayload> type,
                           StreamCodec<FriendlyByteBuf, PluginBytesPayload> codec) {
            this.type = type;
            this.codec = codec;
        }

        static TypeHolder of(String id) {
            CustomPacketPayload.Type<PluginBytesPayload> type =
                    new CustomPacketPayload.Type<>(Identifier.parse(id));
            return new TypeHolder(type, codec(type));
        }
    }

    static StreamCodec<FriendlyByteBuf, PluginBytesPayload> codec(
            CustomPacketPayload.Type<PluginBytesPayload> type) {
        return StreamCodec.of(
                (buf, payload) -> buf.writeBytes(payload.data),
                buf -> {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    return new PluginBytesPayload(type, data);
                });
    }

    @Override
    public CustomPacketPayload.Type<PluginBytesPayload> type() {
        return kind;
    }
}
