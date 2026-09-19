package com.qc.aeonis

import com.qc.aeonis.command.AeonisCommands
import com.qc.aeonis.companion.CompanionCommands
import com.qc.aeonis.entity.AeonisEntities
import com.qc.aeonis.llm.LlmCommands
import com.qc.aeonis.llm.AeonisAssistant
import com.qc.aeonis.llm.config.LlmConfigStorage
import com.qc.aeonis.llm.network.payloads.*
import com.qc.aeonis.llm.provider.LlmProvider
import com.qc.aeonis.llm.provider.LlmProviderFactory
import com.qc.aeonis.llm.provider.LlmProviderType
import com.qc.aeonis.llm.safety.SafetyLimiter
import com.qc.aeonis.minigame.manhunt.ManhuntCommands
import com.qc.aeonis.minigame.prophunt.PropHuntCommands
import com.qc.aeonis.neoforge.NeoForgeRegistrationHelper
import com.qc.aeonis.neoforge.network.AeonisClientNetworking
import com.qc.aeonis.neoforge.client.AeonisKeyBindings
import com.qc.aeonis.network.*
import com.qc.aeonis.registration.AeonisRegistries
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.event.server.ServerStartingEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.client.event.ClientTickEvent

@Mod(Aeonis.MOD_ID)
class AeonisNeoForge(bus: IEventBus) {
    init {
        val helper = NeoForgeRegistrationHelper()
        helper.register(bus)
        AeonisRegistries.setHelper(helper)
        
        com.qc.aeonis.block.AeonisBlocks.register()
        com.qc.aeonis.item.AeonisItems.register()
        com.qc.aeonis.entity.AeonisEntities.register()
        com.qc.aeonis.AeonisPossession.register()
        
        com.qc.aeonis.minigame.prophunt.PropHuntManager.register()
        com.qc.aeonis.minigame.manhunt.ManhuntManager.register()
        com.qc.aeonis.companion.CompanionBotManager.register()
        
        bus.addListener(::onAttributeCreation)
        bus.addListener(::registerPayloads)
        
        val isClient = try { Class.forName("net.minecraft.client.Minecraft"); true } catch (e: Exception) { false }
        if (isClient) {
            bus.addListener(::onRegisterKeyMappings)
            bus.addListener(com.qc.aeonis.neoforge.client.AeonisEntityRenderers::register)
            NeoForge.EVENT_BUS.addListener(::onClientTick)
        }
        
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(::onServerTickPre)
        NeoForge.EVENT_BUS.addListener(::onServerTickPost)
        NeoForge.EVENT_BUS.addListener(::onServerStarting)
        NeoForge.EVENT_BUS.addListener(::onServerStopping)
        
        Aeonis.init()
        org.slf4j.LoggerFactory.getLogger("aeonis-neoforge").info("Aeonis NeoForge initialized successfully!")
    }

    private fun onAttributeCreation(event: EntityAttributeCreationEvent) {
        AeonisEntities.getAttributes().forEach { (type, builder) ->
            event.put(type, builder.build())
        }
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        AeonisCommands.register(event.dispatcher, event.buildContext)
        LlmCommands.registerCommands(event.dispatcher)
        CompanionCommands.register(event.dispatcher)
        PropHuntCommands.register(event.dispatcher)
        ManhuntCommands.register(event.dispatcher)
    }

    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar: PayloadRegistrar = event.registrar("aeonis")
        
