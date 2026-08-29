package com.arxyt.customnpcstaczcompat.client;

import com.arxyt.customnpcstaczcompat.CustomNpcsTaczCompat;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.data.gson.AnimationSerializing;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.Reader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads TaCZ's public `player_animator` resource convention rather than its
 * package-private cache.  An unsupported future resource layout simply yields
 * an empty cache and lets TaCZ's vanilla arm pose remain visible.
 */
public final class NativeNpcAnimationAssets
        extends SimplePreparableReloadListener<Map<ResourceLocation, Map<String, KeyframeAnimation>>> {
    private static final NativeNpcAnimationAssets INSTANCE = new NativeNpcAnimationAssets();
    private final FileToIdConverter files = new FileToIdConverter("player_animator", ".json");
    private final Map<ResourceLocation, Map<String, KeyframeAnimation>> animations = new HashMap<>();
    private final AtomicBoolean failureReported = new AtomicBoolean();

    private NativeNpcAnimationAssets() { }

    public static NativeNpcAnimationAssets get() { return INSTANCE; }

    public synchronized Optional<KeyframeAnimation> find(ResourceLocation displayId, String name) {
        Map<String, KeyframeAnimation> group = animations.get(displayId);
        return group == null ? Optional.empty() : Optional.ofNullable(group.get(name));
    }

    @Override
    protected Map<ResourceLocation, Map<String, KeyframeAnimation>> prepare(ResourceManager manager,
                                                                              ProfilerFiller profiler) {
        Map<ResourceLocation, Map<String, KeyframeAnimation>> output = new HashMap<>();
        for (Map.Entry<ResourceLocation, Resource> entry : files.listMatchingResources(manager).entrySet()) {
            ResourceLocation id = files.fileToId(entry.getKey());
            try (Reader reader = entry.getValue().openAsReader()) {
                for (KeyframeAnimation animation : AnimationSerializing.deserializeAnimation(reader)) {
                    Object rawName = animation.extraData.get("name");
                    if (!(rawName instanceof String name)) continue;
                    String normalized = PlayerAnimationRegistry.serializeTextToString(name).toLowerCase(Locale.ROOT);
                    output.computeIfAbsent(id, ignored -> new HashMap<>()).put(normalized, animation);
                }
            } catch (Throwable error) {
                // A binary/API mismatch is no different from an invalid future resource:
                // expose one diagnostic, keep an empty cache, and preserve TaCZ's fallback.
                if (failureReported.compareAndSet(false, true)) {
                    CustomNpcsTaczCompat.LOGGER.error(
                            "Cannot adapt TaCZ PlayerAnimator resources; native CNPCs use TaCZ fallback poses",
                            error);
                }
                return Map.of();
            }
        }
        return output;
    }

    @Override
    protected synchronized void apply(Map<ResourceLocation, Map<String, KeyframeAnimation>> prepared,
                                      ResourceManager manager, ProfilerFiller profiler) {
        animations.clear();
        animations.putAll(prepared);
        NativeNpcAnimationController.clearAll();
        CustomNpcsTaczCompat.LOGGER.info("Loaded {} native-CNPC PlayerAnimator animation groups", animations.size());
    }
}
