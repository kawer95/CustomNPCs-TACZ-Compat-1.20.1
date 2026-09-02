package com.arxyt.customnpcstaczcompat.client;

import com.arxyt.customnpcstaczcompat.NativeGunNetwork;
import com.arxyt.customnpcstaczcompat.NpcTaczCombatSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import noppes.npcs.entity.EntityNPCInterface;

/** Client hand-off for the server-authoritative CNPC TaCZ combat editor. */
public final class ClientCombatSettings {
    private ClientCombatSettings() { }

    public static void open(EntityNPCInterface npc, Screen parent) {
        if (npc == null) return;
        Minecraft.getInstance().setScreen(new TaczCombatSettingsScreen(parent, npc.getId()));
        NativeGunNetwork.requestCombatSettings(npc.getId());
    }

    public static void accept(int entityId, NpcTaczCombatSettings settings) {
        if (Minecraft.getInstance().screen instanceof TaczCombatSettingsScreen screen
                && screen.entityId() == entityId) {
            screen.acceptServerSettings(settings);
        }
    }
}
