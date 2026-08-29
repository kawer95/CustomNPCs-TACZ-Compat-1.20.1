package com.arxyt.customnpcstaczcompat.client;

import com.arxyt.customnpcstaczcompat.CustomNpcsTaczCompat;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Client-only entrypoint; no PlayerAnimator type escapes this package. */
public final class ClientBootstrap {
    private static boolean initialized;

    private ClientBootstrap() { }

    public static void init() {
        if (initialized) return;
        initialized = true;
        try {
            FMLJavaModLoadingContext.get().getModEventBus().addListener(ClientBootstrap::registerReloadListener);
            MinecraftForge.EVENT_BUS.register(new NativeNpcAnimationController());
            CustomNpcsTaczCompat.LOGGER.info("Native CNPC PlayerAnimator bridge enabled");
        } catch (Throwable error) {
            CustomNpcsTaczCompat.LOGGER.error(
                    "Native CNPC PlayerAnimator bridge is incompatible; TaCZ fallback poses remain active", error);
        }
    }

    private static void registerReloadListener(RegisterClientReloadListenersEvent event) {
        try {
            event.registerReloadListener(NativeNpcAnimationAssets.get());
        } catch (Throwable error) {
            CustomNpcsTaczCompat.LOGGER.error(
                    "Native CNPC PlayerAnimator resource adapter is incompatible; TaCZ fallback poses remain active", error);
        }
    }
}
