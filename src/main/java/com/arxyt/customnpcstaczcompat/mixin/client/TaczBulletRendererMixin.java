package com.arxyt.customnpcstaczcompat.mixin.client;

import com.arxyt.customnpcstaczcompat.NativeNpcEligibility;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.reflect.Method;

/** Makes TaCZ tracers fired by a native CNPC visible from both sides. */
@Pseudo
@Mixin(targets = "com.tacz.guns.client.renderer.entity.EntityBulletRenderer", remap = false)
public abstract class TaczBulletRendererMixin {
    private static volatile Method ysmNpcActive;

    @Redirect(
            method = "renderTracerAmmo",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderType;energySwirl(Lnet/minecraft/resources/ResourceLocation;FF)Lnet/minecraft/client/renderer/RenderType;", remap = true),
            remap = false,
            require = 0)
    private RenderType customnpcsTaczCompat$twoSidedNpcTracer(
            ResourceLocation texture, float u, float v, EntityKineticBullet bullet, float[] tracerColor,
            float partialTicks, PoseStack poseStack, int packedLight) {
        if (bullet.getOwner() instanceof EntityNPCInterface npc && customnpcsTaczCompat$isCompatibleNpcOwner(npc)) {
            // Preserve TaCZ's tracer/no-tracer decision, colour and interval; only remove the
            // back-face culling that makes the emitted geometry vanish from the front side.
            return RenderType.entityTranslucentEmissive(texture, true);
        }
        return RenderType.energySwirl(texture, u, v);
    }

    /** See the reciprocal YSM bridge: either redirect may win Mixin's shared call site. */
    private static boolean customnpcsTaczCompat$isCompatibleNpcOwner(EntityNPCInterface npc) {
        return NativeNpcEligibility.active(npc) || customnpcsTaczCompat$isYsmNpcOwner(npc);
    }

    private static boolean customnpcsTaczCompat$isYsmNpcOwner(EntityNPCInterface npc) {
        if (!ModList.get().isLoaded("customnpcs_ysm_compat")) return false;
        try {
            Method active = ysmNpcActive;
            if (active == null) {
                active = Class.forName("com.arxyt.customnpcsysmcompat.GunCompat", false,
                                TaczBulletRendererMixin.class.getClassLoader())
                        .getMethod("active", EntityNPCInterface.class);
                ysmNpcActive = active;
            }
            return Boolean.TRUE.equals(active.invoke(null, npc));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
