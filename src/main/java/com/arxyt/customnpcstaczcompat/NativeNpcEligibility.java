package com.arxyt.customnpcstaczcompat;

import com.tacz.guns.api.item.IGun;
import net.minecraftforge.fml.ModList;
import noppes.npcs.entity.EntityNPCInterface;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The two CNPC TaCZ add-ons must never control the same entity.  YSM state is
 * queried reflectively so this standalone add-on has no binary dependency on it.
 */
public final class NativeNpcEligibility {
    private static final AtomicBoolean YSM_QUERY_ERROR_REPORTED = new AtomicBoolean();
    private static volatile Method ysmEnabled;
    private static volatile Class<?> ysmDisplayType;

    private NativeNpcEligibility() { }

    public static boolean active(EntityNPCInterface npc) {
        return npc != null && isSixBoneHumanoid(npc) && !usesYsmRenderer(npc)
                && IGun.getIGunOrNull(npc.getMainHandItem()) != null;
    }

    /**
     * GBPort uses a null display-model id for the classic Steve/Alex/64x32 family. A non-null
     * id delegates rendering to a creature model (dragon, slime, horse, golem, …), whose bones
     * are not PlayerAnimator's six-part humanoid rig and must remain untouched.
     */
    public static boolean isSixBoneHumanoid(EntityNPCInterface npc) {
        return npc != null && npc.display != null && npc.display.getModel() == null;
    }

    public static boolean usesYsmRenderer(EntityNPCInterface npc) {
        if (npc == null || !ModList.get().isLoaded("customnpcs_ysm_compat")) return false;
        try {
            Class<?> displayApi = ysmDisplayType;
            Method enabled = ysmEnabled;
            if (displayApi == null || enabled == null) {
                displayApi = Class.forName("com.arxyt.customnpcsysmcompat.api.IYsmNpcDisplay", false,
                        NativeNpcEligibility.class.getClassLoader());
                enabled = displayApi.getMethod("customnpcsYsmCompat$isEnabled");
                ysmDisplayType = displayApi;
                ysmEnabled = enabled;
            }
            if (!displayApi.isInstance(npc.display)) {
                // A failed YSM mixin is unsafe to infer as a native NPC: let YSM retain ownership.
                return true;
            }
            Object value = enabled.invoke(npc.display);
            return value instanceof Boolean enabledValue && enabledValue;
        } catch (Throwable error) {
            if (YSM_QUERY_ERROR_REPORTED.compareAndSet(false, true)) {
                CustomNpcsTaczCompat.LOGGER.error(
                        "Unable to read YSM-CNPC ownership; native TaCZ gun goals will stay disabled for safety", error);
            }
            return true;
        }
    }
}
