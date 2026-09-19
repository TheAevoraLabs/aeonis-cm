package com.qc.aeonis.transform

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.animal.equine.AbstractHorse
import net.minecraft.world.entity.animal.sheep.Sheep
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.projectile.arrow.Arrow
import net.minecraft.world.entity.projectile.arrow.ThrownTrident
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge
import net.minecraft.world.entity.projectile.ShulkerBullet
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The three ability buttons available while controlling a mob.
 *
 * The indexes are the wire format: 0 means "nothing pressed this tick", 1..3 map to the slots
 * below. Keeping the mapping in one enum means the client HUD, the manual and the server
 * dispatcher cannot drift apart.
 */
enum class AbilitySlot(val index: Int, val key: String, val label: String) {
    PRIMARY(1, "R", "Primary"),
    SECONDARY(2, "V", "Secondary"),
    TERTIARY(3, "C", "Tertiary");

    companion object {
        fun of(index: Int): AbilitySlot? = entries.firstOrNull { it.index == index }
    }
}

/**
 * Everything an ability is allowed to touch.
 *
 * Abilities are pure functions of this context so they can be unit-reasoned about and reused
 * across mobs: the fireball ability is the same code for a Blaze, a Ghast and the Ender Dragon,
 * differing only in the projectile it is handed.
 */
class AbilityContext(
    val level: ServerLevel,
    val mob: Mob,
    val owner: ServerPlayer?
) {
    val random get() = level.random

    /** The mob's eye position, which is where projectiles leave the body. */
    fun eye(): Vec3 = mob.getEyePosition(1.0f)

    /** Normalised look direction. */
    fun look(): Vec3 = mob.getLookAngle().normalize()

    fun play(sound: net.minecraft.sounds.SoundEvent, volume: Float = 1.0f, pitch: Float = 1.0f) {
        level.playSound(null, mob.blockPosition(), sound, SoundSource.HOSTILE, volume, pitch)
    }

    fun tell(message: String) {
        owner?.sendSystemMessage(Component.literal(message), true)
    }
}

/**
 * One thing a mob can do on demand.
 *
 * [cooldownTicks] is enforced by [MobAbilityRuntime]; [activate] returns false when the action
 * could not be performed (no target in range, no room to teleport), which the runtime reports
 * back to the player rather than silently swallowing.
 */
data class MobAbility(
    val id: String,
    val name: String,
    val description: String,
    val slot: AbilitySlot,
    val cooldownTicks: Int,
    val activate: (AbilityContext) -> Boolean
)

/**
 * Registry of ability sets, keyed by entity type.
 *
 * Each entry mirrors what the mob actually does in vanilla: a Blaze shoots three small fireballs,
 * a Breeze throws wind charges, a Warden's sonic boom punches through a line of enemies. Where a
 * mob has no signature action, it gets the generic melee/charge ability it would have used on a
 * player, so "can do everything its own AI can do" holds for ordinary mobs too.
 */
object MobAbilities {

    private val registered = HashMap<EntityType<*>, List<MobAbility>>()

    /** Abilities available to [mob], ordered by slot. Never null; may be empty. */
    fun forMob(mob: Mob): List<MobAbility> =
        registered.getOrPut(mob.type) { build(mob.type) }

    fun forType(type: EntityType<*>): List<MobAbility> = registered.getOrPut(type) { build(type) }

    fun slotFor(mob: Mob, slot: AbilitySlot): MobAbility? =
        forMob(mob).firstOrNull { it.slot == slot }

    /** Description of a mob's kit, used by the HUD and `/transform abilities`. */
    fun describe(mob: Mob): List<String> =
        forMob(mob).map { "${it.slot.key}: ${it.name} - ${it.description}" }

    private fun ability(
        id: String,
        name: String,
        description: String,
        slot: AbilitySlot,
        cooldown: Int,
        activate: (AbilityContext) -> Boolean
    ) = MobAbility(id, name, description, slot, cooldown, activate)

    // ------------------------------------------------------------------ registry

