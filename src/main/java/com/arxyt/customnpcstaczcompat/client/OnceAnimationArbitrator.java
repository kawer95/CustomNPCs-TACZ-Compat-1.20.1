package com.arxyt.customnpcstaczcompat.client;

/**
 * Priority policy for the one-shot PlayerAnimator layer.
 *
 * <p>TaCZ sends fire and reload as independent client events. A reload is
 * gameplay-critical visual feedback and must not be discarded merely because
 * the final muzzle-flash clip is still active. Conversely, a late fire packet
 * must never overwrite an in-progress reload.</p>
 */
public final class OnceAnimationArbitrator {
    private OnceAnimationArbitrator() { }

    public enum Action {
        NONE,
        FIRE,
        RELOAD
    }

    public enum Decision {
        START,
        PREEMPT_WITH_RELOAD,
        KEEP_RELOAD,
        BLOCKED_BY_RELOAD,
        BLOCKED_BY_ACTIVE_ACTION,
        NO_ANIMATION_SET,
        MISSING_ASSET
    }

    public static Decision decide(Action current, boolean currentActive, Action requested) {
        if (!currentActive) return Decision.START;
        if (requested == Action.RELOAD) {
            return current == Action.RELOAD ? Decision.KEEP_RELOAD : Decision.PREEMPT_WITH_RELOAD;
        }
        return current == Action.RELOAD ? Decision.BLOCKED_BY_RELOAD : Decision.BLOCKED_BY_ACTIVE_ACTION;
    }
}
