package com.qc.aeonis.transform

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.animal.axolotl.Axolotl
import net.minecraft.world.entity.animal.equine.Horse
import net.minecraft.world.entity.animal.goat.Goat
import net.minecraft.world.entity.animal.parrot.Parrot
import net.minecraft.world.entity.animal.pig.Pig
import net.minecraft.world.entity.animal.sheep.Sheep
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Every variant the transform system understands, declared in one place.
 *
 * The previous implementation was a chain of `if (entity is X)` blocks inside the command
 * class: adding a mob meant editing command code, unknown variants were silently ignored, and
 * several whole families (sheep colours, axolotl, parrot, charged creeper) were commented out
 * or faked. Registering descriptors instead means a new mob is a new [MobVariant] and nothing
 * else.
 *
 * Types follow the real 26.3 hierarchy rather than the intuitive one:
 *  - skeletons all extend [AbstractSkeleton]; Stray is *not* a [net.minecraft.world.entity.monster.skeleton.Skeleton]
 *  - Slime and [net.minecraft.world.entity.monster.cubemob.MagmaCube] both extend [AbstractCubeMob],
 *    so the old `entity is Slime` test silently skipped magma cubes.
 */
object AeonisMobVariants {

    private var registered = false

    fun registerDefaults() {
        if (registered) return

        // Flag is set only after a complete pass: registration is idempotent (ids are replaced),
        // so a partial failure can safely be retried rather than leaving variants missing.
        registerUniversal()
        registerCreeper()
        registerGoat()
        registerSheep()
        registerCubes()
        registerMounts()
        registerZombie()
        registerSkeleton()
        registerAxolotl()
        registerParrot()
        registerSulfurCube()

        registered = true
    }

    // ---------------------------------------------------------------- universal

    private fun registerUniversal() {
        MobVariants.register(
            MobVariant(
                id = "baby",
                summary = "Baby form",
                appliesTo = { true },
                apply = { ctx ->
                    ctx.mob.isBaby = true
                    true
                }
            )
        )
        MobVariants.register(
            MobVariant(
                id = "adult",
                aliases = setOf("grown"),
                summary = "Adult form",
                appliesTo = { true },
                apply = { ctx ->
                    ctx.mob.isBaby = false
                    true
                }
            )
        )
    }

    // ------------------------------------------------------------------ creeper

    private fun registerCreeper() {
        MobVariants.register(
            MobVariant(
                id = "powered",
                aliases = setOf("charged"),
                summary = "Charged creeper (larger explosion)",
                appliesTo = { it is Creeper },
                apply = { ctx ->
                    // The old code could not reach DATA_IS_POWERED and faked this with a
                    // permanent GLOWING effect. A mixin-backed bridge sets the real flag, and
                    // reports unavailable when the loader does not provide one.
                    val bridge = ctx.mob as? CreeperPowerBridge
                    if (bridge == null) {
                        false
                    } else {
                        bridge.aeonisApplyPowered(true)
                        true
                    }
                }
            )
        )
    }

    // --------------------------------------------------------------------- goat

    private fun registerGoat() {
        MobVariants.register(
            MobVariant(
                id = "screaming",
                aliases = setOf("screamer"),
                summary = "Screaming goat",
                appliesTo = { it is Goat },
                apply = { ctx ->
                    val goat = ctx.mob as? Goat
                    if (goat == null) {
                        false
                    } else {
                        goat.setScreamingGoat(true)
                        true
                    }
                }
            )
        )
    }

    // -------------------------------------------------------------------- sheep

    private fun registerSheep() {
        for (color in DyeColor.entries) {
            MobVariants.register(
                MobVariant(
                    id = color.serializedName,
                    summary = "Sheep wool colour: ${color.serializedName}",
                    appliesTo = { it is Sheep },
                    apply = { ctx ->
                        val sheep = ctx.mob as? Sheep
                        if (sheep == null) {
                            false
                        } else {
                            sheep.color = color
                            true
                        }
                    }
                )
            )
        }
        MobVariants.register(
            MobVariant(
                id = "sheared",
                summary = "Sheared sheep",
                appliesTo = { it is Sheep },
                apply = { ctx ->
                    val sheep = ctx.mob as? Sheep
                    if (sheep == null) {
                        false
                    } else {
                        sheep.setSheared(true)
                        true
                    }
                }
            )
        )
    }

    // -------------------------------------------------------------- slime/magma

    private fun registerCubes() {
        fun sizeVariant(id: String, aliases: Set<String>, size: Int, label: String) {
            MobVariants.register(
                MobVariant(
                    id = id,
                    aliases = aliases,
                    summary = label,
                    appliesTo = { it is AbstractCubeMob },
                    apply = { ctx ->
                        val cube = ctx.mob as? AbstractCubeMob
                        if (cube == null) {
                            false
                        } else {
                            cube.setSize(size.coerceIn(AbstractCubeMob.MIN_SIZE, AbstractCubeMob.MAX_SIZE), true)
                            true
                        }
                    }
                )
            )
        }
        sizeVariant("tiny", setOf("small", "smol"), 1, "Smallest cube")
        sizeVariant("medium", emptySet(), 2, "Medium cube")
        sizeVariant("big", setOf("large", "huge"), 4, "Largest natural cube")
        sizeVariant("giant", emptySet(), 8, "Oversized cube")
    }