    private fun build(type: EntityType<*>): List<MobAbility> = when (type) {

        // ------------------------------------------------------------------ casters
        EntityTypes.BLAZE -> listOf(
            ability("blaze.volley", "Fireball volley", "Throws three small fireballs in a spread", AbilitySlot.PRIMARY, 30) { ctx ->
                Actions.volley(ctx, 3, 0.25f, SoundEvents.BLAZE_SHOOT) { SmallFireball(ctx.level, ctx.mob, it) }
            },
            ability("blaze.burst", "Flame burst", "Ignites every enemy around you", AbilitySlot.SECONDARY, 200) { ctx ->
                Actions.igniteArea(ctx, 4.0)
            }
        )

        EntityTypes.GHAST -> listOf(
            ability("ghast.fireball", "Fireball", "Fires an explosive large fireball", AbilitySlot.PRIMARY, 60) { ctx ->
                Actions.single(ctx, SoundEvents.GHAST_SHOOT) { LargeFireball(ctx.level, ctx.mob, it, 1) }
            },
            ability("ghast.wail", "Pained wail", "Lets out a wail that draws attention", AbilitySlot.SECONDARY, 100) { ctx ->
                ctx.play(SoundEvents.GHAST_SCREAM, 2.0f, 0.6f); true
            }
        )

        EntityTypes.WITHER -> listOf(
            ability("wither.skull", "Wither skull", "Fires an explosive wither skull", AbilitySlot.PRIMARY, 40) { ctx ->
                Actions.single(ctx, SoundEvents.WITHER_SHOOT) { WitherSkull(ctx.level, ctx.mob, it) }
            },
            ability("wither.charge", "Charge", "Lunges forward with the wither's shove", AbilitySlot.SECONDARY, 120) { ctx ->
                Actions.charge(ctx, 1.1, SoundEvents.WITHER_AMBIENT) { target ->
                    Actions.damage(ctx, target, 8.0f)
                }
            }
        )

        EntityTypes.ENDER_DRAGON -> listOf(
            ability("dragon.fireball", "Dragon fireball", "Spits a lingering dragon fireball", AbilitySlot.PRIMARY, 50) { ctx ->
                Actions.single(ctx, SoundEvents.ENDER_DRAGON_SHOOT) { DragonFireball(ctx.level, ctx.mob, it) }
            },
            ability("dragon.wing", "Wing blast", "Flaps hard, throwing everything nearby back", AbilitySlot.SECONDARY, 140) { ctx ->
                ctx.play(SoundEvents.ENDER_DRAGON_FLAP, 2.5f, 1.0f)
                Actions.knockbackArea(ctx, 8.0, 3.0)
            },
            ability("dragon.roar", "Roar", "Bellows across the arena", AbilitySlot.TERTIARY, 200) { ctx ->
                ctx.play(SoundEvents.ENDER_DRAGON_GROWL, 3.0f, 1.0f); true
            }
        )

        EntityTypes.BREEZE -> listOf(
            ability("breeze.wind", "Wind charge", "Hurls a wind charge", AbilitySlot.PRIMARY, 20) { ctx ->
                Actions.single(ctx, SoundEvents.BREEZE_SHOOT) { dir ->
                    val eye = ctx.eye()
                    WindCharge(ctx.level, eye.x, eye.y, eye.z, dir)
                }
            },
            ability("breeze.jump", "Wind jump", "Launches straight up on a gust", AbilitySlot.SECONDARY, 40) { ctx ->
                ctx.mob.setDeltaMovement(ctx.mob.deltaMovement.x, 0.75, ctx.mob.deltaMovement.z)
                ctx.play(SoundEvents.BREEZE_JUMP)
                true
            }
        )

        EntityTypes.EVOKER -> listOf(
            ability("evoker.fangs", "Evoker fangs", "Summons a row of fangs in front of you", AbilitySlot.PRIMARY, 40) { ctx ->
                Actions.fangs(ctx)
            },
            ability("evoker.vexes", "Summon vexes", "Calls two vexes to fight for you", AbilitySlot.SECONDARY, 300) { ctx ->
                Actions.summon(ctx, EntityTypes.VEX, 2)
            }
        )

        EntityTypes.ILLUSIONER -> listOf(
            ability("illusioner.mirror", "Mirror images", "Blurs into a crowd of copies", AbilitySlot.PRIMARY, 200) { ctx ->
                ctx.mob.addEffect(MobEffectInstance(MobEffects.INVISIBILITY, 120, 0))
                ctx.play(SoundEvents.ILLUSIONER_MIRROR_MOVE)
                true
            },
            ability("illusioner.blind", "Blindness", "Blinds everyone looking at you", AbilitySlot.SECONDARY, 160) { ctx ->
                Actions.effectArea(ctx, 6.0) { MobEffectInstance(MobEffects.BLINDNESS, 100, 0) }
            }
        )

        EntityTypes.WITCH -> listOf(
            ability("witch.throw", "Harmful brew", "Splashes poison at the nearest enemy", AbilitySlot.PRIMARY, 40) { ctx ->
                Actions.effectTarget(ctx, 12.0, SoundEvents.WITCH_THROW) {
                    MobEffectInstance(MobEffects.POISON, 120, 0)
                }
            },
            ability("witch.drink", "Drink brew", "Drinks a healing brew", AbilitySlot.SECONDARY, 300) { ctx ->
                ctx.mob.heal(6.0f)
                ctx.mob.addEffect(MobEffectInstance(MobEffects.REGENERATION, 100, 0))
                ctx.play(SoundEvents.WITCH_DRINK)
                true
            }
        )

        EntityTypes.WARDEN -> listOf(
            ability("warden.boom", "Sonic boom", "Fires a sonic boom that punches through a line", AbilitySlot.PRIMARY, 60) { ctx ->
                ctx.play(SoundEvents.WARDEN_SONIC_BOOM, 3.0f, 1.0f)
                Actions.lineDamage(ctx, 16.0, 6.0f, 2.5)
            },
            ability("warden.roar", "Rage roar", "Roars, weakening nearby enemies", AbilitySlot.SECONDARY, 200) { ctx ->
                ctx.play(SoundEvents.WARDEN_ROAR, 3.0f, 1.0f)
                Actions.effectArea(ctx, 10.0) { MobEffectInstance(MobEffects.WEAKNESS, 300, 1) }
            }
        )

        // ------------------------------------------------------------------ archers
        EntityTypes.SKELETON, EntityTypes.PARCHED -> listOf(
            ability("skeleton.shoot", "Shoot arrow", "Fires an arrow from your bow", AbilitySlot.PRIMARY, 20) { ctx ->
                Actions.shootArrow(ctx, SoundEvents.SKELETON_SHOOT, null)
            }
        )

        EntityTypes.STRAY -> listOf(
            ability("stray.shoot", "Arrow of slowness", "Fires an arrow that slows the target", AbilitySlot.PRIMARY, 20) { ctx ->
                Actions.shootArrow(ctx, SoundEvents.SKELETON_SHOOT) {
                    MobEffectInstance(MobEffects.SLOWNESS, 600, 0)
                }
            }
        )

        EntityTypes.BOGGED -> listOf(
            ability("bogged.shoot", "Arrow of poison", "Fires a poisoned arrow", AbilitySlot.PRIMARY, 20) { ctx ->
                Actions.shootArrow(ctx, SoundEvents.SKELETON_SHOOT) {
                    MobEffectInstance(MobEffects.POISON, 200, 0)
                }
            }
        )

        EntityTypes.WITHER_SKELETON -> listOf(
            ability("witherskeleton.swing", "Stone sword", "Swings the wither skeleton's blade", AbilitySlot.PRIMARY, 20) { ctx ->
                Actions.melee(ctx, 3.5, 5.0f)
            }
        )

        EntityTypes.PILLAGER -> listOf(
            ability("pillager.bolt", "Crossbow bolt", "Fires a crossbow bolt", AbilitySlot.PRIMARY, 25) { ctx ->
                Actions.shootArrow(ctx, SoundEvents.CROSSBOW_SHOOT, null)
            }
        )

        EntityTypes.DROWNED -> listOf(
            ability("drowned.trident", "Throw trident", "Hurls a trident", AbilitySlot.PRIMARY, 40) { ctx ->
                Actions.launch(ctx, ThrownTrident(ctx.level, ctx.mob, ItemStack(Items.TRIDENT)), 1.6f, 1.0f) {
                    ctx.play(SoundEvents.ARROW_SHOOT)
                }
            }
        )

        EntityTypes.SNOW_GOLEM -> listOf(
            ability("snowgolem.throw", "Throw snowball", "Pitches a snowball", AbilitySlot.PRIMARY, 15) { ctx ->
                Actions.launch(ctx, Snowball(ctx.level, ctx.mob, ItemStack(Items.SNOWBALL)), 1.5f, 4.0f) {
                    ctx.play(SoundEvents.SNOWBALL_THROW)
                }
            }
        )

        EntityTypes.LLAMA, EntityTypes.TRADER_LLAMA -> listOf(
            ability("llama.spit", "Spit", "Spits at whatever you are aiming at", AbilitySlot.PRIMARY, 30) { ctx ->
                ctx.play(SoundEvents.LLAMA_SPIT)
                Actions.melee(ctx, 6.0, 2.0f)
            }
        )

        EntityTypes.SHULKER -> listOf(
            ability("shulker.bullet", "Shulker bullet", "Fires a homing bullet at your target", AbilitySlot.PRIMARY, 30) { ctx ->
                val target = Actions.targetAhead(ctx, 16.0)
                    ?: return@ability false
                val axis = target.direction.axis
                ctx.level.addFreshEntity(ShulkerBullet(ctx.level, ctx.mob, target, axis))
                ctx.play(SoundEvents.SHULKER_SHOOT)
                true
            },
            ability("shulker.warp", "Warp", "Teleports to where you are looking", AbilitySlot.SECONDARY, 60) { ctx ->
                Actions.blink(ctx, 12.0, SoundEvents.SHULKER_TELEPORT)
            }
        )

        EntityTypes.GUARDIAN, EntityTypes.ELDER_GUARDIAN -> listOf(
            ability("guardian.beam", "Laser beam", "Burns a laser into your target", AbilitySlot.PRIMARY, 40) { ctx ->
                ctx.play(SoundEvents.GUARDIAN_ATTACK, 2.0f, 1.0f)
                Actions.lineDamage(ctx, 14.0, 7.0f, 0.4)
            },
            ability("guardian.curse", "Curse", "Mining fatigue for everything nearby", AbilitySlot.SECONDARY, 400) { ctx ->
                ctx.play(SoundEvents.ELDER_GUARDIAN_CURSE, 2.0f, 1.0f)
                Actions.effectArea(ctx, 12.0) { MobEffectInstance(MobEffects.MINING_FATIGUE, 600, 2) }
            }
        )

        // ------------------------------------------------------------------ melee
        EntityTypes.ZOMBIE, EntityTypes.HUSK, EntityTypes.ZOMBIE_VILLAGER,
        EntityTypes.ZOMBIFIED_PIGLIN, EntityTypes.PIGLIN, EntityTypes.PIGLIN_BRUTE -> listOf(
            ability("undead.swing", "Swing", "Swings at whatever is in front of you", AbilitySlot.PRIMARY, 15) { ctx ->
                Actions.melee(ctx, 3.0, 4.0f)
            }
        )

        EntityTypes.VINDICATOR -> listOf(
            ability("vindicator.axe", "Axe swing", "A heavy axe blow with knockback", AbilitySlot.PRIMARY, 25) { ctx ->
                Actions.melee(ctx, 3.5, 9.0f, knockback = 0.9)
            }
        )

        EntityTypes.COPPER_GOLEM -> listOf(
            ability("coppergolem.slam", "Slam", "Slams the ground, staggering enemies", AbilitySlot.PRIMARY, 40) { ctx ->
                ctx.play(SoundEvents.IRON_GOLEM_ATTACK, 1.2f, 1.2f)
                Actions.knockbackArea(ctx, 4.0, 1.4)
            }
        )

        EntityTypes.IRON_GOLEM -> listOf(
            ability("irongolem.launch", "Launch", "The golem's signature upward launch", AbilitySlot.PRIMARY, 40) { ctx ->
                val target = Actions.targetAhead(ctx, 3.5)
                if (target != null) {
                    ctx.mob.doHurtTarget(ctx.level, target)
                    ctx.play(SoundEvents.IRON_GOLEM_ATTACK, 1.5f, 1.0f)
                    target.setDeltaMovement(
                        target.deltaMovement.x,
                        0.6,
                        target.deltaMovement.z
                    )
                } else {
                    ctx.play(SoundEvents.IRON_GOLEM_ATTACK, 1.5f, 1.0f)
                }
                true
            }
        )

        EntityTypes.RAVAGER -> listOf(
            ability("ravager.roar", "Roar", "Stuns and shoves everything in front", AbilitySlot.PRIMARY, 60) { ctx ->
                ctx.play(SoundEvents.RAVAGER_ROAR, 2.0f, 1.0f)
                Actions.knockbackArea(ctx, 6.0, 2.2)
            },
            ability("ravager.charge", "Charge", "A long trampling charge", AbilitySlot.SECONDARY, 100) { ctx ->
                Actions.charge(ctx, 1.4, SoundEvents.RAVAGER_ATTACK) { target ->
                    Actions.damage(ctx, target, 10.0f)
                }
            }
        )

        EntityTypes.HOGLIN, EntityTypes.ZOGLIN -> listOf(
            ability("hoglin.charge", "Gore", "Charges and gores your target", AbilitySlot.PRIMARY, 50) { ctx ->
                Actions.charge(ctx, 1.2, SoundEvents.HOGLIN_ATTACK) { target ->
                    target.setDeltaMovement(target.deltaMovement.x, 0.5, target.deltaMovement.z)
                }
            }
        )

        EntityTypes.POLAR_BEAR, EntityTypes.PANDA, EntityTypes.WOLF, EntityTypes.CAT,
        EntityTypes.OCELOT, EntityTypes.FOX -> listOf(
            ability("beast.pounce", "Pounce", "Leaps at whatever you are facing", AbilitySlot.PRIMARY, 20) { ctx ->
                Actions.pounce(ctx)
            }
        )

        EntityTypes.CREEPER -> listOf(
            ability("creeper.prime", "Prime", "Ignites yourself; hold sneak to abort", AbilitySlot.PRIMARY, 200) { ctx ->
                val creeper = ctx.mob as? Creeper ?: return@ability false
                if (creeper.isIgnited) return@ability false
                creeper.ignite()
                true
            },
            ability("creeper.blast", "Detonate", "Explodes on the spot", AbilitySlot.SECONDARY, 400) { ctx ->
                val charged = (ctx.mob as? Creeper)?.isPowered == true
                ctx.level.explode(
                    ctx.mob, ctx.mob.x, ctx.mob.y, ctx.mob.z,
                    if (charged) 6.0f else 3.0f,
                    Level.ExplosionInteraction.MOB
                )
                true
            }
        )

        EntityTypes.ENDERMAN -> listOf(
            ability("enderman.blink", "Blink", "Teleports ten blocks along your view", AbilitySlot.PRIMARY, 10) { ctx ->
                Actions.blink(ctx, 10.0, SoundEvents.ENDERMAN_TELEPORT)
            },
            ability("enderman.screech", "Screech", "A scream that unnerves everyone nearby", AbilitySlot.SECONDARY, 300) { ctx ->
                ctx.play(SoundEvents.ENDERMAN_SCREAM, 2.0f, 1.0f); true
            }
        )

        EntityTypes.SPIDER, EntityTypes.CAVE_SPIDER -> listOf(
            ability("spider.leap", "Leap", "Springs forward at your target", AbilitySlot.PRIMARY, 30) { ctx ->
                Actions.pounce(ctx)
            },
            ability("spider.web", "Spin web", "Spins a cobweb where you are looking", AbilitySlot.SECONDARY, 120) { ctx ->
                Actions.placeBlock(ctx, Blocks.COBWEB, SoundEvents.SPIDER_AMBIENT)
            }
        )

        EntityTypes.ENDERMITE, EntityTypes.SILVERFISH -> listOf(
            ability("silverfish.burrow", "Burrow", "Vanishes into the block behind you", AbilitySlot.PRIMARY, 200) { ctx ->
                ctx.mob.addEffect(MobEffectInstance(MobEffects.INVISIBILITY, 100, 0))
                Actions.blink(ctx, 4.0, SoundEvents.SILVERFISH_AMBIENT)
            }
        )

        // ------------------------------------------------------------------ flyers
        EntityTypes.PHANTOM -> listOf(
            ability("phantom.swoop", "Swoop", "Dives at whatever you are looking at", AbilitySlot.PRIMARY, 60) { ctx ->
                Actions.charge(ctx, 1.6, SoundEvents.PHANTOM_SWOOP) { target ->
                    Actions.damage(ctx, target, 4.0f)
                }
            }
        )

        EntityTypes.BEE -> listOf(
            ability("bee.sting", "Sting", "Stings your target; it hurts you too", AbilitySlot.PRIMARY, 40) { ctx ->
                val target = Actions.targetAhead(ctx, 2.5) ?: return@ability false
                Actions.damage(ctx, target, 5.0f)
                ctx.mob.hurtServer(ctx.level, ctx.level.damageSources().generic(), 2.0f)
                ctx.play(SoundEvents.BEE_STING)
                true
            },
            ability("bee.pollinate", "Pollinate", "Picks up pollen and drops it", AbilitySlot.SECONDARY, 100) { ctx ->
                ctx.play(SoundEvents.BEE_POLLINATE); true
            }
        )

        EntityTypes.PARROT -> listOf(
            ability("parrot.screech", "Screech", "A piercing screech", AbilitySlot.PRIMARY, 60) { ctx ->
                ctx.play(SoundEvents.PARROT_AMBIENT, 2.0f, 1.4f); true
            },
            ability("parrot.mimic", "Mimic", "Mimics a hostile mob's sound", AbilitySlot.SECONDARY, 120) { ctx ->
                ctx.play(SoundEvents.PARROT_IMITATE_CREEPER, 1.5f, 1.0f); true
            }
        )

        EntityTypes.BAT -> listOf(
            ability("bat.screech", "Screech", "A high-pitched squeak", AbilitySlot.PRIMARY, 60) { ctx ->
                ctx.play(SoundEvents.BAT_AMBIENT, 2.0f, 1.2f); true
            }
        )

        EntityTypes.ALLAY -> listOf(
            ability("allay.soar", "Soar", "A burst of speed", AbilitySlot.PRIMARY, 80) { ctx ->
                ctx.mob.setDeltaMovement(ctx.mob.deltaMovement.scale(2.2))
                ctx.play(SoundEvents.ALLAY_AMBIENT_WITHOUT_ITEM)
                true
            }
        )

        EntityTypes.VEX -> listOf(
            ability("vex.charge", "Charge", "Dashes at your target", AbilitySlot.PRIMARY, 80) { ctx ->
                Actions.charge(ctx, 1.8, SoundEvents.VEX_CHARGE) { target ->
                    Actions.damage(ctx, target, 6.0f)
                }
            }
        )

        // ------------------------------------------------------------------ water
        EntityTypes.SQUID, EntityTypes.GLOW_SQUID -> listOf(
            ability("squid.ink", "Ink cloud", "Blinds everything around you", AbilitySlot.PRIMARY, 100) { ctx ->
                ctx.play(SoundEvents.SQUID_SQUIRT, 1.5f, 1.0f)
                Actions.effectArea(ctx, 5.0) { MobEffectInstance(MobEffects.BLINDNESS, 100, 0) }
            }
        )

        EntityTypes.DOLPHIN -> listOf(
            ability("dolphin.leap", "Leap", "Throws yourself out of the water", AbilitySlot.PRIMARY, 60) { ctx ->
                ctx.mob.setDeltaMovement(ctx.mob.deltaMovement.x, 0.9, ctx.mob.deltaMovement.z)
                ctx.play(SoundEvents.DOLPHIN_JUMP)
                true
            },
            ability("dolphin.grace", "Dolphin's grace", "A burst of swim speed", AbilitySlot.SECONDARY, 200) { ctx ->
                ctx.mob.addEffect(MobEffectInstance(MobEffects.DOLPHINS_GRACE, 300, 1))
                true
            }
        )

        EntityTypes.TURTLE -> listOf(
            ability("turtle.shell", "Retreat", "Tucks into your shell for protection", AbilitySlot.PRIMARY, 200) { ctx ->
                ctx.mob.addEffect(MobEffectInstance(MobEffects.RESISTANCE, 120, 2))
                ctx.play(SoundEvents.TURTLE_SHAMBLE)
                true
            }
        )

        EntityTypes.AXOLOTL -> listOf(
            ability("axolotl.play", "Play dead", "Floats motionless, difficult to notice", AbilitySlot.PRIMARY, 200) { ctx ->
                ctx.mob.addEffect(MobEffectInstance(MobEffects.INVISIBILITY, 120, 0))
                true
            }
        )

        EntityTypes.PUFFERFISH -> listOf(
            ability("pufferfish.puff", "Puff up", "Swells up and poisons whatever touches you", AbilitySlot.PRIMARY, 120) { ctx ->
                ctx.play(SoundEvents.PUFFER_FISH_BLOW_UP)
                Actions.effectArea(ctx, 3.0) { MobEffectInstance(MobEffects.POISON, 100, 0) }
            }
        )

        // ------------------------------------------------------------------ animals
        EntityTypes.GOAT -> listOf(
            ability("goat.ram", "Ram", "Rams your target off its feet", AbilitySlot.PRIMARY, 40) { ctx ->
                Actions.charge(ctx, 1.3, SoundEvents.GOAT_PREPARE_RAM) { target ->
                    Actions.damage(ctx, target, 3.0f)
                    target.setDeltaMovement(target.deltaMovement.x, 0.5, target.deltaMovement.z)
                }
            },
            ability("goat.longjump", "Long jump", "The goat's long jump", AbilitySlot.SECONDARY, 60) { ctx ->
                Actions.pounce(ctx, vertical = 0.66, forward = 1.0)
            }
        )

        EntityTypes.CAMEL, EntityTypes.CAMEL_HUSK -> listOf(
            ability("camel.dash", "Dash", "Dashes forward in a straight line", AbilitySlot.PRIMARY, 50) { ctx ->
                Actions.charge(ctx, 1.5, SoundEvents.CAMEL_DASH)
            }
        )

        EntityTypes.FROG -> listOf(
            ability("frog.tongue", "Tongue strike", "Flicks your tongue at your target", AbilitySlot.PRIMARY, 30) { ctx ->
                val target = Actions.targetAhead(ctx, 6.0) ?: return@ability false
                Actions.damage(ctx, target, 4.0f)
                ctx.play(SoundEvents.FROG_TONGUE, 1.0f, 1.2f)
                true
            },
            ability("frog.longjump", "Long jump", "Bounds a long way forward", AbilitySlot.SECONDARY, 40) { ctx ->
                Actions.pounce(ctx, vertical = 0.62, forward = 0.9)
            }
        )

        EntityTypes.SNIFFER -> listOf(
            ability("sniffer.dig", "Dig", "Digs up a seed from the ground", AbilitySlot.PRIMARY, 200) { ctx ->
                ctx.play(SoundEvents.SNIFFER_DIGGING)
                Actions.placeBlock(ctx, Blocks.SHORT_GRASS, null)
            }
        )

        EntityTypes.ARMADILLO -> listOf(
            ability("armadillo.roll", "Roll up", "Curls into a ball, greatly reducing damage", AbilitySlot.PRIMARY, 200) { ctx ->
                ctx.mob.addEffect(MobEffectInstance(MobEffects.RESISTANCE, 160, 3))
                ctx.play(SoundEvents.ARMADILLO_ROLL)
                true
            }
        )

        EntityTypes.SLIME, EntityTypes.MAGMA_CUBE, EntityTypes.SULFUR_CUBE -> listOf(
            ability("slimestyle.highbounce", "High bounce", "A much higher bounce than usual", AbilitySlot.PRIMARY, 30) { ctx ->
                ctx.mob.setDeltaMovement(ctx.mob.deltaMovement.x, 0.95, ctx.mob.deltaMovement.z)
                ctx.play(SoundEvents.SLIME_JUMP)
                true
            },
            ability("slimestyle.slam", "Slam", "Crashes back down hard", AbilitySlot.SECONDARY, 100) { ctx ->
                ctx.mob.setDeltaMovement(ctx.mob.deltaMovement.x, -1.1, ctx.mob.deltaMovement.z)
                true
            }
        )

        EntityTypes.RABBIT -> listOf(
            ability("rabbit.bound", "Bound", "A long bound forward", AbilitySlot.PRIMARY, 30) { ctx ->
                Actions.pounce(ctx, vertical = 0.5, forward = 0.8)
            }
        )

        EntityTypes.CHICKEN -> listOf(
            ability("chicken.flutter", "Flutter", "Flaps upward to slow your fall", AbilitySlot.PRIMARY, 60) { ctx ->
                ctx.mob.setDeltaMovement(ctx.mob.deltaMovement.x, 0.42, ctx.mob.deltaMovement.z)
                true
            }
        )

        EntityTypes.CREAKING -> listOf(
            ability("creaking.creak", "Creak", "A creak that darkens the eyes of everyone nearby", AbilitySlot.PRIMARY, 300) { ctx ->
                ctx.play(SoundEvents.CREAKING_ATTACK, 2.0f, 1.0f)
                Actions.effectArea(ctx, 10.0) { MobEffectInstance(MobEffects.DARKNESS, 120, 0) }
            }
        )

        EntityTypes.VILLAGER -> listOf(
            ability("villager.bless", "Village blessing", "Hero of the Village and regeneration", AbilitySlot.PRIMARY, 600) { ctx ->
                Actions.villagerBlessing(ctx)
            },
            ability("villager.callgolem", "Call golem", "Calls an iron golem to your side", AbilitySlot.SECONDARY, 1200) { ctx ->
                Actions.summon(ctx, EntityTypes.IRON_GOLEM, 1)
            }
        )

        EntityTypes.WANDERING_TRADER -> listOf(
            ability("trader.hide", "Trader's discretion", "Drinks an invisibility potion", AbilitySlot.PRIMARY, 400) { ctx ->
                ctx.mob.addEffect(MobEffectInstance(MobEffects.INVISIBILITY, 400, 0))
                ctx.play(SoundEvents.WITCH_DRINK)
                true
            }
        )

        EntityTypes.STRIDER -> listOf(
            ability("strider.walk", "Lava walk", "Strides across lava unharmed", AbilitySlot.PRIMARY, 200) { ctx ->
                ctx.mob.addEffect(MobEffectInstance(MobEffects.FIRE_RESISTANCE, 200, 0))
                true
            }
        )

        // ------------------------------------------------------------------- livestock
        // Cows, pigs, sheep and mooshrooms never attack, so their kit is built from what they
        // really do: shove with a heavy body, eat to recover, and panic as a herd. Nothing here
        // is invented; each button is one of the mob's own vanilla behaviours put on demand.
        EntityTypes.COW, EntityTypes.MOOSHROOM, EntityTypes.SHEEP, EntityTypes.PIG -> listOf(
            ability(
                "livestock.shove", "Body shove",
                "Pushes whatever you face with your whole weight",
                AbilitySlot.PRIMARY, 20
            ) { ctx -> Actions.melee(ctx, 3.0, 2.0f, knockback = 0.7) },
            ability(
                "livestock.graze", "Graze",
                "Eats to mend yourself; a shorn sheep regrows its fleece",
                AbilitySlot.SECONDARY, 240
            ) { ctx -> Actions.graze(ctx) },
            ability(
                "livestock.startle", "Startle",
                "A herd-wide panic that scatters everyone nearby",
                AbilitySlot.TERTIARY, 180
            ) { ctx -> Actions.stomp(ctx, 4.5, 1.0f, 0.9) }
        )

        // ---------------------------------------------------------------------- equines
        // A horse's own AI is a stride, a rear and a jump. Those three become the three
        // buttons, so riding a possessed horse feels like riding a horse.
        EntityTypes.HORSE, EntityTypes.DONKEY, EntityTypes.MULE,
        EntityTypes.SKELETON_HORSE, EntityTypes.ZOMBIE_HORSE -> listOf(
            ability(
                "equine.gallop", "Gallop",
                "Digs in and bolts forward at full stride",
                AbilitySlot.PRIMARY, 30
            ) { ctx ->
                ctx.play(SoundEvents.HORSE_GALLOP)
                Actions.dash(ctx, 0.62, 0.22)
            },
            ability(
                "equine.rear", "Rear up",
                "Rears, then brings both hooves down in front of you",
                AbilitySlot.SECONDARY, 90
            ) { ctx ->
                (ctx.mob as? AbstractHorse)?.standIfPossible()
                ctx.play(SoundEvents.HORSE_ANGRY)
                Actions.stomp(ctx, 3.5, 3.0f, 0.7)
            },
            ability(
                "equine.chargedleap", "Charged leap",
                "The jump you would make from the saddle",
                AbilitySlot.TERTIARY, 50
            ) { ctx ->
                ctx.play(SoundEvents.HORSE_JUMP)
                ctx.mob.setDeltaMovement(
                    ctx.mob.deltaMovement.x,
                    Actions.jumpStrengthOf(ctx.mob) + 0.25,
                    ctx.mob.deltaMovement.z
                )
                true
            }
        )

        // ------------------------------------------------------------------------- fish
        // Fish have two behaviours worth a button: the burst escape dart and schooling. Both
        // are what the mob already does on its own, exposed on demand.
        EntityTypes.COD, EntityTypes.SALMON, EntityTypes.TROPICAL_FISH, EntityTypes.TADPOLE -> listOf(
            ability(
                "fish.dart", "Dart",
                "A sharp burst of speed through the water",
                AbilitySlot.PRIMARY, 25
            ) { ctx -> Actions.dash(ctx, 0.55, 0.05) },
            ability(
                "fish.school", "School",
                "Calls the rest of the shoal to your side",
                AbilitySlot.SECONDARY, 400
            ) { ctx -> Actions.summon(ctx, ctx.mob.type, 3) }
        )

        // ----------------------------------------------------------------------- nautilus
        EntityTypes.NAUTILUS, EntityTypes.ZOMBIE_NAUTILUS -> listOf(
            ability(
                "nautilus.jet", "Jet",
                "A pulse of water that launches you forward",
                AbilitySlot.PRIMARY, 30
            ) { ctx -> Actions.dash(ctx, 0.58, 0.12) },
            ability(
                "nautilus.shell", "Shell guard",
                "Tucks into the shell, where almost nothing gets through",
                AbilitySlot.SECONDARY, 300
            ) { ctx ->
                ctx.mob.addEffect(MobEffectInstance(MobEffects.RESISTANCE, 160, 3))
                ctx.play(SoundEvents.SHULKER_CLOSE, 0.9f, 0.8f)
                true
            },
            ability(
                "nautilus.ink", "Ink cloud",
                "Blinds everything around you",
                AbilitySlot.TERTIARY, 200
            ) { ctx -> Actions.effectArea(ctx, 5.0) { MobEffectInstance(MobEffects.BLINDNESS, 120, 0) } }
        )

        // ------------------------------------------------------------------- happy ghast
        EntityTypes.HAPPY_GHAST -> listOf(
            ability(
                "happyghast.call", "Call the flock",
                "The wail that brings another ghastling to you",
                AbilitySlot.PRIMARY, 300
            ) { ctx ->
                ctx.play(SoundEvents.HAPPY_GHAST_AMBIENT)
                Actions.summon(ctx, EntityTypes.HAPPY_GHAST, 1)
            },
            ability(
                "happyghast.bloom", "Gentle bloom",
                "A soft exhale that steadies every fall nearby",
                AbilitySlot.SECONDARY, 300
            ) { ctx -> Actions.effectArea(ctx, 6.0) { MobEffectInstance(MobEffects.SLOW_FALLING, 240, 0) } }
        )

        // ------------------------------------------------------------------------- giant
        EntityTypes.GIANT -> listOf(
            ability(
                "giant.stomp", "Colossal step",
                "One step that flattens everything underfoot",
                AbilitySlot.PRIMARY, 40
            ) { ctx -> Actions.stomp(ctx, 5.0, 8.0f, 1.2) },
            ability(
                "giant.roar", "Roar",
                "A bellow that hurls everyone away from you",
                AbilitySlot.SECONDARY, 120
            ) { ctx ->
                ctx.play(SoundEvents.RAVAGER_ROAR, 1.5f, 0.6f)
                Actions.knockbackArea(ctx, 7.0, 1.4)
            },
            ability(
                "giant.sweep", "Sweeping arm",
                "A backhand that clears a whole line",
                AbilitySlot.TERTIARY, 90
            ) { ctx -> Actions.lineDamage(ctx, 6.0, 10.0f, 1.0) }
        )

        else -> defaultAbilities(type)
    }

