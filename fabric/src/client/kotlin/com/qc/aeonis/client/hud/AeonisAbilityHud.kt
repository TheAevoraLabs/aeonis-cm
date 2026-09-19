package com.qc.aeonis.client.hud

import com.qc.aeonis.network.AeonisClientNetworking
import com.qc.aeonis.screen.AeonisClientSettings
import com.qc.aeonis.transform.MobAbilities
import com.qc.aeonis.transform.MobMovementProfiles
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Shows what the mob you are currently wearing can do.
 *
 * A possessed mob's abilities are not discoverable by pressing keys and hoping, so this panel
 * lists the three slots with their key, name and live cooldown, and explains the movement style
 * of the form. The list comes from the same registry the server dispatches from, so the HUD can
 * never advertise an ability the server would refuse.
 *
 * Everything here is display-only and can be turned off from Options -> Aeonis.
 */
object AeonisAbilityHud : HudElement {

    /** How long the form hint stays on screen after changing form, in ticks. */
    private const val HINT_TICKS = 100

    private var lastFormId = -1
    private var hintTicks = 0

    override fun extractRenderState(graphics: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        if (!AeonisClientSettings.showAbilityHud) return

        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        if (!AeonisClientNetworking.isControlling()) return

        val mobId = AeonisClientNetworking.getControlledMobId()
        val mob = level.getEntity(mobId) as? net.minecraft.world.entity.Mob ?: return

        val font = mc.font
        val abilities = MobAbilities.forMob(mob)
        val formName = mob.type.description.string

        // A hint is worth showing when the player changes into a different form, not every tick.
        if (mobId != lastFormId) {
            lastFormId = mobId
            hintTicks = HINT_TICKS
        }
        if (hintTicks > 0) hintTicks--

        val x = 6
        var y = 6

        val headerWidth = font.width(formName) + 16
        graphics.fill(x, y, x + headerWidth, y + 14, 0xB0101018.toInt())
        graphics.text(font, formName, x + 6, y + 3, 0xFFE2C86A.toInt())
        y += 18

        // The movement line comes from the same profile the server moves the body with, so the
        // panel explains the physics you are actually in rather than an approximation of it. The
        // camouflage level is shown next to it because a player who turned it down has no other
        // way to tell whether the change took.
        if (AeonisClientSettings.showFormDetails) {
            val movement = MobMovementProfiles.profileFor(mob).describe()
            val detail = "$movement  ·  ${AeonisClientSettings.camouflageLevel.label}"
            graphics.fill(x, y, x + font.width(detail) + 12, y + 11, 0x80101018.toInt())
            graphics.text(font, detail, x + 6, y + 2, 0xFF9AA8C2.toInt())
            y += 13
        }

        if (abilities.isEmpty()) {
            graphics.fill(x, y, x + 190, y + 12, 0x90101018.toInt())
            graphics.text(font, "No special abilities in this form", x + 6, y + 2, 0xFF9AA8C2.toInt())
            return
        }

        for (ability in abilities.sortedBy { it.slot.index }) {
            val remaining = AeonisClientNetworking.remainingCooldown(ability.id)
            val ready = remaining <= 0
            val line = buildString {
                append(ability.slot.key)
                append("  ")
                append(ability.name)
                if (!ready && AeonisClientSettings.showCooldownSeconds) {
                    append("  ")
                    append(((remaining + 19) / 20).toString())
                    append("s")
                }
            }
            val width = font.width(line) + 16
            val background = if (ready) 0x90101018.toInt() else 0x90402020.toInt()
            graphics.fill(x, y, x + width, y + 12, background)
            graphics.text(font, line, x + 6, y + 2, if (ready) 0xFFE6EDF7.toInt() else 0xFFB08968.toInt())
            y += 14
        }

        if (hintTicks > 0 && AeonisClientSettings.showFormHints) {
            val hint = "Abilities are also listed in Options > Aeonis"
            graphics.fill(x, y + 2, x + font.width(hint) + 12, y + 14, 0x70101018.toInt())
            graphics.text(font, hint, x + 6, y + 4, 0xFF8A97B0.toInt())
        }

        // A quiet reminder that the form is aware of you; harmless and helps new players.
        if (player.isInvisible && mob.isInvisible) {
            return
        }
    }
}
