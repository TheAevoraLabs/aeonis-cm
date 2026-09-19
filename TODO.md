# Aeonis 5.0.0 - Known Limitations and Next Steps

This document records what is complete, what is not, and what needs a decision. It is
deliberately specific: the 5.0.0 port to Minecraft 26.3 reached a state where both
loader jars build, but several requested workstreams are incomplete.

## Status summary

Completed and verified:

- `gradle.properties`, the Gradle wrapper, and both loader build scripts target the
  26.3 line, with every version checked against official metadata.
- `common`, `fabric`, and `neoforge` all compile against Minecraft 26.3.
- `./gradlew build` produces both `fabric/build/libs/AeonisCM-5.0.0.jar` and
  `neoforge/build/libs/aeonis-5.0.0.jar` from a clean checkout.
- The Fabric jar boots to the main menu inside a real PolyMC instance (Fabric Loader
  0.19.5, Fabric API 0.161.0+26.3), driven by `scripts/mc-smoke-test.py`. This was the
  first time any build of this port was launched, and it found two mixins that
  crashed the client at startup (both fixed).
- Every mixin injection target in `fabric`, `neoforge` and `common` is checked against
  the 26.3 mappings by `scripts/check-mixin-targets.py`, which closes the audited gap
  described in section 4.
- The project is relicensed to GPL-3.0-or-later under Aevora Labs.
- Old branding (QuantumCreeper, Quantum_Creeper, TeamQC-MC, Emberveil, Emberdust) is
  removed from mod metadata, sources, and the in-game splash texts.
- Repository clutter removed: `fabric/temp-sources`, `fabric/temp-client-sources`,
  both committed `temp-*.zip` archives, an accidental full clone of the Fabric Loom
  source tree (`fabric-loom-1.16/`), the NeoForge MDK leftovers
  (`neoforge/README.md`, `neoforge/TEMPLATE_LICENSE.txt`), and the one-off migration
  scripts in `Tools/` that had already served their purpose.

Not completed (details below):

- The NeoForge jar cannot load on 26.3 while KotlinForForge does not support it.
- The Fabric loader has no equivalent of the three bridge mixins added for the
  variant system, so those variants report as unavailable there (harmless, but a gap).
- The special-attack list is still per-mob branching in `ControlledSpecialMobAbilityMixin`.
- Loader parity is only partly verified: the client boots and loads the mod on
  Fabric, but no in-game command has been exercised on either loader.
- Several source sets remain excluded from compilation, and legacy datapack content
  remains excluded from the build.

## 1. Blocking: KotlinForForge does not support Minecraft 26.3

This is an external blocker, not a bug in this repository, and it is the single
reason the NeoForge jar is not usable yet.

- KotlinForForge's own `6.x` branch `gradle.properties` contains
  `unsupported_mc_version = 26.3`.
- The newest published build, `6.3.0a`, declares a Minecraft dependency range of
  `[1.21.9,26.3)` in its `mods.toml`, which excludes 26.3.

The NeoForge module therefore still declares `kotlinforforge` as a required
dependency with range `[6.3.0,)`, and the jar builds, but the loader will refuse to
load it on 26.3.

Options when a decision is needed:

1. Wait for a KotlinForForge release that declares 26.3 support. Lowest effort.
2. Remove the dependency on KotlinForForge. This requires converting the NeoForge
   entrypoint and the other Kotlin classes under `neoforge/src/main/kotlin`
   (including `AeonisNeoForge.kt` and the client renderers) to Java, and bundling
   `kotlin-stdlib` via NeoForge's JarJar. Kotlin code in `common` can remain Kotlin,
   because it would be loaded as ordinary library classes rather than as mod
   entrypoints. This is the most robust option but is a substantial refactor.
3. Ship NeoForge for the last Minecraft version KotlinForForge supports, and Fabric
   for 26.3. This breaks the stated goal of loader parity.

Until one of these is chosen, treat the NeoForge jar as a build artifact only.

## 2. Source sets excluded from compilation

