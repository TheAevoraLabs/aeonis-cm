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
 * Headless `ServerPlayer` surrogate maintained by [CompanionBotEntity].
 *
 * A real player instance is the cheapest way to get authentic behaviour: block breaking
 * honours tool speed, durability, enchantments and drops; block placing honours facing,
 * block states and `canSurvive`; attacking honours crits, sweeping and attribute
 * modifiers. Nothing about that is reimplemented here — the class only wires a dummy
 * network listener so the player exists on the server, and keeps it glued to the bot.
 *
 * Its own inventory is **scratch space**: [mineBlock], [placeBlock] and [attackEntity] put
 * the caller's stack in the hand, let vanilla mutate it, and clear the hand. See
 * [IPlayerSurrogate] for why that matters.
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
            // All outgoing traffic is dropped: the surrogate has no client to receive it.
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
        this.deltaMovement = bot.deltaMovement
    }

    override fun mineBlock(pos: BlockPos, tool: ItemStack): Boolean {
        syncWithBot()
        return withScratchHand(tool) {
            this.gameMode.destroyBlock(pos)
        }
    }

    override fun placeBlock(pos: BlockPos, face: Direction, itemStack: ItemStack): Boolean {
        if (itemStack.isEmpty) return false
        syncWithBot()
        return withScratchHand(itemStack) {
            // `BlockPlaceContext` resolves a click on a replaceable block (air, water,
            // tall grass) to that same block, so passing `pos` as the clicked position
            // places the block exactly at `pos` — no face-offset arithmetic needed.
            val hitResult = BlockHitResult(Vec3.atCenterOf(pos), face, pos, false)
            val context = UseOnContext(this, InteractionHand.MAIN_HAND, hitResult)
            itemStack.useOn(context).consumesAction()
        }
    }

    override fun attackEntity(target: Entity, weapon: ItemStack): Boolean {
        if (target === this || !target.isAlive) return false
        val attackWeapon = if (weapon.isEmpty) bot.mainHandItem else weapon
        syncWithBot()
        return withScratchHand(attackWeapon) {
            this.attack(target)
            true
        }
    }

    /**
     * Runs [action] with [stack] in the main hand, then empties the hand.
     *
     * `finally` rather than a happy-path reset: a placement that throws mid-way must not
     * leave a live reference to an inventory stack in the surrogate's hand, which would
     * make the next action act on a stale item.
     */
    private inline fun <T> withScratchHand(stack: ItemStack, action: () -> T): T {
        setItemInHand(InteractionHand.MAIN_HAND, stack)
        try {
            return action()
        } finally {
            setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
        }
    }
}