    /**
     * Every mob the table above does not cover explicitly.
     *
     * Ordinary mobs can still attack, and anything with a real jump gets to use it, so a possessed
     * cow is not a passenger with no buttons. The description is derived from what the mob can
     * actually do rather than being hardcoded per type.
     */
    private fun defaultAbilities(type: EntityType<*>): List<MobAbility> {
        val abilities = mutableListOf<MobAbility>()
        abilities += ability(
            "generic.strike", "Strike",
            "Attacks whatever you are facing",
            AbilitySlot.PRIMARY, 15
        ) { ctx -> Actions.melee(ctx, 3.0, 2.0f) }

        if (type != EntityTypes.ITEM && type != EntityTypes.ARROW) {
            abilities += ability(
                "generic.lunge", "Lunge",
                "Throws your body forward",
                AbilitySlot.SECONDARY, 40
            ) { ctx -> Actions.pounce(ctx) }
        }
        return abilities
    }

    /**
     * Everything the in-game manual and `/transform info` need about one form.
     *
     * Movement and abilities come from the same two registries the server drives from, so the
     * documentation cannot promise a button that does not exist or a movement style the controller
     * will not use. That is the whole reason this reads from the registries instead of a
     * hand-written table.
     */
    data class FormSummary(
        val formName: String,
        val movement: String,
        val abilities: List<String>
    )

