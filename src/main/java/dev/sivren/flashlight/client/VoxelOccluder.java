package dev.sivren.flashlight.client;

import java.util.OptionalDouble;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Воксельная карта непрозрачности вокруг камеры для окклюзии луча
 * (чтобы свет не проходил сквозь стены). Сетка {@value #N}³ блоков,
 * упакована в 2D-атлас {@value #COLS}x{@value #ROWS} срезов по Z.
 * Обновляется бюджетно: по несколько срезов за тик — без фризов.
 */
public final class VoxelOccluder {

    public static final int N = 48;
    public static final int COLS = 8;
    public static final int ROWS = 6;
    private static final int SLICES_PER_UPDATE = 6;
    private static final int RECENTER_DISTANCE = 10;

    private static GpuTexture texture;
    private static GpuTextureView textureView;
    private static GpuSampler sampler;
    private static NativeImage image;

    private static BlockPos origin = BlockPos.ZERO;
    private static int nextSlice = 0;
    private static boolean ready = false;

    private VoxelOccluder() {
    }

    /** Вызывается каждый кадр из движка. Держит сетку центрированной и свежей. */
    public static void update(ClientLevel level, Vec3 cameraPos) {
        ensureResources();

        BlockPos camBlock = BlockPos.containing(cameraPos);
        BlockPos wantedOrigin = camBlock.offset(-N / 2, -N / 2, -N / 2);
        if (Math.abs(wantedOrigin.getX() - origin.getX()) > RECENTER_DISTANCE
                || Math.abs(wantedOrigin.getY() - origin.getY()) > RECENTER_DISTANCE
                || Math.abs(wantedOrigin.getZ() - origin.getZ()) > RECENTER_DISTANCE
                || !ready && nextSlice == 0) {
            origin = wantedOrigin;
            nextSlice = 0;
            ready = false;
        }

        // Бюджетное обновление: несколько Z-срезов за вызов.
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int updated = 0;
        while (updated < SLICES_PER_UPDATE && nextSlice < N) {
            int z = nextSlice;
            int atlasX0 = (z % COLS) * N;
            int atlasY0 = (z / COLS) * N;
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    boolean opaque = level.getBlockState(cursor).canOcclude();
                    image.setPixel(atlasX0 + x, atlasY0 + y, opaque ? 0xFFFFFFFF : 0xFF000000);
                }
            }
            nextSlice++;
            updated++;
        }
        if (updated > 0 && nextSlice >= N) {
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image);
            ready = true;
            nextSlice = 0; // следующий проход обновит сетку по кругу
        }
    }

    public static boolean ready() {
        return ready;
    }

    /** Origin сетки относительно камеры (для шейдера). */
    public static Vec3 originCameraRelative(Vec3 cameraPos) {
        return new Vec3(origin.getX() - cameraPos.x, origin.getY() - cameraPos.y, origin.getZ() - cameraPos.z);
    }

    public static GpuTextureView textureView() {
        return textureView;
    }

    public static GpuSampler sampler() {
        return sampler;
    }

    private static void ensureResources() {
        if (texture != null) {
            return;
        }
        GpuDevice device = RenderSystem.getDevice();
        texture = device.createTexture(() -> "Flashlight occluder",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                TextureFormat.RGBA8, N * COLS, N * ROWS, 1, 1);
        textureView = device.createTextureView(texture);
        sampler = device.createSampler(
                AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
        image = new NativeImage(NativeImage.Format.RGBA, N * COLS, N * ROWS, true);
    }
}
