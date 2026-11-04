package io.pfaumc.pfauprotect.api

import net.luckperms.api.LuckPermsProvider
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Who a call is made for. [granted] is what a trusted gateway vouched for: the permissions of an offline player,
 * an empty set being none at all; null leaves them to be asked of this server.
 */
internal class Access(val player: UUID, val granted: Set<String>? = null)

/** Throws unless [access] holds [permission]; no access is trusted code, which holds everything. */
internal fun require(access: Access?, permission: String, ask: (Access, String) -> Boolean? = { a, p -> allowed(a, p) }) {
    access ?: return
    when (ask(access, permission)) {
        true -> {}
        false -> throw PfauProtectException(PfauProtectException.FORBIDDEN, "No permission: $permission.")
        null -> throw PfauProtectException(
            PfauProtectException.OFFLINE,
            "The player is offline, and nothing on this server can say whether they hold $permission.",
        )
    }
}

/**
 * Online, Bukkit's own answer. Offline: what the gateway vouched for, else a [PfauProtectPermissions] some plugin
 * registered, else LuckPerms; null when none of them can tell. Asked off the region threads, as any of them may block.
 */
internal fun allowed(access: Access, permission: String, online: (UUID) -> Player? = Bukkit::getPlayer): Boolean? {
    online(access.player)?.let { return it.hasPermission(permission) }
    access.granted?.let { return grants(it, permission) }
    for (registration in Bukkit.getServicesManager().getRegistrations(PfauProtectPermissions::class.java)) {
        registration.provider.has(access.player, permission)?.let { return it }
    }
    // A LuckPerms that did not come up, or whose storage did not answer in time, can say nothing either.
    if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) return runCatching { LuckPermsAccess.has(access.player, permission) }.getOrNull()
    return null
}

/** Whether a set of nodes holds [permission], itself or by a wildcard over it: `pfauprotect.*`, `*`. */
internal fun grants(granted: Set<String>, permission: String): Boolean {
    if (permission in granted || "*" in granted) return true
    val parts = permission.split('.')
    return (1 until parts.size).any { n -> parts.take(n).joinToString(".") + ".*" in granted }
}

// Its own object, so LuckPerms' classes are loaded only on a server that has them.
private object LuckPermsAccess {
    fun has(player: UUID, permission: String): Boolean? {
        val user = LuckPermsProvider.get().userManager.loadUser(player).get(LUCKPERMS_SECONDS, TimeUnit.SECONDS) ?: return null
        return user.cachedData.permissionData.checkPermission(permission).asBoolean()
    }
}

private const val LUCKPERMS_SECONDS = 5L
