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
        check(Math.abs(GunTactics.commandedApproachDistance(15.0D) - 13.5D) < 0.0001D,
                "command pursuit must settle inside the CNPC firing limit");
        check(GunTactics.decideControlled(true, true, 14.0D,
                        GunTactics.commandedApproachDistance(15.0D), false, false)
                        == GunTactics.Maneuver.PURSUE,
                "command pursuit must not stop at the hard fire boundary");
        check(GunTactics.canFire(false, true, 14.0D, 15.0D),
                "the hard CNPC firing range remains authoritative while settling");
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
        check(DominionCommandBridge.hasQueuedAttack("breach", 1), "breach queue should retain ADS");
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
        checkCombatSettingsValidation();
        checkMovementSampling();
        checkOnceAnimationArbitration();
        System.out.println("Native CNPC TaCZ pure-logic checks passed");
    }

    private static void checkCombatSettingsValidation() {
        NpcTaczCombatSettings sanitized = new NpcTaczCombatSettings(
                -1, 101, 80, -5, 16, 3, 12, 4, 90, 2);
        check(sanitized.range() == 1, "TaCZ range must keep a positive lower bound");
        check(sanitized.accuracy() == 100, "TaCZ accuracy must clamp to a percentage");
        check(sanitized.shotIntervalMin() == 80 && sanitized.shotIntervalMax() == 80,
                "shot interval maximum must never fall below its minimum");
        check(sanitized.burstShotsMin() == 16 && sanitized.burstShotsMax() == 16,
                "shots-per-group maximum must never fall below its minimum");
        check(sanitized.burstGroupsMin() == 12 && sanitized.burstGroupsMax() == 12,
                "group-count maximum must never fall below its minimum");
        check(sanitized.groupIntervalMin() == 90 && sanitized.groupIntervalMax() == 90,
                "group interval maximum must never fall below its minimum");
        NpcTaczCombatSettings nativeAuto = new NpcTaczCombatSettings(
                15, 60, 0, 5, 1, 1, 1, 1, 20, 40);
        check(nativeAuto.shotIntervalMin() == 0 && nativeAuto.shotIntervalMax() == 0,
                "a zero shot interval must remain an explicit mode value");
        check(nativeAuto.cadenceMode() == NpcTaczCombatSettings.CadenceMode.NATIVE_AUTO,
                "a zero shot interval must select native TaCZ automatic fire");
        NpcTaczCombatSettings nativeAutoByShots = new NpcTaczCombatSettings(
                15, 60, 5, 5, 1, 0, 1, 1, 20, 40);
        check(nativeAutoByShots.cadenceMode() == NpcTaczCombatSettings.CadenceMode.NATIVE_AUTO,
                "a zero maximum shots-per-group must select native TaCZ automatic fire");
        NpcTaczCombatSettings continuousPoint = new NpcTaczCombatSettings(
                15, 60, 5, 5, 1, 1, 1, 0, 20, 40);
        check(continuousPoint.cadenceMode() == NpcTaczCombatSettings.CadenceMode.CONTINUOUS_POINT,
                "a zero maximum group count must select continuous point fire");
        NpcTaczCombatSettings continuousPointByInterval = new NpcTaczCombatSettings(
                15, 60, 5, 5, 1, 1, 1, 1, 0, 40);
        check(continuousPointByInterval.cadenceMode() == NpcTaczCombatSettings.CadenceMode.CONTINUOUS_POINT,
                "a zero group interval must select continuous point fire");
        check(NpcTaczCombatSettings.DEFAULT.cadenceMode()
                        == NpcTaczCombatSettings.CadenceMode.INTERMITTENT_POINT,
                "fully populated controls must select intermittent point fire");
        check(sanitized.toTag().getInt("Schema") == NpcTaczCombatSettings.SCHEMA,
                "combat settings NBT schema changed unexpectedly");
        check(sanitized.toTag().getBoolean("Configured"),
                "a saved tactical policy must be explicitly marked configured");
        check(NpcTaczFirePattern.delayAfterShot(5, 1, false) == 5,
                "a one-shot group must still respect the configured shot interval");
        check(NpcTaczFirePattern.delayAfterShot(5, 20, true) == 25,
                "group spacing must add to, not replace, the shot interval");
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
