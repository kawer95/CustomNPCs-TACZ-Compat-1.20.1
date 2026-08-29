package com.arxyt.customnpcstaczcompat.mixin.client;

import com.arxyt.customnpcstaczcompat.client.NativeNpcAnimationController;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import noppes.npcs.client.model.ModelClassicPlayer;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Covers CustomNPCs' own classic-player model after it has added native arm sway. */
@Mixin(value = ModelClassicPlayer.class, remap = false)
public abstract class ModelClassicPlayerMixin {
    @Inject(method = "m_6973_", at = @At("TAIL"), remap = false)
    private void customnpcsTaczCompat$animateClassic(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                                      float ageInTicks, float netHeadYaw, float headPitch,
                                                      CallbackInfo ci) {
        if (entity instanceof EntityNPCInterface npc) {
            NativeNpcAnimationController.apply(npc, (HumanoidModel<?>) (Object) this, limbSwingAmount,
                    ageInTicks - (float) Math.floor(ageInTicks));
        }
    }
}
