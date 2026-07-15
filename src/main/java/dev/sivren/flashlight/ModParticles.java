package dev.sivren.flashlight;

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;

import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** Партиклы мода. Спрайты — assets/flashlight/particles/red_smoke.json. */
public final class ModParticles {

    /**
     * Красный дым сигнальной шашки (12 кадров). alwaysSpawn = true: сигнальный
     * дым обязан быть виден издалека — обходим ванильный лимит 32 блока
     * и настройку «минимальные частицы».
     */
    public static final SimpleParticleType RED_SMOKE = FabricParticleTypes.simple(true);

    private ModParticles() {
    }

    public static void init() {
        Registry.register(BuiltInRegistries.PARTICLE_TYPE,
                Identifier.fromNamespaceAndPath(Flashlight.MOD_ID, "red_smoke"), RED_SMOKE);
    }
}