`fabric/build.gradle` still excludes code that was disabled by an earlier
migration attempt rather than fixed. These are not 26.3 issues; they were failing to
compile before 26.3 and the previous work hid them by exclusion.

Excluded in `common`/`fabric`:

- `com/qc/aeonis/entity/ancard/**`
- `com/qc/aeonis/dimension/AncardSovereignSpawnManager.kt`
- `com/qc/aeonis/client/entity/AncardEntityRenderers.kt`
- `com/qc/aeonis/client/entity/AncardMobGeoModel.kt`
- `com/qc/aeonis/client/block/**`
- `com/qc/aeonis/screen/**` (Fabric client)
- `com/qc/aeonis/mixin/client/TransformationCameraMixin.kt`

Consequences to be aware of: the Ancard dimension's custom mob renderers, its block
renderers, and the Fabric control/config screens are not present in the Fabric jar.
The NeoForge side compiles its equivalent screens without exclusion, so the two jars
are not feature-identical here.

The `client/dimension/**` exclusion was removed during this pass, because
`AeonisManagerClient` directly referenced those classes. Doing so required porting two
HUD files to the new API (see section 4) and fixing a genuine syntax error in
`AncardLightningEvents.kt`, which had an unbalanced brace from an earlier edit. The
remaining exclusions still need the same treatment.

## 3. Legacy datapack and worldgen content is excluded

Both loader `processResources` blocks exclude:

- `data/aeonis/worldgen/**`, `data/aeonis/dimension/**`, `data/aeonis/dimension_type/**`,
  `data/aeonis/tags/worldgen/**`
- `data/deeper_dark/**`
- Several `data/minecraft/tags/worldgen/biome` files

The comment in the build script says this content "currently fails registry loading
on 26.1". It has not been re-validated against 26.3. Re-enabling it requires a
registry-loading pass and is the largest remaining content-level task. Until then the
Ancard dimension and its worldgen are not shipped.

## 4. 26.3 API changes applied, and the risk that remains

These renames and relocations were applied and compile cleanly:

| Was | Now |
| --- | --- |
| `EntityType.CHICKEN` and similar constants | `EntityTypes.CHICKEN` (new holder class) |
| `Entity.hurtMarked` | `Entity.syncVelocity` |
| `Entity.setInvulnerable` | `Entity.setPermanentlyInvulnerable` |
| `Entity.invulnerableTime` (public field) | `invulnerableTime` is private; use `getInvulnerableTime()` / `setInvulnerableTime()` |
| `LivingEntity.knockback(double,double,double)` | `knockback(double,double,double,DamageSource,float[,boolean])` |
| `LivingEntity.swing(hand)` | `swing(hand, SwingAnimation, boolean)` |
| `Minecraft.screen` / `Minecraft.setScreen` | `Minecraft.gui.screen()` / `setScreenAndShow(...)` or `Gui.setScreen(...)` |
| `net.minecraft.client.gui.GuiGraphics` | `GuiGraphicsExtractor` for HUD elements |
| `HudRenderCallback` (Fabric API) | `HudElementRegistry` + `HudElement.extractRenderState` |
| `InputConstants.Type.KEYSYM` | `InputConstants.Type.KEYBOARD` |
| `org.lwjgl.glfw.GLFW.GLFW_KEY_*` | `InputConstants.KEY_*` (LWJGL is not on the mod classpath) |
| `net.minecraft.world.entity.monster.Slime` | `...monster.cubemob.Slime` |
| `net.minecraft.advancements.CriteriaTriggers` | `net.minecraft.advancements.triggers.CriteriaTriggers` |
| `EnderMan` | `Enderman` (note the lowercase `m`) |
| `ResourceArgument.getEntityType` | `ResourceArgument.getSummonableEntityType` |
| `SignText` mutable builder | `SignText(List, List, DyeColor, boolean)`; `SignBlockEntity.setText(SignText, SignTextSlot)` |

The real risk is mixins. Mixin targets are resolved by name at runtime, so a mixin
that targets a method or field that changed in 26.3 still compiles and then fails, or
silently does nothing, when the game loads. `:fabric:validateMixinReferences` only
checks that the declared mixin classes exist in the source set; it does not validate
their injection targets.