    // ------------------------------------------------------------------- mounts

    private fun registerMounts() {
        MobVariants.register(
            MobVariant(
                id = "saddle",
                aliases = setOf("saddled"),
                summary = "Saddled and rideable",
                appliesTo = { it is Pig || it is Horse },
                apply = { ctx ->
                    ctx.mob.equipItemIfPossible(ctx.level, ItemStack(Items.SADDLE)).isEmpty
                }
            )
        )
        MobVariants.register(
            MobVariant(
                id = "armor",
                aliases = setOf("barding"),
                summary = "Diamond horse armour",
                appliesTo = { it is Horse },
                apply = { ctx ->
                    ctx.mob.equipItemIfPossible(ctx.level, ItemStack(Items.DIAMOND_HORSE_ARMOR)).isEmpty
                }
            )
        )
    }

    // ------------------------------------------------------------------- zombie

    private fun registerZombie() {
        MobVariants.register(
            MobVariant(
                id = "drowned",
                summary = "Convert to a drowned",
                appliesTo = { it is Zombie },
                apply = { ctx -> ctx.convertTo(EntityTypes.DROWNED) != null }
            )
        )
        MobVariants.register(
            MobVariant(
                id = "husk",
                summary = "Convert to a husk",
                appliesTo = { it is Zombie },
                apply = { ctx -> ctx.convertTo(EntityTypes.HUSK) != null }
            )
        )
        MobVariants.register(
            MobVariant(
                id = "zombie_villager",
                aliases = setOf("zombie-villager"),
                summary = "Convert to a zombie villager",
                appliesTo = { it is Zombie },
                apply = { ctx -> ctx.convertTo(EntityTypes.ZOMBIE_VILLAGER) != null }
            )
        )
    }

    // ----------------------------------------------------------------- skeleton

    private fun registerSkeleton() {
        skeletonConversion("wither_skeleton", setOf("wither"), "Convert to a wither skeleton", EntityTypes.WITHER_SKELETON)
        skeletonConversion("stray", emptySet(), "Convert to a stray", EntityTypes.STRAY)
        skeletonConversion("bogged", emptySet(), "Convert to a bogged", EntityTypes.BOGGED)
        skeletonConversion("skeleton", setOf("plain"), "Convert to a plain skeleton", EntityTypes.SKELETON)
    }

    private fun <T : Mob> skeletonConversion(
        id: String,
        aliases: Set<String>,
        label: String,
        type: EntityType<T>
    ) {
        MobVariants.register(
            MobVariant(
                id = id,
                aliases = aliases,
                summary = label,
                appliesTo = { it is AbstractSkeleton },
                apply = { ctx -> ctx.convertTo(type) != null }
            )
        )
    }

    // ------------------------------------------------------------------ axolotl

    private fun registerAxolotl() {
        for (variant in Axolotl.Variant.entries) {
            MobVariants.register(
                MobVariant(
                    id = variant.serializedName,
                    summary = "Axolotl colour: ${variant.serializedName}",
                    appliesTo = { it is Axolotl },
                    apply = { ctx ->
                        val bridge = ctx.mob as? AxolotlVariantBridge
                        if (bridge == null) {
                            false
                        } else {
                            bridge.aeonisApplyVariantId(variant.id)
                            true
                        }
                    }
                )
            )
        }
    }

    // ------------------------------------------------------------- sulfur cube

    /**
     * Sulfur cubes pick their surface material from a body-slot item. The identifiers are
     * resolved through the item registry rather than referenced as constants, because the
     * mapping is data-ish and an unresolvable entry must report as unavailable instead of
     * failing to compile.
     */
    private fun registerSulfurCube() {
        val surfaceItems = mapOf(
            "regular" to "minecraft:sulfur",
            "bouncy" to "minecraft:oak_planks",
            "slow_flat" to "minecraft:iron_block",
            "fast_flat" to "minecraft:wet_sponge",
            "light" to "minecraft:white_wool",
            "fast_sliding" to "minecraft:packed_ice",
            "slow_sliding" to "minecraft:red_mushroom_block",
            "sticky" to "minecraft:honeycomb_block",
            "high_resistance" to "minecraft:soul_sand"
        )
        for ((id, itemId) in surfaceItems) {
            MobVariants.register(
                MobVariant(
                    id = id,
                    aliases = setOf(id.replace('_', '-')),
                    summary = "Sulfur cube surface: $id",
                    appliesTo = { mob -> MobKinds.isSulfurCube(mob) },
                    apply = { ctx ->
                        val item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId))
                        if (item == Items.AIR) {
                            false
                        } else {
                            ctx.mob.setItemSlot(EquipmentSlot.BODY, ItemStack(item))
                            true
                        }
                    }
                )
            )
        }
    }

    // -------------------------------------------------------------------- parrot

    private fun registerParrot() {
        for (variant in Parrot.Variant.entries) {
            MobVariants.register(
                MobVariant(
                    id = variant.serializedName,
                    summary = "Parrot colour: ${variant.serializedName}",
                    appliesTo = { it is Parrot },
                    apply = { ctx ->
                        val bridge = ctx.mob as? ParrotVariantBridge
                        if (bridge == null) {
                            false
                        } else {
                            bridge.aeonisApplyVariantId(variant.id)
                            true
                        }
                    }
                )
            )
        }
    }
}
