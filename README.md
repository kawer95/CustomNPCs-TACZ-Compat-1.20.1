# CustomNPCs TaCZ Compat

Independent Forge 1.20.1 add-on for original, six-bone CustomNPCs humanoid models using TaCZ weapons. It does not depend on YSM and does not make an NPC pretend to be a player.

## Install

Install on both sides:

- CustomNPCs GBPort `1.20.1.20260711` or newer
- TaCZ `1.1.5` or newer
- This add-on

Install on clients only:

- PlayerAnimator `1.0.2-rc1+1.20` or newer

DominionSword `1.32.42` or newer is optional. It supplies command queue, watch, prone, and target-acquisition coordination when present; ordinary native CNPC gun AI remains active without it.

The release jar never bundles or relocates PlayerAnimator. `mods.toml` intentionally specifies lower bounds without an upper bound: its Gradle versions are only a tested compile baseline, not an installation lock.

## Behavior

- Native CNPCs holding TaCZ guns draw, ADS, shoot, bolt, reload, consume CNPC inventory ammunition, and use CNPC ranged accuracy.
- Movement supports native pursuit/strafe/retreat plus Dominion watch, sentry, close-quarters, prone, and queued target behavior.
- Friendly CNPC/player factions are protected from TaCZ bullet and bullet-explosion damage.
- Steve, Alex, Classic, and 64×32 player-style CNPC models receive PlayerAnimator lower-body, upper-body, one-shot, and root-body layers.
- Dragon, slime, horse, golem, and other non-humanoid models are not targeted.
- If `CustomNPCs YSM Compat` is installed, a YSM-rendered NPC is excluded here. A native-model NPC is controlled only by this add-on.

## Animation fallback

This add-on reads TaCZ gun-pack `player_animator` resources through its own reload listener, rather than calling TaCZ's non-public cache. If a newer TaCZ or PlayerAnimator release changes an API, resource layout, or parse contract, it reports one clear diagnostic, clears the local PlayerAnimator state, and leaves TaCZ's standard third-person hold/aim pose active. Server shooting AI and saved NPC data are unaffected.

Current TaCZ packs generally do not publish explicit `draw_upper` / `bolt_upper` clips. Those transitions fall back to hold/ADS now; packs that provide the conventional clips use them automatically.

## Build and verification

Run `gradlew.bat test jar` from this directory. `test` executes the pure combat-policy verification task before the standard Gradle lifecycle task. The verification avoids ForgeGradle's JUnit worker, which cannot resolve test output in the current non-ASCII mapped workspace path. For a local client smoke test at the compile baseline, use `gradlew.bat runClient -Pwith_playeranimator`; that opt-in never changes the release jar.

Manual in-game acceptance is still required for each supported model: idle, walk/run, ADS, semi/auto fire, empty reload, bolt, prone, resource reload, YSM coexistence, and a dedicated server without PlayerAnimator.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
