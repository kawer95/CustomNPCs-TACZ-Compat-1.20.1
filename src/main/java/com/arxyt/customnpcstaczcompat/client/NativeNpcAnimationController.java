package com.arxyt.customnpcstaczcompat.client;

import com.arxyt.customnpcstaczcompat.CustomNpcsTaczCompat;
import com.arxyt.customnpcstaczcompat.NativeNpcEligibility;
import com.arxyt.customnpcstaczcompat.NpcCrawlState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ReloadState;
import com.tacz.guns.api.event.common.GunDrawEvent;
import com.tacz.guns.api.event.common.GunReloadEvent;
import com.tacz.guns.api.event.common.GunShootEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.resource.GunDisplayInstance;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.layered.AnimationStack;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier;
import dev.kosmx.playerAnim.api.layered.modifier.SpeedModifier;
import dev.kosmx.playerAnim.core.impl.AnimationProcessor;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.core.util.Vec3f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import noppes.npcs.entity.EntityNPCInterface;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Per-NPC PlayerAnimator state with no fake-player entity.  The controller is
 * intentionally an optional visual layer: failures clear only this state and
 * preserve TaCZ's generic HumanoidModel hold/aim animation.
 */
public final class NativeNpcAnimationController {
    private static final int LOWER_PRIORITY = 93;
    private static final int LOOP_UPPER_PRIORITY = 94;
    private static final int ONCE_UPPER_PRIORITY = 95;
    private static final int ROTATION_PRIORITY = 96;
    /** Matches TaCZ's eight-tick PlayerAnimator loop fade and PlayerRenderer's swim-root easing. */
    private static final int PRONE_ROOT_FADE_TICKS = 8;
    // TaCZ's lie body transform lowers the rendered torso beneath the player root. Keep this
    // small compensation separate from the pivot so it affects placement, never the turn center.
    private static final float PRONE_RENDER_LIFT = 0.25F;
    /** CNPC's render origin is its feet; the visual center of the classic human body is Y=0.9. */
    private static final float PRONE_ROOT_PIVOT_Y = 0.9F;
    /** Keeps the feet-origin render frame on the ground while the body rotates about that pivot. */
    private static final float PRONE_GROUND_ANCHOR_Y = -PRONE_ROOT_PIVOT_Y;
    private static final Map<Integer, State> STATES = new HashMap<>();
    private static final AtomicBoolean FAILURE_REPORTED = new AtomicBoolean();
    private static final Set<ResourceLocation> MISSING_ANIMATION_REPORTED = ConcurrentHashMap.newKeySet();
    private static final Set<String> MISSING_ONCE_ANIMATION_REPORTED = ConcurrentHashMap.newKeySet();

    public static synchronized void clearAll() { STATES.clear(); }

    public static void apply(EntityNPCInterface npc, HumanoidModel<?> model, float limbSwingAmount,
                             float partialTick) {
        if (!NativeNpcEligibility.active(npc)) {
            traceIneligible(npc);
            clear(npc);
            return;
        }
        try {
            ItemStack stack = npc.getMainHandItem();
            if (IGun.getIGunOrNull(stack) == null) {
                traceSkip(npc, "main_hand_is_not_a_tacz_gun", null, null);
                clear(npc);
                return;
            }
            Optional<GunDisplayInstance> display = TimelessAPI.getGunDisplay(stack);
            if (display.isEmpty() || display.get().getPlayerAnimator3rd() == null) {
                traceSkip(npc, "gun_display_has_no_player_animator_3rd", null, null);
                clear(npc);
                return;
            }
            State state = state(npc);
            long worldTick = clientWorldTick(npc);
            state.lastSeenWorldTick = worldTick;
            ResourceLocation animationId = display.get().getPlayerAnimator3rd();
            if (!NativeNpcAnimationAssets.get().find(animationId, "hold_upper").isPresent()) {
                if (MISSING_ANIMATION_REPORTED.add(animationId)) {
                    CustomNpcsTaczCompat.LOGGER.warn(
                            "[CNPC-TACZ-ANIM-MISSING] animationSet={} has no hold_upper; NPC animation is disabled",
                            animationId);
                }
                clear(npc);
                return;
            }
            state.switchAnimationSet(animationId);
            state.syncReloadState(npc, stack);
            NativeNpcMovementTracker.Sample movement = state.movement.sample(worldTick, npc.getX(), npc.getZ());
            state.setProneTarget(isProne(npc));
            selectLoops(state, npc, animationId, movement);
            state.applier.setTickDelta(partialTick);
            update(state.applier, "head", model.head);
            update(state.applier, "torso", model.body);
            update(state.applier, "rightArm", model.rightArm);
            update(state.applier, "leftArm", model.leftArm);
            update(state.applier, "rightLeg", model.rightLeg);
            update(state.applier, "leftLeg", model.leftLeg);
            if (model instanceof PlayerModel<?> player) copyWearLayers(player);
            // CNPC's client pose is not rebuilt from Dominion's server-only stance tag.  Its
            // replicated shift bit is therefore the authoritative client animation signal.
            state.trace(npc, model, animationId, worldTick, movement, limbSwingAmount, isProne(npc),
                    tacticalCrouching(npc), npc.isCrouching(), aiming(npc));
        } catch (Throwable error) {
            clear(npc);
            reportFailure(error);
        }
    }

