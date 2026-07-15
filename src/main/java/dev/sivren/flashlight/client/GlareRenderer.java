package dev.sivren.flashlight.client;

import java.util.OptionalDouble;
import java.util.OptionalInt;

import dev.sivren.flashlight.FlashlightItem;
import dev.sivren.flashlight.ModComponents;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Свечение линзы фонаря. Две части:
 * 1) Маленькая ТОЧКА на линзе в мире — с честной глубиной (мобы/стены перекрывают).
 * 2) ОСЛЕПЛЕНИЕ — экранная вспышка (FlashBlindOverlay): сила = реальная
 *    интенсивность конуса в глазах смотрящего, с трассировкой видимости
 *    (блоки И сущности на пути гасят). Здесь только вычисляется и публикуется.
 */
public final class GlareRenderer {

    /** Точка на линзе: ванильная проекция + наш процедурный круглый блик. */
    private static final RenderPipeline DOT_PIPELINE = RenderPipeline.builder(
                    RenderPipelines.MATRICES_PROJECTION_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("flashlight", "pipeline/glare_dot"))
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/position_tex_color"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("flashlight", "core/fl_glare"))
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
            .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
            .build();

    /** Экранный ореол ослепления: NDC-квад, целиком процедурный (без текстуры). */
    private static final RenderPipeline HALO_PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("flashlight", "pipeline/glare_halo"))
            .withVertexShader(Identifier.fromNamespaceAndPath("flashlight", "core/fl_glare"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("flashlight", "core/fl_glare"))
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
            .withCull(false)
            .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
            .build();

    // Публикуется для HUD-вспышки (читает FlashBlindOverlay).
    public static volatile float blindStrength = 0;
    public static volatile float blindScreenX = 0.5f;
    public static volatile float blindScreenY = 0.5f;

    private static boolean compiled = false;

    private GlareRenderer() {
    }

    /** Сброс при выходе из мира (DISCONNECT в FlashlightClient). */
    public static void reset() {
        blindStrength = 0;
        blindScreenX = 0.5f;
        blindScreenY = 0.5f;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    /** Вызывается сразу после рендера мира. */
    public static void render(Matrix4fc projectionMatrix, Matrix4fc modelViewMatrix) {
        Minecraft mc = Minecraft.getInstance();
        float bestBlind = 0;
        float bestX = 0.5f;
        float bestY = 0.5f;
        if (mc.level == null || mc.player == null) {
            blindStrength = 0;
            return;
        }
        // Быстрый выход: блик считается только для чужих фонарей (и своего в F5).
        // Одиночная игра от первого лица — самый частый случай, ноль работы.
        boolean anySource = false;
        for (Player player : mc.level.players()) {
            if (FlashlightEngine.litStack(player) != null
                    && !(player == mc.player && mc.options.getCameraType().isFirstPerson())) {
                anySource = true;
                break;
            }
        }
        if (!anySource) {
            blindStrength = 0;
            return;
        }
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 cameraPos = mc.gameRenderer.getMainCamera().position();
        Matrix4f camRot = new Matrix4f().rotation(mc.gameRenderer.getMainCamera().rotation());
        Vector3f right = camRot.transformDirection(new Vector3f(1, 0, 0));
        Vector3f up = camRot.transformDirection(new Vector3f(0, 1, 0));
        Matrix4f viewProj = new Matrix4f(projectionMatrix).mul(modelViewMatrix);

        BufferBuilder builder = null;
        ByteBufferBuilder bytes = null;
        for (Player player : mc.level.players()) {
            ItemStack lit = FlashlightEngine.litStack(player);
            if (lit == null) {
                continue;
            }
            boolean self = player == mc.player && mc.options.getCameraType().isFirstPerson();
            Vec3 lensWorld = FlashlightEngine.glarePos(player, partialTick);
            Vec3 lens = lensWorld.subtract(cameraPos);
            double dist = lens.length();
            if (self || dist > 160 || dist < 0.5) {
                continue;
            }
            // Видимость: блоки...
            if (mc.level.clip(new ClipContext(cameraPos, lensWorld,
                    ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player))
                    .getType() == HitResult.Type.BLOCK) {
                continue;
            }
            // ...и сущности на пути (моб заслонил фонарь — не слепит).
            if (isBlockedByEntity(mc, player, cameraPos, lensWorld)) {
                continue;
            }

            FlashlightItem item = (FlashlightItem) lit.getItem();
            float focus = lit.getOrDefault(ModComponents.FOCUS, 10) / 10.0f;
            float[] p = FlashlightEngine.beamParams(item, focus);
            Vec3 look = player.getViewVector(partialTick);
            float cosA = (float) look.dot(lens.normalize().scale(-1));
            float angular = smoothstep(p[1] - 0.06f, p[2], cosA);
            float k = p[0] * 0.35f;
            float distFall = (1.0f - smoothstep(p[0] * 0.5f, p[0], (float) dist))
                    * (k / (k + (float) dist));
            int ambient = mc.level.getMaxLocalRawBrightness(
                    net.minecraft.core.BlockPos.containing(lensWorld));
            float ambientFade = 1.0f - 0.05f * ambient;
            float blind = angular * distFall * ambientFade * (item.isWideBeam() ? 1.15f : 1.0f)
                    * FlashlightEngine.lowChargeFactor(lit, item);

            if (blind > bestBlind) {
                // Проекция линзы на экран для центра вспышки.
                Vector4f clip = viewProj.transform(new Vector4f(
                        (float) lens.x, (float) lens.y, (float) lens.z, 1.0f));
                if (clip.w > 0.001f) {
                    bestBlind = blind;
                    bestX = (clip.x / clip.w) * 0.5f + 0.5f;
                    bestY = 1.0f - ((clip.y / clip.w) * 0.5f + 0.5f);
                }
            }

            // Точка на линзе (сбоку — просто маленький огонёк).
            float sideVis = Math.clamp((cosA + 0.1f) / 0.5f, 0.0f, 1.0f);
            float dotAlpha = (0.3f + 0.5f * sideVis) * ambientFade;
            if (dist < 1.2) {
                dotAlpha *= (float) Math.max(0.0, (dist - 0.5) / 0.7);
            }
            if (dotAlpha <= 0.02f) {
                continue;
            }
            float size = 0.14f + 0.1f * sideVis;

            if (builder == null) {
                bytes = new ByteBufferBuilder(1024);
                builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            }
            float cx = (float) lens.x;
            float cy = (float) lens.y;
            float cz = (float) lens.z;
            Vector3f r = new Vector3f(right).mul(size);
            Vector3f u = new Vector3f(up).mul(size);
            builder.addVertex(cx - r.x - u.x, cy - r.y - u.y, cz - r.z - u.z)
                    .setUv(0, 1).setColor(1.0f, 1.0f, 1.0f, dotAlpha);
            builder.addVertex(cx + r.x - u.x, cy + r.y - u.y, cz + r.z - u.z)
                    .setUv(1, 1).setColor(1.0f, 1.0f, 1.0f, dotAlpha);
            builder.addVertex(cx + r.x + u.x, cy + r.y + u.y, cz + r.z + u.z)
                    .setUv(1, 0).setColor(1.0f, 1.0f, 1.0f, dotAlpha);
            builder.addVertex(cx - r.x + u.x, cy - r.y + u.y, cz - r.z + u.z)
                    .setUv(0, 0).setColor(1.0f, 1.0f, 1.0f, dotAlpha);
        }

        blindStrength = bestBlind;
        blindScreenX = bestX;
        blindScreenY = bestY;

        if (builder == null) {
            return;
        }
        final ByteBufferBuilder byteSource = bytes;
        MeshData mesh = builder.build();
        if (mesh == null) {
            byteSource.close();
            return;
        }

        ensurePipelines();

        try (mesh; byteSource) {
            GpuBuffer vertices = DOT_PIPELINE.getVertexFormat().uploadImmediateVertexBuffer(mesh.vertexBuffer());
            RenderSystem.AutoStorageIndexBuffer autoIndices =
                    RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            GpuBuffer indices = autoIndices.getBuffer(mesh.drawState().indexCount());
            GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(
                    new Matrix4f(modelViewMatrix), new Vector4f(1, 1, 1, 1),
                    new Vector3f(), new Matrix4f());
            RenderTarget target = mc.getMainRenderTarget();
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            try (RenderPass pass = encoder.createRenderPass(() -> "Flashlight glare dot",
                    target.getColorTextureView(), OptionalInt.empty(),
                    target.getDepthTextureView(), OptionalDouble.empty())) {
                pass.setPipeline(DOT_PIPELINE);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", dynamicTransforms);
                pass.setVertexBuffer(0, vertices);
                pass.setIndexBuffer(indices, autoIndices.type());
                pass.drawIndexed(0, 0, mesh.drawState().indexCount(), 1);
            }
        }
    }

    /**
     * Экранный ореол ослепления: процедурный круг с гауссовым ядром, центр —
     * в спроецированной точке линзы. Рисуется поверх мира и руки, под GUI.
     * Через включённый ПНВ вспышка зелёная и заметно злее (трубка сатурирует).
     */
    public static void drawBlindHalo(float strength) {
        Minecraft mc = Minecraft.getInstance();
        boolean nvg = mc.player != null && dev.sivren.flashlight.NvgItem.isLit(
                mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD));
        float alpha = Math.clamp(strength * (nvg ? 2.8f : 2.0f), 0.0f, 1.0f);
        if (nvg) {
            drawScreenHalo(blindScreenX, blindScreenY, 0.7f + 3.2f * strength, 0.62f, 1.0f, 0.66f, alpha);
        } else {
            drawScreenHalo(blindScreenX, blindScreenY, 0.7f + 3.2f * strength, 1.0f, 0.95f, 0.86f, alpha);
        }
    }

    /**
     * Процедурный ореол на экране: центр в нормированных координатах (0..1,
     * верх-лево), halfY — полувысота в NDC, квад квадратный в пикселях.
     */
    private static void drawScreenHalo(float centerX, float centerY, float halfY,
                                       float r, float g, float b, float alpha) {
        if (alpha < 0.02f) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.getMainRenderTarget();
        if (target == null) {
            return;
        }
        ensurePipelines();

        float cx = centerX * 2.0f - 1.0f;
        float cy = -(centerY * 2.0f - 1.0f);
        float halfX = halfY * ((float) target.height / (float) target.width);

        try (ByteBufferBuilder bytes = new ByteBufferBuilder(512)) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS,
                    DefaultVertexFormat.POSITION_TEX_COLOR);
            builder.addVertex(cx - halfX, cy - halfY, 0).setUv(0, 1).setColor(r, g, b, alpha);
            builder.addVertex(cx + halfX, cy - halfY, 0).setUv(1, 1).setColor(r, g, b, alpha);
            builder.addVertex(cx + halfX, cy + halfY, 0).setUv(1, 0).setColor(r, g, b, alpha);
            builder.addVertex(cx - halfX, cy + halfY, 0).setUv(0, 0).setColor(r, g, b, alpha);
            try (MeshData mesh = builder.build()) {
                if (mesh == null) {
                    return;
                }
                GpuBuffer vertices = HALO_PIPELINE.getVertexFormat()
                        .uploadImmediateVertexBuffer(mesh.vertexBuffer());
                RenderSystem.AutoStorageIndexBuffer autoIndices =
                        RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
                GpuBuffer indices = autoIndices.getBuffer(mesh.drawState().indexCount());
                CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
                try (RenderPass pass = encoder.createRenderPass(() -> "Flashlight blind halo",
                        target.getColorTextureView(), OptionalInt.empty())) {
                    pass.setPipeline(HALO_PIPELINE);
                    pass.setVertexBuffer(0, vertices);
                    pass.setIndexBuffer(indices, autoIndices.type());
                    pass.drawIndexed(0, 0, mesh.drawState().indexCount(), 1);
                }
            }
        }
    }

    private static void ensurePipelines() {
        if (!compiled) {
            RenderSystem.getDevice().precompilePipeline(DOT_PIPELINE);
            RenderSystem.getDevice().precompilePipeline(HALO_PIPELINE);
            compiled = true;
        }
    }

    private static boolean isBlockedByEntity(Minecraft mc, Player owner, Vec3 from, Vec3 to) {
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == owner || entity == mc.player || entity == mc.getCameraEntity()) {
                continue;
            }
            if (entity.position().distanceToSqr(from) > 160 * 160) {
                continue;
            }
            if (entity.getBoundingBox().clip(from, to).isPresent()) {
                return true;
            }
        }
        return false;
    }
}
