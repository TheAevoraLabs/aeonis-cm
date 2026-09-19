package com.qc.aeonis.inventory

import com.qc.aeonis.companion.inventory.CompanionMenu
import com.qc.aeonis.registration.AeonisRegistries
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.MenuType
import java.lang.reflect.Proxy
import java.util.function.Supplier

fun interface MenuFactory<T : AbstractContainerMenu> {
    fun create(syncId: Int, playerInventory: Inventory): T
}

object AeonisMenus {
    lateinit var COMPANION_MENU: Supplier<MenuType<CompanionMenu>>

    @Suppress("UNCHECKED_CAST")
    fun <T : AbstractContainerMenu> createMenuType(factory: MenuFactory<T>): MenuType<T> {
        val constructor = MenuType::class.java.declaredConstructors.first()
        constructor.isAccessible = true
        val supplierInterface = constructor.parameterTypes[0]

        val proxy = Proxy.newProxyInstance(
            supplierInterface.classLoader,
            arrayOf(supplierInterface)
        ) { _, method, args ->
            if (method.name == "create") {
                factory.create(args[0] as Int, args[1] as Inventory)
            } else {
                null
            }
        }

        return constructor.newInstance(proxy, FeatureFlags.VANILLA_SET) as MenuType<T>
    }

    fun register() {
        COMPANION_MENU = AeonisRegistries.registerMenuType("companion_menu") {
            createMenuType { syncId, playerInventory ->
                CompanionMenu.createClientMenu(syncId, playerInventory)
            }
        }
    }
}
