package com.qc.aeonis.item

import com.qc.aeonis.registration.AeonisRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.Item
import java.util.function.Supplier

object AeonisItems {
    lateinit var ANCARD_LIGHTER: Supplier<Item>
    lateinit var RHISTEL: Supplier<Item>

    fun register() {
        ANCARD_LIGHTER = register("ancard_lighter") { AncardLighterItem(Item.Properties().setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("aeonis", "ancard_lighter"))).stacksTo(1)) }
        RHISTEL = register("rhistel") { RhistelItem(Item.Properties().setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("aeonis", "rhistel")))) }
    }

    private fun <T : Item> register(name: String, supplier: () -> T): Supplier<T> {
        return AeonisRegistries.registerItem(name, Supplier { supplier() })
    }
}
