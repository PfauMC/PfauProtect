package io.pfaumc.pfauprotect

import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TeleportModeTest {
    private fun player(mode: GameMode, bypass: Boolean): Player = stub(
        Player::class.java,
        mapOf("getGameMode" to mode, "hasPermission" to { args: Array<out Any?> -> bypass && args[0] == "pfauprotect.teleport.bypass" }),
    )

    @Test
    fun `without bypass only spectator teleports, with it every mode does`() {
        for (mode in GameMode.entries) {
            val plain = player(mode, bypass = false)
            assertEquals(mode != GameMode.SPECTATOR, teleportRefused(plain, plain), "no bypass, $mode")
            val admin = player(mode, bypass = true)
            assertEquals(false, teleportRefused(admin, admin), "bypass, $mode")
        }
    }

    @Test
    fun `the asker's bypass counts, not the teleported player's`() {
        val target = player(GameMode.SURVIVAL, bypass = true)
        assertEquals(true, teleportRefused(player(GameMode.SPECTATOR, bypass = false), target))
        assertEquals(false, teleportRefused(player(GameMode.SURVIVAL, bypass = true), player(GameMode.SURVIVAL, bypass = false)))
    }
}
