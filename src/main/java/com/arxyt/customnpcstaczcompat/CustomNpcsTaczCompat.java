package com.arxyt.customnpcstaczcompat;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * Owns the common, server-safe CNPC TaCZ bridge.  PlayerAnimator is intentionally
 * loaded only by the client bootstrap so a dedicated server never resolves its classes.
 */
@Mod(CustomNpcsTaczCompat.MOD_ID)
public final class CustomNpcsTaczCompat {
    public static final String MOD_ID = "customnpcs_tacz_compat";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CustomNpcsTaczCompat() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, NativeGunConfig.SPEC);
        MinecraftForge.EVENT_BUS.register(new NativeGunEvents());
        if (ModList.get().isLoaded("dominionsword")) {
            loadOptionalDominionCompat();
        }
        DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> com.arxyt.customnpcstaczcompat.client.ClientBootstrap::init);
        LOGGER.info("Native CustomNPCs TaCZ compatibility loaded");
    }

    /** Keeps Dominion API symbols out of this mandatory, standalone-safe entrypoint. */
    private static void loadOptionalDominionCompat() {
        try {
            Class.forName("com.arxyt.customnpcstaczcompat.DominionCompatBootstrap")
                    .getMethod("load").invoke(null);
        } catch (Throwable error) {
            LOGGER.error("Dominion Sword was found but the native-CNPC optional adapter failed to load", error);
        }
    }
}
