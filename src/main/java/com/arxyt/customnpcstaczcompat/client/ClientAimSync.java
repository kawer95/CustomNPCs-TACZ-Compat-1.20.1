package com.arxyt.customnpcstaczcompat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import noppes.npcs.entity.EntityNPCInterface;

/** Applies the server's authoritative gun aim without vanilla body-yaw interpolation drift. */
public final class ClientAimSync {
    private ClientAimSync() { }

    public static void apply(int entityId, float yaw, float pitch, boolean snap) {
        if (Minecraft.getInstance().level == null) return;
        Entity entity = Minecraft.getInstance().level.getEntity(entityId);
        if (!(entity instanceof EntityNPCInterface npc)) return;
        npc.setYRot(yaw);
        npc.yBodyRot = yaw;
        npc.setYHeadRot(yaw);
        npc.setXRot(pitch);
        if (snap) {
            npc.yRotO = yaw;
            npc.yBodyRotO = yaw;
            npc.yHeadRotO = yaw;
            npc.xRotO = pitch;
        }
    }
}
