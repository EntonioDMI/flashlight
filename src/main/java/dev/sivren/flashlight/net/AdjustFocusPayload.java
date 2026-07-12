package dev.sivren.flashlight.net;

import dev.sivren.flashlight.Flashlight;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C2S: игрок крутит колёсико — меняет фокус линзы фонарика (+1 уже, -1 шире). */
public record AdjustFocusPayload(int delta) implements CustomPacketPayload {

    public static final Type<AdjustFocusPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Flashlight.MOD_ID, "focus"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AdjustFocusPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, AdjustFocusPayload::delta, AdjustFocusPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
