package com.qc.aeonis.screen

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.qc.aeonis.transform.CamouflageLevel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Client-side Aeonis preferences.
 *
 * Stored as a flat JSON object in `config/aeonis-client.json` shared between loaders.
 */
object AeonisClientSettings {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path by lazy {
        Path.of("config", "aeonis-client.json")
    }

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
     */
    var camouflageLevel: CamouflageLevel = CamouflageLevel.NATURAL

    /** Show the camouflage level and movement style in the ability bar header. */
    var showFormDetails: Boolean = true

    /** Show floating companion dialogue card HUD overlay. */
    var companionSpeechOverlay: Boolean = true

    /** Play typewriter clicking sound as companion dialogue reveals. */
    var companionSound: Boolean = true

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
            companionSpeechOverlay = root.readBoolean("companion_speech_overlay", companionSpeechOverlay)
            companionSound = root.readBoolean("companion_sound", companionSound)
        } catch (_: Exception) {
            // A corrupt file must never stop the client from starting; defaults win.
        }
    }

    fun save() {
        try {
            val parent = path.parent
            if (parent != null) Files.createDirectories(parent)
            val root = JsonObject()
            root.addProperty("show_ability_hud", showAbilityHud)
            root.addProperty("show_cooldown_seconds", showCooldownSeconds)
            root.addProperty("show_form_hints", showFormHints)
            root.addProperty("large_form_camera", largeFormCamera)
            root.addProperty("camouflage_level", camouflageLevel.id)
            root.addProperty("show_form_details", showFormDetails)
            root.addProperty("companion_speech_overlay", companionSpeechOverlay)
            root.addProperty("companion_sound", companionSound)
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
