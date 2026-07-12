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
 * параметры до 4 фонарей едут uniform-буфером и обновляются каждый кадр —
 * мгновенно, с настоящими текстурами (albedo * cone) и видимо всем игрокам:
 * конусы других игроков собираются из их предметов в руках (компоненты
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

    private static final int MAX_LIGHTS = 4;
    private static final int MAX_SPHERES = 8;
    /** std140: mat4 + count + 3x4 vec4 огней + 2 vec4 вокселей + 8 vec4 сфер. */
    private static final int UBO_SIZE = 64 + 16 + MAX_LIGHTS * 3 * 16 + 32 + MAX_SPHERES * 16;

    private static GpuBuffer ubo;
    private static boolean compiled = false;
    private static final ByteBuffer SCRATCH = ByteBuffer.allocateDirect(UBO_SIZE).order(ByteOrder.nativeOrder());

    // Инерция луча локального игрока: конус плавно догоняет взгляд.
    private static final Vector3f smoothDir = new Vector3f(0, 0, -1);
    private static long lastFrameNanos = 0;

    private FlashlightEngine() {
    }

    /** true, если подмена пайплайна активна (буфер готов). */
    public static boolean active = false;

    /** Вызывается в начале кадра (HEAD GameRenderer.renderLevel). */
    public static void update() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            active = false;
            return;
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
        smoothDir.lerp(new Vector3f((float) look.x, (float) look.y, (float) look.z), alpha);
        smoothDir.normalize();

        // Собираем до 4 фонарей: локальный игрок + ближайшие игроки с включённым фонарём.
        record Beam(Vec3 pos, Vector3f dir, FlashlightItem item, float focus, float lowFactor) {
        }
        List<Beam> beams = new ArrayList<>();
        List<? extends Player> players = new ArrayList<>(mc.level.players());
        players.sort(Comparator.comparingDouble(p -> p.position().distanceToSqr(cameraPos)));
        for (Player player : players) {
            if (beams.size() >= MAX_LIGHTS) {
                break;
            }
            ItemStack litStack = litStack(player);
            if (litStack == null) {
                continue;
            }
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
            beams.add(new Beam(lens.subtract(cameraPos), dir, item, focus,
                    lowChargeFactor(litStack, item)));
        }

        // Ни одного горящего фонаря — рендерим ванилью (ноль накладных расходов).
        if (beams.isEmpty()) {
            active = false;
            return;
        }

        // std140-упаковка: FlInvView (поворот камеры: view -> camera-relative world).
        SCRATCH.clear();
        org.joml.Matrix4f invView = new org.joml.Matrix4f()
                .rotation(mc.gameRenderer.getMainCamera().rotation());
        invView.get(SCRATCH);
        // Сферы-окклюдеры: БЛИЖАЙШИЕ сущности отбрасывают мягкую тень.
        // Высоким мобам — две сферы (тело+ноги), радиус от ШИРИНЫ, не роста.
        // Игроков с горящим фонарём пропускаем — их луч рождается внутри них самих.
        List<net.minecraft.world.entity.LivingEntity> shadowCasters = new ArrayList<>();
        for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof net.minecraft.world.entity.LivingEntity living) || living.isRemoved()) {
                continue;
            }
            if (living instanceof Player player && litStack(player) != null) {
                continue;
            }
            if (living.position().distanceToSqr(cameraPos) > 40 * 40) {
                continue;
            }
            shadowCasters.add(living);
        }
        shadowCasters.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(cameraPos)));

        List<float[]> spheres = new ArrayList<>(MAX_SPHERES);
        for (net.minecraft.world.entity.LivingEntity living : shadowCasters) {
            if (spheres.size() >= MAX_SPHERES) {
                break;
            }
            net.minecraft.world.phys.AABB box = living.getBoundingBox();
            float radius = (float) net.minecraft.util.Mth.clamp(box.getXsize() * 0.5 * 1.05, 0.15, 0.6);
            double height = box.getYsize();
            if (height > 1.4 && spheres.size() + 1 < MAX_SPHERES) {
                addSphere(spheres, box, cameraPos, 0.28, radius);
                addSphere(spheres, box, cameraPos, 0.74, radius);
            } else {
                addSphere(spheres, box, cameraPos, 0.5, radius);
            }
        }

        SCRATCH.position(64);
        SCRATCH.putFloat(beams.size()).putFloat(spheres.size()).putFloat(0).putFloat(0);
        for (int i = 0; i < MAX_LIGHTS; i++) {
            if (i < beams.size()) {
                Beam b = beams.get(i);
                float[] p = beamParams(b.item(), b.focus());
                float bright = p[3] * b.lowFactor();
                SCRATCH.position(80 + i * 16);
                SCRATCH.putFloat((float) b.pos().x).putFloat((float) b.pos().y)
                        .putFloat((float) b.pos().z).putFloat(p[0]);
                SCRATCH.position(80 + (MAX_LIGHTS + i) * 16);
                SCRATCH.putFloat(b.dir().x).putFloat(b.dir().y).putFloat(b.dir().z).putFloat(p[1]);
                SCRATCH.position(80 + (MAX_LIGHTS * 2 + i) * 16);
                SCRATCH.putFloat(1.0f * bright).putFloat(0.96f * bright)
                        .putFloat(0.86f * bright).putFloat(p[2]);
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

        // Сферы сущностей (camera-relative центр + радиус).
        int spheresBase = 80 + MAX_LIGHTS * 3 * 16 + 32;
        for (int j = 0; j < spheres.size(); j++) {
            float[] sphere = spheres.get(j);
            SCRATCH.position(spheresBase + j * 16);
            SCRATCH.putFloat(sphere[0]).putFloat(sphere[1]).putFloat(sphere[2]).putFloat(sphere[3]);
        }

        SCRATCH.position(0).limit(UBO_SIZE);
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(ubo.slice(), SCRATCH);
        active = true;
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

        // Дефайны: значения (float/int) + флаги.
        original.getShaderDefines().values().forEach((key, value) -> {
            if (value.contains(".")) {
                builder.withShaderDefine(key, Float.parseFloat(value));
            } else {
                builder.withShaderDefine(key, Integer.parseInt(value));
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

    private static void addSphere(List<float[]> spheres, net.minecraft.world.phys.AABB box,
                                  Vec3 cameraPos, double heightFraction, float radius) {
        spheres.add(new float[]{
                (float) ((box.minX + box.maxX) * 0.5 - cameraPos.x),
                (float) (box.minY + box.getYsize() * heightFraction - cameraPos.y),
                (float) ((box.minZ + box.maxZ) * 0.5 - cameraPos.z),
                radius});
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
        return eye.add(bodyForward.scale(0.28))
                .add(bodyRight.scale(0.4 * side))
                .add(0, -0.5, 0);
    }

    private static void ensureResources() {
        GpuDevice device = RenderSystem.getDevice();
        if (ubo == null) {
            ubo = device.createBuffer(() -> "FlashLights UBO",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);
        }
        if (!compiled) {
            device.precompilePipeline(FL_SOLID);
            device.precompilePipeline(FL_CUTOUT);
            device.precompilePipeline(FL_TRANSLUCENT);
            compiled = true;
        }
    }
}