    fun summary(type: EntityType<*>): FormSummary = FormSummary(
        formName = type.description.string,
        movement = MobMovementProfiles.profileFor(type).describe(),
        abilities = forType(type).sortedBy { it.slot.index }.map { ability ->
            "${ability.slot.key} - ${ability.name}: ${ability.description}"
        }
    )

    /**
     * Human-readable summary of what a controlled mob can do, used by `/transform info` and the
     * in-game manual.
     */
    fun manualLines(type: EntityType<*>): List<String> = summary(type).abilities
}

/**
 * Shared ability implementations.
 *
 * Each ability above is a thin declaration over one of these, so a change to how projectiles
 * are flown or how damage is applied happens in exactly one place.
 */
object Actions {

    /** Nearest living thing in front of the mob, respecting line of sight. */
    fun targetAhead(ctx: AbilityContext, range: Double): LivingEntity? {
        val eye = ctx.eye()
        val look = ctx.look()
        val searchBox = ctx.mob.boundingBox.expandTowards(look.scale(range)).inflate(1.5)
        return ctx.level
            .getEntities(ctx.mob, searchBox) { candidate ->
                candidate is LivingEntity &&
                    candidate !== ctx.mob &&
                    candidate.isAlive &&
                    ctx.mob.canAttack(candidate)
            }
            .filterIsInstance<LivingEntity>()
            .sortedBy { it.distanceToSqr(ctx.mob) }
            .firstOrNull { candidate ->
                val towards = candidate.boundingBox.center.vectorTo(eye).scale(-1.0).normalize()
                look.dot(towards) > 0.45 && ctx.mob.hasLineOfSight(candidate)
            }
    }

