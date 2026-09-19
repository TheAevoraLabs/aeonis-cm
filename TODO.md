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
- The `transform` system improvements (variant support, data-driven special attacks,
  hardened untransform) were not implemented.
- Loader parity was not verified by running the game; only by compiling.
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
their injection targets. **Every mixin in `fabric/src` and `neoforge/src` should be
audited against the 26.3 mappings before release.** This was not done.

No build has been launched, so nothing in this port has been verified at runtime.

## 5. Requested work that was not started

### Transform system hardening (highest-value remaining work)

The `/transform` and mob possession system was ported but not improved. Outstanding:

- `[variant...]` is still effectively non-functional. The command signature accepts
  variants, but only a small hardcoded set (slime size, a few mob-specific cases) is
  honoured. Variants should map onto a validated, documented per-entity schema, and
  unsupported variants should produce a clear error instead of being ignored.
- Untransform and cleanup still rely on several independent state maps
  (`transformedEntities`, camera state, inventory, gamemode) that can desynchronise.
  Past changelogs already flag players getting stuck in a broken post-transform
  state. This needs a single authoritative per-player transform record with an
  idempotent teardown that restores inventory, gamemode, camera, and collision, and
  that also runs on player death, disconnect, and dimension change.
- The special-attack list (Wither, Ender Dragon, Breeze, Blaze, Ghast, Chicken, and
  others) is still implemented as per-mob branching in `ControlledSpecialMobAbilityMixin`
  and `AeonisCommands`. It should be data-driven, for example a registry of
  attack definitions with cooldowns, so new mobs do not require new branches.
- Entity ID validation produces generic errors. It should distinguish unknown IDs,
  valid but non-possessable entities, and entities that exist but cannot be
  possessed in the current dimension or gamemode.

### Loader parity verification

The parity requirement was not met in practice. Both jars compile, but no command in
the list (`/transform`, `/prank`, `/ability`, `/ai`, `/event`, `/aeonis`, `/comp`) has
been executed on either loader. Given the mixin risk above and the excluded source
sets, assume parity is unverified rather than achieved. This is the main reason not
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

- **The multi-loader restructure was never committed.** Local `dev` sits one commit
  ahead of `origin/dev` in a commit that deletes the entire old `src/` tree and
  rewrites the build files into a multi-project layout - but the `common/`, `fabric/`,
  and `neoforge/` source trees it depends on were left untracked. As committed,
  the repository cannot build. All of the source changes described in this file are
  currently uncommitted working-tree changes.
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

Note that the 9.4.1 distribution listed in some notes is not used; the wrapper was
moved to 9.5.1, which is what Fabric's own 26.3 example mod uses.
