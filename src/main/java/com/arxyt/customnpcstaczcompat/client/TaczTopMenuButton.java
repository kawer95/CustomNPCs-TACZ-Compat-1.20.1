package com.arxyt.customnpcstaczcompat.client;

import noppes.npcs.entity.EntityNPCInterface;
import noppes.npcs.shared.client.gui.components.GuiMenuTopButton;
import noppes.npcs.shared.client.gui.listeners.IGuiInterface;

/** A separate row avoids squeezing an eighth tab into CustomNPCs' already full top menu. */
public final class TaczTopMenuButton extends GuiMenuTopButton {
    private final EntityNPCInterface npc;

    public TaczTopMenuButton(IGuiInterface parent, EntityNPCInterface npc, int x, int y) {
        super(parent, 10_271, x, y, "gui.customnpcs_tacz_compat.combat.tab");
        this.npc = npc;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        gui.save();
        ClientCombatSettings.open(npc, gui instanceof net.minecraft.client.gui.screens.Screen screen ? screen : null);
    }
}
