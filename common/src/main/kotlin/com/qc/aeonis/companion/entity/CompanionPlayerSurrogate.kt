package com.qc.aeonis.companion.entity

import com.mojang.authlib.GameProfile
import com.qc.aeonis.entity.CompanionBotEntity
import io.netty.channel.ChannelFutureListener
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.network.ServerGamePacketListenerImpl
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Headless ServerPlayer surrogate maintained by CompanionBotEntity.
 * Executes player-grade block breaking, placing, tool usage, and container interactions.
 */
class CompanionPlayerSurrogate(
    level: ServerLevel,
    val bot: CompanionBotEntity,
    ownerProfile: GameProfile?
) : ServerPlayer(
    level.server,
    level,
    GameProfile(
        UUID.nameUUIDFromBytes("aeonis:surrogate:${bot.uuid}".toByteArray()),
        "[Aeonis] ${ownerProfile?.name ?: "Companion"}"
    ),
    ClientInformation.createDefault()
), IPlayerSurrogate {

    init {
        val dummyConnection = Connection(PacketFlow.SERVERBOUND)
        val dummyCookie = CommonListenerCookie.createInitial(gameProfile, false)
        this.connection = object : ServerGamePacketListenerImpl(level.server, dummyConnection, this, dummyCookie) {
            override fun send(packet: Packet<*>) {}
            override fun send(packet: Packet<*>, listener: ChannelFutureListener?) {}
        }
    }

    override fun syncWithBot() {
        val botLevel = bot.level() as? ServerLevel ?: return
        if (this.level() != botLevel) {
            this.setServerLevel(botLevel)
        }
        this.setPos(bot.x, bot.y, bot.z)
        this.yRot = bot.yRot
        this.xRot = bot.xRot
        this.yHeadRot = bot.yHeadRot
    }

    override fun mineBlock(pos: BlockPos): Boolean {
        syncWithBot()
        val currentTool = bot.inventory.mainHandItem
        this.setItemInHand(InteractionHand.MAIN_HAND, currentTool)

        val success = this.gameMode.destroyBlock(pos)
        bot.inventory.mainHandItem = this.mainHandItem
        return success
    }

    override fun placeBlock(pos: BlockPos, face: Direction, itemStack: ItemStack): Boolean {
        syncWithBot()
        this.setItemInHand(InteractionHand.MAIN_HAND, itemStack)
        val hitResult = BlockHitResult(Vec3.atCenterOf(pos), face, pos, false)
        val context = UseOnContext(this, InteractionHand.MAIN_HAND, hitResult)

        val result = itemStack.useOn(context)
        bot.inventory.mainHandItem = this.mainHandItem
        return result.consumesAction()
    }

    override fun attackEntity(target: Entity): Boolean {
        syncWithBot()
        this.setItemInHand(InteractionHand.MAIN_HAND, bot.inventory.mainHandItem)
        this.attack(target)
        bot.inventory.mainHandItem = this.mainHandItem
        return true
    }
}
