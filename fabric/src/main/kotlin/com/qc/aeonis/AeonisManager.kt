package com.qc.aeonis

import com.qc.aeonis.command.AeonisCommands
import com.qc.aeonis.config.AeonisFeatures
import com.qc.aeonis.entity.AeonisEntities
import com.qc.aeonis.entity.HerobrineEntity
import com.qc.aeonis.entity.HerobrineSpawner
import com.qc.aeonis.llm.AeonisAssistant
import com.qc.aeonis.llm.LlmCommands
import com.qc.aeonis.llm.config.LlmConfigStorage
import com.qc.aeonis.llm.network.LlmNetworking
import com.qc.aeonis.llm.safety.SafetyLimiter
import com.qc.aeonis.companion.CompanionBotManager
import com.qc.aeonis.companion.CompanionCommands
import com.qc.aeonis.minigame.prophunt.PropHuntCommands
import com.qc.aeonis.minigame.prophunt.PropHuntManager
import com.qc.aeonis.network.AeonisNetwork
import com.qc.aeonis.network.AeonisServerNetworking
import com.qc.aeonis.transform.TransformService
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import org.slf4j.LoggerFactory

object AeonisManager : ModInitializer {
    private val logger = LoggerFactory.getLogger("aeonis-manager")

	override fun onInitialize() {
        com.qc.aeonis.registration.AeonisRegistries.setHelper(com.qc.aeonis.fabric.FabricRegistrationHelper())
        Aeonis.init()
        com.qc.aeonis.entity.AeonisEntities.getAttributes().forEach { (type, builder) ->
            net.fabricmc.fabric.api.`object`.builder.v1.entity.FabricDefaultAttributeRegistry.register(type, builder)
        }

		com.qc.aeonis.block.AeonisBlocks.register()
		AeonisEntities.register()
		com.qc.aeonis.item.AeonisItems.register()
		com.qc.aeonis.inventory.AeonisMenus.register()
		AeonisPossession.register()
		
		PropHuntManager.register()
		logger.info("Prop Hunt minigame registered")

		CompanionBotManager.register()
		logger.info("Companion bot system registered")
		
		com.qc.aeonis.minigame.manhunt.ManhuntManager.register()
		logger.info("Manhunt minigame registered")

        ServerTickEvents.START_SERVER_TICK.register { server ->
			TransformationManager.tickStart(server)
		}

        ServerTickEvents.END_SERVER_TICK.register { server ->
            AeonisCommands.tickActors(server)
            AeonisCommands.tickPetVexes(server)
            AeonisCommands.tickPrankMorphs(server)
            AeonisCommands.tickThunderDomes(server)
            CompanionBotManager.tick(server)
            PropHuntManager.tickAllGames(server)
            com.qc.aeonis.minigame.manhunt.ManhuntManager.tickAllGames(server)
            com.qc.aeonis.dimension.AncardDimensionRules.tick(server)
            com.qc.aeonis.dimension.AncardEclipseEvent.tick(server)
            HerobrineSpawner.tick(server)

            // Detects a possessed mob that died or unloaded and unwinds the session before the
            // sync loop below can act on a stale entity id.
            TransformService.tick(server)
            TransformationManager.tickEnd(server)
        }
		
		net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register { dispatcher, context, _ ->
            AeonisCommands.register(dispatcher, context)
            CompanionCommands.register(dispatcher)
            PropHuntCommands.register(dispatcher)
            com.qc.aeonis.minigame.manhunt.ManhuntCommands.register(dispatcher)
            LlmCommands.registerCommands(dispatcher)
        }

		AeonisServerNetworking.register()
		LlmNetworking.registerServer()
		
		ServerLifecycleEvents.SERVER_STARTED.register { server ->
			LlmConfigStorage.init(server)
			val config = LlmConfigStorage.getInstance().config
			SafetyLimiter.init(config.maxBlocksPerMinute, config.maxEditRadius, config.griefProtectionEnabled)
			com.qc.aeonis.llm.script.AeonisScriptManager.initialize(server)
			logger.info("LLM config storage and script manager initialized")
		}
		
		ServerLifecycleEvents.SERVER_STOPPING.register { server ->
			// End every transform session so no playerless mob is serialised into the save and no
			// restore snapshot is lost across a restart.
			TransformService.shutdown(server)
			AeonisAssistant.despawnAll()
			LlmConfigStorage.shutdown()
			logger.info("LLM shutdown complete")
		}
		
		PlayerBlockBreakEvents.BEFORE.register { world, player, pos, state, blockEntity ->
			if (world is ServerLevel && player is ServerPlayer) {
				HerobrineEntity.onBlockBroken(world, pos, player)
			}
			true
		}
		
		EntitySleepEvents.START_SLEEPING.register { entity, sleepingPos ->
			if (entity is ServerPlayer) {
				HerobrineEntity.triggerDreamEvent(entity)
			}
		}

		ServerPlayConnectionEvents.JOIN.register { handler, sender, server ->
			val player = handler.player
			AeonisNetwork.clearControlState(player.uuid)
			
			if (!AeonisFeatures.hasShownWorldInstallNotice(server)) {
				AeonisFeatures.markWorldInstallNoticeShown(server)
				server.execute {
					player.sendSystemMessage(Component.literal("§eThanks for installing Aeonis! This build targets Minecraft 26.3. See TODO.md for known gaps."))
				}
			}

			server.execute {
				AeonisPossession.onPlayerJoin(handler.player, server)
				com.qc.aeonis.companion.CompanionBotManager.onPlayerJoin(handler.player)
				AeonisNetwork.broadcastControllingPlayers(server)
			}
		}

		ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
			val player = handler.player
			// Runs synchronously so the restored gamemode and visibility are part of the data the
			// server saves for this disconnect. Only the broadcast is deferred.
			TransformService.handleQuit(player)
			com.qc.aeonis.companion.CompanionBotManager.onPlayerDisconnect(player)
			server.execute {
				AeonisNetwork.broadcastControllingPlayers(server)
				AeonisPossession.onPlayerDisconnect(player, server)
			}
		}

		// Dying mid-morph used to leave the session alive, so the position-sync loop dragged the
		// respawned player straight back to the abandoned mob.
		ServerPlayerEvents.AFTER_RESPAWN.register { newPlayer, _, _ ->
			TransformService.handleRespawn(newPlayer)
		}
		
		logger.info("Aeonis Plus core initialized")
	}
}
