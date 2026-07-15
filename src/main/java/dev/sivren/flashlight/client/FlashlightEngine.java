package dev.sivren.flashlight.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.sivren.flashlight.FlashlightItem;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3f;

/**
 * Движок фонарика v4: конусный свет считается прямо в террейн-шейдере.
 * Пайплайны чанков подменяются на копии с нашим fsh (паттерн Enhanced Darkness),
 * параметры до {@value #MAX_LIGHTS} источников едут uniform-буфером и обновляются
 * каждый кадр — мгновенно, с настоящими текстурами (albedo * cone) и видимо всем
 * игрокам: конусы других игроков собираются из их предметов в руках (компоненты
 * синхронизируются ванилью).
 */
public final class FlashlightEngine {

    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("flashlight", "core/fl_terrain");

    public static final RenderPipeline FL_SOLID = RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("flashlight", "pipeline/fl_solid_terrain"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withUniform("FlashLights", UniformType.UNIFORM_BUFFER)
            .withSampler("FlOccluder")
            .build();

    public static final RenderPipeline FL_CUTOUT = RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("flashlight", "pipeline/fl_cutout_terrain"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withShaderDefine("ALPHA_CUTOUT", 0.5F)
            .withUniform("FlashLights", UniformType.UNIFORM_BUFFER)
            .withSampler("FlOccluder")
            .build();

    public static final RenderPipeline FL_TRANSLUCENT = RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("flashlight", "pipeline/fl_translucent_terrain"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withShaderDefine("ALPHA_CUTOUT", 0.01F)
            .withUniform("FlashLights", UniformType.UNIFORM_BUFFER)
            .withSampler("FlOccluder")
            .build();

    private static final Identifier ENTITY_SHADER = Identifier.fromNamespaceAndPath("flashlight", "core/fl_entity");
    private static final Identifier PARTICLE_SHADER = Identifier.fromNamespaceAndPath("flashlight", "core/fl_particle");
    private static final Identifier BLOCK_SHADER = Identifier.fromNamespaceAndPath("flashlight", "core/fl_block");
    private static final Identifier ITEM_SHADER = Identifier.fromNamespaceAndPath("flashlight", "core/fl_item");
    private static final Identifier SHADOW_SHADER = Identifier.fromNamespaceAndPath("flashlight", "core/fl_shadow");

    // ВНИМАНИЕ: 32/16 продублированы в fl_lights.glsl (размеры массивов UBO и циклы).
    // Меняешь здесь — меняй и там, иначе std140-упаковка разъедется с шейдером.
    private static final int MAX_LIGHTS = 32;
    private static final int MAX_CAPSULES = 16;
    /**
     * Лимит источников НА ЧАНК: толпа фонарей/шашек в одном месте не съедает
     * все слоты UBO — дальние чанки получают свои, и потолок MAX_LIGHTS
     * на практике не ощущается. Кандидаты уже отсортированы по дистанции,
     * так что в переполненном чанке выживают ближайшие 4.
     */
    private static final int MAX_PER_CHUNK = 4;
    /** Занятые слоты по чанкам в текущем кадре: ключ ChunkPos.asLong. */
    private static final java.util.HashMap<Long, Integer> CHUNK_SLOTS = new java.util.HashMap<>();
    /** std140: mat4 + count + 3x4 vec4 огней + 2 vec4 вокселей + 16x2 vec4 капсул. */
    private static final int UBO_SIZE = 64 + 16 + MAX_LIGHTS * 3 * 16 + 32 + MAX_CAPSULES * 32;

    private static GpuBuffer ubo;
    private static boolean uboHasData = false;
    private static boolean compiled = false;
    private static final ByteBuffer SCRATCH = ByteBuffer.allocateDirect(UBO_SIZE).order(ByteOrder.nativeOrder());

    // Инерция луча локального игрока: конус плавно догоняет взгляд.
    private static final Vector3f smoothDir = new Vector3f(0, 0, -1);
    private static final Vector3f lookTarget = new Vector3f();
    private static long lastFrameNanos = 0;
    private static net.minecraft.client.multiplayer.ClientLevel lastLevel = null;

    private FlashlightEngine() {
    }

    /** true, если подмена пайплайна активна (буфер готов). */
    public static boolean active = false;

    /**
     * Полный сброс при выходе из мира: GPU-буфер и воксельный атлас
     * освобождаются, всё пересоздастся лениво при следующем входе.
     */
    public static void reset() {
        if (ubo != null) {
            ubo.close();
            ubo = null;
        }
        uboHasData = false;
        worldBeams = List.of();
        active = false;
        lastFrameNanos = 0;
        lastLevel = null;
        VoxelOccluder.close();
    }

    /** Вызывается в начале кадра (HEAD GameRenderer.renderLevel). */
    public static void update() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            worldBeams = List.of();
            active = false;
            return;
        }
        if (mc.level != lastLevel) {
            // Смена измерения/сервера: воксельная сетка прошлого мира — мусор.
            lastLevel = mc.level;
            VoxelOccluder.invalidate();
        }
        ensureResources();

        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 cameraPos = mc.gameRenderer.getMainCamera().position();

        // Инерция направления для локального игрока.
        Vec3 look = mc.player.getViewVector(partialTick);
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0.016f : Math.min((now - lastFrameNanos) / 1.0e9f, 0.1f);
        lastFrameNanos = now;
        float alpha = 1.0f - (float) Math.exp(-dt / 0.09f);
        lookTarget.set((float) look.x, (float) look.y, (float) look.z);
        smoothDir.lerp(lookTarget, alpha);
        smoothDir.normalize();

        // Собираем до MAX_LIGHTS источников: фонари (конусы) + сигнальные шашки
        // (точечный красный свет: cosOuter < -1 => angular = 1 во все стороны).
        record Beam(Vec3 pos, Vector3f dir, float[] params, Vector3f color, float bright) {
        }
        List<Beam> beams = new ArrayList<>();
        CHUNK_SLOTS.clear();
        List<? extends Player> players = mc.level.players();
        if (players.size() > 1) {
            // Слоты ограничены — ближние к камере важнее.
            List<Player> sorted = new ArrayList<>(players);
            sorted.sort(Comparator.comparingDouble(p -> p.position().distanceToSqr(cameraPos)));
            players = sorted;
        }
        long now0 = System.nanoTime();
        for (Player player : players) {
            if (beams.size() >= MAX_LIGHTS) {
                break;
            }
            ItemStack litStack = litStack(player);
            if (litStack != null && reserveChunkSlot(player.position())) {
                FlashlightItem item = (FlashlightItem) litStack.getItem();
                float focus = litStack.getOrDefault(dev.sivren.flashlight.ModComponents.FOCUS, 10) / 10.0f;
                Vec3 lens = lensPos(player, partialTick);
                Vector3f dir;
                if (player == mc.player) {
                    dir = new Vector3f(smoothDir);
                } else {
                    Vec3 view = player.getViewVector(partialTick);
                    dir = new Vector3f((float) view.x, (float) view.y, (float) view.z);
                }
                float[] p = beamParams(item, focus);
                beams.add(new Beam(lens.subtract(cameraPos), dir, p,
                        new Vector3f(1.0f, 0.96f, 0.86f),
                        p[3] * lowChargeFactor(litStack, item)));
            }
        }
        // Один проход по сущностям: горящие шашки (свет) + живые сущности
        // (капсулы-окклюдеры для силуэтных теней) — вместо двух обходов за кадр.
        // Капсулы: игроков с горящим фонарём пропускаем — луч рождается в них самих.
        List<dev.sivren.flashlight.FlareEntity> flares = new ArrayList<>();
        List<net.minecraft.world.entity.LivingEntity> shadowCasters = new ArrayList<>();
        for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof dev.sivren.flashlight.FlareEntity flare
                    && flare.position().distanceToSqr(cameraPos) < 96 * 96) {
                flares.add(flare);
            } else if (entity instanceof net.minecraft.world.entity.LivingEntity living
                    && !living.isRemoved()
                    && !(living instanceof Player player && litStack(player) != null)
                    && living.position().distanceToSqr(cameraPos) <= 40 * 40) {
                shadowCasters.add(living);
            }
        }
        flares.sort(Comparator.comparingDouble(f -> f.position().distanceToSqr(cameraPos)));
        for (dev.sivren.flashlight.FlareEntity flare : flares) {
            if (beams.size() >= MAX_LIGHTS) {
                break;
            }
            if (!reserveChunkSlot(flare.position())) {
                continue; // в этом чанке уже 4 источника — ближайшие уже светят
            }
            // Фаза мерцания своя у каждой шашки (по id сущности).
            double phase = flare.getId() * 1.7;
            float flicker = 0.8f + 0.14f * (float) Math.sin(now0 / 9.0e7 + phase)
                    + 0.06f * (float) Math.sin(now0 / 2.3e7 + phase * 2.0);
            beams.add(new Beam(flare.position().add(0, 0.25, 0).subtract(cameraPos),
                    new Vector3f(0, 1, 0), FLARE_PARAMS,
                    new Vector3f(1.0f, 0.30f, 0.16f), flicker));
        }

