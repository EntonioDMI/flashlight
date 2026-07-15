package dev.sivren.flashlight.client;

import java.util.Arrays;
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
 *
 * Обновление в три режима:
 * 1) полная перестройка после рецентра/входа в мир — бюджетно, по несколько
 *    срезов за кадр (без фризов);
 * 2) устойчивое состояние — только срезы, где реально менялись блоки
 *    (ClientLevelMixin -> {@link #markDirty}); статичный мир не сканируется;
 * 3) медленный фоновый круг — страховка от пропущенных событий.
 * Аплоад атласа в GPU — только когда содержимое реально изменилось.
 */
public final class VoxelOccluder {

    public static final int N = 48;
    public static final int COLS = 8;
    public static final int ROWS = 6;
    private static final int SLICES_PER_UPDATE = 6;
    private static final int RECENTER_DISTANCE = 10;
    /** Фоновая страховка: 1 срез раз в столько кадров (полный круг ~6 c при 60 FPS). */
    private static final int TRICKLE_INTERVAL = 8;

    static {
        // Атлас упаковывает ровно N срезов в сетку COLS x ROWS —
        // иначе setPixel пишет мимо атласа (порча памяти/краш).
        if (COLS * ROWS != N) {
            throw new IllegalStateException(
                    "VoxelOccluder: COLS*ROWS (" + COLS * ROWS + ") != N (" + N + ")");
        }
    }

    private static GpuTexture texture;
    private static GpuTextureView textureView;
    private static GpuSampler sampler;
    private static NativeImage image;

    private static BlockPos origin = BlockPos.ZERO;
    /** < N — идёт полная перестройка (индекс следующего среза). */
    private static int rebuildSlice = N;
    private static int trickleSlice = 0;
    private static int frameCounter = 0;
    private static boolean ready = false;
    /** Срезы с изменившимися блоками (индекс = z относительно origin). */
    private static final boolean[] dirty = new boolean[N];

    private VoxelOccluder() {
    }

    /** Вызывается каждый кадр из движка. Держит сетку центрированной и свежей. */
    public static void update(ClientLevel level, Vec3 cameraPos) {
        ensureResources();
        frameCounter++;

        BlockPos camBlock = BlockPos.containing(cameraPos);
        BlockPos wantedOrigin = camBlock.offset(-N / 2, -N / 2, -N / 2);
        if (Math.abs(wantedOrigin.getX() - origin.getX()) > RECENTER_DISTANCE
                || Math.abs(wantedOrigin.getY() - origin.getY()) > RECENTER_DISTANCE
                || Math.abs(wantedOrigin.getZ() - origin.getZ()) > RECENTER_DISTANCE
                || !ready && rebuildSlice >= N) {
            origin = wantedOrigin;
            rebuildSlice = 0;
            ready = false;
            Arrays.fill(dirty, false);
        }

        boolean changed = false;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        if (rebuildSlice < N) {
            // Полная перестройка: несколько Z-срезов за кадр, аплоад по завершении.
            int updated = 0;
            while (updated < SLICES_PER_UPDATE && rebuildSlice < N) {
                scanSlice(level, cursor, rebuildSlice);
                rebuildSlice++;
                updated++;
            }
            if (rebuildSlice >= N) {
                ready = true;
                changed = true;
            }
        } else {
            // Устойчивое состояние: только грязные срезы...
            int budget = SLICES_PER_UPDATE;
            for (int z = 0; z < N && budget > 0; z++) {
                if (dirty[z]) {
                    dirty[z] = false;
                    changed |= scanSlice(level, cursor, z);
                    budget--;
                }
            }
            // ...и медленный фоновый круг — на случай изменений без
            // setBlocksDirty (массовые апдейты, пистоны на границе сетки).
            if (frameCounter % TRICKLE_INTERVAL == 0) {
                changed |= scanSlice(level, cursor, trickleSlice);
                trickleSlice = (trickleSlice + 1) % N;
            }
        }
        if (changed) {
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image);
        }
    }

    /** Пересканирует один Z-срез. true, если содержимое реально изменилось. */
    private static boolean scanSlice(ClientLevel level, BlockPos.MutableBlockPos cursor, int z) {
        int atlasX0 = (z % COLS) * N;
        int atlasY0 = (z / COLS) * N;
        boolean changed = false;
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                int pixel = level.getBlockState(cursor).canOcclude() ? 0xFFFFFFFF : 0xFF000000;
                if (image.getPixel(atlasX0 + x, atlasY0 + y) != pixel) {
                    image.setPixel(atlasX0 + x, atlasY0 + y, pixel);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * Клиентское изменение блока (ClientLevelMixin.setBlocksDirty):
     * помечает Z-срез грязным, если блок внутри сетки.
     */
    public static void markDirty(BlockPos pos) {
        int x = pos.getX() - origin.getX();
        int y = pos.getY() - origin.getY();
        int z = pos.getZ() - origin.getZ();
        if (x >= 0 && x < N && y >= 0 && y < N && z >= 0 && z < N) {
            dirty[z] = true;
        }
    }

    /** Мир сменился (измерение/новый сервер): сетка устарела целиком. */
    public static void invalidate() {
        ready = false;
        rebuildSlice = N; // условие в update() запустит полную перестройку
        Arrays.fill(dirty, false);
    }

    /**
     * Освобождает GPU-текстуру и нативную память атласа (выход из мира).
     * Сэмплер не трогаем: он крошечный и может быть закэширован устройством.
     */
    public static void close() {
        if (texture != null) {
            textureView.close();
            texture.close();
            image.close();
            textureView = null;
            texture = null;
            image = null;
        }
        invalidate();
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

    /** Сырая текстура атласа (Sodium-компат биндит её raw GL-ем) или null. */
    public static GpuTexture texture() {
        return texture;
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
        if (sampler == null) {
            sampler = device.createSampler(
                    AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
        }
        image = new NativeImage(NativeImage.Format.RGBA, N * COLS, N * ROWS, true);
    }
}
