package dev.sivren.flashlight.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SimpleAnimatedParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;

/**
 * Красный дым сигнальной шашки: 12-кадровая анимация, медленно всплывает,
 * растёт и тает. Спавнится клиентом у горящей шашки в руке (FlashlightClient).
 */
public class RedSmokeParticle extends SimpleAnimatedParticle {

    protected RedSmokeParticle(ClientLevel level, double x, double y, double z,
                               double xd, double yd, double zd, SpriteSet sprites) {
        super(level, x, y, z, sprites, -0.01f);
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.friction = 0.97f;
        // Сигнальный столб: ~0.12 блока/тик на протяжении ~13 секунд = до ~25 блоков.
        this.lifetime = 240 + this.random.nextInt(80);
        this.quadSize = 0.25f + this.random.nextFloat() * 0.15f;
        this.alpha = 0.85f;
        setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        // Поддерживаем подъём (friction гасит скорость — возвращаем её).
        if (this.age < this.lifetime * 0.85f) {
            this.yd = Math.min(this.yd + 0.012, 0.13);
        }
        // Лёгкий ветровой дрейф, растёт и тает к концу жизни.
        this.xd += (this.random.nextDouble() - 0.5) * 0.0015;
        this.zd += (this.random.nextDouble() - 0.5) * 0.0015;
        this.quadSize *= 1.006f;
        if (this.age > this.lifetime * 0.75f) {
            this.alpha = Math.max(0.0f, this.alpha - 0.015f);
        }
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {

        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double xd, double yd, double zd, RandomSource random) {
            return new RedSmokeParticle(level, x, y, z, xd, yd, zd, sprites);
        }
    }
}
