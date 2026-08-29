package com.arxyt.customnpcstaczcompat;

/** Common façade used by goals, capability hooks, and tick synchronization. */
public final class NativeGunRuntime {
    private static final NativeTaczGunFacade TACZ = new NativeTaczGunFacade();

    private NativeGunRuntime() { }

    public static NativeTaczGunFacade tacz() { return TACZ; }
}
