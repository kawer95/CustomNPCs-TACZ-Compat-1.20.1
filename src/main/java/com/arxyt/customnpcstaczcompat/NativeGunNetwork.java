package com.arxyt.customnpcstaczcompat;

import com.arxyt.customnpcstaczcompat.client.ClientAimSync;
import com.arxyt.customnpcstaczcompat.client.ClientCombatSettings;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import noppes.npcs.CustomItems;
import noppes.npcs.CustomNpcsPermissions;
import noppes.npcs.NoppesUtilServer;
import noppes.npcs.entity.EntityNPCInterface;

import java.util.function.Supplier;

/** Lightweight exact body/head rotation sync; CNPC's full update packet omits live rotations. */
public final class NativeGunNetwork {
    private static final String VERSION = "2";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CustomNpcsTaczCompat.MOD_ID, "main"), () -> VERSION,
            VERSION::equals, VERSION::equals);

    private NativeGunNetwork() { }

    public static void init() {
        CHANNEL.messageBuilder(AimSync.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((message, buffer) -> {
                    buffer.writeVarInt(message.entityId());
                    buffer.writeFloat(message.yaw());
                    buffer.writeFloat(message.pitch());
                    buffer.writeBoolean(message.snap());
                })
                .decoder(buffer -> new AimSync(buffer.readVarInt(), buffer.readFloat(), buffer.readFloat(),
                        buffer.readBoolean()))
                .consumerMainThread(NativeGunNetwork::handleAimSync)
                .add();
        CHANNEL.messageBuilder(CombatSettingsRequest.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder((message, buffer) -> buffer.writeVarInt(message.entityId()))
                .decoder(buffer -> new CombatSettingsRequest(buffer.readVarInt()))
                .consumerMainThread(NativeGunNetwork::handleCombatSettingsRequest)
                .add();
        CHANNEL.messageBuilder(CombatSettingsSnapshot.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((message, buffer) -> {
                    buffer.writeVarInt(message.entityId());
                    writeSettings(buffer, message.settings());
                })
                .decoder(buffer -> new CombatSettingsSnapshot(buffer.readVarInt(), readSettings(buffer)))
                .consumerMainThread(NativeGunNetwork::handleCombatSettingsSnapshot)
                .add();
        CHANNEL.messageBuilder(CombatSettingsSave.class, 3, NetworkDirection.PLAY_TO_SERVER)
                .encoder((message, buffer) -> {
                    buffer.writeVarInt(message.entityId());
                    writeSettings(buffer, message.settings());
                })
                .decoder(buffer -> new CombatSettingsSave(buffer.readVarInt(), readSettings(buffer)))
                .consumerMainThread(NativeGunNetwork::handleCombatSettingsSave)
                .add();
    }

    public static void syncAim(EntityNPCInterface npc, float yaw, float pitch, boolean snap) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> npc), new AimSync(npc.getId(), yaw, pitch, snap));
    }

    /** Client-only request used by the dedicated CNPC TaCZ combat tab. */
    public static void requestCombatSettings(int entityId) {
        CHANNEL.sendToServer(new CombatSettingsRequest(entityId));
    }

    /** Client-only save used by the dedicated CNPC TaCZ combat tab. */
    public static void saveCombatSettings(int entityId, NpcTaczCombatSettings settings) {
        CHANNEL.sendToServer(new CombatSettingsSave(entityId, settings));
    }

    private static void handleAimSync(AimSync message, Supplier<NetworkEvent.Context> context) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientAimSync.apply(message.entityId(), message.yaw(), message.pitch(), message.snap()));
        context.get().setPacketHandled(true);
    }

    private static void handleCombatSettingsRequest(CombatSettingsRequest message,
                                                     Supplier<NetworkEvent.Context> context) {
        ServerPlayer player = context.get().getSender();
        EntityNPCInterface npc = configurableNpc(player, message.entityId());
        if (npc != null) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new CombatSettingsSnapshot(npc.getId(), NpcTaczCombatSettings.resolve(npc)));
        } else {
            reportCombatSettingsDenied(player);
        }
        context.get().setPacketHandled(true);
    }

    private static void handleCombatSettingsSave(CombatSettingsSave message,
                                                  Supplier<NetworkEvent.Context> context) {
        ServerPlayer player = context.get().getSender();
        EntityNPCInterface npc = configurableNpc(player, message.entityId());
        if (npc != null) {
            NpcTaczCombatSettings.save(npc, message.settings());
            NpcTaczCombatApi.resetPattern(npc);
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new CombatSettingsSnapshot(npc.getId(), NpcTaczCombatSettings.resolve(npc)));
            player.displayClientMessage(Component.translatable(
                    "message.customnpcs_tacz_compat.combat_saved", npc.getDisplayName()), true);
            CustomNpcsTaczCompat.LOGGER.info(
                    "[CNPC-TACZ-COMBAT] saved npcId={} player={} range={} accuracy={} shotInterval={}-{} " +
                            "shotsPerGroup={}-{} groups={}-{} groupInterval={}-{}",
                    npc.getId(), player.getGameProfile().getName(), message.settings().range(),
                    message.settings().accuracy(), message.settings().shotIntervalMin(),
                    message.settings().shotIntervalMax(), message.settings().burstShotsMin(),
                    message.settings().burstShotsMax(), message.settings().burstGroupsMin(),
                    message.settings().burstGroupsMax(), message.settings().groupIntervalMin(),
                    message.settings().groupIntervalMax());
        } else {
            reportCombatSettingsDenied(player);
        }
        context.get().setPacketHandled(true);
    }

    private static void handleCombatSettingsSnapshot(CombatSettingsSnapshot message,
                                                      Supplier<NetworkEvent.Context> context) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientCombatSettings.accept(message.entityId(), message.settings()));
        context.get().setPacketHandled(true);
    }

    /**
     * Exact authority model used by CNPC's MODEL/DISPLAY menu packets. In particular, CNPC's
     * editable GUI is not synonymous with vanilla permission level 2: single-player worlds
     * without cheats and servers with a CustomNPC permission provider may legitimately grant
     * this page while {@link ServerPlayer#hasPermissions(int)} is false.
     */
    private static EntityNPCInterface configurableNpc(ServerPlayer player, int entityId) {
        if (player == null) return null;
        EntityNPCInterface npc = NoppesUtilServer.getEditingNpc(player);
        if (npc == null || npc.getId() != entityId) return null;
        if (!CustomNpcsPermissions.hasPermission(player, CustomNpcsPermissions.NPC_DISPLAY)) return null;
        return player.getMainHandItem().getItem() == CustomItems.wand ? npc : null;
    }

    private static void reportCombatSettingsDenied(ServerPlayer player) {
        if (player != null) {
            player.displayClientMessage(Component.translatable(
                    "message.customnpcs_tacz_compat.combat_denied").withStyle(ChatFormatting.RED), true);
        }
    }

    private static void writeSettings(net.minecraft.network.FriendlyByteBuf buffer,
                                      NpcTaczCombatSettings settings) {
        buffer.writeVarInt(settings.range());
        buffer.writeVarInt(settings.accuracy());
        buffer.writeVarInt(settings.shotIntervalMin());
        buffer.writeVarInt(settings.shotIntervalMax());
        buffer.writeVarInt(settings.burstShotsMin());
        buffer.writeVarInt(settings.burstShotsMax());
        buffer.writeVarInt(settings.burstGroupsMin());
        buffer.writeVarInt(settings.burstGroupsMax());
        buffer.writeVarInt(settings.groupIntervalMin());
        buffer.writeVarInt(settings.groupIntervalMax());
    }

    private static NpcTaczCombatSettings readSettings(net.minecraft.network.FriendlyByteBuf buffer) {
        return new NpcTaczCombatSettings(
                buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt());
    }

    private record AimSync(int entityId, float yaw, float pitch, boolean snap) { }
    private record CombatSettingsRequest(int entityId) { }
    private record CombatSettingsSnapshot(int entityId, NpcTaczCombatSettings settings) { }
    private record CombatSettingsSave(int entityId, NpcTaczCombatSettings settings) { }
}