        // Server-bound
        registrar.playToServer(MobControlPayload.ID, MobControlPayload.CODEC) { payload, context ->
            context.enqueueWork { AeonisNetwork.updateControlInput(context.player().uuid, payload) }
        }
        registrar.playToServer(PossessPayload.ID, PossessPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val entity = context.player().level().getEntity(payload.entityId)
                if (entity is LivingEntity) AeonisPossession.possess(context.player() as ServerPlayer, entity)
            }
        }
        registrar.playToServer(ReleasePayload.ID, ReleasePayload.CODEC) { payload, context ->
            context.enqueueWork { AeonisPossession.handleRelease(context.player() as ServerPlayer, payload.x, payload.y, payload.z) }
        }
        registrar.playToServer(LlmConfigSubmitPayload.ID, LlmConfigSubmitPayload.CODEC) { payload, context ->
            context.enqueueWork {
                val storage = LlmConfigStorage.getInstance()
                storage.updateConfig(payload.provider(), payload.model(), if (payload.hasNewApiKey()) payload.apiKey() else null, payload.temperature(), payload.maxTokens())
                storage.updateSafetySettings(payload.maxBlocksPerMinute(), payload.maxEditRadius(), payload.griefProtection())
                SafetyLimiter.init(payload.maxBlocksPerMinute(), payload.maxEditRadius(), payload.griefProtection())
            }
        }
        registrar.playToServer(LlmSpawnBotPayload.ID, LlmSpawnBotPayload.CODEC) { payload, context ->
            context.enqueueWork {
                if (payload.spawn()) AeonisAssistant.spawn(context.player() as ServerPlayer)
                else AeonisAssistant.getInstance(context.player().level() as net.minecraft.server.level.ServerLevel)?.despawn()
            }
        }
        
        // Client-bound
        val isClient = try { Class.forName("net.minecraft.client.Minecraft"); true } catch (e: Exception) { false }
        if (isClient) {
            registrar.playToClient(ControlModePayload.ID, ControlModePayload.CODEC) { payload, context ->
                context.enqueueWork { AeonisClientNetworking.onControlMode(payload) }
            }
            registrar.playToClient(AeonisNetworking.SyncControllingPlayersPayload.ID, AeonisNetworking.SyncControllingPlayersPayload.CODEC) { payload, context ->
                context.enqueueWork { AeonisClientNetworking.onSyncControllingPlayers(payload) }
            }
            registrar.playToClient(AeonisNetworking.ControlledEntityStatusPayload.ID, AeonisNetworking.ControlledEntityStatusPayload.CODEC) { payload, context ->
                context.enqueueWork { AeonisClientNetworking.onControlledEntityStatus(payload) }
            }
            registrar.playToClient(LlmOpenGuiPayload.ID, LlmOpenGuiPayload.CODEC) { payload, context ->
                context.enqueueWork {
                    Minecraft.getInstance().setScreenAndShow(com.qc.aeonis.neoforge.llm.screen.AeonisLlmConfigScreen(payload))
                }
            }
            registrar.playToClient(LlmStatusUpdatePayload.ID, LlmStatusUpdatePayload.CODEC) { payload, context ->
                context.enqueueWork {
                    val mc = Minecraft.getInstance()
                    if (mc.gui.screen() is com.qc.aeonis.neoforge.llm.screen.AeonisLlmConfigScreen) {
                        (mc.gui.screen() as com.qc.aeonis.neoforge.llm.screen.AeonisLlmConfigScreen).handleStatusUpdate(payload.message(), payload.success())
                    }
                }
            }
        }
    }

    private fun onRegisterKeyMappings(event: RegisterKeyMappingsEvent) {
        event.register(AeonisKeyBindings.TELEPORT_KEY)
        event.register(AeonisKeyBindings.POSSESS_KEY)
        event.register(AeonisKeyBindings.RELEASE_KEY)
        event.register(AeonisKeyBindings.ABILITY_KEY)
        event.register(AeonisKeyBindings.SOUL_POSSESS_KEY)
        event.register(AeonisKeyBindings.ZOOM_KEY)
        event.register(AeonisKeyBindings.FREECAM_TOGGLE_KEY)
        event.register(AeonisKeyBindings.FREECAM_REMOTE_BREAK_KEY)
    }

    private fun onClientTick(event: ClientTickEvent.Post) {
        AeonisClientNetworking.onClientTick(Minecraft.getInstance())
    }

    private fun onServerStarting(event: ServerStartingEvent) {
        LlmConfigStorage.init(event.server)
        val config = LlmConfigStorage.getInstance().config
        SafetyLimiter.init(config.maxBlocksPerMinute, config.maxEditRadius, config.griefProtectionEnabled)
        com.qc.aeonis.llm.script.AeonisScriptManager.initialize(event.server)
    }

    private fun onServerStopping(event: ServerStoppingEvent) {
        AeonisAssistant.despawnAll()
        LlmConfigStorage.shutdown()
    }

    private fun onServerTickPre(event: ServerTickEvent.Pre) {
        com.qc.aeonis.TransformationManager.tickStart(event.server)
    }

    private fun onServerTickPost(event: ServerTickEvent.Post) {
        val server = event.server
        AeonisCommands.tickActors(server)
        AeonisCommands.tickPetVexes(server)
        AeonisCommands.tickPrankMorphs(server)
        AeonisCommands.tickThunderDomes(server)
        com.qc.aeonis.companion.CompanionBotManager.tick(server)
        com.qc.aeonis.minigame.prophunt.PropHuntManager.tickAllGames(server)
        com.qc.aeonis.minigame.manhunt.ManhuntManager.tickAllGames(server)
        com.qc.aeonis.dimension.AncardDimensionRules.tick(server)
        com.qc.aeonis.dimension.AncardEclipseEvent.tick(server)
        com.qc.aeonis.entity.HerobrineSpawner.tick(server)
        
        com.qc.aeonis.TransformationManager.tickEnd(server)
    }
}
