package com.arxyt.customnpcstaczcompat.mixin.client;

import com.arxyt.customnpcstaczcompat.client.TaczTopMenuButton;
import noppes.npcs.client.gui.util.GuiNpcMenu;
import noppes.npcs.entity.EntityNPCInterface;
import noppes.npcs.shared.client.gui.components.GuiMenuTopButton;
import noppes.npcs.shared.client.gui.listeners.IGuiInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/** Adds a real CNPC editor tab without touching CustomNPCs' private menu implementation. */
@Mixin(value = GuiNpcMenu.class, remap = false)
public abstract class GuiNpcMenuMixin {
    @Shadow private GuiMenuTopButton[] topButtons;
    @Shadow private IGuiInterface parent;
    @Shadow private EntityNPCInterface npc;

    @Inject(method = "initGui", at = @At("TAIL"), remap = false)
    private void customnpcsTaczCompat$addCombatTab(int guiLeft, int guiTop, int imageWidth, CallbackInfo ci) {
        GuiMenuTopButton[] expanded = Arrays.copyOf(topButtons, topButtons.length + 1);
        // A second tab row is deliberate: the stock six tabs, close and delete controls already
        // consume the entire first row, so an eighth first-row tab would overlap a stock control.
        expanded[expanded.length - 1] = new TaczTopMenuButton(parent, npc, guiLeft + 4, guiTop - 37);
        topButtons = expanded;
    }
}
