package com.qc.aeonis.screen

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.qc.aeonis.transform.CamouflageLevel
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Client-side Aeonis preferences.
 *
 * These are deliberately only the things a client owns: display and input comfort. Anything that
 * changes gameplay (world toggles, feature flags) stays on the server, where it applies to
 * everyone in the world and cannot be overridden by a modified client. The manual says so
 * explicitly, so nobody goes looking for a slider that does not exist.
 *
 * Stored as a flat JSON object so a hand-edit is survivable: unknown keys are ignored and missing
 * keys fall back to the defaults.
 */
object AeonisClientSettings {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path = FabricLoader.getInstance().configDir.resolve("aeonis-client.json")

    /** Show the ability bar (form name, ability keys, cooldowns) while controlling a mob. */
    var showAbilityHud: Boolean = true

    /** Show the numeric cooldown next to each ability key. */
    var showCooldownSeconds: Boolean = true

    /** Show a one-line hint the first time a new form is taken. */
    var showFormHints: Boolean = true

    /** Pull the third-person camera further back for large forms such as the dragon. */
    var largeFormCamera: Boolean = true

    /**
     * How hard the possessed body tries to look like a wild one.
     *
     * This is a client preference but it is executed on the server: the level rides the movement
     * packet, so it cannot be used by a modified client to gain anything a normal client could
     * not also ask for.
     */
    var camouflageLevel: CamouflageLevel = CamouflageLevel.NATURAL

    /** Show the camouflage level and movement style in the ability bar header. */
    var showFormDetails: Boolean = true

    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        try {
            if (!Files.exists(path)) return
            val root = gson.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject::class.java)
                ?: return
            showAbilityHud = root.readBoolean("show_ability_hud", showAbilityHud)
            showCooldownSeconds = root.readBoolean("show_cooldown_seconds", showCooldownSeconds)
            showFormHints = root.readBoolean("show_form_hints", showFormHints)
            largeFormCamera = root.readBoolean("large_form_camera", largeFormCamera)
            camouflageLevel = CamouflageLevel.byId(root.readString("camouflage_level", camouflageLevel.id))
            showFormDetails = root.readBoolean("show_form_details", showFormDetails)
        } catch (_: Exception) {
            // A corrupt file must never stop the client from starting; defaults win.
        }
    }

    fun save() {
        try {
            Files.createDirectories(path.parent)
            val root = JsonObject()
            root.addProperty("show_ability_hud", showAbilityHud)
            root.addProperty("show_cooldown_seconds", showCooldownSeconds)
            root.addProperty("show_form_hints", showFormHints)
            root.addProperty("large_form_camera", largeFormCamera)
            root.addProperty("camouflage_level", camouflageLevel.id)
            root.addProperty("show_form_details", showFormDetails)
            Files.writeString(path, gson.toJson(root), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            // Read-only config directories exist; failing to save is not worth a crash.
        }
    }

    /** Where the file lives, shown in the About section so players can find it. */
    fun filePath(): String = path.toAbsolutePath().toString()

    private fun JsonObject.readBoolean(key: String, fallback: Boolean): Boolean =
        if (has(key)) get(key).asBoolean else fallback

    private fun JsonObject.readString(key: String, fallback: String): String =
        if (has(key)) get(key).asString else fallback
}
