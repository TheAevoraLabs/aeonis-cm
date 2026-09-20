package com.qc.aeonis.companion.ui

import com.qc.aeonis.entity.CompanionBotEntity
import com.qc.aeonis.platform.INetworkHelper
import com.qc.aeonis.platform.Services
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

object CompanionDialogueManager {

    /**
     * Sends companion dialogue to the owner's client via HUD overlay card.
     * Replaces chat spam with an authentic, non-intrusive floating speech notification.
     */
    fun speak(bot: CompanionBotEntity, owner: ServerPlayer, message: String, emotion: String = "FRIENDLY") {
        try {
            val name = bot.customName?.string ?: "Aeonis Companion"
            Services.load(INetworkHelper::class.java).sendCompanionDialogue(
                owner,
                name,
                message,
                emotion
            )
        } catch (_: Exception) {
            owner.sendSystemMessage(Component.literal("§6[Companion] §7$message"))
        }
    }
}
