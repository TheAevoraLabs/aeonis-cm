# Fabric for Minecraft 26.1
**Date:** Mar 14, 2026

## Overview
A new version of Minecraft (26.1) is coming with significant changes affecting all mod makers.

## Major Modder-Facing Changes

### Fabric Changes
- **Loom/Gradle:** Use Loom 1.15 and Gradle 9.4.0.
- **Java:** Requires Java 25 minimum for the Gradle JVM.
- **IDE:** IntelliJ IDEA 2025.3 or above required for mixins.
- **Deprecations/Removals:**
    - `fabric` mod ID removed (use `fabric-api`).
    - `fabric-convention-tags-v1` and `fabric-loot-api-v2` removed.
    - `HudRenderCallback` removed in favor of `HudElementRegistry`.

### Unobfuscated Loom
- 26.1 is the first version not obfuscated.
- Switch to `net.fabricmc.fabric-loom` plugin (no remapping).
- Use `implementation`/`compileOnly` instead of `modImplementation`/`modCompileOnly`.
- Use `jar` instead of `remapJar`.

### Fabric API Renames (Official Mappings)
- API names updated to match official Mojang names.
- Example: `ItemGroupEvents` -> `CreativeModeTabEvents`.

### New Fabric API Features
- **Dimension Events:** `DimensionEvents.MODIFY_ATTRIBUTES` for dimension-wide attribute changes.
- **Block/Item Events:** 
    - `BlockEvents.USE_ITEM_ON`
    - `BlockEvents.USE_WITHOUT_ITEM`
    - `ItemEvents.USE_ON`
    - `ItemEvents.USE`
- **Block Color Registry:** `BlockColorRegistry.register` replaces `ColorProviderRegistry.BLOCK`.

### Minecraft 26.1 Core Changes
- **ItemStackTemplate:** `ItemStack` can no longer be created until a world is loaded. `ItemStackTemplate` (immutable) is used instead.
- **Recipe Serializers:** Simplified to `MapCodec` and `StreamCodec`.
- **Render Layers:** `ChunkSectionLayer` replaces `RenderType/RenderLayer`. Automatic layer assignment based on sprite properties.
- **Villager Trading:** Data-driven (`data/<namespace>/villager_trade`). `TradeOfferHelper` removed.
- **Fluid Rendering:** `FluidModel` replaces `FluidRenderHandler`.

## Tooling
- **mcsrc.dev:** Online tool to view decompiled Minecraft source.
