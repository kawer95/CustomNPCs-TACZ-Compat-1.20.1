package com.arxyt.customnpcstaczcompat.mixin;

import com.arxyt.customnpcstaczcompat.NativeNpcEligibility;
import noppes.npcs.ai.EntityAIRangedAttack;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stops CustomNPCs from firing its normal projectile alongside TaCZ bullets. */
@Mixin(value = EntityAIRangedAttack.class, remap = false)
public abstract class EntityAIRangedAttackMixin {
    @Shadow @Final private EntityNPCInterface npc;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true, remap = true)
    private void customnpcsTaczCompat$disableNativeProjectile(CallbackInfoReturnable<Boolean> cir) {
        if (NativeNpcEligibility.active(npc)) cir.setReturnValue(false);
    }
}