That gap is now covered by `scripts/check-mixin-targets.py`, which resolves every
`@Inject`/`@Redirect`/`@Accessor`/`@Shadow` selector against the deobfuscated 26.3 jar
with `javap`. It verifies that each target class exists, that each `method` selector
matches a real method (including inherited ones), that an `@Inject` handler carries the
target's parameters verbatim followed by its callback, and that handler static-ness
matches the target. It currently resolves 133 injection targets with no findings, and
it was validated by re-introducing a known-bad descriptor and confirming it is
reported.

Two mixin failures were real and are fixed:

| Mixin | Problem | Fix |
| --- | --- | --- |
| `MobRenderMixin` (both loaders) | `EntityRenderer#shouldRender` gained a trailing `float partialTick`; the handler still had the old signature, so the whole client died with `InvalidInjectionException` | handler now takes the `float` |
| `InGameOverlayRendererMixin` | `ScreenEffectRenderer#getViewBlockingState` no longer exists; the wall-overlay logic moved to `LevelExtractor#getViewBlockingState(LocalPlayer, Frustum)` | retargeted, with the new parameter list |

The static check cannot see injection *points* (`@At` selectors, ordinals, local
captures), so an `@Inject` whose method exists but whose `@At` target does not will
still fail only at runtime, when the target class loads. The client boot test covers
the classes that load before the main menu; entity, world and HUD mixins are still
only covered when a world is opened.

## 5. Transform system: done in this pass, and what remains

### Done

The `/transform` feature was rebuilt around a `com.qc.aeonis.transform` package:

- `MobVariants` is a registry of declarative variant descriptors. `AeonisMobVariants`
  registers every built-in variant; adding a mob is a new descriptor and no command
  changes. Variants are validated: a token that names a real variant for the wrong mob
  reports "not available for this mob", an unknown token reports "unknown", and a
  variant that fails reports "could not be applied". Nothing is silently ignored.
- Variants now actually work: all 16 sheep wool colours, `powered` creeper (real synced
  flag, not a fake glowing effect), `screaming` goat, cube sizes, `saddle`/`armor`,
  zombie/skeleton conversions via vanilla `Mob#convertTo`, axolotl and parrot colours,
  and sulfur-cube surfaces. `baby`/`adult` apply to every mob.
- Two real bugs fixed along the way: `MagmaCube` does not extend `Slime`, and every
  skeleton subtype extends `AbstractSkeleton` rather than `Skeleton`, so several
  variants never matched the mobs they were written for.
- The command takes a greedy variant list (was two fixed slots), tab-completes variants
  filtered to the chosen mob, and gained `/transform variants <entity>` and
  `/transform info`.
- Transform state is one record (`TransformSession` plus a `PlayerRestoreState`
  snapshot) rather than four loose maps shared with soul mode. Restoration is a single
  idempotent operation that always resets gamemode, visibility, collision, flight,
  camera and health.
- Teardown now runs on untransform, mob death, player death/respawn, disconnect and
  server shutdown. Disconnecting mid-morph used to leave the possessed mob saved into
  the world and the returning player stuck invisible; dying mid-morph used to drag the
  respawned player back to the abandoned mob.
- Entity validation creates the entity and rejects anything that is not a `Mob`, which
  closes the case where transforming into a non-mob left a player invisible forever.
- `/ai chaotic` no longer uses reflection (26.3 exposes `Mob#getGoalSelector`) and
  `/ai chaotic stop` removes exactly the goals it added; it previously removed the
  first three goals in priority order, deleting the mob's own AI, and its tracking set
  was never populated so the command always reported 0.
- `/aeonis reload` and server shutdown now unwind every session, rather than clearing
  bookkeeping and orphaning mobs. A player sitting in soul mode at shutdown used to be
  saved mid-spectator.

### Remaining

