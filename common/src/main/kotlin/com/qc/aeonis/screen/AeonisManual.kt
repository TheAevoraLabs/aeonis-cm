package com.qc.aeonis.screen

/**
 * One page of the in-game manual.
 *
 * [body] entries are paragraphs, not pre-wrapped lines: the screen measures them against the
 * current GUI scale and wraps them, so the text stays readable at every resolution and window
 * size instead of being cut off.
 */
data class ManualSection(
    val id: String,
    val title: String,
    val body: List<String>
)

/**
 * The manual itself.
 *
 * This is the single source of truth for "how do I use this mod" in game. It is written for a
 * player who has just installed Aeonis and does not want to read a repository: every command the
 * mod adds is listed, every movement style is explained, and every ability is named with the key
 * that triggers it.
 */
object AeonisManual {

    val sections: List<ManualSection> = listOf(
        ManualSection(
            id = "start",
            title = "1. Getting started",
            body = listOf(
                "Aeonis lets you take over a mob and play as it. The mob keeps its own body, its own physics and its own abilities; you supply the intent.",
                "Everything is driven by three commands: /transform to become a mob, /untransform to go back, and /transform info to see what you are currently wearing as a body.",
                "Look at a mob and press G to possess that exact mob instead of spawning a new one. Press H to step out of it again. This is the fastest way to try the system: no commands needed.",
                "Possessing an existing mob leaves the mob where it already was, which makes it ideal for single player testing and for multiplayer pranks: nothing about the mob changes visually."
            )
        ),
        ManualSection(
            id = "keys",
            title = "2. Keys and controls",
            body = listOf(
                "G - Possess the mob you are looking at. Works with any living mob and takes over the body you pointed at.",
                "H - Release. Returns you to your own body at the mob's feet.",
                "P - Soul mode possession. Enter soul mode to drift as a spectator, then press P on a mob to slip into it.",
                "R - Primary ability. The main action of your current form (a Blaze's fireball, a Skeleton's bow, a Spider's leap).",
                "V - Secondary ability. The supporting action, such as a Creeper's detonation, a Witch's healing brew or a Spider's cobweb.",
                "C - Tertiary ability. The rare signature move: dragon roars, warden rage, parrot mimicry.",
                "Z - Zoom. A hold-to-zoom scope that works in any form.",
                "B - Toggle freecam. Leaves your body behind and flies a detached camera; press again to snap back.",
                "N - Remote break. While freecam is active, lets you break blocks at the camera position.",
                "All of these are ordinary key bindings. Rebind them in Options, Controls, under the Aeonis category, and the HUD labels update to match.",
                "The HUD shows your current form, how it moves and its three ability keys with live cooldowns. Turn any of that off from the hub screen this manual lives in.",
                "The hub opens from Options, Aeonis, or with /aeonis menu. It is where the manual, the form catalogue, your display preferences and a few one-click actions live."
            )
        ),
        ManualSection(
            id = "catalogue",
            title = "3. The form catalogue",
            body = listOf(
                "Every form the mod can give you is listed in the catalogue, grouped by how it moves: things that walk, fly, hover, swim, climb and hop.",
                "Selecting a form shows three things: the exact command that becomes it, the locomotion the server will drive its body with, and the three abilities bound to R, V and C with a description of each.",
                "This page is generated from the same registries the gameplay code runs on. It cannot list a button that does nothing, and a form cannot gain an ability without this page gaining it too.",
                "Use it before you transform. Picking a form for how it moves is usually more useful than picking it for how it looks, since the movement is what you actually spend your time doing.",
                "A random form is one button away if you would rather be surprised."
            )
        ),
        ManualSection(
            id = "movement",
            title = "4. Movement is the mob's, not yours",
            body = listOf(
                "A transformed mob does not walk like a player. Aeonis reads each mob family's locomotion and reproduces it, so a possessed fish swims, a possessed bee hovers, and a possessed slime hops.",
                "Ground walkers use ordinary gravity, jumping and step-up. Holding jump while in water swims upward, exactly as vanilla expects for a wet mob.",
                "Flyers (bee, bat, parrot, allay, vex, phantom, breeze, wither, ender dragon, and the ghast family) get sustained vertical thrust and hold their altitude when you stop pressing jump. Pointing the camera up or down while moving forward steers them.",
                "Hovering mobs (ghast, happy ghast) hold altitude with a slow idle bob and move much more slowly than their size suggests, because that is how a ghast flies.",
                "Aquatic mobs (fish, squid, dolphin, turtle, axolotl, guardian) swim in three dimensions and follow your camera pitch, so looking down dives. Out of water they flop, with no thrust at all; that is intentional.",
                "Climbers (spider, cave spider) climb any wall they press against. Hold jump to go up the wall, sneak to come down it, and hold still to hang in place without sliding.",
                "Leapers (slime, magma cube, sulfur cube, frog, rabbit, goat, camel) move in a rhythm of hops. Holding jump does not remove the rhythm; it adds height.",
                "The chicken slow-falls. Its descent is clamped while gliding, which is the closest thing to a glide in vanilla.",
                "Sprinting is only given to forms with the physique for it. Hold the sprint key while moving forward and the form runs; a pufferfish will never outrun you."
            )
        ),
        ManualSection(
            id = "abilities",
            title = "5. Reading a mob's abilities",
            body = listOf(
                "Every ability is copied from what the mob does on its own. A Blaze throws its three-fireball spread, a Breeze throws wind charges, a Warden fires a sonic boom that punches through a line of enemies, and a Snow Golem pitches snowballs.",
                "Press the ability key for the slot you want. If nothing happens, a message tells you why: either the ability is still recharging, or it needs a target you were not aiming at.",
                "Cooldowns are per player, not per mob, so transforming again does not reset them and you cannot spam a dragon breath by hopping bodies.",
                "/transform abilities lists the kit of your current form in chat, and every ability is also described in the Abilities section of this menu."
            )
        ),
        ManualSection(
            id = "variants",
            title = "6. Variants",
            body = listOf(
                "A variant is a twist on a mob, applied when you transform. Add variant tokens after the mob name: /transform sheep red_sheep, /transform creeper powered, /transform goat screaming.",
                "Sheep support all sixteen wool colours. Creepers support powered, which really sets the charged flag rather than faking a glowing effect. Goats support screaming. Sizes, saddles, armour and baby/adult work on the mobs that have them.",
                "Some variants are conversions, not tweaks: /transform skeleton stray, /transform zombie husk. Those use the vanilla conversion path, so the new body keeps its equipment and data, and your session re-binds to it automatically.",
                "Unknown tokens, tokens for the wrong mob, and tokens that fail are all reported. Nothing is ever silently ignored.",
                "Run /transform variants <mob> to list what a mob supports, or /transform variants with a mob targeted."
            )
        ),
        ManualSection(
            id = "stealth",
            title = "7. Blending in",
            body = listOf(
                "A possessed mob is built to be harder to pick out than an ordinary one. It does not float a name tag, it does not glow, and it is not silent, and those three facts are enforced on the server every tick whether or not the client asks for them.",
                "When you stop giving input, the head keeps wandering on a slow, natural curve instead of locking to your camera, and the body keeps turning to catch up with it. A mob whose head is welded to one direction is the single clearest sign that a player is behind it.",
                "Idle bodies also shuffle. A possession that never changes position is a statue, so an idle form takes occasional small steps and turns slightly, the way a wandering mob does. It never fires while you are giving input, so it can never fight you for control.",
                "Mobs vocalise on their own schedule, using their own ambient sounds, so a possessed zombie still groans and a possessed creeper still hisses.",
                "Crouching is mirrored onto the body when the ground allows it, so you do not stand bolt upright in a one-block gap.",
                "There are three camouflage levels, chosen from the hub screen. Obvious turns all idle behaviour off: the body stands perfectly still whenever you are not driving it. You are easiest to aim and easiest to spot. This is the level to pick while learning a form.",
                "Natural is the default. Idle bodies drift, shuffle and vocalise like wild ones, which is what makes a possession survive a second look.",
                "Hidden adds two more tells: the form holds its gaze fixed on a point for seconds at a time, the way a mob does when it has noticed something, and it murmurs more often. This is the hardest to pick out and slightly the floatiest to steer, because the body is never quite still.",
                "The body you leave behind is hidden while you are inside a mob, so there is no abandoned player model standing next to a suspiciously well-behaved cow.",
                "Third person is supported: the camera anchors to the mob's own eye position and backs off further for large forms, which is what makes a possessed ender dragon actually playable.",
                "Camouflage is not invulnerability. A suspicious player can still test you by hitting you, and your abilities give you away the moment you use them. It only removes the mechanical tells that would let someone pick you out at a glance."
            )
        ),
        ManualSection(
            id = "commands",
            title = "8. Commands",
            body = listOf(
                "/transform <mob> [variants...] - Become that mob. Example: /transform enderman.",
                "/transform random - Become a random mob that is legal to possess.",
                "/transform info - Show your current form, its movement style, its variants and how long you have held it.",
                "/transform abilities - List the ability kit of your current form with its keys.",
                "/transform variants <mob> - List the variants a mob supports.",
                "/untransform - Return to your own body. Safe to run at any time, including from a menu.",
                "/possess - Take over the mob you are looking at, keeping it as it is.",
                "/prank morph <player> <mob> - Force another player into a form.",
                "/prank <target> <effect> - The prank toolbox: sounds, fake messages, particles, item swaps.",
                "/ability <name> - Standalone ability commands (dash, blink, mimic, summon) usable outside a transform.",
                "/ai <subcommand> - Inspect and drive mob AI: what a mob is targeting, its goals, and a chaotic mode for demonstrations.",
                "/event <name> - Timed world events such as thunder domes, ambushes and pro-gamer mode.",
                "/aeonis - Status and administration: features, reload, diagnostics, and /aeonis menu to reopen this screen.",
                "/comp - Companion bot management.",
                "Commands with a player argument accept a selector or a name. Most gameplay commands require operator permission; the transform commands do not, unless the server turns them off."
            )
        ),
        ManualSection(
            id = "world",
            title = "9. World settings",
            body = listOf(
                "World settings are server-side on purpose: they apply to everyone in the world and cannot be changed by a modified client. They live in aeonis_features.properties in the world folder.",
                "/aeonis features extra_mobs true|false - Enable or disable the extra mob spawns.",
                "/aeonis features safe_chest_physics true|false - Chests that do not crush or suffocate mobs.",
                "/aeonis features chest_fall_damage true|false - Whether falling chests still hurt entities.",
                "/aeonis reload - Re-read all configuration and unwind every active transform safely.",
                "This menu shows the current values but does not edit them; use the commands above, or edit the file and reload."
            )
        ),
        ManualSection(
            id = "multiplayer",
            title = "10. Multiplayer and servers",
            body = listOf(
                "Transforming is server-authoritative. The server spawns the body, owns its movement, health and abilities, and decides what everyone else sees.",
                "That means nobody can give themselves a dragon on a server that has not installed Aeonis: the client side of the mod only draws what the server tells it.",
                "Your health bar follows the mob. Damage taken in a form is real damage, and stepping out carries the remaining fraction back to your own body instead of refunding it.",
                "Disconnecting, dying or the server restarting all unwind the session cleanly, so you never come back invisible with an orphaned mob saved in the world.",
                "Mod detection: because possessed mobs are camouflaged, other players cannot identify a mind-controlled mob from the client alone. See it in action, though, and the abilities give it away."
            )
        ),
        ManualSection(
            id = "trouble",
            title = "11. Troubleshooting",
            body = listOf(
                "Nothing happens when I press R. Open the HUD, or run /transform abilities: either your form has no ability in that slot, or it is recharging.",
                "I am stuck as a mob. Run /untransform. It is idempotent and works even if the session record is gone; it restores your gamemode, visibility, walk speed and health.",
                "The camera is inside the mob's head. Turn on third person, or use a form whose eye height suits a first-person view; small mobs are intentionally close to the ground.",
                "I cannot fly as a bee. Flight is granted by the form's own locomotion. Press jump to gain altitude and release to hold it; if you are still sinking, you are a mob that cannot fly.",
                "My body is missing. That is intended: the body is hidden while you are inside a mob so you cannot be identified by it.",
                "An ability reports 'needs a valid target'. Aim at a living entity within the ability's range and try again; several abilities also require line of sight."
            )
        ),
        ManualSection(
            id = "about",
            title = "12. About and credits",
            body = listOf(
                "Aeonis 5.0 targets Minecraft 26.3 or newer on Fabric and NeoForge from a single shared codebase with unified SpruceUI architecture.",
                "Developed and maintained by QuantumCreeper & MrMK from Aevora Labs. Licensed under GNU General Public License v3.0 (GPLv3).",
                "Official Website: https://aeonis.prenxy.app · Source Code & Issues: https://github.com/TheAevoraLabs/aeonis-cm",
                "/aeonis diagnostics prints the runtime version, loader parity, and feature state in real time."
            )
        )
    )

    fun byId(id: String): ManualSection? = sections.firstOrNull { it.id == id }

    fun indexOf(id: String): Int = sections.indexOfFirst { it.id == id }.coerceAtLeast(0)
}
