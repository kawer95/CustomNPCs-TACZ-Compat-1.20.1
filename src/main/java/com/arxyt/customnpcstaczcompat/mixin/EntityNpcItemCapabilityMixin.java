package com.arxyt.customnpcstaczcompat.mixin;

import com.arxyt.customnpcstaczcompat.NpcAmmoItemHandler;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Makes TaCZ consume ammunition from CNPC's real projectile/drop inventory slots. */
@Mixin(LivingEntity.class)
public abstract class EntityNpcItemCapabilityMixin {
    @Unique private LazyOptional<IItemHandler> customnpcsTaczCompat$inventory = LazyOptional.empty();

    @Inject(method = "getCapability", at = @At("HEAD"), cancellable = true, remap = false)
    private <T> void customnpcsTaczCompat$getInventory(@Nonnull Capability<T> capability, @Nullable Direction side,
                                                        CallbackInfoReturnable<LazyOptional<T>> cir) {
        if (capability != ForgeCapabilities.ITEM_HANDLER || !((Object) this instanceof EntityNPCInterface npc)) return;
        if (!customnpcsTaczCompat$inventory.isPresent()) {
            customnpcsTaczCompat$inventory = LazyOptional.of(() -> new NpcAmmoItemHandler(npc));
        }
        cir.setReturnValue(customnpcsTaczCompat$inventory.cast());
    }

    @Inject(method = "invalidateCaps", at = @At("TAIL"), remap = false)
    private void customnpcsTaczCompat$invalidateInventory(CallbackInfo ci) {
        customnpcsTaczCompat$inventory.invalidate();
        customnpcsTaczCompat$inventory = LazyOptional.empty();
    }

    @Inject(method = "reviveCaps", at = @At("TAIL"), remap = false)
    private void customnpcsTaczCompat$reviveInventory(CallbackInfo ci) {
        customnpcsTaczCompat$inventory = LazyOptional.empty();
    }
}