        // Ни одного горящего фонаря — рендерим ванилью (ноль накладных расходов).
        // Патченый шейдер Sodium читает UBO всегда — гасим счётчик явно.
        if (beams.isEmpty()) {
            if (uboHasData) {
                zeroUbo();
            }
            worldBeams = List.of();
            active = false;
            return;
        }

        // Публикуем лучи в мировых координатах — для подавления ванильной
        // blob-тени у сущностей, попавших в конус (EntityRenderDispatcherMixin).
        List<WorldBeam> published = new ArrayList<>(beams.size());
        for (Beam b : beams) {
            published.add(new WorldBeam(b.pos().add(cameraPos),
                    new Vec3(b.dir().x, b.dir().y, b.dir().z), b.params()[0], b.params()[1]));
        }
        worldBeams = published;

        // std140-упаковка: FlInvView (поворот камеры: view -> camera-relative world).
        SCRATCH.clear();
        org.joml.Matrix4f invView = new org.joml.Matrix4f()
                .rotation(mc.gameRenderer.getMainCamera().rotation());
        invView.get(SCRATCH);
        // Капсулы-окклюдеры: БЛИЖАЙШИЕ сущности отбрасывают силуэтную тень
        // (список собран в общем проходе выше). Гуманоидам — вертикальная капсула
        // по росту, широким мобам (паук) — горизонтальная вдоль поворота тела.
        shadowCasters.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(cameraPos)));

        List<float[]> capsules = new ArrayList<>(MAX_CAPSULES);
        for (net.minecraft.world.entity.LivingEntity living : shadowCasters) {
            if (capsules.size() >= MAX_CAPSULES) {
                break;
            }
            addCapsule(capsules, living, cameraPos, partialTick);
        }

        SCRATCH.position(64);
        SCRATCH.putFloat(beams.size()).putFloat(capsules.size()).putFloat(0).putFloat(0);
        for (int i = 0; i < MAX_LIGHTS; i++) {
            if (i < beams.size()) {
                Beam b = beams.get(i);
                float[] p = b.params();
                float bright = b.bright();
                SCRATCH.position(80 + i * 16);
                SCRATCH.putFloat((float) b.pos().x).putFloat((float) b.pos().y)
                        .putFloat((float) b.pos().z).putFloat(p[0]);
                SCRATCH.position(80 + (MAX_LIGHTS + i) * 16);
                SCRATCH.putFloat(b.dir().x).putFloat(b.dir().y).putFloat(b.dir().z).putFloat(p[1]);
                SCRATCH.position(80 + (MAX_LIGHTS * 2 + i) * 16);
                SCRATCH.putFloat(b.color().x * bright).putFloat(b.color().y * bright)
                        .putFloat(b.color().z * bright).putFloat(p[2]);
            }
        }
        // Воксельная окклюзия: держим сетку свежей, пока хоть один фонарь горит.
        VoxelOccluder.update(mc.level, cameraPos);
        boolean occlusion = VoxelOccluder.ready();
        Vec3 voxelOrigin = VoxelOccluder.originCameraRelative(cameraPos);
        SCRATCH.position(80 + MAX_LIGHTS * 3 * 16);
        SCRATCH.putFloat((float) voxelOrigin.x).putFloat((float) voxelOrigin.y)
                .putFloat((float) voxelOrigin.z).putFloat(VoxelOccluder.N);
        SCRATCH.putFloat(VoxelOccluder.COLS).putFloat(0).putFloat(0)
                .putFloat(occlusion ? 1.0f : 0.0f);

        // Капсулы сущностей: FlCapsuleA[8] (конец A + радиус), затем FlCapsuleB[8].
        int capsulesBase = 80 + MAX_LIGHTS * 3 * 16 + 32;
        for (int j = 0; j < capsules.size(); j++) {
            float[] capsule = capsules.get(j);
            SCRATCH.position(capsulesBase + j * 16);
            SCRATCH.putFloat(capsule[0]).putFloat(capsule[1]).putFloat(capsule[2]).putFloat(capsule[3]);
            SCRATCH.position(capsulesBase + MAX_CAPSULES * 16 + j * 16);
            SCRATCH.putFloat(capsule[4]).putFloat(capsule[5]).putFloat(capsule[6]).putFloat(0);
        }

        SCRATCH.position(0).limit(UBO_SIZE);
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(ubo.slice(), SCRATCH);
        uboHasData = true;
        active = true;
    }

    /**
     * Пытается занять слот источника в чанке позиции {@code pos}
     * (не больше {@value #MAX_PER_CHUNK} на чанк за кадр).
     */
    private static boolean reserveChunkSlot(Vec3 pos) {
        long key = net.minecraft.world.level.ChunkPos.pack(
                (int) Math.floor(pos.x) >> 4, (int) Math.floor(pos.z) >> 4);
        int used = CHUNK_SLOTS.getOrDefault(key, 0);
        if (used >= MAX_PER_CHUNK) {
            return false;
        }
        CHUNK_SLOTS.put(key, used + 1);
        return true;
    }

    /** Подмена терраин-пайплайна на наш (или null, если не терраин). */
    public static RenderPipeline swap(RenderPipeline original) {
        if (!active) {
            return null;
        }
        if (original == RenderPipelines.SOLID_TERRAIN) {
            return FL_SOLID;
        }
        if (original == RenderPipelines.CUTOUT_TERRAIN) {
            return FL_CUTOUT;
        }
        if (original == RenderPipelines.TRANSLUCENT_TERRAIN) {
            return FL_TRANSLUCENT;
        }
        return null;
    }

    // ==================== Универсальный клонер (сущности, партиклы) ====================

    private static final java.util.Map<RenderPipeline, java.util.Optional<RenderPipeline>> CLONES =
            new java.util.HashMap<>();

    /**
     * Подмена для immediate-отрисовки (RenderType.draw): любой пайплайн с шейдером
     * core/entity или core/particle клонируется в вариант с нашим конусным светом.
     * Клон повторяет оригинал полностью (дефайны, бленд, сэмплеры, формат вершин).
     */
    public static RenderPipeline swapImmediate(RenderPipeline original) {
        if (!active) {
            return null;
        }
        return CLONES.computeIfAbsent(original, FlashlightEngine::buildClone).orElse(null);
    }

    private static java.util.Optional<RenderPipeline> buildClone(RenderPipeline original) {
        Identifier fragment = original.getFragmentShader();
        if (!fragment.getNamespace().equals("minecraft")) {
            return java.util.Optional.empty();
        }
        Identifier replacement = switch (fragment.getPath()) {
            case "core/entity" -> ENTITY_SHADER;
            case "core/particle" -> PARTICLE_SHADER;
            case "core/block" -> BLOCK_SHADER;
            case "core/item" -> ITEM_SHADER;
            case "core/rendertype_entity_shadow" -> SHADOW_SHADER;
            default -> null;
        };
        if (replacement == null) {
            return java.util.Optional.empty();
        }

        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("flashlight",
                        "pipeline/fl_" + original.getLocation().getPath().replace('/', '_')))
                .withVertexShader(replacement)
                .withFragmentShader(replacement)
                .withVertexFormat(original.getVertexFormat(), original.getVertexFormatMode())
                .withPolygonMode(original.getPolygonMode())
                .withCull(original.isCull())
                .withColorTargetState(original.getColorTargetState())
                .withDepthStencilState(java.util.Optional.ofNullable(original.getDepthStencilState()));

        // Дефайны: значения (float/int) + флаги. Нечисловое значение (возможный
        // строковый дефайн будущих версий/модов) не должно ронять весь клон —
        // иначе свет фонаря молча гаснет для этого рендер-типа.
        original.getShaderDefines().values().forEach((key, value) -> {
            try {
                if (value.contains(".")) {
                    builder.withShaderDefine(key, Float.parseFloat(value));
                } else {
                    builder.withShaderDefine(key, Integer.parseInt(value));
                }
            } catch (NumberFormatException e) {
                dev.sivren.flashlight.Flashlight.LOGGER.warn(
                        "Flashlight: пропущен нечисловой дефайн {}={} у {}",
                        key, value, original.getLocation());
            }
        });
        original.getShaderDefines().flags().forEach(builder::withShaderDefine);

        for (String samplerName : original.getSamplers()) {
            builder.withSampler(samplerName);
        }
        for (RenderPipeline.UniformDescription uniform : original.getUniforms()) {
            if (uniform.textureFormat() != null) {
                builder.withUniform(uniform.name(), uniform.type(), uniform.textureFormat());
            } else {
                builder.withUniform(uniform.name(), uniform.type());
            }
        }
        builder.withUniform("FlashLights", UniformType.UNIFORM_BUFFER);
        builder.withSampler("FlOccluder");

        RenderPipeline clone = builder.build();
        RenderSystem.getDevice().precompilePipeline(clone);
        dev.sivren.flashlight.Flashlight.LOGGER.info("Flashlight: клонирован пайплайн {} -> {}",
                original.getLocation(), clone.getLocation());
        return java.util.Optional.of(clone);
    }

    public static GpuBuffer buffer() {
        return ubo;
    }

    /**
     * UBO для Sodium-компата: шейдер Sodium ссылается на блок безусловно,
     * поэтому буфер должен существовать (хотя бы занулённый) с первого кадра.
     */
    public static GpuBuffer uboForCompat() {
        if (ubo == null) {
            ensureResources();
        }
        return ubo;
    }

    /** Обнуляет UBO (счётчик лучей = 0) — свет выключен для всех шейдеров. */
    private static void zeroUbo() {
        SCRATCH.clear();
        for (int i = 0; i < UBO_SIZE; i += 8) {
            SCRATCH.putLong(0L);
        }
        SCRATCH.position(0).limit(UBO_SIZE);
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(ubo.slice(), SCRATCH);
        uboHasData = false;
    }

    /**
     * Просадка яркости на разряженной батарее: ниже 20% заряда фонарь
     * заметно тускнеет (до ~25% силы на последних секундах).
     */
    public static float lowChargeFactor(ItemStack stack, FlashlightItem item) {
        float ratio = FlashlightItem.charge(stack) / (float) item.maxCharge();
        if (ratio >= 0.2f) {
            return 1.0f;
        }
        return 0.25f + 0.75f * (ratio / 0.2f);
    }

    /**
     * Параметры луча {range, cosOuter, cosInner, brightness} — единая точка правды
     * для шейдера и расчёта ослепления (глэра).
     */
    public static float[] beamParams(FlashlightItem item, float focus) {
        if (item.isWideBeam()) {
            // Прожектор: широкий ~40° конус, дальность фиксированная.
            return new float[]{item.beamRange(), 0.76f, 0.90f, 1.15f};
        }
        // Универсал: фокус линзы 0..1 — от «широко/близко» до «узко/далеко».
        return new float[]{
                net.minecraft.util.Mth.lerp(focus, 25.0f, item.beamRange()),
                net.minecraft.util.Mth.lerp(focus, 0.913f, 0.988f),
                net.minecraft.util.Mth.lerp(focus, 0.951f, 0.995f),
                net.minecraft.util.Mth.lerp(focus, 1.1f, 1.0f)};
    }

    /**
     * Капсулы-окклюдеры по сущности: {ax, ay, az, r, bx, by, bz} (camera-relative).
     * Высоким — ДВЕ вертикальные (ноги тоньше + корпус толще): силуэт с
     * перепадом ширины, а не «столб». Широким (паук) — горизонтальная вдоль тела.
     */
    private static void addCapsule(List<float[]> capsules,
                                   net.minecraft.world.entity.LivingEntity living,
                                   Vec3 cameraPos, float partialTick) {
        net.minecraft.world.phys.AABB box = living.getBoundingBox();
        float width = (float) box.getXsize();
        float height = (float) box.getYsize();
        float cx = (float) ((box.minX + box.maxX) * 0.5 - cameraPos.x);
        float cz = (float) ((box.minZ + box.maxZ) * 0.5 - cameraPos.z);
        float minY = (float) (box.minY - cameraPos.y);
        if (height >= width * 1.15f) {
            // Ноги: узкая капсула от земли до середины роста.
            float rLegs = net.minecraft.util.Mth.clamp(width * 0.30f, 0.08f, 0.35f);
            float split = minY + height * 0.5f;
            capsules.add(new float[]{cx, minY + rLegs * 0.7f, cz, rLegs, cx, split, cz});
            if (capsules.size() >= MAX_CAPSULES) {
                return;
            }
            // Корпус + голова: толстая капсула от пояса до макушки.
            float rBody = net.minecraft.util.Mth.clamp(width * 0.5f * 0.95f, 0.1f, 0.5f);
            float top = minY + height - rBody * 0.85f;
            capsules.add(new float[]{cx, split, cz, rBody, cx, Math.max(top, split), cz});
        } else {
            // Широкий моб: горизонтальная капсула вдоль поворота тела.
            float r = net.minecraft.util.Mth.clamp(height * 0.5f * 0.9f, 0.1f, 0.6f);
            float half = Math.max(width * 0.5f - r, 0.05f);
            double yaw = Math.toRadians(net.minecraft.util.Mth.lerp(partialTick,
                    living.yBodyRotO, living.yBodyRot));
            float fx = (float) -Math.sin(yaw) * half;
            float fz = (float) Math.cos(yaw) * half;
            float cy = minY + height * 0.5f;
            capsules.add(new float[]{cx - fx, cy, cz - fz, r, cx + fx, cy, cz + fz});
        }
    }

    // ==================== Подавление ванильной blob-тени ====================

    /** Луч фонаря в мировых координатах (для CPU-проверок вне шейдера). */
    public record WorldBeam(Vec3 pos, Vec3 dir, float range, float cosOuter) {
    }

    private static volatile List<WorldBeam> worldBeams = List.of();

    /**
     * true, если сущность попадает в конус хотя бы одного горящего фонаря —
     * тогда её ванильная blob-тень гасится (наша силуэтная тень главнее).
     */
    public static boolean beamTouches(net.minecraft.world.entity.Entity entity) {
        List<WorldBeam> list = worldBeams;
        if (list.isEmpty()) {
            return false;
        }
        Vec3 center = entity.getBoundingBox().getCenter();
        for (WorldBeam beam : list) {
            Vec3 to = center.subtract(beam.pos());
            double dist = to.length();
            if (dist > beam.range()) {
                continue;
            }
            if (dist < 2.0) {
                return true;
            }
            // Запас к краю конуса, чтобы тень не мигала на границе луча.
            if (to.scale(1.0 / dist).dot(beam.dir()) > beam.cosOuter() - 0.08) {
                return true;
            }
        }
        return false;
    }

    public static FlashlightItem litFlashlight(Player player) {
        ItemStack stack = litStack(player);
        return stack == null ? null : (FlashlightItem) stack.getItem();
    }

    /** Стек включённого фонаря в руках игрока (главная рука приоритетнее) или null. */
    public static ItemStack litStack(Player player) {
        ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (FlashlightItem.isLit(main)) {
            return main;
        }
        ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
        if (FlashlightItem.isLit(off)) {
            return off;
        }
        return null;
    }

    /** Параметры света шашки: {range, cosOuter, cosInner, -} — точечный (сфера). */
    private static final float[] FLARE_PARAMS = {12.0f, -1.05f, -1.0f, 1.0f};

    /**
     * Позиция линзы фонаря: у руки, держащей фонарь (не «изо лба»).
     * Отсюда исходит луч (физика света).
     */
    public static Vec3 lensPos(Player player, float partialTick) {
        boolean mainLit = FlashlightItem.isLit(player.getItemInHand(InteractionHand.MAIN_HAND));
        boolean rightArm = player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
        int side = (mainLit == rightArm) ? 1 : -1;
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 look = player.getViewVector(partialTick);
        Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize();
        return eye.add(look.scale(0.45))
                .add(right.scale(0.33 * side))
                .add(0, -0.25, 0);
    }

    /**
     * Позиция для БЛИКА: линза предмета в руке. Рука ходит с ПОВОРОТОМ ТЕЛА
     * (не головы), торец предмета — чуть выше и впереди кисти.
     */
    public static Vec3 glarePos(Player player, float partialTick) {
        boolean mainLit = FlashlightItem.isLit(player.getItemInHand(InteractionHand.MAIN_HAND));
        boolean rightArm = player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
        int side = (mainLit == rightArm) ? 1 : -1;
        Vec3 eye = player.getEyePosition(partialTick);
        double bodyYaw = Math.toRadians(net.minecraft.util.Mth.lerp(partialTick,
                player.yBodyRotO, player.yBodyRot));
        Vec3 bodyForward = new Vec3(-Math.sin(bodyYaw), 0, Math.cos(bodyYaw));
        Vec3 bodyRight = new Vec3(-Math.cos(bodyYaw), 0, -Math.sin(bodyYaw));
        return eye.add(bodyForward.scale(0.5))
                .add(bodyRight.scale(0.38 * side))
                .add(0, -0.75, 0);
    }

    private static void ensureResources() {
        GpuDevice device = RenderSystem.getDevice();
        if (ubo == null) {
            ubo = device.createBuffer(() -> "FlashLights UBO",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);
            zeroUbo(); // содержимое нового буфера не определено — гасим сразу
        }
        if (!compiled) {
            device.precompilePipeline(FL_SOLID);
            device.precompilePipeline(FL_CUTOUT);
            device.precompilePipeline(FL_TRANSLUCENT);
            compiled = true;
        }
    }
}
