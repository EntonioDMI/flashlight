package dev.sivren.flashlight.client;

import dev.sivren.flashlight.FlashlightItem;
import dev.sivren.flashlight.ModSounds;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

/**
 * Гул работающего большого фонаря: зациклен, следует за игроком, глохнет,
 * когда фонарь выключили/сменили предмет/игрок ушёл. Управляется из
 * FlashlightClient (один инстанс на игрока с горящим прожектором).
 */
public class LanternHumSound extends AbstractTickableSoundInstance {

    private final Player player;

    public LanternHumSound(Player player) {
        super(ModSounds.LANTERN_LOOP, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
        this.player = player;
        this.looping = true;
        this.volume = 0.45f;
        this.x = player.getX();
        this.y = player.getY();
        this.z = player.getZ();
    }

    @Override
    public void tick() {
        FlashlightItem lit = FlashlightEngine.litFlashlight(player);
        if (player.isRemoved() || lit == null || !lit.isWideBeam()) {
            stop();
            return;
        }
        this.x = player.getX();
        this.y = player.getY();
        this.z = player.getZ();
    }
}
