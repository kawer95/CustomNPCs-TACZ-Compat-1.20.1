package com.arxyt.customnpcstaczcompat.mixin.client;

import com.arxyt.customnpcstaczcompat.client.NativeNpcAnimationController;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies after PlayerAnimator's player-only mixin for raw Steve/Alex/64x32 CNPC models. */
@Mixin(value = PlayerModel.class, priority = 900)
public abstract class PlayerModelMixin<T extends LivingEntity> {
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void customnpcsTaczCompat$animateNativeNpc(T entity, float limbSwing, float limbSwingAmount,
                                                        float ageInTicks, float netHeadYaw, float headPitch,
                                                        CallbackInfo ci) {
        // ModelClassicPlayer adds its own arm sway after the superclass; its dedicated mixin runs later.
        if (entity instanceof EntityNPCInterface npc
                && !((Object) this).getClass().getName().equals("noppes.npcs.client.model.ModelClassicPlayer")) {
            NativeNpcAnimationController.apply(npc, (PlayerModel<?>) (Object) this, limbSwingAmount,
                    ageInTicks - (float) Math.floor(ageInTicks));
        }
    }
}
