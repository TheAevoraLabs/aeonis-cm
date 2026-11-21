package com.qc.aeonis.command

import com.mojang.brigadier.CommandDispatcher
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.level.block.Blocks
import java.util.EnumSet
import net.minecraft.core.BlockPos
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.PathfinderMob
import com.mojang.brigadier.context.CommandContext
import com.qc.aeonis.config.AeonisFeatures
import com.qc.aeonis.entity.AeonisEntities
import com.qc.aeonis.entity.HerobrineEntity
import com.qc.aeonis.entity.HerobrineSpawner
import com.qc.aeonis.network.AeonisNetwork
import com.qc.aeonis.transform.MobCatalogue
import com.qc.aeonis.transform.MobKinds
import com.qc.aeonis.transform.MobVariants
import com.qc.aeonis.transform.SoulMode
import com.qc.aeonis.transform.TransformResult
import com.qc.aeonis.transform.TransformService
import com.qc.aeonis.transform.TransformSessions
import net.minecraft.commands.CommandBuildContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.commands.arguments.ResourceArgument
import net.minecraft.core.registries.Registries
import net.minecraft.core.registries.BuiltInRegistries
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LightningBolt
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.animal.golem.IronGolem
import net.minecraft.world.entity.animal.wolf.Wolf
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.monster.Vex
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.Difficulty
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.entity.monster.skeleton.Skeleton
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.cubemob.Slime
import net.minecraft.world.entity.animal.equine.Horse
import net.minecraft.world.entity.animal.pig.Pig
import net.minecraft.world.entity.animal.axolotl.Axolotl
import net.minecraft.world.entity.animal.parrot.Parrot
import net.minecraft.world.entity.animal.dolphin.Dolphin
import net.minecraft.world.entity.animal.goat.Goat
import net.minecraft.world.entity.animal.frog.Frog
import net.minecraft.resources.Identifier
import net.minecraft.world.item.DyeColor
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import kotlin.math.cos
import kotlin.math.sin

object AeonisCommands {
        private const val PRANK_MORPH_DURATION_TICKS = 20 * 120 // 2 minutes
        private val FUN_TRANSFORM_THEMES = mapOf(
            "chaos" to listOf(EntityTypes.CREEPER, EntityTypes.ENDERMAN, EntityTypes.BREEZE, EntityTypes.WITCH, EntityTypes.RAVAGER),
            "cute" to listOf(EntityTypes.AXOLOTL, EntityTypes.ALLAY, EntityTypes.FROG, EntityTypes.BEE, EntityTypes.PARROT),
            "scary" to listOf(EntityTypes.WARDEN, EntityTypes.WITHER_SKELETON, EntityTypes.PHANTOM, EntityTypes.EVOKER, EntityTypes.VINDICATOR),
            "boss" to listOf(EntityTypes.WITHER, EntityTypes.ENDER_DRAGON, EntityTypes.RAVAGER, EntityTypes.ELDER_GUARDIAN),
            "cube" to listOf(EntityTypes.SLIME, EntityTypes.MAGMA_CUBE),
            "meme" to listOf(EntityTypes.CHICKEN, EntityTypes.GOAT, EntityTypes.LLAMA, EntityTypes.PUFFERFISH, EntityTypes.SNIFFER)
        )
        // Track all entities made chaotic for easy disabling
        private val chaoticEntities = mutableSetOf<Int>()
        private val prankMorphTimers = mutableMapOf<java.util.UUID, Int>()

        private fun stopChaotic(ctx: CommandContext<CommandSourceStack>): Int {
            var count = 0
            val server = ctx.source.server
            for (level in server.allLevels) {
                for (entityId in chaoticEntities) {
                    val entity = level.getEntity(entityId) as? PathfinderMob ?: continue
                    val injected = chaoticGoals.remove(entityId)
                    if (injected != null) {
                        // Remove exactly the goals we added. The previous implementation removed
                        // the first three goals in priority order, which deleted the mob's own AI
                        // instead of the injected ones.
                        val selector = entity.goalSelector
                        injected.forEach { selector.removeGoal(it) }
                        count++
                    }
                    entity.removeEffect(MobEffects.SPEED)
                }
            }
            chaoticEntities.clear()
            chaoticGoals.clear()
            ctx.source.sendSuccess({ Component.literal("§aStopped chaotic AI on $count entities.") }, true)
            return count
        }

        private fun reloadMod(ctx: CommandContext<CommandSourceStack>): Int {
            // Transform sessions and soul mode live in the transform package and are reset
            // there, so there is no second set of maps to forget to clear.
            chaoticEntities.clear()
            chaoticGoals.clear()
            prankMorphTimers.clear()
            petVexOwners.clear()
            actorWalkTargets.clear()
            actorLookTargets.clear()
            actorAttackTargets.clear()
            // Full teardown rather than dropping bookkeeping: every morphed player is unwound and
            // every possessed mob removed, so nothing is left orphaned in the world.
            TransformService.shutdown(ctx.source.server)
            ctx.source.sendSuccess({ Component.literal("§bAeonis mod features reloaded and states reset.") }, true)
            return 1
        }
    
    // Track pet vex ownership: vex entity ID -> owner player UUID
    internal val petVexOwners = mutableMapOf<Int, java.util.UUID>()

    // Goals injected by /ai chaotic, tracked per entity so they can be removed precisely.
    private val chaoticGoals = mutableMapOf<Int, MutableList<net.minecraft.world.entity.ai.goal.Goal>>()
    
    // Director Mode: Active Orders
    private val actorWalkTargets = mutableMapOf<Int, Vec3>()
    private val actorLookTargets = mutableMapOf<Int, Int>() // EntityID -> TargetEntityID
    private val actorAttackTargets = mutableMapOf<Int, Int>() // EntityID -> TargetEntityID

    private fun syncControllingPlayers(player: ServerPlayer) {
        val serverLevel = player.level()
        val server = serverLevel.server
        AeonisNetwork.broadcastControllingPlayers(server)
    }
    
    /**
     * Called every tick to enforce Director Mode orders
     */
    fun tickActors(server: net.minecraft.server.MinecraftServer) {
        // 1. Handle Walking
        val walkIterator = actorWalkTargets.iterator()
        while (walkIterator.hasNext()) {
            val entry = walkIterator.next()
            val entityId = entry.key
            val targetPos = entry.value
            
            var found = false
            for (level in server.allLevels) {
                val entity = level.getEntity(entityId)
                if (entity is PathfinderMob && entity.isAlive) {
                    found = true
                    
                    // Check if arrived (within 1.5 blocks)
                    if (entity.position().distanceToSqr(targetPos) < 2.25) {
                        walkIterator.remove() // Arrived
                        entity.navigation.stop()
                    } else {
                        // Re-issue move command if not moving or every 20 ticks (approx)
                        if (entity.tickCount % 10 == 0 || entity.navigation.isDone) {
                            entity.navigation.moveTo(targetPos.x, targetPos.y, targetPos.z, 1.2)
                        }
                    }
                    break
                }
            }
            if (!found) {
                walkIterator.remove() // Entity dead or gone
            }
        }

        // 2. Handle Looking
        val lookIterator = actorLookTargets.iterator()
        while (lookIterator.hasNext()) {
            val entry = lookIterator.next()
            val entityId = entry.key
            val targetId = entry.value
            
            var found = false
            for (level in server.allLevels) {
                val entity = level.getEntity(entityId)
                if (entity is Mob && entity.isAlive) {
                    found = true
                    val target = level.getEntity(targetId)
                    if (target != null && target.isAlive) {
                        entity.lookControl.setLookAt(target, 30.0f, 30.0f)
                    } else {
                        lookIterator.remove() // Target gone
                    }
                    break
                }
            }
            if (!found) {
                lookIterator.remove() // Entity gone
            }
        }

        // 3. Handle Attacking
        val attackIterator = actorAttackTargets.iterator()
        while (attackIterator.hasNext()) {
            val entry = attackIterator.next()
            val entityId = entry.key
            val targetId = entry.value
            
            var found = false
            for (level in server.allLevels) {
                val entity = level.getEntity(entityId)
                if (entity is Mob && entity.isAlive) {
                    found = true
                    val target = level.getEntity(targetId) as? LivingEntity
                    if (target != null && target.isAlive) {
                        if (entity.target != target) {
                            entity.target = target
                        }
                    } else {
                        attackIterator.remove() // Target gone
                    }
                    break
                }
            }
            if (!found) {
                attackIterator.remove() // Entity gone
            }
        }
    }

    /**
     * Called every tick to redirect pet vex targets away from their owners
     */
    fun tickPetVexes(server: net.minecraft.server.MinecraftServer) {
        val toRemove = mutableListOf<Int>()
        
        for ((vexId, ownerUUID) in petVexOwners) {
            var found = false
            for (level in server.allLevels) {
                val entity = level.getEntity(vexId)
                if (entity is Vex && entity.isAlive) {
                    found = true
                    val owner = server.playerList.getPlayer(ownerUUID)
                    
                    // If vex is targeting owner, redirect to a hostile mob
                    if (entity.target == owner || entity.target is Player) {
                        val nearbyHostile = level.getEntitiesOfClass(
                            Monster::class.java,
                            entity.boundingBox.inflate(24.0)
                        ) { it !is Vex && it.isAlive }.minByOrNull { it.distanceToSqr(entity) }
                        
                        entity.setTarget(nearbyHostile)
                    }
                    break
                }
            }
            if (!found) {
                toRemove.add(vexId)
            }
        }
        
        // Clean up dead vexes
        toRemove.forEach { petVexOwners.remove(it) }
    }

