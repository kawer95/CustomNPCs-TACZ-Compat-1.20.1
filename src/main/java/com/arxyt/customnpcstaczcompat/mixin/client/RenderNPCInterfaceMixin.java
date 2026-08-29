package com.arxyt.customnpcstaczcompat.mixin.client;

import com.arxyt.customnpcstaczcompat.client.NativeNpcAnimationController;
import com.mojang.blaze3d.vertex.PoseStack;
import noppes.npcs.client.renderer.RenderNPCInterface;
import noppes.npcs.entity.EntityNPCInterface;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces only the native Crawl(7) renderer root with PlayerRenderer's swim-root convention,
 * then applies TaCZ PlayerAnimator's body transform.  CNPC's own crawl branch uses a different
 * axis sequence and is incompatible with TaCZ's lie_* arm keyframes.
 */
@Mixin(value = RenderNPCInterface.class, remap = false)
public abstract class RenderNPCInterfaceMixin {
    @Redirect(method = "setupRotations", at = @At(value = "FIELD",
            target = "Lnoppes/npcs/entity/EntityNPCInterface;currentAnimation:I", opcode = Opcodes.GETFIELD),
            remap = false)
    private int customnpcsTaczCompat$selectPlayerProneRoot(EntityNPCInterface npc) {
        return NativeNpcAnimationController.replacesNativeCrawlRoot(npc) ? 0 : npc.currentAnimation;
    }

    @Inject(method = "setupRotations", at = @At("TAIL"), remap = false)
    private void customnpcsTaczCompat$applyRoot(EntityNPCInterface npc, PoseStack poseStack,
                                                float ageInTicks, float rotationYaw, float partialTick,
                                                CallbackInfo ci) {
        NativeNpcAnimationController.applyBodyTransform(npc, poseStack, partialTick);
    }
}
