package com.qc.aeonis.network

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

object AeonisNetworking {
    data class SyncControllingPlayersPayload(val playerUUIDs: List<UUID>) : CustomPacketPayload {
        companion object {
            val ID = CustomPacketPayload.Type<SyncControllingPlayersPayload>(Identifier.fromNamespaceAndPath("aeonis", "sync_controlling_players"))
            val CODEC: StreamCodec<RegistryFriendlyByteBuf, SyncControllingPlayersPayload> = StreamCodec.of(
                { buf, p -> 
                    buf.writeInt(p.playerUUIDs.size)
                    p.playerUUIDs.forEach { buf.writeUUID(it) }
                },
                { buf -> 
                    val size = buf.readInt()
                    val list = mutableListOf<UUID>()
                    repeat(size) { list.add(buf.readUUID()) }
                    SyncControllingPlayersPayload(list)
                }
            )
        }
        override fun type() = ID
    }

    data class ControlledEntityStatusPayload(val entityId: Int, val health: Float, val maxHealth: Float, val name: String) : CustomPacketPayload {
        companion object {
            val ID = CustomPacketPayload.Type<ControlledEntityStatusPayload>(Identifier.fromNamespaceAndPath("aeonis", "controlled_entity_status"))
            val CODEC: StreamCodec<RegistryFriendlyByteBuf, ControlledEntityStatusPayload> = StreamCodec.of(
                { buf, p -> buf.writeInt(p.entityId); buf.writeFloat(p.health); buf.writeFloat(p.maxHealth); buf.writeUtf(p.name) },
                { buf -> ControlledEntityStatusPayload(buf.readInt(), buf.readFloat(), buf.readFloat(), buf.readUtf()) }
            )
        }
        override fun type() = ID
    }

    data class SoulModePayload(val enabled: Boolean) : CustomPacketPayload {
        companion object {
            val ID = CustomPacketPayload.Type<SoulModePayload>(Identifier.fromNamespaceAndPath("aeonis", "soul_mode"))
            val CODEC: StreamCodec<RegistryFriendlyByteBuf, SoulModePayload> = StreamCodec.of(
                { buf, p -> buf.writeBoolean(p.enabled) },
                { buf -> SoulModePayload(buf.readBoolean()) }
            )
        }
        override fun type() = ID
    }

    data class OpenManhuntGuiPayload(val dummy: Boolean = true) : CustomPacketPayload {
        companion object {
            val ID = CustomPacketPayload.Type<OpenManhuntGuiPayload>(Identifier.fromNamespaceAndPath("aeonis", "open_manhunt_gui"))
            val CODEC: StreamCodec<RegistryFriendlyByteBuf, OpenManhuntGuiPayload> = StreamCodec.unit(OpenManhuntGuiPayload())
        }
        override fun type() = ID
    }

    data class SoulPossessPayload(val entityId: Int) : CustomPacketPayload {
        companion object {
            val ID = CustomPacketPayload.Type<SoulPossessPayload>(Identifier.fromNamespaceAndPath("aeonis", "soul_possess"))
            val CODEC: StreamCodec<RegistryFriendlyByteBuf, SoulPossessPayload> = StreamCodec.of(
                { buf, p -> buf.writeInt(p.entityId) },
                { buf -> SoulPossessPayload(buf.readInt()) }
            )
        }
        override fun type() = ID
    }
}

// Top level payloads for direct usage

/**
 * One tick of player input for a possessed mob.
 *
 * [ability] carries the ability slot the player pressed this tick (see `AbilitySlot`): 0 means
 * nothing was pressed, 1..3 select the primary/secondary/tertiary ability. [sprint] lets a form
 * that can run do so without inventing a second packet for it.
 *
 * [camouflage] carries the player's `CamouflageLevel` ordinal, because how much idle behaviour a
 * possessed body invents is a client preference but has to be executed on the server. Riding the
 * movement packet means the setting takes effect within one tick of being changed and costs no
 * extra traffic.
 */
