package com.arxyt.customnpcstaczcompat;

import com.arxyt.customnpcstaczcompat.client.ClientAimSync;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import noppes.npcs.entity.EntityNPCInterface;

import java.util.function.Supplier;

/** Lightweight exact body/head rotation sync; CNPC's full update packet omits live rotations. */
public final class NativeGunNetwork {
    private static final String VERSION = "1";
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
    }

    public static void syncAim(EntityNPCInterface npc, float yaw, float pitch, boolean snap) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> npc), new AimSync(npc.getId(), yaw, pitch, snap));
    }

    private static void handleAimSync(AimSync message, Supplier<NetworkEvent.Context> context) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientAimSync.apply(message.entityId(), message.yaw(), message.pitch(), message.snap()));
        context.get().setPacketHandled(true);
    }

    private record AimSync(int entityId, float yaw, float pitch, boolean snap) { }
}