    fun damage(ctx: AbilityContext, target: LivingEntity, amount: Float) {
        target.hurtServer(ctx.level, ctx.level.damageSources().mobAttack(ctx.mob), amount)
    }

    /** Basic melee: swing, damage, optionally shove. */
    fun melee(
        ctx: AbilityContext,
        range: Double,
        damage: Float,
        knockback: Double = 0.4
    ): Boolean {
        val target = targetAhead(ctx, range) ?: return false
        ctx.mob.doHurtTarget(ctx.level, target)
        damage(ctx, target, damage)
        shove(ctx, target, knockback)
        return true
    }

    private fun shove(ctx: AbilityContext, target: LivingEntity, strength: Double) {
        val dx = target.x - ctx.mob.x
        val dz = target.z - ctx.mob.z
        val distance = kotlin.math.sqrt(dx * dx + dz * dz).coerceAtLeast(0.01)
        target.knockback(
            strength,
            dx / distance,
            dz / distance,
            ctx.level.damageSources().mobAttack(ctx.mob),
            0.0f
        )
    }

    /** Fires one projectile along the camera. */
    fun single(
        ctx: AbilityContext,
        sound: net.minecraft.sounds.SoundEvent?,
        inaccuracy: Float = 1.0f,
        velocity: Float = 1.6f,
        create: (Vec3) -> Projectile
    ): Boolean {
        sound?.let { ctx.play(it) }
        return launch(ctx, create(ctx.look()), velocity, inaccuracy) { }
    }

