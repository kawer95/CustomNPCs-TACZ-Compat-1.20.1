package com.arxyt.customnpcstaczcompat;

import com.arxyt.customnpcstaczcompat.client.NativeNpcMovementTracker;
import com.arxyt.customnpcstaczcompat.client.OnceAnimationArbitrator;

/**
 * Dependency-free automatic verification entry point. ForgeGradle's JUnit worker
 * is not reliable for this mapped 1.20.1 workspace, so Gradle invokes this
 * directly as part of its ordinary {@code test} lifecycle task.
 */
public final class NativeGunLogicCheck {
    private NativeGunLogicCheck() { }

    public static void main(String[] args) {
        check(GunTactics.decide(true, 40.0D, 32.0D, false) == GunTactics.Maneuver.PURSUE,
                "long-range native target should be pursued");
        check(GunTactics.decide(true, 6.0D, 32.0D, false) == GunTactics.Maneuver.RETREAT,
                "close native target should trigger retreat");
        check(GunTactics.decide(true, 6.0D, 32.0D, true) == GunTactics.Maneuver.HOLD,
                "close-quarters command must suppress retreat");
        check(!GunTactics.canFire(true, false, 200.0D, 32.0D), "prone does not shoot through walls");
        check(GunTactics.canFire(true, true, 200.0D, 32.0D), "prone may fire past preferred range");
        check(NpcCrawlState.CRAWL_ANIMATION == 7, "GBPort crawl animation contract changed");
        check(TargetReactionTactics.duration(false, 90.0D, false) == 20, "static reaction duration changed");
        check(TargetReactionTactics.duration(true, 0.0D, false) == 10, "rifle forward reaction floor changed");
        check(TargetReactionTactics.duration(true, 180.0D, true) == 40, "machine-gun about-face delay changed");
        check(TargetReactionTactics.duration(true, 0.0D, true) == 1, "machine-gun forward floor changed");
        check(TargetReactionTactics.bypassesReactionWindow(true), "direct attack must bypass reaction");
        check(!TargetReactionTactics.bypassesReactionWindow(false), "queued attacks must retain reaction");
        check(GunTactics.decideControlled(false, true, 8.0D, 32.0D, false, false)
                        == GunTactics.Maneuver.SENTRY,
                "a non-attack command must not create native pursuit");
        check(GunTactics.effectiveLineOfSight(true, true, false) == false,
                "watch must use Dominion's authoritative ray result");
        check(DominionCommandBridge.hasQueuedAttack("watch", 1), "watch queue should retain ADS");
        check(DominionCommandBridge.isDirectSingleTargetAttack("attack", true),
                "direct attack snapshot changed");
        check(!DominionCommandBridge.isDirectSingleTargetAttack("attack", false),
                "queued attack must not bypass the reaction window");
        check(NpcGunAimLock.nextReadyTick(true, 30, 9) == 31,
                "a changed target needs one orientation synchronization tick");
        check(NpcGunAimLock.nextReadyTick(false, 30, 31) == 31,
                "a continuous target must not repeatedly delay fire");
        check(!NpcGunAimLock.shouldClearDuringTail(false, false),
                "an autonomous target must retain its first-shot ready tick after tail maintenance");
        check(NpcGunAimLock.shouldClearDuringTail(true, true),
                "a blocked Dominion command must still clear its forced aim state");
        check(NativeGunTimeoutPolicy.transientFailure("IS_SPRINTING"),
                "sprinting must participate in stuck-state recovery");
        check(NativeGunTimeoutPolicy.timeoutTicks("IS_SPRINTING") == 40,
                "sprint recovery timeout changed");
        check(NativeGunTimeoutPolicy.timeoutTicks("IS_RELOADING") == 600,
                "legitimate long reloads need a conservative timeout");
        checkMovementSampling();
        checkOnceAnimationArbitration();
        System.out.println("Native CNPC TaCZ pure-logic checks passed");
    }

    private static void checkMovementSampling() {
        NativeNpcMovementTracker movement = new NativeNpcMovementTracker();
        check(!movement.sample(0, 0.0D, 0.0D).walking(), "first movement sample must be stationary");
        check(movement.sample(1, 0.2D, 0.0D).walking(), "real X/Z movement must select walk");
        check(movement.sample(2, 0.205D, 0.0D).walking(), "walk stop hysteresis changed");
        check(movement.sample(3, 0.205D, 0.0D).walking(), "network interpolation gap should retain walk");
        check(movement.sample(4, 0.205D, 0.0D).walking(), "stop grace should prevent loop flicker");
        check(!movement.sample(6, 0.205D, 0.0D).walking(), "stopped NPC must leave walk loop after grace");
        check(!movement.sample(7, 4.0D, 0.0D).walking(), "teleport must not play a walk loop");
    }

    private static void checkOnceAnimationArbitration() {
        check(OnceAnimationArbitrator.decide(OnceAnimationArbitrator.Action.FIRE, true,
                        OnceAnimationArbitrator.Action.RELOAD)
                        == OnceAnimationArbitrator.Decision.PREEMPT_WITH_RELOAD,
                "reload must preempt the final fire clip");
        check(OnceAnimationArbitrator.decide(OnceAnimationArbitrator.Action.RELOAD, true,
                        OnceAnimationArbitrator.Action.FIRE)
                        == OnceAnimationArbitrator.Decision.BLOCKED_BY_RELOAD,
                "late fire packets must not overwrite a reload animation");
        check(OnceAnimationArbitrator.decide(OnceAnimationArbitrator.Action.RELOAD, true,
                        OnceAnimationArbitrator.Action.RELOAD)
                        == OnceAnimationArbitrator.Decision.KEEP_RELOAD,
                "reload-state fallback must not restart an event-started reload");
        check(OnceAnimationArbitrator.decide(OnceAnimationArbitrator.Action.FIRE, false,
                        OnceAnimationArbitrator.Action.RELOAD)
                        == OnceAnimationArbitrator.Decision.START,
                "an inactive fire layer must allow reload to start");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