    fun tickPrankMorphs(server: net.minecraft.server.MinecraftServer) {
        val iterator = prankMorphTimers.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val playerUuid = entry.key
            val ticks = entry.value
            val player = server.playerList.getPlayer(playerUuid)
            if (player == null) {
                iterator.remove()
                continue
            }

            if (!TransformService.isTransformed(player)) {
                iterator.remove()
                continue
            }

            val nextTicks = ticks - 1
            if (nextTicks <= 0) {
                TransformService.untransform(player, showMessage = true)
                player.sendSystemMessage(Component.literal("§e🎭 Prank morph expired. Back to normal!"))
                iterator.remove()
            } else {
                entry.setValue(nextTicks)
            }
        }
    }
    
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>, registryAccess: CommandBuildContext) {
        registerCommands(dispatcher)
        registerTransformCommand(dispatcher, registryAccess)
        registerAiCommand(dispatcher)
        registerPrankCommand(dispatcher)
        registerAbilityCommand(dispatcher)
        registerEventCommand(dispatcher)
    }
    
    private fun registerTransformCommand(
        dispatcher: CommandDispatcher<CommandSourceStack>,
        registryAccess: net.minecraft.commands.CommandBuildContext
    ) {
        // /transform random|roulette|theme|info|variants|<entity> [variants...]
        dispatcher.register(
            Commands.literal("transform")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("random")
                    .executes { transformRandom(it) }
                )
                .then(Commands.literal("roulette")
                    .executes { transformRoulette(it) }
                )
                .then(Commands.literal("info")
                    .executes { transformInfo(it) }
                )
                .then(Commands.literal("variants")
                    .then(Commands.argument("entity", ResourceArgument.resource(registryAccess, Registries.ENTITY_TYPE))
                        .executes { transformListVariants(it) }
                    )
                )
                .then(Commands.literal("theme")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests { _, builder ->
                            FUN_TRANSFORM_THEMES.keys.sorted().forEach { builder.suggest(it) }
                            builder.buildFuture()
                        }
                        .executes { transformTheme(it) }
                    )
                )
                .then(Commands.argument("entity", ResourceArgument.resource(registryAccess, Registries.ENTITY_TYPE))
                    .executes { transformIntoEntity(it, emptyList()) }
                    // Greedy so any number of variants can be chained; the old form allowed two.
                    .then(Commands.argument("variants", StringArgumentType.greedyString())
                        .suggests(variantSuggester)
                        .executes {
                            transformIntoEntity(it, parseVariants(StringArgumentType.getString(it, "variants")))
                        }
                    )
                )
        )
        
        // /untransform - Exit spectator and kill the entity
        dispatcher.register(
            Commands.literal("untransform")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes { untransform(it) }
        )
    }

    private fun transformRandom(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.player as? ServerPlayer ?: run {
            ctx.source.sendFailure(Component.literal("§cOnly players can transform!"))
            return 0
        }
        val pool = buildFunTransformPool()
        val pick = pool.randomOrNull() ?: EntityTypes.ZOMBIE
        val ok = forcePlayerTransform(player, pick)
        if (!ok) {
            ctx.source.sendFailure(Component.literal("§cRandom transform failed."))
            return 0
        }
        player.sendSystemMessage(Component.literal("§d🎲 Random morph: §b${pick.description.string}"))
        return 1
    }

    private fun transformRoulette(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.player as? ServerPlayer ?: run {
            ctx.source.sendFailure(Component.literal("§cOnly players can transform!"))
            return 0
        }
        val roulettePool = buildFunTransformPool().shuffled().take(5)
        if (roulettePool.isEmpty()) {
            ctx.source.sendFailure(Component.literal("§cNo valid roulette forms found."))
            return 0
        }

        var success = false
        roulettePool.forEachIndexed { index, type ->
            if (index == roulettePool.lastIndex) {
                success = forcePlayerTransform(player, type)
            }
            player.sendSystemMessage(Component.literal("§7🎰 ${index + 1}/5 -> ${type.description.string}"))
        }
        if (!success) {
            ctx.source.sendFailure(Component.literal("§cRoulette transform failed."))
            return 0
        }
        player.sendSystemMessage(Component.literal("§6🎉 Jackpot form locked in!"))
        return 1
    }

    private fun transformTheme(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.player as? ServerPlayer ?: run {
            ctx.source.sendFailure(Component.literal("§cOnly players can transform!"))
            return 0
        }
        val theme = StringArgumentType.getString(ctx, "name").lowercase()
        val themePool = FUN_TRANSFORM_THEMES[theme]
        if (themePool == null || themePool.isEmpty()) {
            val options = FUN_TRANSFORM_THEMES.keys.sorted().joinToString(", ")
            ctx.source.sendFailure(Component.literal("§cUnknown theme. Try: $options"))
            return 0
        }

        val picked = themePool.random()
        val variants = if (theme == "cube" && BuiltInRegistries.ENTITY_TYPE.getKey(picked).path == "sulfur_cube") {
            listOf(listOf("bouncy"), listOf("sticky"), listOf("fast_sliding"), listOf("light")).random()
        } else {
            emptyList()
        }

        val ok = forcePlayerTransform(player, picked, variants)
        if (!ok) {
            ctx.source.sendFailure(Component.literal("§cTheme transform failed."))
            return 0
        }
        val suffix = if (variants.isEmpty()) "" else " §7(${variants.joinToString(", ")})"
        player.sendSystemMessage(Component.literal("§a✨ Theme [$theme] -> §b${picked.description.string}$suffix"))
        return 1
    }



    private fun actorWalkTo(ctx: CommandContext<CommandSourceStack>, speed: Double): Int {
        val targets = EntityArgument.getEntities(ctx, "targets")
        val pos = Vec3Argument.getVec3(ctx, "pos")
        var count = 0
        
        for (entity in targets) {
            if (entity is PathfinderMob) {
                // Clear other orders
                actorLookTargets.remove(entity.id)
                actorAttackTargets.remove(entity.id)
                
                // Set new order
                actorWalkTargets[entity.id] = pos
                
                // Initial move
                entity.navigation.moveTo(pos.x, pos.y, pos.z, speed)
                count++
            }
        }
        
        ctx.source.sendSuccess({ Component.literal("§aOrdered $count entities to walk to ${pos.x}, ${pos.y}, ${pos.z}") }, true)
        return count
    }

    private fun actorLookAt(ctx: CommandContext<CommandSourceStack>): Int {
        val targets = EntityArgument.getEntities(ctx, "targets")
        val target = EntityArgument.getEntity(ctx, "target")
        var count = 0
        
        for (entity in targets) {
            if (entity is Mob) {
                // Clear other orders
                actorWalkTargets.remove(entity.id)
                
                // Set new order
                actorLookTargets[entity.id] = target.id
                
                // Initial look
                entity.lookControl.setLookAt(target, 30.0f, 30.0f)
                count++
            }
        }
        
        ctx.source.sendSuccess({ Component.literal("§aOrdered $count entities to look at ${target.name.string}") }, true)
        return count
    }

    private fun actorAttack(ctx: CommandContext<CommandSourceStack>): Int {
        val targets = EntityArgument.getEntities(ctx, "targets")
        val target = EntityArgument.getEntity(ctx, "target")
        var count = 0
        
        if (target !is LivingEntity) {
            ctx.source.sendFailure(Component.literal("§cTarget must be a living entity!"))
            return 0
        }

        for (entity in targets) {
            if (entity is Mob) {
                // Clear other orders
                actorWalkTargets.remove(entity.id)
                
                // Set new order
                actorAttackTargets[entity.id] = target.id
                
                // Initial attack
                entity.target = target
                count++
            }
        }
        
        ctx.source.sendSuccess({ Component.literal("§aOrdered $count entities to attack ${target.name.string}") }, true)
        return count
    }

    private fun actorStop(ctx: CommandContext<CommandSourceStack>): Int {
        val targets = EntityArgument.getEntities(ctx, "targets")
        var count = 0
        
        for (entity in targets) {
            if (entity is Mob) {
                // Clear all orders
                actorWalkTargets.remove(entity.id)
                actorLookTargets.remove(entity.id)
                actorAttackTargets.remove(entity.id)
                
                entity.navigation.stop()
                entity.target = null
                count++
            }
        }
        
        ctx.source.sendSuccess({ Component.literal("§aStopped $count entities") }, true)
        return count
    }

    private fun actorIgnite(ctx: CommandContext<CommandSourceStack>): Int {
        val targets = EntityArgument.getEntities(ctx, "targets")
        var count = 0
        
        for (entity in targets) {
            if (entity is Creeper) {
                entity.ignite()
                count++
            }
        }
        
        if (count == 0) {
             ctx.source.sendFailure(Component.literal("§cNo creepers found in selection!"))
             return 0
        }
        
        ctx.source.sendSuccess({ Component.literal("§aIgnited $count creepers") }, true)
        return count
    }

    private fun registerAiCommand(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("ai")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("chaotic")
                    .then(Commands.argument("targets", EntityArgument.entities())
                        .executes { makeChaotic(it) }
                    )
                    .then(Commands.literal("stop")
                        .executes { stopChaotic(it) }
                    )
                )
                .then(Commands.literal("director")
                    .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.literal("walk_to")
                            .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes { actorWalkTo(it, 1.0) }
                                .then(Commands.argument("speed", DoubleArgumentType.doubleArg(0.1, 5.0))
                                    .executes { actorWalkTo(it, DoubleArgumentType.getDouble(it, "speed")) }
                                )
                            )
                        )
                        .then(Commands.literal("look_at")
                            .then(Commands.argument("target", EntityArgument.entity())
                                .executes { actorLookAt(it) }
                            )
                        )
                        .then(Commands.literal("attack")
                            .then(Commands.argument("target", EntityArgument.entity())
                                .executes { actorAttack(it) }
                            )
                        )
                        .then(Commands.literal("stop")
                            .executes { actorStop(it) }
                        )
                        .then(Commands.literal("ignite")
                            .executes { actorIgnite(it) }
                        )
                    )
                )
        )
    }
    
    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("aeonis")
                .then(Commands.literal("help").executes { showHelp(it) })
                .then(Commands.literal("features")
                    .then(Commands.literal("extra_mobs")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes { showExtraMobsStatus(it) }
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                            .executes { setExtraMobs(it) }))
                    .then(Commands.literal("safe_chest_physics")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes { showSafeChestPhysicsStatus(it) }
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                            .executes { setSafeChestPhysics(it) }))
                    .then(Commands.literal("safe_chest_fall_hurt")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes { showSafeChestFallHurtStatus(it) }
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                            .executes { setSafeChestFallHurt(it) })))
                .then(Commands.literal("summon_herobrine")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes { summonHerobrine(it, "behind") }
                    .then(Commands.literal("behind").executes { summonHerobrine(it, "behind") })
                    .then(Commands.literal("roaming").executes { summonHerobrine(it, "roaming") })
                    .then(Commands.literal("staring").executes { summonHerobrine(it, "staring") })
                    .then(Commands.literal("hunting").executes { summonHerobrine(it, "hunting") }))
                .then(Commands.literal("sys")
                    .then(Commands.literal("ping")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes { systemPing(it) })
                    .then(Commands.literal("story")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes { tellStory(it) }))
                .then(Commands.literal("menu").executes { showMenuHint(it) })
                .then(Commands.literal("diagnostics").executes { diagnostics(it) })
                .then(Commands.literal("reload")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes { reloadMod(it) })
                .then(Commands.literal("soul")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes { enterSoulMode(it) })
                .then(Commands.literal("unsoul")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes { exitSoulMode(it) })
        )
    }

    private fun registerPrankCommand(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("prank")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("smite")
                    .then(Commands.argument("targets", EntityArgument.players())
                        .executes { smitePlayer(it) }))
                .then(Commands.literal("yeet")
                    .then(Commands.argument("targets", EntityArgument.players())
                        .executes { yeetPlayer(it) }))
                .then(Commands.literal("disco")
                    .then(Commands.argument("targets", EntityArgument.players())
                        .executes { discoMode(it) }))
                .then(Commands.literal("supersize")
                    .then(Commands.argument("targets", EntityArgument.players())
                        .executes { supersizePlayer(it) }))
                .then(Commands.literal("smol")
                    .then(Commands.argument("targets", EntityArgument.players())
                        .executes { shrinkPlayer(it) }))
                .then(Commands.literal("chaos")
                    .then(Commands.argument("targets", EntityArgument.players())
                        .executes { chaosMode(it) }))
                .then(Commands.literal("rocket")
                    .then(Commands.argument("targets", EntityArgument.players())
                        .executes { rocketPlayer(it) }))
                .then(Commands.literal("spin")
                    .then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("times", IntegerArgumentType.integer(1, 50))
                            .executes { spinPlayer(it) })))
                .then(Commands.literal("freeze")
                    .then(Commands.argument("targets", EntityArgument.players())
                        .executes { freezePlayer(it) }))
                .then(Commands.literal("burn")
                    .then(Commands.argument("targets", EntityArgument.players())
                        .executes { burnPlayer(it) }))
                .then(Commands.literal("drunk")
                    .then(Commands.argument("targets", EntityArgument.players())
                        .executes { drunkVision(it) }))
                .then(Commands.literal("boing")
                    .then(Commands.argument("targets", EntityArgument.players())
                        .executes { boingPlayers(it) }))
                .then(Commands.literal("confetti")
                    .then(Commands.argument("targets", EntityArgument.players())
                        .executes { confettiPlayers(it) }))
                .then(Commands.literal("swap")
                    .then(Commands.argument("player_a", EntityArgument.player())
                        .then(Commands.argument("player_b", EntityArgument.player())
                            .executes { swapPlayers(it) })))
                .then(Commands.literal("morph")
                    .then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.literal("chicken").executes { prankMorphPlayers(it, EntityTypes.CHICKEN) })
                        .then(Commands.literal("pig").executes { prankMorphPlayers(it, EntityTypes.PIG) })
                        .then(Commands.literal("goat").executes { prankMorphPlayers(it, EntityTypes.GOAT) })
                        .then(Commands.literal("frog").executes { prankMorphPlayers(it, EntityTypes.FROG) })
                        .then(Commands.literal("parrot").executes { prankMorphPlayers(it, EntityTypes.PARROT) })
                        .then(Commands.literal("random_funny").executes { prankMorphPlayersRandom(it) })))
        )
    }

    private fun registerAbilityCommand(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("ability")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("mimic")
                    .then(Commands.literal("zombie").executes { mimicSound(it, "zombie") })
                    .then(Commands.literal("wither").executes { mimicSound(it, "wither") })
                    .then(Commands.literal("ghast").executes { mimicSound(it, "ghast") })
                    .then(Commands.literal("dragon").executes { mimicSound(it, "dragon") }))
                .then(Commands.literal("dash")
                    .executes { dash(it) })
                .then(Commands.literal("blink")
                    .then(Commands.argument("range", IntegerArgumentType.integer(1, 25))
                        .executes { blink(it) }))
                .then(Commands.literal("jump")
                    .executes { moonJump(it) })
                .then(Commands.literal("roar")
                    .executes { wardenRoar(it) })
                .then(Commands.literal("darkness")
                    .executes { darknessPulse(it) })
                .then(Commands.literal("summon")
                    .then(Commands.literal("vex").executes { petVex(it) })
                    .then(Commands.literal("wolves")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 5))
                            .executes { spiritWolves(it) })))
        )
    }

    private fun registerEventCommand(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("event")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("ambush")
                    .executes { ambushMode(it) })
                .then(Commands.literal("scan")
                    .executes { detectHostiles(it) })
                .then(Commands.literal("thunder")
                    .executes { thunderDome(it) })
                .then(Commands.literal("copper")
                    .executes { copperDrop(it) })
                .then(Commands.literal("time")
                    .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 24000))
                        .executes { timeWarp(it) }))
                .then(Commands.literal("cleanse")
                    .then(Commands.argument("targets", EntityArgument.players())
                        .executes { clearEffects(it) }))
                .then(Commands.literal("crit_save")
                    .executes { critSave(it) })
                .then(Commands.literal("pro_gamer")
                    .executes { proGamerMode(it) })
                .then(Commands.literal("exitbody")
                    .then(Commands.literal("s").executes { exitBody(it, GameType.SURVIVAL) })
                    .then(Commands.literal("c").executes { exitBody(it, GameType.CREATIVE) })
                )
        )
    }
    
    private fun showHelp(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        source.sendSuccess({ Component.literal("§6=== Aeonis Plus Commands ===") }, false)

        // Main Features
        source.sendSuccess({ Component.literal("§2=== Main Features ===") }, false)
        source.sendSuccess({ Component.literal("§a/aeonis features extra_mobs <true/false>§7 - Toggle Aeonis mobs (Stalker & Herobrine)") }, false)
        source.sendSuccess({ Component.literal("§a/aeonis features safe_chest_physics <true/false>§7 - Toggle Safe Chest falling behavior") }, false)
        source.sendSuccess({ Component.literal("§a/aeonis features safe_chest_fall_hurt <true/false>§7 - Toggle Safe Chest fall damage") }, false)
        source.sendSuccess({ Component.literal("§a/aeonis summon_herobrine [behind|roaming|staring|hunting]§7 - Summon Herobrine") }, false)
        source.sendSuccess({ Component.literal("§a/transform <entity> [variant...]§7 - Become any mob! Tab-completes variants.") }, false)
        source.sendSuccess({ Component.literal("§a/transform variants <entity>§7 - List the variants that mob supports") }, false)
        source.sendSuccess({ Component.literal("§a/transform info§7 - Show your current form") }, false)
        source.sendSuccess({ Component.literal("§a/transform random|roulette|theme <name>§7 - Randomised transforms") }, false)
        source.sendSuccess({ Component.literal("§a/untransform§7 - Return to normal") }, false)
        source.sendSuccess({ Component.literal("§a/aeonis soul§7 - Enter soul mode (spectator + possess existing mobs with P key)") }, false)
        source.sendSuccess({ Component.literal("§a/aeonis unsoul§7 - Exit soul mode") }, false)

        // AI Tools
        source.sendSuccess({ Component.literal("§5=== AI Tools (/ai) ===") }, false)
        source.sendSuccess({ Component.literal("§d/ai chaotic <entities>§7 - Enable chaotic AI on entities") }, false)
        source.sendSuccess({ Component.literal("§d/ai director <entities> walk_to <x y z> [speed]§7 - Walk entities to position") }, false)
        source.sendSuccess({ Component.literal("§d/ai director <entities> look_at <target>§7 - Make entities look at a target") }, false)
        source.sendSuccess({ Component.literal("§d/ai director <entities> attack <target>§7 - Make entities attack a target") }, false)
        source.sendSuccess({ Component.literal("§d/ai director <entities> stop§7 - Stop all orders for entities") }, false)

        // Pranks
        source.sendSuccess({ Component.literal("§e=== Pranks (/prank) ===") }, false)
        source.sendSuccess({ Component.literal("§e/prank smite <players>§7 - Strike with lightning") }, false)
        source.sendSuccess({ Component.literal("§e/prank yeet <players>§7 - Launch into air") }, false)
        source.sendSuccess({ Component.literal("§e/prank disco <players>§7 - Disco mode") }, false)
        source.sendSuccess({ Component.literal("§e/prank supersize <players>§7 - Make huge") }, false)
        source.sendSuccess({ Component.literal("§e/prank smol <players>§7 - Make tiny") }, false)
        source.sendSuccess({ Component.literal("§e/prank chaos <players>§7 - Chaotic effects") }, false)
        source.sendSuccess({ Component.literal("§e/prank rocket <players>§7 - Rocket launch") }, false)
        source.sendSuccess({ Component.literal("§e/prank spin <players> <times>§7 - Spin players") }, false)
        source.sendSuccess({ Component.literal("§e/prank freeze <players>§7 - Freeze in place") }, false)
        source.sendSuccess({ Component.literal("§e/prank burn <players>§7 - Set on fire") }, false)
        source.sendSuccess({ Component.literal("§e/prank drunk <players>§7 - Drunk vision") }, false)
        source.sendSuccess({ Component.literal("§e/prank boing <players>§7 - Super bounce prank") }, false)
        source.sendSuccess({ Component.literal("§e/prank confetti <players>§7 - Firework confetti burst") }, false)
        source.sendSuccess({ Component.literal("§e/prank swap <player_a> <player_b>§7 - Swap two players instantly") }, false)
        source.sendSuccess({ Component.literal("§e/prank morph <players> <chicken|pig|goat|frog|parrot|random_funny>§7 - Transform for 2 minutes") }, false)

        // Abilities
        source.sendSuccess({ Component.literal("§b=== Abilities (/ability) ===") }, false)
        source.sendSuccess({ Component.literal("§b/ability mimic <zombie|wither|ghast|dragon>§7 - Mimic mob sounds") }, false)
        source.sendSuccess({ Component.literal("§b/ability dash§7 - Quick dash forward") }, false)
        source.sendSuccess({ Component.literal("§b/ability blink <range>§7 - Teleport a short distance") }, false)
        source.sendSuccess({ Component.literal("§b/ability jump§7 - Moon jump") }, false)
        source.sendSuccess({ Component.literal("§b/ability roar§7 - Warden roar") }, false)
        source.sendSuccess({ Component.literal("§b/ability darkness§7 - Darkness pulse") }, false)
        source.sendSuccess({ Component.literal("§b/ability summon vex§7 - Summon pet vex") }, false)
        source.sendSuccess({ Component.literal("§b/ability summon wolves <count>§7 - Summon spirit wolves") }, false)

        // Event Tools
        source.sendSuccess({ Component.literal("§c=== Event Tools (/event) ===") }, false)
        source.sendSuccess({ Component.literal("§c/event ambush§7 - Surprise mob ambush") }, false)
        source.sendSuccess({ Component.literal("§c/event scan§7 - Scan for hostiles nearby") }, false)
        source.sendSuccess({ Component.literal("§c/event thunder§7 - Start thunder dome event") }, false)
        source.sendSuccess({ Component.literal("§c/event copper§7 - Drop copper on players") }, false)
        source.sendSuccess({ Component.literal("§c/event time <ticks>§7 - Warp time forward") }, false)
        source.sendSuccess({ Component.literal("§c/event cleanse <players>§7 - Remove all effects") }, false)
        source.sendSuccess({ Component.literal("§c/event crit_save§7 - Critical save (revive)") }, false)
        source.sendSuccess({ Component.literal("§c/event pro_gamer§7 - Pro gamer mode") }, false)
        source.sendSuccess({ Component.literal("§c/event exitbody <s|c>§7 - Exit body (Survival/Creative)") }, false)

        // System
        source.sendSuccess({ Component.literal("§7=== System (/aeonis sys) ===") }, false)
        source.sendSuccess({ Component.literal("§7/aeonis sys ping§7 - Show server/mod stats") }, false)
        source.sendSuccess({ Component.literal("§7/aeonis sys story§7 - Aeonis story info") }, false)
        return 1
    }
    
    // ========== FEATURES ==========
    
    private fun showExtraMobsStatus(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val server = source.server
        val enabled = AeonisFeatures.isExtraMobsEnabled(server)
        val status = if (enabled) "§aENABLED" else "§cDISABLED"
        source.sendSuccess({ Component.literal("§6Aeonis Extra Mobs: $status") }, false)
        return 1
    }
    
    private fun setExtraMobs(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val server = source.server
        val enabled = BoolArgumentType.getBool(ctx, "enabled")
        
        AeonisFeatures.setExtraMobsEnabled(server, enabled)
        
        if (enabled) {
            source.sendSuccess({ Component.literal("§a§l✓ Aeonis Extra Mobs ENABLED!") }, true)
            source.sendSuccess({ Component.literal("§7Stalkers will now spawn at night in the Overworld.") }, false)
            source.sendSuccess({ Component.literal("§7Herobrine may appear behind you... be careful.") }, false)
        } else {
            source.sendSuccess({ Component.literal("§c§l✗ Aeonis Extra Mobs DISABLED!") }, true)
            source.sendSuccess({ Component.literal("§7Stalkers will no longer spawn naturally.") }, false)
            source.sendSuccess({ Component.literal("§7Herobrine will no longer appear.") }, false)
        }
        
        return 1
    }

    private fun showSafeChestPhysicsStatus(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val server = source.server
        val enabled = AeonisFeatures.isSafeChestPhysicsEnabled(server)
        val status = if (enabled) "§aENABLED" else "§cDISABLED"
        source.sendSuccess({ Component.literal("§6Safe Chest Physics: $status") }, false)
        return 1
    }

    private fun setSafeChestPhysics(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val server = source.server
        val enabled = BoolArgumentType.getBool(ctx, "enabled")

        AeonisFeatures.setSafeChestPhysicsEnabled(server, enabled)

        if (enabled) {
            source.sendSuccess({ Component.literal("§a§lSafe Chest physics ENABLED!") }, true)
        } else {
            source.sendSuccess({ Component.literal("§c§lSafe Chest physics DISABLED!") }, true)
        }

        return 1
    }

    private fun showSafeChestFallHurtStatus(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val server = source.server
        val enabled = AeonisFeatures.doesSafeChestFallHurtEntities(server)
        val status = if (enabled) "§aENABLED" else "§cDISABLED"
        source.sendSuccess({ Component.literal("§6Safe Chest Fall Damage: $status") }, false)
        return 1
    }

    private fun setSafeChestFallHurt(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val server = source.server
        val enabled = BoolArgumentType.getBool(ctx, "enabled")

        AeonisFeatures.setSafeChestFallHurtsEntities(server, enabled)

        if (enabled) {
            source.sendSuccess({ Component.literal("§a§lSafe Chest fall damage ENABLED!") }, true)
        } else {
            source.sendSuccess({ Component.literal("§c§lSafe Chest fall damage DISABLED!") }, true)
        }

        return 1
    }
    
    private fun summonHerobrine(ctx: CommandContext<CommandSourceStack>, mode: String): Int {
        val source = ctx.source
        val player = source.player as? ServerPlayer ?: run {
            source.sendFailure(Component.literal("§cOnly players can summon Herobrine!"))
            return 0
        }
        
        val state = when (mode) {
            "behind" -> HerobrineEntity.HerobrineState.WATCHING_BEHIND
            "roaming" -> HerobrineEntity.HerobrineState.ROAMING
            "staring" -> HerobrineEntity.HerobrineState.STARING
            "hunting" -> HerobrineEntity.HerobrineState.HUNTING_ANIMALS
            else -> HerobrineEntity.HerobrineState.WATCHING_BEHIND
        }
        
        val success = HerobrineSpawner.forceSpawn(player, state)
        
        if (success) {
            source.sendSuccess({ Component.literal("§c§lHerobrine has been summoned... ($mode mode)") }, true)
        } else {
            source.sendFailure(Component.literal("§cFailed to summon Herobrine. Make sure you're in the Overworld."))
        }
        
        return if (success) 1 else 0
    }
    
    // ========== ENTITY TRANSFORM ==========

    /**
     * `/transform <entity> [variants...]`
     *
     * All validation and state handling lives in [TransformService]. This only turns the command
     * context into a request and the result back into chat feedback, so the command path and the
     * programmatic paths (random, themes, pranks, possession) cannot drift apart.
     */
    private fun transformIntoEntity(ctx: CommandContext<CommandSourceStack>, variants: List<String>): Int {
        val source = ctx.source
        val player = source.player as? ServerPlayer ?: run {
            source.sendFailure(Component.literal("§cOnly players can transform!"))
            return 0
        }
        val entityType = ResourceArgument.getSummonableEntityType(ctx, "entity").value() as? EntityType<*>
        if (entityType == null) {
            source.sendFailure(Component.literal("§cInvalid entity type!"))
            return 0
        }

        return when (val result = TransformService.transform(player, entityType, variants)) {
            is TransformResult.Success -> {
                announceTransform(player, result)
                1
            }
            is TransformResult.Rejected -> {
                source.sendFailure(Component.literal("§c${result.reason}"))
                if (result.herobrineScare) {
                    (player.level() as? ServerLevel)?.let { triggerHerobrineScareSequence(player, it) }
                }
                0
            }
            is TransformResult.Failed -> {
                source.sendFailure(Component.literal("§c${result.reason}"))
                0
            }
        }
    }

    /**
     * Sound, particles and the success message. Played only once the transform really happened,
     * so an invalid request no longer produces a celebratory sound effect.
     */
    private fun announceTransform(player: ServerPlayer, result: TransformResult.Success) {
        val level = player.level()
        val sounds = listOf(
            SoundEvents.PLAYER_LEVELUP,
            SoundEvents.FIREWORK_ROCKET_LARGE_BLAST,
            SoundEvents.AMETHYST_CLUSTER_BREAK,
            SoundEvents.GOAT_SCREAMING_AMBIENT
        )
        level.playSound(null, player.blockPosition(), sounds.random(), SoundSource.PLAYERS, 1.2f, 1.0f)
        val particles = listOf(ParticleTypes.HAPPY_VILLAGER, ParticleTypes.EXPLOSION, ParticleTypes.PORTAL)
        level.sendParticles(particles.random(), player.x, player.y + 1.0, player.z, 32, 0.5, 1.0, 0.5, 0.1)

        val entityName = result.mob.type.description.string
        val suffix = if (result.report.applied.isEmpty()) "" else " §7(${result.report.appliedSummary()})§a"
        player.sendSystemMessage(
            Component.literal("§aYou transformed into a §b$entityName§a$suffix. Use WASD to move and SPACE to jump; §e/untransform§a to return.")
        )
        result.loadout?.let { kit ->
            player.sendSystemMessage(Component.literal("§7[Aeonis] §aStarting kit: §e$kit"))
        }
        result.report.problems()?.let { note ->
            player.sendSystemMessage(Component.literal("§eVariant note: §7$note"))
        }
    }

    /** `/transform info` - describes the caller's current form. */
    private fun transformInfo(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val player = source.player as? ServerPlayer ?: run {
            source.sendFailure(Component.literal("§cOnly players can check a transform!"))
            return 0
        }
        val description = TransformService.sessionDescription(player)
        if (description == null) {
            source.sendSuccess({ Component.literal("§7You are not transformed.") }, false)
            return 1
        }
        val name = TransformService.entityNameFor(player) ?: "unknown"
        source.sendSuccess({ Component.literal("§6Current form: §b$name §7($description)") }, false)
        return 1
    }

    /**
     * `/transform variants <entity>` - lists every variant that mob accepts, sourced from the
     * same registry the transform itself uses, so the list can never go stale.
     */
    private fun transformListVariants(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val level = source.level as? ServerLevel ?: run {
            source.sendFailure(Component.literal("§cThis command needs a server world."))
            return 0
        }
        val entityType = ResourceArgument.getSummonableEntityType(ctx, "entity").value() as? EntityType<*>
        if (entityType == null) {
            source.sendFailure(Component.literal("§cInvalid entity type!"))
            return 0
        }
        val name = entityType.description.string
        val variants = TransformService.variantsFor(level, entityType)
        if (variants.isEmpty()) {
            source.sendFailure(Component.literal("§c$name is not a mob, so it cannot be possessed."))
            return 0
        }
        source.sendSuccess({ Component.literal("§6Variants for §b$name§6 (${variants.size}):") }, false)
        source.sendSuccess({ Component.literal("§e${variants.joinToString(", ") { it.id }}") }, false)
        source.sendSuccess({ Component.literal("§7Usage: §a/transform $name <variant> [variant...]") }, false)
        return 1
    }

    /** Accepts variants separated by spaces or commas so both styles work. */
    private fun parseVariants(raw: String): List<String> =
        raw.split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Tab completion for variants. The argument is greedy, so the token under the cursor is
     * resolved and only that token is rewritten; tokens already used are filtered out.
     */
    private val variantSuggester =
        com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> { ctx, builder ->
            val offsetInInput = builder.start
            val text = builder.input.substring(offsetInInput)
            val lastBreak = text.lastIndexOfAny(charArrayOf(' ', ','))
            val prefix = if (lastBreak >= 0) text.substring(lastBreak + 1) else text
            val tokenStart = offsetInInput + (if (lastBreak >= 0) lastBreak + 1 else 0)
            val alreadyUsed = parseVariants(if (lastBreak >= 0) text.substring(0, lastBreak) else "")
                .map { it.lowercase() }
                .toSet()

            val level = ctx.source.level
            val entityType = try {
                ResourceArgument.getSummonableEntityType(ctx, "entity").value() as? EntityType<*>
            } catch (_: Exception) {
                null
            }
            val ids = if (entityType != null && level is ServerLevel) {
                TransformService.variantsFor(level, entityType).map { it.id }
            } else {
                MobVariants.allIds()
            }

            val target = builder.createOffset(tokenStart)
            ids.asSequence()
                .filter { it.startsWith(prefix, ignoreCase = true) && it.lowercase() !in alreadyUsed }
                .sorted()
                .take(40)
                .forEach { target.suggest(it) }
            target.buildFuture()
        }

    private fun untransform(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val player = source.player as? ServerPlayer ?: run {
            source.sendFailure(Component.literal("§cOnly players can untransform!"))
            return 0
        }

        if (TransformService.untransform(player, showMessage = true)) {
            return 1
        }

        // Legacy body-swap path; AeonisPossession owns its own lifecycle.
        if (com.qc.aeonis.AeonisPossession.isActivelyPossessing(player.uuid)) {
            com.qc.aeonis.AeonisPossession.handleRelease(player, player.x, player.y, player.z)
            return 1
        }

        source.sendFailure(Component.literal("§cYou're not transformed!"))
        return 0
    }

    // ========== SOUL MODE ==========

    /**
     * Soul mode - a spectator state where the player can possess an existing mob.
     *
     * Its restore snapshot is held by [SoulMode] rather than sharing the transform gamemode map,
     * which is what previously let a transform restore the wrong mode after a possession.
     */
    private fun enterSoulMode(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val player = source.player as? ServerPlayer ?: run {
            source.sendFailure(Component.literal("§cOnly players can use soul mode!"))
            return 0
        }

        if (TransformService.isTransformed(player)) {
            source.sendFailure(Component.literal("§cYou're already controlling a mob! Use /untransform first."))
            return 0
        }

        if (!SoulMode.enter(player)) {
            source.sendFailure(Component.literal("§cYou're already in soul mode! Use /aeonis unsoul to exit."))
            return 0
        }

        player.setGameMode(GameType.SPECTATOR)
        AeonisNetwork.sendSoulModeStatus(player, true)
        source.sendSuccess({
            Component.literal("§bSoul Mode activated! §7Look at any mob and press §eP§7 to possess it. Use §e/aeonis unsoul§7 to exit.")
        }, false)
        return 1
    }

    /** Exit soul mode - return to normal. */
    private fun exitSoulMode(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val player = source.player as? ServerPlayer ?: run {
            source.sendFailure(Component.literal("§cOnly players can exit soul mode!"))
            return 0
        }

        val restore = SoulMode.leave(player.uuid) ?: run {
            source.sendFailure(Component.literal("§cYou're not in soul mode!"))
            return 0
        }

        restore.applyTo(player)
        AeonisNetwork.sendSoulModeStatus(player, false)
        source.sendSuccess({ Component.literal("§aSoul mode deactivated!") }, false)
        return 1
    }

    /** Check if player is in soul mode. */
    fun isInSoulMode(playerUuid: java.util.UUID): Boolean = SoulMode.isActive(playerUuid)

    /**
     * Called when a player possesses an existing mob from soul mode. Delegated to the service so
     * the resulting session behaves exactly like a spawned-body transform.
     */
    fun possessExistingMob(player: ServerPlayer, mob: Mob) {
        when (val result = TransformService.possess(player, mob)) {
            is TransformResult.Success -> {
                val entityName = mob.type.description.string
                player.sendSystemMessage(
                    Component.literal("§aYou possessed a §b$entityName§a! Use WASD to move, §e/untransform§a to release.")
                )
                result.loadout?.let { kit ->
                    player.sendSystemMessage(Component.literal("§7[Aeonis] §aStarting kit: §e$kit"))
                }
            }
            is TransformResult.Rejected -> player.sendSystemMessage(Component.literal("§c${result.reason}"))
            is TransformResult.Failed -> player.sendSystemMessage(Component.literal("§c${result.reason}"))
        }
    }
    private fun smitePlayer(ctx: CommandContext<CommandSourceStack>): Int {
        val targets = EntityArgument.getPlayers(ctx, "targets")
        var count = 0
        
        for (target in targets) {
            val level = target.level()
            
            val lightning = LightningBolt(EntityTypes.LIGHTNING_BOLT, level)
            lightning.setPos(target.x, target.y, target.z)
            lightning.setVisualOnly(false)
            level.addFreshEntity(lightning)
            count++
        }
        
        ctx.source.sendSuccess({ Component.literal("§c⚡ Smitten $count player(s)!") }, true)
        return count
    }
    
    private fun yeetPlayer(ctx: CommandContext<CommandSourceStack>): Int {
        val targets = EntityArgument.getPlayers(ctx, "targets")
        var count = 0
        
        for (target in targets) {
            target.setDeltaMovement(target.deltaMovement.add(0.0, 3.0, 0.0))
            target.syncVelocity = true
            
            val level = target.level() as? net.minecraft.server.level.ServerLevel
            level?.playSound(null, target.x, target.y, target.z, 
                SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.0f, 1.0f)
            count++
        }
        
        ctx.source.sendSuccess({ Component.literal("§b🚀 YEETED $count player(s)!") }, true)
        return count
    }
    
    private fun discoMode(ctx: CommandContext<CommandSourceStack>): Int {
        val targets = EntityArgument.getPlayers(ctx, "targets")
        var count = 0
        
        for (target in targets) {
            val level = target.level()
            
            // Give glowing, speed, and jump boost
            target.addEffect(MobEffectInstance(MobEffects.GLOWING, 200, 0))
            target.addEffect(MobEffectInstance(MobEffects.SPEED, 200, 2))
            target.addEffect(MobEffectInstance(MobEffects.JUMP_BOOST, 200, 1))
            
            // Spawn colorful particles
            repeat(50) {
                level.sendParticles(ParticleTypes.NOTE,
                    target.x + (Math.random() - 0.5) * 2,
                    target.y + Math.random() * 2,
                    target.z + (Math.random() - 0.5) * 2,
                    1, 0.0, 0.0, 0.0, 0.0)
            }
            
            level.playSound(null, target.x, target.y, target.z,
                SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0f, 1.0f)
            count++
        }
        
        ctx.source.sendSuccess({ Component.literal("§d🎵 DISCO MODE for $count player(s)!") }, true)
        return count
    }
    
    private fun supersizePlayer(ctx: CommandContext<CommandSourceStack>): Int {
        val targets = EntityArgument.getPlayers(ctx, "targets")
        var count = 0
        
        for (target in targets) {
            // Slowness + resistance + strength to simulate being big
            target.addEffect(MobEffectInstance(MobEffects.SLOWNESS, 400, 1))
            target.addEffect(MobEffectInstance(MobEffects.RESISTANCE, 400, 1))
            target.addEffect(MobEffectInstance(MobEffects.STRENGTH, 400, 2))
            target.addEffect(MobEffectInstance(MobEffects.HEALTH_BOOST, 400, 4))
            count++
        }
        
        ctx.source.sendSuccess({ Component.literal("§a🦖 SUPERSIZED $count player(s)!") }, true)
        return count
    }
    
    private fun shrinkPlayer(ctx: CommandContext<CommandSourceStack>): Int {
        val targets = EntityArgument.getPlayers(ctx, "targets")
        var count = 0
        
        for (target in targets) {
            // Speed + weakness + invisibility to simulate being small
            target.addEffect(MobEffectInstance(MobEffects.SPEED, 400, 2))
            target.addEffect(MobEffectInstance(MobEffects.WEAKNESS, 400, 1))
            target.addEffect(MobEffectInstance(MobEffects.INVISIBILITY, 400, 0))
            target.addEffect(MobEffectInstance(MobEffects.JUMP_BOOST, 400, 2))
            count++
        }
        
        ctx.source.sendSuccess({ Component.literal("§7🐜 Shrunk $count player(s)!") }, true)
        return count
    }
    
    private fun chaosMode(ctx: CommandContext<CommandSourceStack>): Int {
        val targets = EntityArgument.getPlayers(ctx, "targets")
        var count = 0
        
        for (target in targets) {
            val random = target.random
            
            val effects = listOf(
                MobEffects.LEVITATION,
                MobEffects.BLINDNESS,
                MobEffects.NAUSEA,
                MobEffects.SPEED,
                MobEffects.SLOWNESS,
                MobEffects.JUMP_BOOST,
                MobEffects.GLOWING,
                MobEffects.NIGHT_VISION,
                MobEffects.POISON,
                MobEffects.REGENERATION
            )
            
            repeat(3) {
                val effect = effects[random.nextInt(effects.size)]
                val amplifier = random.nextInt(3)
                target.addEffect(MobEffectInstance(effect, 200, amplifier))
            }
            count++
        }
        
        ctx.source.sendSuccess({ Component.literal("§c🎲 CHAOS unleashed on $count player(s)!") }, true)
        return count
    }
    
    private fun rocketPlayer(ctx: CommandContext<CommandSourceStack>): Int {
        val targets = EntityArgument.getPlayers(ctx, "targets")
        var count = 0
        
        for (target in targets) {
            val level = target.level()
            
            // Launch high with slow falling
            target.setDeltaMovement(Vec3(0.0, 5.0, 0.0))
            target.syncVelocity = true
            target.addEffect(MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0))
            
            // Particles and sound
            level.sendParticles(ParticleTypes.FLAME, target.x, target.y, target.z, 30, 0.3, 0.1, 0.3, 0.1)
            level.playSound(null, target.x, target.y, target.z,
                SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.0f, 0.5f)
            count++
        }
        
        ctx.source.sendSuccess({ Component.literal("§6🚀 LAUNCHED $count player(s)!") }, true)
        return count
    }
    
    private fun spinPlayer(ctx: CommandContext<CommandSourceStack>): Int {
        val targets = EntityArgument.getPlayers(ctx, "targets")
        val times = IntegerArgumentType.getInteger(ctx, "times")
        var count = 0
        
        for (target in targets) {
            // Apply nausea for spinning effect
            target.addEffect(MobEffectInstance(MobEffects.NAUSEA, times * 20, 0))
            
            // Rotate player rapidly
            val newYaw = target.yRot + (360f * times)
            target.setYRot(newYaw)
            count++
        }
        
        ctx.source.sendSuccess({ Component.literal("§e🌀 Spinning $count player(s) ${times} times!") }, true)
        return count
    }
    
    private fun freezePlayer(ctx: CommandContext<CommandSourceStack>): Int {
        val targets = EntityArgument.getPlayers(ctx, "targets")
        var count = 0
        
        for (target in targets) {
            val level = target.level()
            
            target.addEffect(MobEffectInstance(MobEffects.SLOWNESS, 100, 255))
            target.addEffect(MobEffectInstance(MobEffects.JUMP_BOOST, 100, 128))
            target.ticksFrozen = 300
            
            level.sendParticles(ParticleTypes.SNOWFLAKE, target.x, target.y + 1, target.z, 20, 0.5, 0.5, 0.5, 0.0)
            level.playSound(null, target.x, target.y, target.z,
                SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.0f, 1.5f)
            count++
        }
        
        ctx.source.sendSuccess({ Component.literal("§b❄ FROZE $count player(s)!") }, true)
        return count
    }
    
    private fun burnPlayer(ctx: CommandContext<CommandSourceStack>): Int {
        val targets = EntityArgument.getPlayers(ctx, "targets")
        var count = 0
        
        for (target in targets) {
            val level = target.level()
            
            target.remainingFireTicks = 100
            level.sendParticles(ParticleTypes.FLAME, target.x, target.y + 1, target.z, 30, 0.5, 0.5, 0.5, 0.05)
            count++
        }
        
        ctx.source.sendSuccess({ Component.literal("§c🔥 Set $count player(s) on FIRE!") }, true)
        return count
    }

    private fun boingPlayers(ctx: CommandContext<CommandSourceStack>): Int {
        val targets = EntityArgument.getPlayers(ctx, "targets")
        var count = 0

        for (target in targets) {
            val level = target.level()
            target.setDeltaMovement(target.deltaMovement.x, 1.15, target.deltaMovement.z)
            target.addEffect(MobEffectInstance(MobEffects.JUMP_BOOST, 200, 3))
            target.addEffect(MobEffectInstance(MobEffects.SLOW_FALLING, 120, 0))
            level.playSound(null, target.x, target.y, target.z, SoundEvents.SLIME_SQUISH, SoundSource.PLAYERS, 1.0f, 0.8f)
            level.sendParticles(ParticleTypes.ITEM_SLIME, target.x, target.y + 0.5, target.z, 20, 0.35, 0.25, 0.35, 0.02)
            count++
        }

        ctx.source.sendSuccess({ Component.literal("§a🟢 BOING on $count player(s)!") }, true)
        return count
    }

    private fun confettiPlayers(ctx: CommandContext<CommandSourceStack>): Int {
        val targets = EntityArgument.getPlayers(ctx, "targets")
        var count = 0

        for (target in targets) {
            val level = target.level()
            level.sendParticles(ParticleTypes.FIREWORK, target.x, target.y + 1.0, target.z, 80, 0.8, 0.8, 0.8, 0.05)
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, target.x, target.y + 1.0, target.z, 20, 0.5, 0.5, 0.5, 0.03)
            level.playSound(null, target.x, target.y, target.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 1.0f, 1.1f)
            count++
        }

        ctx.source.sendSuccess({ Component.literal("§d🎉 Confetti blast on $count player(s)!") }, true)
        return count
    }

    private fun swapPlayers(ctx: CommandContext<CommandSourceStack>): Int {
        val playerA = EntityArgument.getPlayer(ctx, "player_a")
        val playerB = EntityArgument.getPlayer(ctx, "player_b")
        if (playerA == playerB) {
            ctx.source.sendFailure(Component.literal("§cPick two different players for swap."))
            return 0
        }

        val aPos = playerA.position()
        val bPos = playerB.position()
        val aYaw = playerA.yRot
        val aPitch = playerA.xRot
        val bYaw = playerB.yRot
        val bPitch = playerB.xRot

        playerA.teleportTo(bPos.x, bPos.y, bPos.z)
        playerB.teleportTo(aPos.x, aPos.y, aPos.z)
        playerA.setYRot(bYaw)
        playerA.setXRot(bPitch)
        playerB.setYRot(aYaw)
        playerB.setXRot(aPitch)

        val level = playerA.level()
        level.playSound(null, playerA.x, playerA.y, playerA.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0f, 1.15f)
        level?.playSound(null, playerB.x, playerB.y, playerB.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0f, 1.15f)

        ctx.source.sendSuccess({ Component.literal("§b🌀 Swapped ${playerA.name.string} and ${playerB.name.string}!") }, true)
        return 1
    }

    private fun prankMorphPlayersRandom(ctx: CommandContext<CommandSourceStack>): Int {
        val funnyTypes = listOf(EntityTypes.CHICKEN, EntityTypes.PIG, EntityTypes.GOAT, EntityTypes.FROG, EntityTypes.PARROT)
        return prankMorphPlayers(ctx, funnyTypes.random())
    }

    private fun prankMorphPlayers(ctx: CommandContext<CommandSourceStack>, targetType: EntityType<*>): Int {
        val targets = EntityArgument.getPlayers(ctx, "targets")
        var count = 0

        for (target in targets) {
            if (forcePlayerTransform(target, targetType)) {
                prankMorphTimers[target.uuid] = PRANK_MORPH_DURATION_TICKS
                target.sendSystemMessage(Component.literal("§e🎭 You were prank-morphed into ${targetType.description.string} for 2 minutes!"))
                count++
            }
        }

        if (count == 0) {
            ctx.source.sendFailure(Component.literal("§cNo players could be morph-pranked."))
            return 0
        }

        ctx.source.sendSuccess({
            Component.literal("§6🎭 Morph-pranked $count player(s) into ${targetType.description.string} for 2 minutes!")
        }, true)
        return count
    }

    /**
     * Programmatic transform used by the random/theme/roulette pools and /prank morph.
     *
     * This used to synthesise a `transform ...` string and feed it back through the command
     * dispatcher, which made those commands depend on the command layer's parsing and
     * permissions. It now calls the service directly.
     */
    private fun forcePlayerTransform(target: ServerPlayer, entityType: EntityType<*>, variants: List<String> = emptyList()): Boolean =
        TransformService.replaceTransform(target, entityType, variants).isSuccess

    /**
     * A random handful of legal forms.
     *
     * The eligibility rules now live in [MobCatalogue], which the in-game form browser also
     * reads, so the two can no longer disagree about what a player is allowed to become.
     */
    private fun buildFunTransformPool(): List<EntityType<*>> =
        MobCatalogue.possessable().shuffled().take(40)

    private fun isCubeStyleMob(entity: Entity): Boolean = entity is Mob && MobKinds.isCubeStyle(entity)
    
    // ========== TRANSFORM ABILITIES ==========
    
    private fun wardenRoar(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val player = source.player ?: run {
            source.sendFailure(Component.literal("§cOnly players can use this!"))
            return 0
        }
        val level = player.level()
        
        // Play warden roar sound
        level.playSound(
            null, player.x, player.y, player.z,
            SoundEvents.WARDEN_ROAR, SoundSource.PLAYERS,
            3.0f, 0.8f
        )
        
        // Sonic boom particles in a ring
        val radius = 8.0
        for (i in 0 until 36) {
            val angle = Math.toRadians(i * 10.0)
            val px = player.x + Math.cos(angle) * radius
            val pz = player.z + Math.sin(angle) * radius
            level.sendParticles(
                ParticleTypes.SONIC_BOOM,
                px, player.y + 1.0, pz,
                1, 0.0, 0.0, 0.0, 0.0
            )
        }
        
        // Knockback all nearby players (within 10 blocks)
        val nearbyPlayers = level.getEntitiesOfClass(
            Player::class.java,
            AABB.ofSize(player.position(), 20.0, 10.0, 20.0)
        ) { it != player }
        
        for (target in nearbyPlayers) {
            val direction = target.position().subtract(player.position()).normalize()
            val knockback = direction.multiply(2.5, 1.0, 2.5).add(0.0, 0.8, 0.0)
            target.setDeltaMovement(knockback)
            target.syncVelocity = true
            
            // Apply brief weakness
            if (target is ServerPlayer) {
                target.addEffect(MobEffectInstance(MobEffects.WEAKNESS, 60, 1))
            }
        }
        
        val count = nearbyPlayers.size
        source.sendSuccess({ 
            Component.literal("§5🔊 You unleash a WARDEN ROAR! $count player(s) affected!") 
        }, true)
        return 1
    }
    
    private fun darknessPulse(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val player = source.player ?: run {
            source.sendFailure(Component.literal("§cOnly players can use this!"))
            return 0
        }
        val level = player.level()
        
        // Play warden heartbeat/darkness sounds
        level.playSound(
            null, player.x, player.y, player.z,
            SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS,
            2.0f, 0.5f
        )
        level.playSound(
            null, player.x, player.y, player.z,
            SoundEvents.WARDEN_TENDRIL_CLICKS, SoundSource.PLAYERS,
            1.5f, 0.7f
        )
        
        // Dark particles emanating outward
        for (i in 0 until 50) {
            val angle = Math.random() * Math.PI * 2
            val dist = Math.random() * 8.0
            val px = player.x + Math.cos(angle) * dist
            val pz = player.z + Math.sin(angle) * dist
            level.sendParticles(
                ParticleTypes.SCULK_SOUL,
                px, player.y + Math.random() * 2.0, pz,
                1, 0.2, 0.2, 0.2, 0.02
            )
        }
        
        // Apply darkness to all nearby players (within 15 blocks)
        val nearbyPlayers = level.getEntitiesOfClass(
            Player::class.java,
            AABB.ofSize(player.position(), 30.0, 15.0, 30.0)
        ) { it != player }
        
        for (target in nearbyPlayers) {
            if (target is ServerPlayer) {
                // Apply darkness effect (like warden)
                target.addEffect(MobEffectInstance(MobEffects.DARKNESS, 200, 0))
                // Also apply brief blindness for extra spook
                target.addEffect(MobEffectInstance(MobEffects.BLINDNESS, 40, 0))
            }
        }
        
        val count = nearbyPlayers.size
        source.sendSuccess({ 
            Component.literal("§8🌑 You release a DARKNESS PULSE! $count player(s) blinded!") 
        }, true)
        return 1
    }
    
    // ========== NEW COMMANDS ==========
    
    private fun mimicSound(ctx: CommandContext<CommandSourceStack>, mob: String): Int {
        val source = ctx.source
        val player = source.player ?: run {
            source.sendFailure(Component.literal("§cOnly players can use this!"))
            return 0
        }
        val level = player.level()
        
        val sound = when (mob) {
            "zombie" -> SoundEvents.ZOMBIE_AMBIENT
            "wither" -> SoundEvents.WITHER_AMBIENT
            "ghast" -> SoundEvents.GHAST_AMBIENT
            "dragon" -> SoundEvents.ENDER_DRAGON_GROWL
            else -> SoundEvents.ZOMBIE_AMBIENT
        }
        
        level.playSound(null, player.x, player.y, player.z, sound, SoundSource.HOSTILE, 2.0f, 1.0f)
        source.sendSuccess({ Component.literal("§a🔊 Mimicked $mob sound!") }, false)
        return 1
    }
    
    private fun ambushMode(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val player = source.player ?: run {
            source.sendFailure(Component.literal("§cOnly players can use this!"))
            return 0
        }
        val level = player.level()
        
        // Spawn 4 iron golems in a circle
        val radius = 3.0
        for (i in 0 until 4) {
            val angle = (i * 90.0) * (Math.PI / 180.0)
            val x = player.x + Math.cos(angle) * radius
            val z = player.z + Math.sin(angle) * radius
            
            val golem = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND)
            golem?.let {
                it.setPos(x, player.y, z)
                level.addFreshEntity(it)
            }
        }
        
        level.playSound(null, player.x, player.y, player.z, SoundEvents.IRON_GOLEM_REPAIR, SoundSource.NEUTRAL, 1.0f, 1.0f)
        source.sendSuccess({ Component.literal("§6⚔ AMBUSH MODE! 4 Iron Golems summoned to protect you!") }, true)
        return 1
    }
    
    private fun detectHostiles(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val player = source.player ?: run {
            source.sendFailure(Component.literal("§cOnly players can use this!"))
            return 0
        }
        val level = player.level()
        
        // Find all hostile mobs in 40-block radius
        val hostiles = level.getEntitiesOfClass(
            Monster::class.java,
            AABB.ofSize(player.position(), 80.0, 40.0, 80.0)
        )
        
        for (hostile in hostiles) {
            hostile.addEffect(MobEffectInstance(MobEffects.GLOWING, 200, 0)) // 10 seconds
        }
        
        source.sendSuccess({ Component.literal("§e👁 Detected ${hostiles.size} hostile mobs! They are now glowing.") }, true)
        return 1
    }
    
    private fun moonJump(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val player = source.player as? ServerPlayer ?: run {
            source.sendFailure(Component.literal("§cOnly players can use this!"))
            return 0
        }
        
        // Low gravity = high jump boost + slow falling
        player.addEffect(MobEffectInstance(MobEffects.JUMP_BOOST, 600, 4)) // 30 sec, level 5
        player.addEffect(MobEffectInstance(MobEffects.SLOW_FALLING, 600, 0)) // 30 sec
        
        source.sendSuccess({ Component.literal("§b🌙 MOON JUMP activated! Low gravity for 30 seconds.") }, true)
        return 1
    }
    
    // Track active thunder domes: player UUID -> remaining waves
    private val activeThunderDomes = mutableMapOf<java.util.UUID, Int>()
    
    /**
     * Called every tick to process thunder dome waves
     */
    fun tickThunderDomes(server: net.minecraft.server.MinecraftServer) {
        val iterator = activeThunderDomes.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val playerUUID = entry.key
            var wavesRemaining = entry.value
            
            val player = server.playerList.getPlayer(playerUUID)
            if (player == null || wavesRemaining <= 0) {
                iterator.remove()
                continue
            }
            
            val level = player.level()
            
            // Only spawn lightning every 20 ticks (1 second)
            if (server.tickCount % 20 == 0) {
                for (j in 0 until 8) {
                    val angle = Math.random() * Math.PI * 2
                    val dist = 5.0 + Math.random() * 25.0
                    val x = player.x + Math.cos(angle) * dist
                    val z = player.z + Math.sin(angle) * dist
                    
                    val lightning = LightningBolt(EntityTypes.LIGHTNING_BOLT, level)
                    lightning.setPos(x, player.y, z)
                    lightning.setVisualOnly(true) // Don't damage
                    level.addFreshEntity(lightning)
                }
                
                wavesRemaining--
                activeThunderDomes[playerUUID] = wavesRemaining
            }
        }
    }
    
    private fun thunderDome(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val player = source.player ?: run {
            source.sendFailure(Component.literal("§cOnly players can use this!"))
            return 0
        }
        
        // Start 15 waves of lightning
        activeThunderDomes[player.uuid] = 15
        
        source.sendSuccess({ Component.literal("§c⚡ THUNDER DOME activated! Lightning storm for 15 seconds!") }, true)
        return 1
    }
    
    private fun copperDrop(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val player = source.player ?: run {
            source.sendFailure(Component.literal("§cOnly players can use this!"))
            return 0
        }
        val level = player.level()
        
        // Rain copper ingots from above
        for (i in 0 until 32) {
            val x = player.x + (Math.random() - 0.5) * 10
            val y = player.y + 15 + Math.random() * 5
            val z = player.z + (Math.random() - 0.5) * 10
            
            val stack = ItemStack(Items.COPPER_INGOT, 1)
            val itemEntity = ItemEntity(level, x, y, z, stack)
            itemEntity.setDeltaMovement(0.0, -0.2, 0.0)
            level.addFreshEntity(itemEntity)
        }
        
        level.playSound(null, player.x, player.y, player.z, SoundEvents.COPPER_BREAK, SoundSource.BLOCKS, 1.0f, 1.0f)
        source.sendSuccess({ Component.literal("§6🥉 COPPER DROP! Copper is raining from the sky!") }, true)
        return 1
    }
    
    private fun timeWarp(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val ticks = IntegerArgumentType.getInteger(ctx, "ticks")
        source.sendFailure(Component.literal("§e⏰ Time warp is temporarily disabled in the 26.3 migration build."))
        return 0
    }
    
    private fun drunkVision(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val targets = EntityArgument.getPlayers(ctx, "targets")
        
        for (target in targets) {
            target.addEffect(MobEffectInstance(MobEffects.NAUSEA, 160, 0)) // 8 sec
            target.addEffect(MobEffectInstance(MobEffects.SLOWNESS, 160, 1)) // 8 sec
            target.addEffect(MobEffectInstance(MobEffects.BLINDNESS, 60, 0)) // 3 sec
        }
        
        source.sendSuccess({ Component.literal("§a🍺 DRUNK VISION applied to ${targets.size} player(s)!") }, true)
        return 1
    }
    
    private fun critSave(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val player = source.player as? ServerPlayer ?: run {
            source.sendFailure(Component.literal("§cOnly players can use this!"))
            return 0
        }
        
        if (player.health <= 10f) {
            player.addEffect(MobEffectInstance(MobEffects.ABSORPTION, 200, 3)) // 10 sec, 8 hearts
            player.addEffect(MobEffectInstance(MobEffects.REGENERATION, 100, 2)) // 5 sec, regen III
            player.addEffect(MobEffectInstance(MobEffects.RESISTANCE, 100, 1)) // 5 sec
            
            val level = player.level() as? net.minecraft.server.level.ServerLevel
            level?.playSound(null, player.x, player.y, player.z, SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0f, 1.0f)
            
            source.sendSuccess({ Component.literal("§c💔 CRIT SAVE! Emergency healing activated!") }, true)
        } else {
            source.sendFailure(Component.literal("§eYour HP is above 10, no crit save needed."))
        }
        return 1
    }
    
    private fun proGamerMode(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val player = source.player as? ServerPlayer ?: run {
            source.sendFailure(Component.literal("§cOnly players can use this!"))
            return 0
        }
        val level = source.level
        val server = source.server
        
        // Set difficulty to hard
        server.setDifficulty(Difficulty.HARD, true)
        
        // Time mutation is temporarily disabled in the 26.3 migration build.
        
        // Give speed II for 20 sec
        player.addEffect(MobEffectInstance(MobEffects.SPEED, 400, 1))
        player.addEffect(MobEffectInstance(MobEffects.NIGHT_VISION, 400, 0))
        
        source.sendSuccess({ Component.literal("§4🎮 PRO GAMER MODE! Difficulty: HARD, Time: NIGHT, Speed II active!") }, true)
        return 1
    }
    
    /**
     * Points the player at the in-game menu.
     *
     * The menu itself is a client screen, so the server cannot open it directly without a
     * dedicated packet; telling the player exactly where to click is honest and works on every
     * client, including vanilla ones connected to an Aeonis server.
     */
    private fun showMenuHint(ctx: CommandContext<CommandSourceStack>): Int {
        ctx.source.sendSuccess(
            {
                Component.literal("§e[AEONIS] §7The manual and settings are in ") .append(
                    Component.literal("§fOptions > Aeonis")
                ).append(Component.literal("§7, or Mod Menu > Aeonis. Everything in it is also documented in /aeonis help."))
            },
            false
        )
        return 1
    }

    /**
     * One block of state for a bug report: version, session count, and the caller's own form.
     *
     * Deliberately duplicates nothing from `/aeonis sys ping` (which reports performance); this
     * answers "what is Aeonis doing right now".
     */
    private fun diagnostics(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val player = source.player as? ServerPlayer
        val server = source.server

        val sessionCount = com.qc.aeonis.transform.TransformSessions.count()
        val form = player?.let { com.qc.aeonis.transform.TransformService.entityNameFor(it) } ?: "none"
        val movement = player?.let { p ->
            val entityId = com.qc.aeonis.network.AeonisNetwork.getControlledEntityId(p.uuid)
            val mob = entityId?.let { p.level().getEntity(it) } as? net.minecraft.world.entity.Mob
            mob?.let { com.qc.aeonis.transform.MobMovementProfiles.profileFor(it).describe() }
        } ?: "n/a"
        val abilities = player?.let { p ->
            val entityId = com.qc.aeonis.network.AeonisNetwork.getControlledEntityId(p.uuid)
            val mob = entityId?.let { p.level().getEntity(it) } as? net.minecraft.world.entity.Mob
            mob?.let { m -> com.qc.aeonis.transform.MobAbilities.forMob(m).size.toString() }
        } ?: "0"

        source.sendSuccess(
            {
                Component.literal("§6[AEONIS] §7diagnostics")
                    .append(Component.literal("\n§7  version: §f5.0 (Minecraft 26.3+)"))
                    .append(Component.literal("\n§7  credits: §fQuantumCreeper, MrMK (Aevora Labs)"))
                    .append(Component.literal("\n§7  license: §fGPL-3.0-or-later"))
                    .append(Component.literal("\n§7  active transform sessions: §f$sessionCount"))
                    .append(Component.literal("\n§7  your form: §f$form"))
                    .append(Component.literal("\n§7  movement: §f$movement"))
                    .append(Component.literal("\n§7  abilities available: §f$abilities"))
                    .append(Component.literal("\n§7  players online: §f${server.playerList.playerCount}"))
            },
            false
        )
        return 1
    }

    private fun systemPing(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val server = source.server
        val player = source.player as? ServerPlayer
        val level = source.level as? net.minecraft.server.level.ServerLevel
        
        // Calculate TPS from tick times
        val tickTimes = server.tickTimesNanos
        val avgTickTime = if (tickTimes.isNotEmpty()) tickTimes.average() else 0.0
        val mspt = avgTickTime / 1_000_000.0
        val tps = (1000.0 / mspt).coerceAtMost(20.0)
        val tpsColor = when {
            tps >= 19.0 -> "§a"
            tps >= 15.0 -> "§e"
            tps >= 10.0 -> "§6"
            else -> "§c"
        }
        
        // Memory info
        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val maxMem = runtime.maxMemory() / 1024 / 1024
        val memPercent = (usedMem * 100 / maxMem).toInt()
        val memColor = when {
            memPercent < 60 -> "§a"
            memPercent < 80 -> "§e"
            else -> "§c"
        }
        
        // Player ping (latency)
        val pingMs = player?.connection?.latency() ?: 0
        val pingColor = when {
            pingMs < 50 -> "§a"
            pingMs < 100 -> "§e"
            pingMs < 200 -> "§6"
            else -> "§c"
        }
        
        // World info
        val worldTime = level?.gameTime ?: 0L
        val dayCount = (worldTime / 24000L) + 1
        val timeOfDay = worldTime % 24000L
        val timeStr = when {
            timeOfDay < 6000 -> "§e☀ Morning"
            timeOfDay < 12000 -> "§6☀ Noon"
            timeOfDay < 13000 -> "§6☀ Afternoon"
            timeOfDay < 18000 -> "§c🌅 Evening"
            else -> "§8🌙 Night"
        }
        
        // Entity counts
        val entityCount = level?.allEntities?.count() ?: 0
        val loadedChunks = level?.chunkSource?.loadedChunksCount ?: 0
        
        // Player counts
        val onlinePlayers = server?.playerCount ?: 0
        val maxPlayers = server?.maxPlayers ?: 0
        
        // Difficulty
        val difficulty = server.worldData.difficulty.displayName.string
        
        // Active Aeonis systems
        val transformedCount = TransformSessions.count()
        val activeVexes = petVexOwners.size
        val activeThunder = activeThunderDomes.size
        val activeDirector = actorWalkTargets.size + actorLookTargets.size + actorAttackTargets.size
        
        source.sendSuccess({ Component.literal("§6§l═══════ AEONIS SYSTEM ═══════") }, false)
        source.sendSuccess({ Component.literal("§e§lMOD INFO") }, false)
        source.sendSuccess({ Component.literal("  §7ID: §bAeonis §7| §7Ver: §b2.1.0") }, false)
        source.sendSuccess({ Component.literal("  §7Extra Mobs: " + if (AeonisFeatures.isExtraMobsEnabled(server)) "§aON" else "§cOFF") }, false)
        
        source.sendSuccess({ Component.literal("§c§l⚡ PERFORMANCE") }, false)
        source.sendSuccess({ Component.literal("  §7TPS: ${tpsColor}%.1f §7/ 20 §8(%.2fms/tick)".format(tps, mspt)) }, false)
        source.sendSuccess({ Component.literal("  §7Memory: ${memColor}${usedMem}MB §7/ ${maxMem}MB §8(${memPercent}%%)") }, false)
        source.sendSuccess({ Component.literal("  §7Latency: ${pingColor}${pingMs}ms") }, false)
        
        source.sendSuccess({ Component.literal("§a§l🌍 WORLD") }, false)
        source.sendSuccess({ Component.literal("  §7Time: $timeStr §8(Day $dayCount)") }, false)
        source.sendSuccess({ Component.literal("  §7Difficulty: §e$difficulty") }, false)
        source.sendSuccess({ Component.literal("  §7Entities: §b$entityCount §7| Chunks: §b$loadedChunks") }, false)
        source.sendSuccess({ Component.literal("  §7Players: §a$onlinePlayers§7/$maxPlayers") }, false)
        
        source.sendSuccess({ Component.literal("§d§l✦ AEONIS ACTIVE") }, false)
        source.sendSuccess({ Component.literal("  §7Transforms: §b$transformedCount §7| Pet Vexes: §b$activeVexes") }, false)
        source.sendSuccess({ Component.literal("  §7Thunder Domes: §b$activeThunder §7| Director Orders: §b$activeDirector") }, false)
        
        source.sendSuccess({ Component.literal("§6§l═════════════════════════════") }, false)
        return 1
    }
    
    private fun tellStory(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val stories = listOf(
            "§5§o\"The ancient ones whisper of a power beyond comprehension...\"",
            "§5§o\"In the shadows, something stirs. The Aeonis awakens.\"",
            "§5§o\"They said it was impossible. They were wrong.\"",
            "§5§o\"The world trembles as a new legend is born.\"",
            "§5§o\"From the void, power flows. Will you seize it?\"",
            "§5§o\"Heroes are not born. They are forged in chaos.\"",
            "§5§o\"The end is merely another beginning...\"",
            "§5§o\"Reality bends to those who dare command it.\""
        )
        
        val story = stories.random()
        source.server.playerList.broadcastSystemMessage(Component.literal(story), false)
        return 1
    }

    private fun blink(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val player = source.player as? ServerPlayer ?: run {
            source.sendFailure(Component.literal("§cOnly players can blink!"))
            return 0
        }
        val range = IntegerArgumentType.getInteger(ctx, "range").coerceIn(1, 25)
        val look = player.lookAngle.normalize()
        val eye = player.eyePosition
        val target = eye.add(look.scale(range.toDouble()))
        val hit = player.level().clip(
            ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
        )

        val destination = when (hit.type) {
            HitResult.Type.BLOCK -> hit.location.subtract(look.scale(0.5))
            else -> target
        }

        player.teleportTo(destination.x, destination.y, destination.z)
        player.addEffect(MobEffectInstance(MobEffects.RESISTANCE, 40, 4)) // brief invuln
        source.sendSuccess({ Component.literal("§b✦ Blinked $range blocks!") }, true)
        return 1
    }

    private fun dash(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val player = source.player as? ServerPlayer ?: run {
            source.sendFailure(Component.literal("§cOnly players can dash!"))
            return 0
        }
        val dir = player.lookAngle.normalize().scale(1.5)
        player.setDeltaMovement(dir)
        player.syncVelocity = true
        source.sendSuccess({ Component.literal("§e⚡ Dashed forward!") }, true)
        return 1
    }

    private fun petVex(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val player = source.player as? ServerPlayer ?: run {
            source.sendFailure(Component.literal("§cOnly players can summon pets!"))
            return 0
        }
        val level = player.level()

        val vex = EntityTypes.VEX.spawn(level, player.blockPosition().above(), EntitySpawnReason.COMMAND)
        if (vex == null) {
            source.sendFailure(Component.literal("§cFailed to summon pet vex!"))
            return 0
        }
        
        // Track this vex as owned by the player
        petVexOwners[vex.id] = player.uuid
        
        // Give vex effects to make it visible and strong
        vex.addEffect(MobEffectInstance(MobEffects.GLOWING, 1200, 0))
        vex.addEffect(MobEffectInstance(MobEffects.STRENGTH, 1200, 1))
        vex.setLimitedLife(1200) // 60s
        
        // Find nearest hostile mob and set as target
        val nearbyHostile = level.getEntitiesOfClass(
            Monster::class.java,
            vex.boundingBox.inflate(16.0)
        ) { it !is Vex }.minByOrNull { it.distanceToSqr(vex) }
        
        if (nearbyHostile != null) {
            vex.setTarget(nearbyHostile)
        }

        source.sendSuccess({ Component.literal("§d🧚 Pet Vex summoned! It will attack nearby hostiles.") }, true)
        return 1
    }

    private fun spiritWolves(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val player = source.player as? ServerPlayer ?: run {
            source.sendFailure(Component.literal("§cOnly players can summon wolves!"))
            return 0
        }
        val level = player.level()
        val count = IntegerArgumentType.getInteger(ctx, "count").coerceIn(1, 5)

        repeat(count) {
            val wolf = EntityTypes.WOLF.spawn(level, player.blockPosition(), EntitySpawnReason.COMMAND) ?: return@repeat
            // Apply effects for spirit wolf appearance
            wolf.setPos(player.x + (Math.random() - 0.5) * 2, player.y, player.z + (Math.random() - 0.5) * 2)
            wolf.addEffect(MobEffectInstance(MobEffects.GLOWING, 900, 0))
            wolf.addEffect(MobEffectInstance(MobEffects.STRENGTH, 900, 1))
            wolf.addEffect(MobEffectInstance(MobEffects.SPEED, 900, 1))
        }

        source.sendSuccess({ Component.literal("§f🐺 Spirit Wolves summoned ($count)!") }, true)
        return 1
    }

    private fun clearEffects(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val targets = EntityArgument.getPlayers(ctx, "targets")
        for (t in targets) {
            t.removeAllEffects()
        }
        source.sendSuccess({ Component.literal("§a✨ Cleared effects for ${targets.size} player(s).") }, true)
        return 1
    }
    
    private fun exitBody(ctx: CommandContext<CommandSourceStack>, targetMode: GameType): Int {
        val source = ctx.source
        val player = source.player as? ServerPlayer ?: run {
            source.sendFailure(Component.literal("§cOnly players can use this!"))
            return 0
        }

        if (!TransformService.isTransformed(player)) {
            source.sendFailure(Component.literal("§cYou're not transformed!"))
            return 0
        }

        // Reuse the normal restore path (visibility, health, flight, camera, mob removal), then
        // layer the explicitly requested gamemode on top. Duplicating the cleanup here is how
        // /event exitbody drifted out of sync with /untransform in the first place.
        TransformService.untransform(player, showMessage = false, carryDamage = false)
        player.setGameMode(targetMode)
        player.onUpdateAbilities()

        val modeName = if (targetMode == GameType.SURVIVAL) "Survival" else "Creative"
        source.sendSuccess({
            Component.literal("§a✨ Exited body! Now in $modeName mode.")
        }, true)
        return 1
    }

    private fun makeChaotic(ctx: CommandContext<CommandSourceStack>): Int {
        val targets = EntityArgument.getEntities(ctx, "targets")
        var count = 0

        for (entity in targets) {
            if (entity is PathfinderMob) {
                // Mob#getGoalSelector is public in 26.3, so no reflection is needed at all.
                val selector = entity.goalSelector
                val goals = listOf(SuicidalLavaGoal(entity), CliffJumpGoal(entity), CrazySpinGoal(entity))
                goals.forEachIndexed { priority, goal -> selector.addGoal(priority, goal) }

                // Track exactly what was added per entity. The previous implementation never
                // recorded anything, so /ai chaotic stop always reported 0 and did nothing.
                chaoticGoals.getOrPut(entity.id) { mutableListOf() }.addAll(goals)
                chaoticEntities.add(entity.id)
                entity.addEffect(MobEffectInstance(MobEffects.SPEED, Int.MAX_VALUE, 2))
                count++
            }
        }

        ctx.source.sendSuccess({ Component.literal("§c🤪 Made $count entities chaotic!") }, true)
        return count
    }

    class SuicidalLavaGoal(private val mob: PathfinderMob) : Goal() {
        private var lavaPos: BlockPos? = null

        init {
            this.flags = EnumSet.of(Flag.MOVE)
        }

        override fun canUse(): Boolean {
            if (mob.tickCount % 20 != 0) return false
            val pos = mob.blockPosition()
            val level = mob.level()
            
            // Scan for lava
            for (x in -15..15) {
                for (y in -5..5) {
                    for (z in -15..15) {
                        val checkPos = pos.offset(x, y, z)
                        if (level.getBlockState(checkPos).block == Blocks.LAVA) {
                            lavaPos = checkPos
                            return true
                        }
                    }
                }
            }
            return false
        }

        override fun start() {
            lavaPos?.let {
                mob.navigation.moveTo(it.x.toDouble(), it.y.toDouble(), it.z.toDouble(), 1.5)
            }
        }
    }

    class CliffJumpGoal(private val mob: PathfinderMob) : Goal() {
        private var jumpPos: BlockPos? = null

        init {
            this.flags = EnumSet.of(Flag.MOVE)
        }

        override fun canUse(): Boolean {
            if (mob.tickCount % 20 != 0) return false
            val pos = mob.blockPosition()
            val level = mob.level()
            
            // Scan for drops
            for (x in -10..10) {
                for (z in -10..10) {
                    val checkPos = pos.offset(x, 0, z)
                    if (level.isEmptyBlock(checkPos.below()) && 
                        level.isEmptyBlock(checkPos.below(2)) && 
                        level.isEmptyBlock(checkPos.below(3))) {
                        jumpPos = checkPos
                        return true
                    }
                }
            }
            return false
        }

        override fun start() {
            jumpPos?.let {
                mob.navigation.moveTo(it.x.toDouble(), it.y.toDouble(), it.z.toDouble(), 1.5)
            }
        }
    }

    class CrazySpinGoal(private val mob: PathfinderMob) : Goal() {
        init {
            this.flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
        }

        override fun canUse(): Boolean {
            return mob.random.nextFloat() < 0.05f // 5% chance to start spinning
        }

        override fun tick() {
            mob.yRot += 45f
            mob.yBodyRot += 45f
            mob.navigation.moveTo(mob.x + mob.random.nextGaussian(), mob.y, mob.z + mob.random.nextGaussian(), 2.0)
        }
    }
    
    // ========== HEROBRINE SCARE SEQUENCE ==========
    // Triggered when player tries to /transform into Herobrine
    
    private val herobrineScareMessages = listOf(
        "§4§lYou chose to be ME??? ENJOY...",
        "§c§oI AM ALWAYS WATCHING",
        "§4§lYOU CANNOT ESCAPE",
        "§c§oLOOK BEHIND YOU",
        "§4§lTHIS IS MY WORLD NOW",
        "§c§oYOU SHOULD NOT HAVE DONE THAT",
        "§4§lI SEE EVERYTHING",
        "§c§oDID YOU REALLY THINK YOU COULD BE ME?",
        "§4§lYOUR SOUL BELONGS TO ME NOW",
        "§c§oRUN. IT WON'T HELP.",
        "§4§lI HAVE BEEN HERE SINCE THE BEGINNING",
        "§c§oYOU WILL NEVER SLEEP AGAIN"
    )
    
    private fun triggerHerobrineScareSequence(player: ServerPlayer, level: net.minecraft.server.level.ServerLevel) {
        // Apply 5 seconds of darkness effect
        player.addEffect(MobEffectInstance(MobEffects.DARKNESS, 100, 1, false, false)) // 5 seconds
        player.addEffect(MobEffectInstance(MobEffects.BLINDNESS, 40, 0, false, false)) // Brief blindness for initial shock
        
        // Play scary sounds
        level.playSound(null, player.blockPosition(), SoundEvents.AMBIENT_CAVE.value(), SoundSource.HOSTILE, 1.5f, 0.5f)
        level.playSound(null, player.blockPosition(), SoundEvents.WARDEN_AMBIENT, SoundSource.HOSTILE, 0.8f, 0.3f)
        
        // Spawn 5 Herobrines in a circle around the player (3-5 blocks away)
        val herobrines = mutableListOf<HerobrineEntity>()
        val playerPos = player.position()
        val random = level.random
        
        for (i in 0 until 5) {
            val angle = (i * 72.0) * (Math.PI / 180.0) // 360 / 5 = 72 degrees apart
            val distance = 3.0 + random.nextDouble() * 2.0 // 3-5 blocks away
            
            val spawnX = playerPos.x + cos(angle) * distance
            val spawnZ = playerPos.z + sin(angle) * distance
            val spawnY = playerPos.y
            
            val herobrine = com.qc.aeonis.entity.AeonisEntities.HEROBRINE.get().create(level, EntitySpawnReason.COMMAND)
            if (herobrine != null) {
                herobrine.setPos(spawnX, spawnY, spawnZ)
                
                // Make them look at the player
                val lookX = playerPos.x - spawnX
                val lookZ = playerPos.z - spawnZ
                val yaw = (Math.atan2(-lookX, lookZ) * (180.0 / Math.PI)).toFloat()
                herobrine.yRot = yaw
                herobrine.yBodyRot = yaw
                herobrine.yHeadRot = yaw
                
                // Add to world
                level.addFreshEntity(herobrine)
                herobrines.add(herobrine)
            }
        }
        
        // Schedule the scare sequence with delayed messages
        val server = level.server
        val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        
        // Initial message after 0.5 seconds (darkness kicks in)
        scheduler.schedule({
            server.execute {
                player.sendSystemMessage(Component.literal("§4§l§kXX§r §4§lYou chose to be ME??? §4§l§kXX"))
            }
        }, 500, java.util.concurrent.TimeUnit.MILLISECONDS)
        
        // Make Herobrines "laugh" by bobbing heads (simulated through quick teleports)
        for (tick in 1..30) {
            scheduler.schedule({
                server.execute {
                    for (herobrine in herobrines) {
                        if (herobrine.isAlive) {
                            // Simulate laughing by bobbing head pitch
                            val bobAmount = if (tick % 4 < 2) 15f else -10f
                            herobrine.xRot = bobAmount
                            
                            // Also wobble slightly
                            val wobble = sin(tick * 0.5) * 3f
                            herobrine.yHeadRot = herobrine.yRot + wobble.toFloat()
                        }
                    }
                }
            }, (tick * 100).toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        
        // More scary messages at intervals
        scheduler.schedule({
            server.execute {
                player.sendSystemMessage(Component.literal("§c§o*laughing echoes*"))
                level.playSound(null, player.blockPosition(), SoundEvents.WITCH_CELEBRATE, SoundSource.HOSTILE, 1.2f, 0.2f)
            }
        }, 1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        
        scheduler.schedule({
            server.execute {
                val randomMsg = herobrineScareMessages[random.nextInt(herobrineScareMessages.size)]
                player.sendSystemMessage(Component.literal(randomMsg))
            }
        }, 2500, java.util.concurrent.TimeUnit.MILLISECONDS)
        
        scheduler.schedule({
            server.execute {
                player.sendSystemMessage(Component.literal("§4§l§kXXXX§r §c§oENJOY YOUR NIGHTMARE §4§l§kXXXX"))
                level.playSound(null, player.blockPosition(), SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 0.6f, 0.4f)
            }
        }, 3500, java.util.concurrent.TimeUnit.MILLISECONDS)
        
        // Final message and cleanup after 4.5 seconds
        scheduler.schedule({
            server.execute {
                val finalMessages = listOf(
                    "§4§lYOU CAN NEVER BE ME",
                    "§c§oI will find you again...",
                    "§4§l§kXXX§r §4TRANSFORMATION DENIED §4§l§kXXX"
                )
                for (msg in finalMessages) {
                    player.sendSystemMessage(Component.literal(msg))
                }
                
                // Despawn all the Herobrines with particle effects
                for (herobrine in herobrines) {
                    if (herobrine.isAlive) {
                        // Smoke particles
                        level.sendParticles(
                            ParticleTypes.LARGE_SMOKE,
                            herobrine.x, herobrine.y + 1.0, herobrine.z,
                            20, 0.3, 0.5, 0.3, 0.02
                        )
                        level.sendParticles(
                            ParticleTypes.SOUL,
                            herobrine.x, herobrine.y + 1.0, herobrine.z,
                            10, 0.2, 0.3, 0.2, 0.05
                        )
                        herobrine.discard()
                    }
                }
                
                // Play final sound
                level.playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0f, 0.5f)
            }
            
            scheduler.shutdown()
        }, 4500, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
}


