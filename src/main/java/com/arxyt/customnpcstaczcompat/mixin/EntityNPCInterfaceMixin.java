package com.arxyt.customnpcstaczcompat.mixin;

import com.arxyt.customnpcstaczcompat.NativeTaczGunGoal;
import com.arxyt.customnpcstaczcompat.NpcGunAimLock;
import com.arxyt.customnpcstaczcompat.ProneTaczGunGoal;
import com.arxyt.customnpcstaczcompat.WatchTaczGunGoal;
import com.arxyt.customnpcstaczcompat.AutonomousReturnSprint;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Installs dormant goals once per CNPC; their eligibility checks supply the YSM exclusion. */
@Mixin(value = EntityNPCInterface.class, remap = false)
public abstract class EntityNPCInterfaceMixin extends PathfinderMob {
    protected EntityNPCInterfaceMixin(EntityType<? extends PathfinderMob> type, Level level) { super(type, level); }

    @Inject(method = "updateTasks", at = @At("TAIL"), remap = false)
    private void customnpcsTaczCompat$installNativeGunGoals(CallbackInfo ci) {
        if (level().isClientSide) return;
        EntityNPCInterface npc = (EntityNPCInterface) (Object) this;
        goalSelector.addGoal(-1, new ProneTaczGunGoal(npc));
        goalSelector.addGoal(-1, new WatchTaczGunGoal(npc));
        // The complete gun-combat goal owns MOVE and LOOK at high priority. It yields explicitly
        // to prone/watch goals, but ordinary CNPC movement may not starve autonomous combat.
        goalSelector.addGoal(-2, new NativeTaczGunGoal(npc));
    }

    /** Runs after CNPC's own tick so queued-command aim does not snap back to idle. */
    @Inject(method = "m_8119_", at = @At("TAIL"), remap = false, require = 0)
    private void customnpcsTaczCompat$maintainCommandGunAim(CallbackInfo ci) {
        if (!level().isClientSide) {
            EntityNPCInterface npc = (EntityNPCInterface) (Object) this;
            NpcGunAimLock.maintain(npc);
            // CNPC rewrites the ordinary sprint bit during its own update. Reassert autonomous
            // return after that write so vanilla entity data replicates the run-animation flag.
            AutonomousReturnSprint.maintain(npc);
        }
    }
}
