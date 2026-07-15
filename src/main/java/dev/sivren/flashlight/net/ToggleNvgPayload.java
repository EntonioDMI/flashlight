package dev.sivren.flashlight.net;

import dev.sivren.flashlight.Flashlight;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C2S: игрок нажал клавишу переключения ПНВ. */
public record ToggleNvgPayload() implements CustomPacketPayload {

    public static final ToggleNvgPayload INSTANCE = new ToggleNvgPayload();

    public static final Type<ToggleNvgPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Flashlight.MOD_ID, "toggle_nvg"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleNvgPayload> CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