- Special attacks (Wither, Ender Dragon, Breeze, Blaze, Ghast, Chicken) are still
  per-mob branches in `ControlledSpecialMobAbilityMixin`. The variant registry is the
  natural template for turning those into data-driven attack definitions with cooldowns.
- `Wolf` and `Frog` variants use registry-backed `Holder<...Variant>` values rather than
  an int id, so they are not covered by the bridge-mixin pattern and remain unsupported.
- NeoForge needs the three bridge mixins ported (`CreeperPowerMixin`,
  `AxolotlVariantMixin`, `ParrotVariantMixin`) or those variants will silently report as
  unavailable on that loader.
- Soul mode and the `AeonisPossession` body-swap remain two separate systems. They were
  decoupled so they cannot corrupt each other's restore state, but a future pass should
  merge them into one possession service.
- Nothing here has been exercised in a running game. Entity interactions in particular
  need an in-game pass: verifying a conversion variant keeps the session bound to the
  replacement entity, and that `powered`/`axolotl`/`parrot` bridges actually apply.

### Loader parity verification

The parity requirement is still not met. The Fabric jar now boots to the main menu
with the mod loaded, which proves the startup path and the core mixin set, but no
command in the list (`/transform`, `/prank`, `/ability`, `/ai`, `/event`, `/aeonis`,
`/comp`) has been executed on either loader, and the NeoForge jar still cannot load at
all (section 1). Given the remaining mixin risk above and the excluded source sets,
assume in-game parity is unverified rather than achieved. This is the main reason not
to publish 5.0.0 as a finished release.

### Deeper refactors worth considering

- `AeonisCommands.kt` is roughly 2,800 lines and mixes command declaration, gameplay
  logic, and entity spawning. It should be split per command tree.
- The LLM provider layer duplicates HTTP and error handling across
  `OpenAiProvider`, `OpenRouterProvider`, and `GeminiProvider`. A shared client with
  consistent retry and timeout behaviour would remove that duplication.
- Transform logic, special attacks, and the companion/hunter bot state machines
  should be extracted from command classes into dedicated services so both loaders
  and the AI assistant share one implementation.

## 6. Repository state caveats

These are pre-existing conditions, not introduced by the 26.3 port, but they will
confuse anyone who picks this up next.

- **The multi-loader restructure is now committed.** The `common/`, `fabric/` and
  `neoforge/` source trees, the transform package and the two new scripts under
  `scripts/` landed in the same commit as this file, so a fresh clone builds. Before
  that commit the repository could not build, because the source trees were untracked.
- **The remote was renamed.** `origin` now points at
  `https://github.com/TheAevoraLabs/aeonis-cm`. The old `TeamQC-MC/aeonis-cm` URL
  redirects there, so previously cloned checkouts still fetch successfully.
- **`temp/` deletions are staged in the working tree.** 176 tracked files under
  `temp/` are deleted in the working tree but the deletions are not committed. Left
  as found.
- **The old `src/` tree is recoverable** from `origin/dev` if any of the restructure
  turned out to be wrong.

## 7. Decisions to make

1. **Package namespace.** The code still lives under `com.qc.aeonis`. This encodes
   the old "QC" branding but renaming it is not free: the mod ID stays `aeonis`, so
   world saves are unaffected, but every mixin config entry, access transformer,
   reflective reference, and the `kotlinforforge` entrypoint class name depend on it.
   Renaming is safe for existing worlds (registries use the `aeonis` mod ID, not the
   package) but is a large mechanical change that should be done deliberately rather
   than as part of a version bump. Left unchanged for this pass.
2. **Relicensing consent.** The project moved from MIT to GPL-3.0-or-later. Changing
   a license requires the agreement of all copyright holders. The MIT `LICENSE` this
   replaces named QuantumCreeper, and the repository records two human contributors.
   Confirm that every contributor to the pre-existing code consents to the relicence
   before publishing under GPLv3.
3. **Project domain.** `fabric.mod.json` and `neoforge.mods.toml` still advertise
   `https://aeonis.prenxy.app` as the homepage, and the in-game config screens use the
   same text. This is the project site rather than old company branding, so it was
   left alone, but confirm the domain mapping is still intended for Aevora Labs.