    public static void applyBodyTransform(EntityNPCInterface npc, PoseStack poseStack, float partialTick) {
        if (!NativeNpcEligibility.active(npc)) {
            clear(npc);
            return;
        }
        State state;
        synchronized (NativeNpcAnimationController.class) { state = STATES.get(npc.getId()); }
        if (state == null || !npc.getUUID().equals(state.uuid)) return;
        try {
            state.applier.setTickDelta(partialTick);
            // RenderNPCInterface's native Crawl(7) transform was replaced with the same
            // -90 degree swim-root convention used by PlayerRenderer.  The TaCZ lie clips are
            // authored against precisely that player coordinate system; easing this root with
            // the loop fade avoids a frame where one coordinate system has switched but the
            // hands are still blended from the other.
            float proneRoot = state.proneRootProgress(partialTick);
            float proneAnchor = 0.0F;
            if (proneRoot > 0.0F) {
                // setupRotations receives the CNPC foot origin. A torso pivot without a separate
                // ground anchor raises the entire figure by the pivot height and makes it float.
                // Apply the anchor first (world-up), then rotate about the torso, so placement
                // and rotation center are independent rather than fighting each other.
                proneAnchor = (PRONE_GROUND_ANCHOR_Y + PRONE_RENDER_LIFT) * proneRoot;
                poseStack.translate(0.0D, proneAnchor, 0.0D);
                poseStack.translate(0.0D, PRONE_ROOT_PIVOT_Y, 0.0D);
                poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F * proneRoot));
                poseStack.translate(0.0D, -PRONE_ROOT_PIVOT_Y, 0.0D);
            }
            Vec3f position = state.applier.get3DTransform("body", TransformType.POSITION, Vec3f.ZERO);
            Vec3f rotation = state.applier.get3DTransform("body", TransformType.ROTATION, Vec3f.ZERO);
            state.traceRoot(npc, clientWorldTick(npc), position, rotation, proneRoot,
                    PRONE_RENDER_LIFT * proneRoot, PRONE_ROOT_PIVOT_Y, proneAnchor);
            poseStack.translate(position.getX().floatValue(), position.getY().floatValue() + 0.7D,
                    position.getZ().floatValue());
            poseStack.mulPose(Axis.ZP.rotation(rotation.getZ().floatValue()));
            poseStack.mulPose(Axis.YP.rotation(rotation.getY().floatValue()));
            poseStack.mulPose(Axis.XP.rotation(rotation.getX().floatValue()));
            poseStack.translate(0.0D, -0.7D, 0.0D);
        } catch (Throwable error) {
            clear(npc);
            reportFailure(error);
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        synchronized (NativeNpcAnimationController.class) {
            STATES.values().removeIf(state -> {
                try {
                    state.stack.tick();
                    state.tickProneRoot();
                } catch (Throwable error) {
                    reportFailure(error);
                    return true;
                }
                return Minecraft.getInstance().level == null
                        || state.lastSeenWorldTick + 200L < Minecraft.getInstance().level.getGameTime();
            });
        }
    }

    @SubscribeEvent
    public void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof EntityNPCInterface npc && event.getLevel().isClientSide) clear(npc);
    }

    @SubscribeEvent
    public void onShoot(GunShootEvent event) {
        if (event.getLogicalSide() != LogicalSide.CLIENT || !(event.getShooter() instanceof EntityNPCInterface npc)
                || !NativeNpcEligibility.active(npc)) return;
        safePlayOnce(npc, event.getGunItemStack(), isProne(npc) ? (aiming(npc) ? "lie_aim_fire" : "lie_normal_fire")
                : (aiming(npc) ? "aim_fire_upper" : "normal_fire_upper"), OnceAnimationArbitrator.Action.FIRE,
                "GUN_SHOOT_EVENT");
    }

    @SubscribeEvent
    public void onReload(GunReloadEvent event) {
        if (event.getLogicalSide() != LogicalSide.CLIENT || !(event.getEntity() instanceof EntityNPCInterface npc)
                || !NativeNpcEligibility.active(npc)) return;
        state(npc).noteReloadEvent();
        safePlayOnce(npc, event.getGunItemStack(), isProne(npc) ? "lie_reload" : "reload_upper",
                OnceAnimationArbitrator.Action.RELOAD, "GUN_RELOAD_EVENT");
    }

    @SubscribeEvent
    public void onDraw(GunDrawEvent event) {
        if (event.getLogicalSide() != LogicalSide.CLIENT || !(event.getEntity() instanceof EntityNPCInterface npc)
                || !NativeNpcEligibility.active(npc)) return;
        // GunDrawEvent can be replayed while client weapon data synchronizes.  Rebuilding the
        // whole stack here restarts the walk fade every replay, which looks like a flickering
        // sliding model. The render path resets loop layers only when its animator resource id
        // actually changes; this event needs to discard only a stale one-shot action.
        clearOnceUnlessReloading(npc);
    }

    private static void safePlayOnce(EntityNPCInterface npc, ItemStack stack, String name,
                                     OnceAnimationArbitrator.Action action, String source) {
        try {
            OncePlayback result = playOnce(npc, stack, name, action);
            if (action == OnceAnimationArbitrator.Action.RELOAD) traceReload(npc, name, source, result);
        } catch (Throwable error) {
            clear(npc);
            reportFailure(error);
        }
    }

    private static OncePlayback playOnce(EntityNPCInterface npc, ItemStack stack, String name,
                                         OnceAnimationArbitrator.Action action) {
        Optional<GunDisplayInstance> display = TimelessAPI.getGunDisplay(stack);
        if (display.isEmpty() || display.get().getPlayerAnimator3rd() == null) {
            return OncePlayback.noAnimationSet();
        }
        ResourceLocation id = display.get().getPlayerAnimator3rd();
        State state = state(npc);
        // An event may arrive just before the NPC's first render. Record the same set used to
        // resolve the one-shot now, otherwise apply() would treat its first render as a set
        // switch and clear this freshly-started reload animation.
        state.switchAnimationSet(id);
        Optional<dev.kosmx.playerAnim.core.data.KeyframeAnimation> animation = NativeNpcAnimationAssets.get().find(id, name);
        if (animation.isEmpty()) {
            String missingKey = id + "#" + name;
            if (MISSING_ONCE_ANIMATION_REPORTED.add(missingKey)) {
                CustomNpcsTaczCompat.LOGGER.warn(
                        "[CNPC-TACZ-ANIM-MISSING] animationSet={} has no one-shot {}; retaining the matching hold/lie pose",
                        id, name);
            }
            return OncePlayback.missingAsset(id, state.onceAction, state.onceActive());
        }
        OnceAnimationArbitrator.Action previous = state.onceAction;
        boolean previousActive = state.onceActive();
        OnceAnimationArbitrator.Decision decision = OnceAnimationArbitrator.decide(previous, previousActive, action);
        if (decision == OnceAnimationArbitrator.Decision.START
                || decision == OnceAnimationArbitrator.Decision.PREEMPT_WITH_RELOAD) {
            if (action == OnceAnimationArbitrator.Action.RELOAD) {
                if (state.reloadPlaybackSpeed <= 0.0F) {
                    state.reloadPlaybackSpeed = reloadPlaybackSpeed(stack, animation.get());
                }
                state.onceSpeed.speed = state.reloadPlaybackSpeed;
            } else {
                state.onceSpeed.speed = 1.0F;
            }
            state.once.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(3, Ease.INOUTSINE),
                    new KeyframeAnimationPlayer(animation.get()));
            state.onceAction = action;
        }
        return new OncePlayback(id, true, previous, previousActive, decision);
    }

    private static void traceReload(EntityNPCInterface npc, String requested, String source, OncePlayback result) {
        CustomNpcsTaczCompat.LOGGER.info(
                "[CNPC-TACZ-RELOAD-ANIM] npcId={} uuid={} source={} prone={} reloadState={} animationSet={} requested={} assetFound={} previousAction={} previousActive={} decision={}",
                npc.getId(), npc.getUUID(), source, isProne(npc), reloadState(npc), result.animationSet(), requested,
                result.assetFound(), result.previousAction(), result.previousActive(), result.decision());
    }

    private static boolean reloadActive(EntityNPCInterface npc) {
        ReloadState reloadState = IGunOperator.fromLivingEntity(npc).getSynReloadState();
        // ReloadState is a mutable value object, not an enum.  Its public countdown sentinel
        // is the stable TaCZ API contract across the tested 1.1.x baseline and later builds.
        return reloadState != null && reloadState.getCountDown() != ReloadState.NOT_RELOADING_COUNTDOWN;
    }

    private static String reloadState(EntityNPCInterface npc) {
        try {
            return String.valueOf(IGunOperator.fromLivingEntity(npc).getSynReloadState());
        } catch (Throwable ignored) {
            return "UNAVAILABLE";
        }
    }

    /** Match a pack's generic PlayerAnimator clip to this gun's authoritative TaCZ reload time. */
    private static float reloadPlaybackSpeed(ItemStack stack,
                                             dev.kosmx.playerAnim.core.data.KeyframeAnimation animation) {
        try {
            IGun gun = IGun.getIGunOrNull(stack);
            if (gun == null || animation == null || animation.getLength() <= 0) return 1.0F;
            return TimelessAPI.getCommonGunIndex(gun.getGunId(stack)).map(index -> {
                var reload = index.getGunData().getReloadData();
                if (reload == null || reload.getCooldown() == null) return 1.0F;
                boolean empty = gun.getCurrentAmmoCount(stack) <= 0;
                float seconds = empty ? reload.getCooldown().getEmptyTime() : reload.getCooldown().getTacticalTime();
                if (!(seconds > 0.0F)) return 1.0F;
                return Mth.clamp(animation.getLength() / (seconds * 20.0F), 0.05F, 4.0F);
            }).orElse(1.0F);
        } catch (RuntimeException | LinkageError ignored) {
            return 1.0F;
        }
    }

    private static void selectLoops(State state, EntityNPCInterface npc, ResourceLocation id,
                                    NativeNpcMovementTracker.Sample movement) {
        boolean prone = isProne(npc);
        boolean moving = movement.walking();
        // EntityNPCInterface#isCrouching reads its locally cached Pose. Dominion deliberately
        // does not set that pose, because doing so resizes CNPC's collision box. The explicit
        // synchronized shift bit is the client-safe source for PlayerAnimator selection.
        boolean crouching = tacticalCrouching(npc);
        IGunOperator operator = IGunOperator.fromLivingEntity(npc);
        boolean aiming = operator.getSynAimingProgress() > 0.0F;
        if (prone) {
            // Native Crawl(7) remains the authoritative server state, but its client renderer
            // root is replaced by PlayerRenderer's prone convention. This lets the full TaCZ
            // lie family own torso, head, arms, weapon and one-shots without a double rotation.
            setLoop(state.lower, state, Slot.LOWER, id, "hold_lower");
            setLoop(state.loopUpper, state, Slot.UPPER, id,
                    specialUpper(operator, true, moving ? "lie_move" : aiming ? "lie_aim" : "lie"));
        } else {
            boolean running = moving && npc.isSprinting();
            String lower = npc.getVehicle() != null ? "ride_lower" : running ? "run_lower"
                    : moving ? crouching ? "crouch_walk_lower" : "walk_lower"
                    : crouching ? "crouch_lower" : "hold_lower";
            String upper = aiming ? "aim_upper" : running ? "run_upper"
                    : moving ? crouching ? "crouch_walk_upper" : "walk_upper" : "hold_upper";
            setLoop(state.lower, state, Slot.LOWER, id, lower);
            setLoop(state.loopUpper, state, Slot.UPPER, id, specialUpper(operator, false, upper));
        }
    }

    private static boolean tacticalCrouching(EntityNPCInterface npc) {
        return npc.isShiftKeyDown();
    }

    /**
     * Current TaCZ packs normally omit explicit draw/bolt PlayerAnimator clips;
     * when they do, the requested clip simply resolves to the ordinary hold/ADS
     * loop below. Packs that add those conventional names gain the animation
     * without an API or version change.
     */
    private static String specialUpper(IGunOperator operator, boolean prone, String fallback) {
        if (operator.getSynIsBolting()) return prone ? "lie_bolt" : "bolt_upper";
        if (operator.getSynDrawCoolDown() > 0L) return prone ? "lie_draw" : "draw_upper";
        return fallback;
    }

    private static void setLoop(ModifierLayer<IAnimation> layer, State state, Slot slot,
                                ResourceLocation id, String name) {
        state.requested.put(slot, name);
        if (name.equals(state.names.get(slot))) return;
        Optional<dev.kosmx.playerAnim.core.data.KeyframeAnimation> exact = NativeNpcAnimationAssets.get().find(id, name);
        exact.or(() -> fallbackAnimation(id, name)).ifPresentOrElse(animation -> {
            layer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(8, Ease.INOUTSINE),
                    new KeyframeAnimationPlayer(animation));
            state.names.put(slot, name);
            state.resolved.put(slot, exact.isPresent() ? name : "fallback:" + fallbackName(name));
        }, () -> {
            layer.setAnimation(null);
            // Remember the failed request too: retrying every render would endlessly reset a
            // fade and conceal the real pack-asset problem.
            state.names.put(slot, name);
            state.resolved.put(slot, "MISSING");
        });
    }

    private static Optional<dev.kosmx.playerAnim.core.data.KeyframeAnimation> fallbackAnimation(
            ResourceLocation id, String requested) {
        if ("crouch_walk_upper".equals(requested)) {
            return NativeNpcAnimationAssets.get().find(id, "walk_upper")
                    .or(() -> NativeNpcAnimationAssets.get().find(id, "hold_upper"));
        }
        if ("crouch_lower".equals(requested)) {
            return NativeNpcAnimationAssets.get().find(id, "hold_lower");
        }
        if ("crouch_walk_lower".equals(requested)) {
            return NativeNpcAnimationAssets.get().find(id, "walk_lower")
                    .or(() -> NativeNpcAnimationAssets.get().find(id, "hold_lower"));
        }
        if ("bolt_upper".equals(requested) || "draw_upper".equals(requested)) {
            return NativeNpcAnimationAssets.get().find(id, "hold_upper");
        }
        if ("lie_bolt".equals(requested) || "lie_draw".equals(requested)) {
            return NativeNpcAnimationAssets.get().find(id, "lie");
        }
        return Optional.empty();
    }

    private static String fallbackName(String requested) {
        return switch (requested) {
            case "crouch_walk_upper" -> "walk_upper";
            case "crouch_lower" -> "hold_lower";
            case "crouch_walk_lower" -> "walk_lower";
            case "bolt_upper", "draw_upper" -> "hold_upper";
            case "lie_bolt", "lie_draw" -> "lie";
            default -> "none";
        };
    }

    private static boolean aiming(EntityNPCInterface npc) {
        return IGunOperator.fromLivingEntity(npc).getSynAimingProgress() > 0.0F;
    }

    private static boolean isProne(EntityNPCInterface npc) {
        // Crawl(7), not TaCZ's slightly earlier/later capability packet, is the visible native
        // CNPC state. Selecting lie clips from the capability alone produced one-frame mixed
        // coordinate systems at the transition boundary.
        return NpcCrawlState.isCrawling(npc);
    }

    /** Used by the renderer mixin to bypass only CNPC's incompatible Crawl(7) root branch. */
    public static boolean replacesNativeCrawlRoot(EntityNPCInterface npc) {
        if (npc == null || !NativeNpcEligibility.active(npc) || !NpcCrawlState.isCrawling(npc)) return false;
        synchronized (NativeNpcAnimationController.class) {
            State state = STATES.get(npc.getId());
            // Never suppress the base CNPC crawl unless this NPC already has a live TaCZ
            // PlayerAnimator state to supply the replacement root and lie bones.
            return state != null && npc.getUUID().equals(state.uuid);
        }
    }

    private static void update(AnimationProcessor applier, String partName, ModelPart part) {
        // AnimationApplier is the concrete processor used by PlayerAnimator's own PlayerModel mixin.
        ((dev.kosmx.playerAnim.impl.animation.AnimationApplier) applier).updatePart(partName, part);
    }

    private static void copyWearLayers(PlayerModel<?> model) {
        model.hat.copyFrom(model.head);
        model.jacket.copyFrom(model.body);
        model.leftSleeve.copyFrom(model.leftArm);
        model.rightSleeve.copyFrom(model.rightArm);
        model.leftPants.copyFrom(model.leftLeg);
        model.rightPants.copyFrom(model.rightLeg);
    }

    private static synchronized State state(EntityNPCInterface npc) {
        State current = STATES.get(npc.getId());
        if (current == null || !current.uuid.equals(npc.getUUID())) {
            current = new State(npc);
            STATES.put(npc.getId(), current);
        }
        return current;
    }

    private static synchronized void clear(EntityNPCInterface npc) { STATES.remove(npc.getId()); }

    private static synchronized void clearOnce(EntityNPCInterface npc) {
        State state = STATES.get(npc.getId());
        if (state != null && npc.getUUID().equals(state.uuid)) state.clearOnce();
    }

    /** A TaCZ synchronization draw replay must never restart an active reload clip. */
    private static synchronized void clearOnceUnlessReloading(EntityNPCInterface npc) {
        State state = STATES.get(npc.getId());
        if (state == null || !npc.getUUID().equals(state.uuid)) return;
        if (reloadActive(npc) || state.onceAction == OnceAnimationArbitrator.Action.RELOAD) return;
        state.clearOnce();
    }

    /**
     * GBPort's client-side NPC replica can keep a stale Entity#tickCount while its position,
     * world time and renderer continue normally.  Never use that per-entity counter for a
     * render-side lifecycle, animation tick boundary or motion sample.
     */
    private static long clientWorldTick(EntityNPCInterface npc) {
        return Minecraft.getInstance().level == null ? npc.tickCount : Minecraft.getInstance().level.getGameTime();
    }

    /** Emits one detailed record every two seconds per gun-holding NPC that was not accepted. */
    private static void traceIneligible(EntityNPCInterface npc) {
        if (IGun.getIGunOrNull(npc.getMainHandItem()) == null) return;
        boolean humanoid = NativeNpcEligibility.isSixBoneHumanoid(npc);
        boolean ysm = humanoid && NativeNpcEligibility.usesYsmRenderer(npc);
        traceSkip(npc, humanoid ? (ysm ? "ysm_renderer_owns_npc" : "eligibility_rejected") : "non_humanoid_model",
                null, null);
    }

    private static void traceSkip(EntityNPCInterface npc, String reason, ResourceLocation animationSet, String detail) {
        if (npc == null || npc.tickCount % 40 != 0) return;
        CustomNpcsTaczCompat.LOGGER.info(
                "[CNPC-TACZ-ANIM-SKIP] npcId={} uuid={} tick={} reason={} modelId={} gun={} animationSet={} detail={}",
                npc.getId(), npc.getUUID(), npc.tickCount, reason,
                npc.display == null ? "<null-display>" : npc.display.getModel(), npc.getMainHandItem(), animationSet, detail);
    }

    private static void reportFailure(Throwable error) {
        if (FAILURE_REPORTED.compareAndSet(false, true)) {
            CustomNpcsTaczCompat.LOGGER.error(
                    "Native CNPC PlayerAnimator bridge failed; TaCZ fallback pose remains active", error);
        }
    }

    private enum Slot { LOWER, UPPER }

    private record OncePlayback(ResourceLocation animationSet, boolean assetFound,
                                OnceAnimationArbitrator.Action previousAction, boolean previousActive,
                                OnceAnimationArbitrator.Decision decision) {
        private static OncePlayback noAnimationSet() {
            return new OncePlayback(null, false, OnceAnimationArbitrator.Action.NONE, false,
                    OnceAnimationArbitrator.Decision.NO_ANIMATION_SET);
        }

        private static OncePlayback missingAsset(ResourceLocation animationSet,
                                                 OnceAnimationArbitrator.Action previousAction,
                                                 boolean previousActive) {
            return new OncePlayback(animationSet, false, previousAction, previousActive,
                    OnceAnimationArbitrator.Decision.MISSING_ASSET);
        }
    }

    private static final class State {
        private final UUID uuid;
        private final AnimationStack stack = new AnimationStack();
        private final ModifierLayer<IAnimation> lower = new ModifierLayer<>();
        private final ModifierLayer<IAnimation> loopUpper = new ModifierLayer<>();
        private final SpeedModifier onceSpeed = new SpeedModifier(1.0F);
        private final ModifierLayer<IAnimation> once = new ModifierLayer<>(null, onceSpeed);
        private final ModifierLayer<IAnimation> rotation;
        private final dev.kosmx.playerAnim.impl.animation.AnimationApplier applier;
        private final Map<Slot, String> names = new HashMap<>();
        private final Map<Slot, String> requested = new HashMap<>();
        private final Map<Slot, String> resolved = new HashMap<>();
        private final NativeNpcMovementTracker movement = new NativeNpcMovementTracker();
        private ResourceLocation animationSet;
        private OnceAnimationArbitrator.Action onceAction = OnceAnimationArbitrator.Action.NONE;
        private boolean synchronizedReloadActive;
        private boolean reloadEventSeen;
        private float reloadPlaybackSpeed;
        private boolean proneTarget;
        private float proneRootProgress;
        private float previousProneRootProgress;
        private long lastSeenWorldTick = Long.MIN_VALUE;
        private long lastTraceWorldTick = Long.MIN_VALUE;
        private long lastRootTraceWorldTick = Long.MIN_VALUE;
        private String lastTraceSignature = "";

        private State(EntityNPCInterface npc) {
            this.uuid = npc.getUUID();
            this.rotation = new ModifierLayer<>(null, new AdjustmentModifier(new NativeNpcRotationAdjustment(npc)));
            stack.addAnimLayer(LOWER_PRIORITY, lower);
            stack.addAnimLayer(LOOP_UPPER_PRIORITY, loopUpper);
            stack.addAnimLayer(ONCE_UPPER_PRIORITY, once);
            stack.addAnimLayer(ROTATION_PRIORITY, rotation);
            applier = new dev.kosmx.playerAnim.impl.animation.AnimationApplier(stack);
        }

        private void switchAnimationSet(ResourceLocation next) {
            if (next.equals(animationSet)) return;
            animationSet = next;
            // A resource set change genuinely needs a clean fade.  Merely receiving another
            // GunDrawEvent does not: it may happen during an ordinary synchronization replay.
            lower.setAnimation(null);
            loopUpper.setAnimation(null);
            clearOnce();
            names.clear();
            requested.clear();
            resolved.clear();
        }

        private boolean onceActive() {
            return once.getAnimation() != null && once.getAnimation().isActive();
        }

        private void clearOnce() {
            once.setAnimation(null);
            onceAction = OnceAnimationArbitrator.Action.NONE;
        }

        private void noteReloadEvent() {
            reloadEventSeen = true;
        }

        private void syncReloadState(EntityNPCInterface npc, ItemStack stack) {
            boolean active = reloadActive(npc);
            if (!active) {
                synchronizedReloadActive = false;
                reloadEventSeen = false;
                reloadPlaybackSpeed = 0.0F;
                onceSpeed.speed = 1.0F;
                if (onceAction == OnceAnimationArbitrator.Action.RELOAD) clearOnce();
                return;
            }
            if (!synchronizedReloadActive) {
                synchronizedReloadActive = true;
                if (reloadEventSeen) return;
            } else if (onceAction == OnceAnimationArbitrator.Action.RELOAD) {
                return;
            }
            // Packet loss/order must not leave an NPC frozen in its hold pose for a successful
            // server reload. Do not loop or periodically replay an expired clip: SpeedModifier
            // interpolation around an artificial final-frame loop produces visible recoil-like
            // twitches. The matching base hold pose is the safe fallback until TaCZ ends reload.
            safePlayOnce(npc, stack, isProne(npc) ? "lie_reload" : "reload_upper",
                    OnceAnimationArbitrator.Action.RELOAD,
                    reloadEventSeen ? "SYNC_RELOAD_STATE_RECOVERY" : "SYNC_RELOAD_STATE_EDGE");
        }

        private void setProneTarget(boolean prone) {
            proneTarget = prone;
        }

        private void tickProneRoot() {
            previousProneRootProgress = proneRootProgress;
            float step = 1.0F / PRONE_ROOT_FADE_TICKS;
            proneRootProgress = Mth.clamp(proneRootProgress + (proneTarget ? step : -step), 0.0F, 1.0F);
        }

        /**
         * PlayerRenderer samples its swim angle with partial ticks.  Rendering a raw server-tick
         * counter here made every crawl transition advance in 11.25-degree steps.  The same
         * INOUTSINE curve used by TaCZ's eight-tick loop fade keeps root and limbs continuous.
         */
        private float proneRootProgress(float partialTick) {
            float raw = Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F),
                    previousProneRootProgress, proneRootProgress);
            return 0.5F - 0.5F * Mth.cos(raw * Mth.PI);
        }

        private void trace(EntityNPCInterface npc, HumanoidModel<?> model, ResourceLocation animationId,
                           long worldTick,
                           NativeNpcMovementTracker.Sample movement, float limbSwingAmount,
                           boolean prone, boolean crouching, boolean poseCrouching, boolean aiming) {
            String signature = animationId + "|" + model.getClass().getName() + "|" + movement.walking()
                    + "|" + movement.teleported() + "|" + npc.isSprinting() + "|" + prone + "|" + crouching
                    + "|" + poseCrouching + "|" + aiming
                    + "|" + requested + "|" + resolved;
            if (lastTraceWorldTick == worldTick) return;
            if (lastTraceWorldTick != Long.MIN_VALUE && worldTick - lastTraceWorldTick < 20L
                    && signature.equals(lastTraceSignature)) return;
            lastTraceWorldTick = worldTick;
            lastTraceSignature = signature;
            CustomNpcsTaczCompat.LOGGER.info(
                    "[CNPC-TACZ-ANIM] npcId={} uuid={} worldTick={} npcTick={} model={} animator={} pos=({},{},{}) limbSwingAmount={} moving={} distancePerTick={} speed={} movementYaw={} idleTicks={} teleport={} backpedal={} sprint={} prone={} crouchShift={} crouchPose={} aiming={} requested={} resolved={} lowerActive={} upperActive={} onceActive={} legs=({},{}; {},{})",
                    npc.getId(), npc.getUUID(), worldTick, npc.tickCount, model.getClass().getName(), animationId,
                    decimal(npc.getX()), decimal(npc.getY()), decimal(npc.getZ()), decimal(limbSwingAmount),
                    movement.walking(), decimal(movement.distancePerTick()), decimal(movement.speed()),
                    decimal(movement.movementYaw()), movement.idleTicks(), movement.teleported(),
                    movement.backpedalling(npc.yBodyRot), npc.isSprinting(), prone, crouching, poseCrouching, aiming,
                    requested, resolved, lower.isActive(), loopUpper.isActive(), once.isActive(),
                    decimal(model.rightLeg.xRot), decimal(model.rightLeg.yRot),
                    decimal(model.leftLeg.xRot), decimal(model.leftLeg.yRot));
        }

        private void traceRoot(EntityNPCInterface npc, long worldTick, Vec3f position, Vec3f rotation,
                               float proneRoot, float proneLift, float pronePivotY, float proneAnchor) {
            if (lastRootTraceWorldTick == worldTick || lastRootTraceWorldTick != Long.MIN_VALUE
                    && worldTick - lastRootTraceWorldTick < 20L) return;
            lastRootTraceWorldTick = worldTick;
            CustomNpcsTaczCompat.LOGGER.info(
                    "[CNPC-TACZ-ROOT] npcId={} worldTick={} npcTick={} playerProneRoot={} proneLift={} pronePivotY={} proneAnchor={} bodyPosition=({},{},{}) bodyRotation=({},{},{})",
                    npc.getId(), worldTick, npc.tickCount, decimal(proneRoot), decimal(proneLift), decimal(pronePivotY), decimal(proneAnchor), decimal(position.getX().floatValue()), decimal(position.getY().floatValue()),
                    decimal(position.getZ().floatValue()), decimal(rotation.getX().floatValue()),
                    decimal(rotation.getY().floatValue()), decimal(rotation.getZ().floatValue()));
        }

        private static String decimal(double value) {
            return Double.isFinite(value) ? String.format(java.util.Locale.ROOT, "%.4f", value) : "nan";
        }

    }

    /** CNPC equivalent of TaCZ's player body/head/arm correction layer. */
    private static final class NativeNpcRotationAdjustment
            implements Function<String, Optional<AdjustmentModifier.PartModifier>> {
        private final WeakReference<EntityNPCInterface> npc;

        private NativeNpcRotationAdjustment(EntityNPCInterface npc) {
            this.npc = new WeakReference<>(npc);
        }

        @Override
        public Optional<AdjustmentModifier.PartModifier> apply(String partName) {
            EntityNPCInterface entity = npc.get();
            if (entity == null) return Optional.empty();

            float partialTick = Minecraft.getInstance().getPartialTick();
            float pitch = Mth.wrapDegrees(Mth.lerp(partialTick, entity.xRotO, entity.getXRot()));

            return switch (partName) {
                case "head" -> Optional.of(new AdjustmentModifier.PartModifier(
                        new Vec3f(pitch * Mth.DEG_TO_RAD, 0.0F, 0.0F), Vec3f.ZERO));
                case "leftArm", "rightArm" -> TimelessAPI.getGunDisplay(entity.getMainHandItem())
                        .map(GunDisplayInstance::is3rdFixedHand).orElse(false) ? Optional.empty()
                        : Optional.of(new AdjustmentModifier.PartModifier(
                        new Vec3f(pitch * Mth.DEG_TO_RAD, 0.0F, 0.0F), Vec3f.ZERO));
                default -> Optional.empty();
            };
        }
    }
}
