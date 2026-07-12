package dev.sivren.flashlight.net;

import dev.sivren.flashlight.Flashlight;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C2S: игрок нажал клавишу перезарядки фонаря. */
public record ReloadFlashlightPayload() implements CustomPacketPayload {

    public static final ReloadFlashlightPayload INSTANCE = new ReloadFlashlightPayload();

    public static final Type<ReloadFlashlightPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Flashlight.MOD_ID, "reload"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ReloadFlashlightPayload> CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