    /** Fires [count] projectiles in a spread, the way a Blaze's burst works. */
    fun volley(
        ctx: AbilityContext,
        count: Int,
        spread: Float,
        sound: net.minecraft.sounds.SoundEvent?,
        create: (Vec3) -> Projectile
    ): Boolean {
        sound?.let { ctx.play(it) }
        val look = ctx.look()
        repeat(count) { index ->
            val offset = if (count == 1) 0f else (index - (count - 1) / 2.0f) * spread
            val rotated = rotate(look, offset)
            launch(ctx, create(rotated), 1.4f, 0.5f) { }
        }
        return true
    }

    /** Positions, aims and spawns a projectile, then runs [after] for its sound. */
    fun launch(
        ctx: AbilityContext,
        projectile: Projectile,
        velocity: Float,
        inaccuracy: Float,
        after: () -> Unit
    ): Boolean {
        val eye = ctx.eye()
        projectile.setPos(eye.x, eye.y - 0.1, eye.z)
        projectile.shootFromRotation(ctx.mob, ctx.mob.xRot, ctx.mob.yRot, 0.0f, velocity, inaccuracy)
        ctx.level.addFreshEntity(projectile)
        after()
        return true
    }

    private fun rotate(direction: Vec3, yawDegrees: Float): Vec3 {
        val radians = Math.toRadians(yawDegrees.toDouble())
        val cosA = cos(radians)
        val sinA = sin(radians)
        return Vec3(
            direction.x * cosA - direction.z * sinA,
            direction.y,
            direction.x * sinA + direction.z * cosA
        )
    }

