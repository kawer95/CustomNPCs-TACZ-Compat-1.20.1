package com.arxyt.customnpcstaczcompat.client;

import com.arxyt.customnpcstaczcompat.NativeGunNetwork;
import com.arxyt.customnpcstaczcompat.NpcTaczCombatSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Compact second-row CNPC editor page for TaCZ-only shooting behaviour. */
public final class TaczCombatSettingsScreen extends Screen {
    private static final int FIELD_WIDTH = 56;
    private final Screen parent;
    private final int entityId;
    private final Map<String, EditBox> fields = new LinkedHashMap<>();
    private NpcTaczCombatSettings settings = NpcTaczCombatSettings.DEFAULT;

    TaczCombatSettingsScreen(Screen parent, int entityId) {
        super(Component.translatable("gui.customnpcs_tacz_compat.combat.title"));
        this.parent = parent;
        this.entityId = entityId;
    }

    int entityId() { return entityId; }

    @Override
    protected void init() {
        int panelLeft = width / 2 - 196;
        int firstY = height / 2 - 82;
        addPair(panelLeft, firstY, "range", settings.range(), 1, 256,
                "gui.customnpcs_tacz_compat.combat.range");
        addPair(panelLeft + 200, firstY, "accuracy", settings.accuracy(), 0, 100,
                "gui.customnpcs_tacz_compat.combat.accuracy");
        addRangePair(panelLeft, firstY + 30, "shotInterval", settings.shotIntervalMin(), settings.shotIntervalMax(),
                "gui.customnpcs_tacz_compat.combat.shot_interval");
        addRangePair(panelLeft + 200, firstY + 30, "burstShots", settings.burstShotsMin(), settings.burstShotsMax(),
                "gui.customnpcs_tacz_compat.combat.burst_shots");
        addRangePair(panelLeft, firstY + 60, "burstGroups", settings.burstGroupsMin(), settings.burstGroupsMax(),
                "gui.customnpcs_tacz_compat.combat.burst_groups");
        addRangePair(panelLeft + 200, firstY + 60, "groupInterval", settings.groupIntervalMin(), settings.groupIntervalMax(),
                "gui.customnpcs_tacz_compat.combat.group_interval");
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> save())
                .bounds(width / 2 + 6, firstY + 104, 86, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> back())
                .bounds(width / 2 - 92, firstY + 104, 86, 20).build());
    }

    private void addPair(int x, int y, String key, int value, int min, int max, String label) {
        EditBox field = new EditBox(font, x + 138, y, FIELD_WIDTH, 18, Component.translatable(label));
        field.setValue(Integer.toString(value));
        field.setFilter(input -> input.isEmpty() || input.chars().allMatch(Character::isDigit));
        field.setHint(Component.literal(min + "-" + max));
        fields.put(key, field);
        addRenderableWidget(field);
    }

    private void addRangePair(int x, int y, String key, int minValue, int maxValue, String label) {
        EditBox min = new EditBox(font, x + 82, y, FIELD_WIDTH, 18, Component.literal("min"));
        EditBox max = new EditBox(font, x + 138, y, FIELD_WIDTH, 18, Component.literal("max"));
        min.setValue(Integer.toString(minValue));
        max.setValue(Integer.toString(maxValue));
        min.setFilter(input -> input.isEmpty() || input.chars().allMatch(Character::isDigit));
        max.setFilter(input -> input.isEmpty() || input.chars().allMatch(Character::isDigit));
        fields.put(key + "Min", min);
        fields.put(key + "Max", max);
        addRenderableWidget(min);
        addRenderableWidget(max);
    }

    void acceptServerSettings(NpcTaczCombatSettings settings) {
        this.settings = settings;
        if (minecraft != null) rebuildCombatWidgets();
    }

    private void rebuildCombatWidgets() {
        clearWidgets();
        init();
    }

    private void save() {
        NativeGunNetwork.saveCombatSettings(entityId, currentCandidate());
        back();
    }

    private NpcTaczCombatSettings currentCandidate() {
        return new NpcTaczCombatSettings(
                integer("range", settings.range()), integer("accuracy", settings.accuracy()),
                integer("shotIntervalMin", settings.shotIntervalMin()), integer("shotIntervalMax", settings.shotIntervalMax()),
                integer("burstShotsMin", settings.burstShotsMin()), integer("burstShotsMax", settings.burstShotsMax()),
                integer("burstGroupsMin", settings.burstGroupsMin()), integer("burstGroupsMax", settings.burstGroupsMax()),
                integer("groupIntervalMin", settings.groupIntervalMin()), integer("groupIntervalMax", settings.groupIntervalMax()));
    }

    private int integer(String key, int fallback) {
        try { return Integer.parseInt(fields.get(key).getValue()); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private void back() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override public void onClose() { back(); }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        int panelLeft = width / 2 - 196;
        int firstY = height / 2 - 82;
        graphics.drawCenteredString(font, title, width / 2, firstY - 24, 0xFFFFFF);
        graphics.drawString(font, Component.translatable("gui.customnpcs_tacz_compat.combat.description"),
                panelLeft, firstY - 10, 0xA0A0A0);
        label(graphics, "gui.customnpcs_tacz_compat.combat.range", panelLeft, firstY + 5);
        label(graphics, "gui.customnpcs_tacz_compat.combat.accuracy", panelLeft + 200, firstY + 5);
        // Keep the min/max headings above their inputs. Drawing them on the same baseline as
        // the boxes makes the text appear inside the editable values on small GUI scales.
        rangeLabel(graphics, "gui.customnpcs_tacz_compat.combat.shot_interval", panelLeft, firstY + 20);
        rangeLabel(graphics, "gui.customnpcs_tacz_compat.combat.burst_shots", panelLeft + 200, firstY + 20);
        rangeLabel(graphics, "gui.customnpcs_tacz_compat.combat.burst_groups", panelLeft, firstY + 50);
        rangeLabel(graphics, "gui.customnpcs_tacz_compat.combat.group_interval", panelLeft + 200, firstY + 50);
        graphics.drawCenteredString(font, Component.translatable(modeTranslationKey(currentCandidate().cadenceMode())),
                width / 2, firstY + 88, 0x8FE3A0);
    }

    private void label(GuiGraphics graphics, String key, int x, int y) {
        graphics.drawString(font, Component.translatable(key), x, y, 0xE0E0E0);
    }

    private void rangeLabel(GuiGraphics graphics, String key, int x, int y) {
        label(graphics, key, x, y);
        graphics.drawString(font, "min", x + 84, y, 0x909090);
        graphics.drawString(font, "max", x + 140, y, 0x909090);
    }

    private static String modeTranslationKey(NpcTaczCombatSettings.CadenceMode mode) {
        return switch (mode) {
            case NATIVE_AUTO -> "gui.customnpcs_tacz_compat.combat.mode.native_auto";
            case CONTINUOUS_POINT -> "gui.customnpcs_tacz_compat.combat.mode.continuous_point";
            case INTERMITTENT_POINT -> "gui.customnpcs_tacz_compat.combat.mode.intermittent_point";
        };
    }
}