data class MobControlPayload(
    val forward: Float,
    val strafe: Float,
    val jump: Boolean,
    val sneak: Boolean,
    val yaw: Float,
    val pitch: Float,
    val attack: Boolean,
    val teleport: Boolean = false,
    val ability: Int = 0,
    val sprint: Boolean = false,
    val camouflage: Int = 1
) : CustomPacketPayload {
    companion object {
        val ID = CustomPacketPayload.Type<MobControlPayload>(Identifier.fromNamespaceAndPath("aeonis", "mob_control"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, MobControlPayload> = StreamCodec.of(
            { buf, p ->
                buf.writeFloat(p.forward); buf.writeFloat(p.strafe); buf.writeBoolean(p.jump)
                buf.writeBoolean(p.sneak); buf.writeFloat(p.yaw); buf.writeFloat(p.pitch)
                buf.writeBoolean(p.attack); buf.writeBoolean(p.teleport)
                buf.writeVarInt(p.ability); buf.writeBoolean(p.sprint)
                buf.writeVarInt(p.camouflage)
            },
            { buf ->
                MobControlPayload(
                    buf.readFloat(), buf.readFloat(), buf.readBoolean(), buf.readBoolean(),
                    buf.readFloat(), buf.readFloat(), buf.readBoolean(), buf.readBoolean(),
                    buf.readVarInt(), buf.readBoolean(), buf.readVarInt()
                )
            }
        )
    }
    override fun type() = ID
}

data class ControlModePayload(
    val enabled: Boolean,
    val mobId: Int = -1,
    val selectedSlot: Int = -1
) : CustomPacketPayload {
    companion object {
        val ID = CustomPacketPayload.Type<ControlModePayload>(Identifier.fromNamespaceAndPath("aeonis", "control_mode"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ControlModePayload> = StreamCodec.of(
            { buf, p -> buf.writeBoolean(p.enabled); buf.writeInt(p.mobId); buf.writeInt(p.selectedSlot) },
            { buf -> ControlModePayload(buf.readBoolean(), buf.readInt(), buf.readInt()) }
        )
    }
    override fun type() = ID
}

data class PossessPayload(val entityId: Int, val selectedSlot: Int) : CustomPacketPayload {
    companion object {
        val ID = CustomPacketPayload.Type<PossessPayload>(Identifier.fromNamespaceAndPath("aeonis", "possess"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PossessPayload> = StreamCodec.of(
            { buf, p -> buf.writeInt(p.entityId); buf.writeInt(p.selectedSlot) },
            { buf -> PossessPayload(buf.readInt(), buf.readInt()) }
        )
    }
    override fun type() = ID
}

data class ReleasePayload(val selectedSlot: Int, val x: Double, val y: Double, val z: Double) : CustomPacketPayload {
    companion object {
        val ID = CustomPacketPayload.Type<ReleasePayload>(Identifier.fromNamespaceAndPath("aeonis", "release"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ReleasePayload> = StreamCodec.of(
            { buf, p -> buf.writeInt(p.selectedSlot); buf.writeDouble(p.x); buf.writeDouble(p.y); buf.writeDouble(p.z) },
            { buf -> ReleasePayload(buf.readInt(), buf.readDouble(), buf.readDouble(), buf.readDouble()) }
        )
    }
    override fun type() = ID
}

data class AbilityPayload(val dummy: Boolean = true) : CustomPacketPayload {
    companion object {
        val ID = CustomPacketPayload.Type<AbilityPayload>(Identifier.fromNamespaceAndPath("aeonis", "ability"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, AbilityPayload> = StreamCodec.unit(AbilityPayload())
    }
    override fun type() = ID
}

data class SelectedSlotPayload(val slot: Int) : CustomPacketPayload {
    companion object {
        val ID = CustomPacketPayload.Type<SelectedSlotPayload>(Identifier.fromNamespaceAndPath("aeonis", "selected_slot"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, SelectedSlotPayload> = StreamCodec.of(
            { buf, p -> buf.writeInt(p.slot) },
            { buf -> SelectedSlotPayload(buf.readInt()) }
        )
    }
    override fun type() = ID
}