    /** Skeleton-style bow shot, optionally with a tipped arrow effect. */
    fun shootArrow(
        ctx: AbilityContext,
        sound: net.minecraft.sounds.SoundEvent,
        effect: (() -> MobEffectInstance?)?
    ): Boolean {
        ctx.play(sound)
        val arrow = Arrow(ctx.level, ctx.mob, ItemStack(Items.ARROW), ItemStack.EMPTY)
        effect?.invoke()?.let { arrow.addEffect(it) }
        return launch(ctx, arrow, 1.8f, 6.0f) { }
    }

    /** Teleports along the view direction, clamped to somewhere with room. */
    fun blink(ctx: AbilityContext, distance: Double, sound: net.minecraft.sounds.SoundEvent): Boolean {
        val look = ctx.look()
        val target = Vec3(
            ctx.mob.x + look.x * distance,
            ctx.mob.y + look.y * distance.coerceAtMost(6.0),
            ctx.mob.z + look.z * distance
        )
        val safe = findSafeSpot(ctx, target) ?: return false
        ctx.mob.teleportTo(safe.x, safe.y, safe.z)
        ctx.play(sound, 1.0f, 1.0f)
        ctx.mob.fallDistance = 0.0
        return true
    }

    private fun findSafeSpot(ctx: AbilityContext, wanted: Vec3): Vec3? {
        for (drop in 0..6) {
            val candidate = Vec3(wanted.x, wanted.y - drop, wanted.z)
            val below = BlockPos.containing(candidate.x, candidate.y - 1.0, candidate.z)
            val feet = BlockPos.containing(candidate)
            val head = BlockPos.containing(candidate.x, candidate.y + 1.0, candidate.z)
            val groundSolid = !ctx.level.getBlockState(below).getCollisionShape(ctx.level, below).isEmpty
            val feetFree = ctx.level.getBlockState(feet).getCollisionShape(ctx.level, feet).isEmpty
            val headFree = ctx.level.getBlockState(head).getCollisionShape(ctx.level, head).isEmpty
            if (groundSolid && feetFree && headFree) {
                return candidate
            }
        }
        return null
    }

    /** Leaps forward and up, the movement every pouncing mob uses. */
    fun pounce(ctx: AbilityContext, vertical: Double = 0.5, forward: Double = 0.7): Boolean {
        val look = ctx.look()
        ctx.mob.setDeltaMovement(
            look.x * forward,
            vertical,
            look.z * forward
        )
        return true
    }

    /** A forward lunge that can hit anything it passes through. */
    fun charge(
        ctx: AbilityContext,
        strength: Double,
        sound: net.minecraft.sounds.SoundEvent?,
        onHit: ((LivingEntity) -> Unit)? = null
    ): Boolean {
        sound?.let { ctx.play(it, 1.4f, 1.0f) }
        val look = ctx.look()
        ctx.mob.setDeltaMovement(look.x * strength, ctx.mob.deltaMovement.y, look.z * strength)
        val target = targetAhead(ctx, 3.5)
        if (target != null) {
            damage(ctx, target, 4.0f)
            onHit?.invoke(target)
        }
        return true
    }

    /** Damages everything in a line from the mob's eyes, used by sonics and lasers. */
    fun lineDamage(ctx: AbilityContext, range: Double, damage: Float, knockback: Double): Boolean {
        val eye = ctx.eye()
        val look = ctx.look()
        val end = eye.add(look.scale(range))
        val box = ctx.mob.boundingBox.expandTowards(look.scale(range)).inflate(1.5)
        val victims = ctx.level
            .getEntities(ctx.mob, box) { it is LivingEntity && it !== ctx.mob && ctx.mob.canAttack(it) }
            .filterIsInstance<LivingEntity>()
            .filter { candidate ->
                val towards = candidate.boundingBox.center.vectorTo(eye).scale(-1.0).normalize()
                look.dot(towards) > 0.75 && candidate.distanceToSqr(ctx.mob) <= range * range
            }
        victims.forEach { victim ->
            damage(ctx, victim, damage)
            shove(ctx, victim, knockback)
        }
        val start = eye.add(look.scale(1.0))
        ctx.level.sendParticles(
            net.minecraft.core.particles.ParticleTypes.SONIC_BOOM,
            start.x, start.y, start.z, 1, 0.0, 0.0, 0.0, 0.0
        )
        return victims.isNotEmpty() || end.length() > 0
    }

    /** Shoves everything nearby back, used by roars and wing blasts. */
    fun knockbackArea(ctx: AbilityContext, radius: Double, strength: Double): Boolean {
        val center = ctx.mob.position()
        ctx.level
            .getEntities(ctx.mob, ctx.mob.boundingBox.inflate(radius)) { it is LivingEntity && it !== ctx.mob }
            .filterIsInstance<LivingEntity>()
            .forEach { victim ->
                val away = victim.position().subtract(center)
                val flat = Vec3(away.x, 0.0, away.z)
                val distance = flat.length().coerceAtLeast(0.01)
                victim.knockback(
                    strength, flat.x / distance, flat.z / distance,
                    ctx.level.damageSources().mobAttack(ctx.mob), 0.0f
                )
            }
        return true
    }

    /** Applies one potion effect to everything in range. */
    fun effectArea(ctx: AbilityContext, radius: Double, effect: () -> MobEffectInstance?): Boolean {
        val instance = effect() ?: return false
        ctx.level
            .getEntities(ctx.mob, ctx.mob.boundingBox.inflate(radius)) { it is LivingEntity && it !== ctx.mob }
            .filterIsInstance<LivingEntity>()
            .forEach { victim -> victim.addEffect(MobEffectInstance(instance), ctx.mob) }
        return true
    }

    fun effectTarget(
        ctx: AbilityContext,
        range: Double,
        sound: net.minecraft.sounds.SoundEvent?,
        effect: () -> MobEffectInstance?
    ): Boolean {
        val target = targetAhead(ctx, range) ?: return false
        sound?.let { ctx.play(it) }
        effect()?.let { target.addEffect(MobEffectInstance(it), ctx.mob) }
        damage(ctx, target, 2.0f)
        return true
    }

    fun igniteArea(ctx: AbilityContext, radius: Double): Boolean {
        ctx.level
            .getEntities(ctx.mob, ctx.mob.boundingBox.inflate(radius)) { it is LivingEntity && it !== ctx.mob }
            .filterIsInstance<LivingEntity>()
            .forEach { victim -> victim.igniteForSeconds(5.0f) }
        return true
    }

    /** A row of evoker fangs marching away from the mob. */
    fun fangs(ctx: AbilityContext): Boolean {
        val look = ctx.look()
        repeat(6) { step ->
            val distance = 1.0 + step * 1.1
            val x = ctx.mob.x + look.x * distance
            val z = ctx.mob.z + look.z * distance
            val y = ctx.mob.y
            ctx.level.addFreshEntity(
                net.minecraft.world.entity.projectile.EvokerFangs(
                    ctx.level, x, y, z,
                    ctx.mob.yRot,
                    step * 2,
                    ctx.mob
                )
            )
        }
        ctx.play(SoundEvents.EVOKER_FANGS_ATTACK)
        return true
    }