4. **Quilt.** `fabric/src/main/resources/quilt.mod.json` was removed. It declared
   `intermediate_mappings: net.fabricmc:intermediary`, which cannot be correct for a
   build that uses `noIntermediateMappings()`, and its Minecraft range still said
   `>=26.1 <26.2`. Quilt is not a stated target. Re-add only with a valid manifest.

## 8. Versions to re-check before release

All of these move quickly and at least one is expected to change.

- **NeoForge 26.3.0.6-beta** - no non-beta or RC exists for 26.3 yet. Check for a
  promoted release before shipping.
- **KotlinForForge** - the deciding dependency for the NeoForge jar. Check for a
  build that declares 26.3 support.
- **Fabric API 0.161.0+26.3** - 0.160.5+26.3 was the stated minimum; 0.161.0+26.3 was
  current at port time.
- **Fabric Loom 1.17-SNAPSHOT** - a moving snapshot. Consider pinning to a released
  1.17.x build once one is validated.
- **Mod Menu 21.0.0-beta.1** - beta, and optional.
- **Gradle 9.5.1** - pinned in the wrapper for reproducibility.

## 9. Automated run testing

Two scripts under `scripts/` turn the parts of this document that used to read "not
verified" into something reproducible. Both are plain `python3` with no third-party
dependencies.

### `scripts/mc-smoke-test.py` - does the client actually boot?

Builds the Fabric jar, copies it into the PolyMC instance's `mods/` folder, and
launches Minecraft using that instance's own launcher metadata (`meta/`, `libraries/`,
`assets/`). No launcher, no account and no network access are needed: the client runs
on a local dev profile. The run passes when the client reaches the main menu and fails
on a crash report, a mixin apply error or an entrypoint failure. The client is killed
as soon as a verdict is reached, so a passing run takes well under a minute.

```
python3 scripts/mc-smoke-test.py                  # build, sync, launch, check
python3 scripts/mc-smoke-test.py --no-build       # reuse the existing jar
python3 scripts/mc-smoke-test.py --dry-run        # print the launch command
python3 scripts/mc-smoke-test.py --instance "26.3"   # any PolyMC instance
```

The defaults point at the `26.3 Fabric` instance through
`~/.var/app/org.polymc.PolyMC/data/PolyMC`; override with `POLYMC_HOME` or
`--polymc-home`. Exit status is 0 only on a green run, so it can gate a release.

What it does **not** cover: anything past the main menu. Mixins that apply to entity,
world or HUD classes are not exercised until a world is loaded, so a main-menu pass is
a floor, not a parity check.

### `scripts/check-mixin-targets.py` - are the mixins still pointing at real code?

Resolves every mixin injection in the repository against the deobfuscated Minecraft
jar from the Loom cache (see section 4). This is the check `validateMixinReferences`
never performed, and it is the fastest way to find the class of bug that broke
`MobRenderMixin`.

```
python3 scripts/check-mixin-targets.py
python3 scripts/check-mixin-targets.py --module fabric
python3 scripts/check-mixin-targets.py --jar /path/to/minecraft-merged-deobf-26.3.jar
```

It requires a deobfuscated jar, so run `./gradlew :fabric:build` at least once first.
It does not understand dynamic selectors (selectors built from constants), and it does
not see `@At` injection points, so it is a complement to the boot test rather than a
replacement for it.

### Known gap found by the boot test

The client logs `Missing model for variant: 'Block{aeonis:permanent_flame}'`. The
`permanent_flame` block is registered in `AeonisBlocks`, but the jar ships no
`blockstates/`, no block models and no item model for it - in fact
`common/src/main/resources/assets/aeonis` has nothing but `textures/`. Every block and
item the mod registers is therefore missing its model. This needs a model pass (or
generated models), not just a one-line fix.

Note that the 9.4.1 distribution listed in some notes is not used; the wrapper was
moved to 9.5.1, which is what Fabric's own 26.3 example mod uses.