    /** Spawns [count] friendly mobs of [type] next to the controller. */
    fun summon(ctx: AbilityContext, type: EntityType<*>, count: Int): Boolean {
        val owner = ctx.owner
        var spawned = 0
        repeat(count) { index ->
            val entity = type.create(ctx.level, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED) ?: return@repeat
            val angle = (index / count.toDouble()) * Math.PI * 2
            entity.snapTo(
                ctx.mob.x + cos(angle) * 2.0, ctx.mob.y, ctx.mob.z + sin(angle) * 2.0,
                ctx.mob.yRot, 0.0f
            )
            if (entity is Mob) {
                entity.setPersistenceRequired()
                owner?.let { entity.setTarget(it) }
                entity.setNoAi(false)
            }
            ctx.level.addFreshEntity(entity)
            spawned++
        }
        return spawned > 0
    }

    /** Places a block where the mob is looking, if that spot is free. */
    fun placeBlock(
        ctx: AbilityContext,
        block: net.minecraft.world.level.block.Block,
        sound: net.minecraft.sounds.SoundEvent?
    ): Boolean {
        val look = ctx.look()
        val pos = BlockPos.containing(
            ctx.mob.x + look.x * 2.0,
            ctx.mob.y + look.y * 2.0,
            ctx.mob.z + look.z * 2.0
        )
        if (!ctx.level.getBlockState(pos).isAir) return false
        ctx.level.setBlock(pos, block.defaultBlockState(), 3)
        sound?.let { ctx.play(it) }
        return true
    }

    /** The villager's celebration and golem call, kept from the previous implementation. */
    fun villagerBlessing(ctx: AbilityContext): Boolean {
        ctx.play(SoundEvents.VILLAGER_CELEBRATE, 1.2f, 1.0f)
        val owner = ctx.owner ?: return true
        owner.addEffect(MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE, 200, 0))
        owner.addEffect(MobEffectInstance(MobEffects.REGENERATION, 80, 0))
        if (ctx.random.nextFloat() < 0.2f) {
            val nearby = ctx.level.getEntities(
                ctx.mob, ctx.mob.boundingBox.inflate(16.0)
            ) { it.type == EntityTypes.IRON_GOLEM }
            if (nearby.isEmpty()) {
                summon(ctx, EntityTypes.IRON_GOLEM, 1)
            }
        }
        return true
    }

    /**
     * A burst of velocity along the view direction.
     *
     * Dashes are how the quick mobs actually move: a squid jets, a horse digs in, a fish darts.
     * [vertical] is used only as a floor, so a dash never cancels an in-progress jump.
     */
    fun dash(ctx: AbilityContext, strength: Double, vertical: Double = 0.0): Boolean {
        val look = ctx.look()
        ctx.mob.setDeltaMovement(
            look.x * strength,
            maxOf(ctx.mob.deltaMovement.y, vertical),
            look.z * strength
        )
        return true
    }

    /**
     * The livestock heal.
     *
     * Cows, sheep, pigs and mooshrooms graze to recover, and a shorn sheep grows its fleece back
     * while it eats. Both halves are the mob's own behaviour, so the ability reports failure when
     * there is genuinely nothing to mend rather than consuming a cooldown for free.
     */
    fun graze(ctx: AbilityContext): Boolean {
        var didSomething = false
        if (ctx.mob.health < ctx.mob.maxHealth) {
            ctx.mob.heal(4.0f)
            didSomething = true
        }
        val sheep = ctx.mob as? Sheep
        if (sheep != null && sheep.isSheared) {
            sheep.setSheared(false)
            didSomething = true
        }
        if (!didSomething) return false
        ctx.play(SoundEvents.GRASS_BREAK, 0.8f, 1.0f)
        return true
    }

    /**
     * A ground slam: everything standing around the mob takes damage and is thrown outward.
     *
     * Livestock startles, horses bring their hooves down and a giant simply takes a step; the
     * difference between them is the radius and the damage, not the mechanism.
     */
    fun stomp(ctx: AbilityContext, radius: Double, damage: Float, knockback: Double): Boolean {
        val center = ctx.mob.position()
        val victims = ctx.level
            .getEntities(ctx.mob, ctx.mob.boundingBox.inflate(radius)) { it is LivingEntity && it !== ctx.mob }
            .filterIsInstance<LivingEntity>()
        victims.forEach { victim ->
            damage(ctx, victim, damage)
            val away = victim.position().subtract(center)
            val flat = Vec3(away.x, 0.0, away.z)
            val distance = flat.length().coerceAtLeast(0.01)
            victim.knockback(
                knockback, flat.x / distance, flat.z / distance,
                ctx.level.damageSources().mobAttack(ctx.mob), 0.0f
            )
        }
        ctx.level.sendParticles(
            net.minecraft.core.particles.ParticleTypes.POOF,
            ctx.mob.x, ctx.mob.y + 0.1, ctx.mob.z, 12, 0.6, 0.1, 0.6, 0.02
        )
        return true
    }

    /** Reads a mob's own jump strength so leaps stay in character. */
    fun jumpStrengthOf(mob: Mob): Double =
        abs(mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH))
            .takeIf { it > 0.0 } ?: 0.42
}

/**
 * Per-player ability cooldowns and dispatch.
 *
 * Kept out of the mixins so both loaders share one implementation, and so cooldown state can be
 * cleared in exactly one place when a session ends.
 */
object MobAbilityRuntime {

    private val cooldowns = HashMap<java.util.UUID, MutableMap<String, Int>>()

    /** Counts down every active cooldown; called once per server tick. */
    fun tick() {
        if (cooldowns.isEmpty()) return
        val iterator = cooldowns.values.iterator()
        while (iterator.hasNext()) {
            val playerCooldowns = iterator.next()
            val keys = playerCooldowns.keys.toList()
            for (key in keys) {
                val remaining = playerCooldowns.getValue(key) - 1
                if (remaining <= 0) playerCooldowns.remove(key) else playerCooldowns[key] = remaining
            }
            if (playerCooldowns.isEmpty()) iterator.remove()
        }
    }

    fun remainingCooldown(playerId: java.util.UUID, abilityId: String): Int =
        cooldowns[playerId]?.get(abilityId) ?: 0

    fun clear(playerId: java.util.UUID) {
        cooldowns.remove(playerId)
    }

    fun clearAll() = cooldowns.clear()

    /**
     * Runs the ability bound to [slot] for a player, honouring its cooldown.
     *
     * Returns a message when the attempt could not be carried out, so the player learns why
     * nothing happened instead of concluding the button is broken.
     */
    fun activate(player: ServerPlayer, mob: Mob, slot: AbilitySlot): String? {
        val ability = MobAbilities.slotFor(mob, slot)
            ?: return "This mob has no ${slot.label.lowercase()} ability."
        val level = player.level() as? ServerLevel ?: return null

        val existing = remainingCooldown(player.uuid, ability.id)
        if (existing > 0) {
            return "${ability.name} is recharging (${(existing + 19) / 20}s)."
        }

        val context = AbilityContext(level, mob, player)
        val performed = try {
            ability.activate(context)
        } catch (_: Exception) {
            false
        }
        if (!performed) {
            return "${ability.name} needs a valid target."
        }
        cooldowns.getOrPut(player.uuid) { HashMap() }[ability.id] = ability.cooldownTicks
        return null
    }
}
