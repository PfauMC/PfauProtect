package io.pfaumc.pfauprotect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class RedstoneTest {
    private val world = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val alice = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val bob = UUID.fromString("00000000-0000-4000-8000-0000000000b0")
    private var clock = 1_700_000_000_000L
    private val energy = Energy { clock }

    private fun at(x: Int, y: Int = 0, z: Int = 0) = WorldBlock(world, x, y, z)

    // A lever through a solid block into a dispenser is two steps away, which is as far as it reaches.
    @Test
    fun `a component finds the note two blocks away and no further`() {
        energy.note(at(0), Attributed(alice, Confidence.FACT))
        assertEquals(alice, energy.near(at(2, 2, -2))?.actor)
        assertNull(energy.near(at(3)))
    }

    // Each step copies the note onto itself, so the chain outruns the reach of its first note.
    @Test
    fun `a chain carries its note further than one reach`() {
        energy.note(at(0), Attributed(alice, Confidence.FACT))
        for (x in 1..20) energy.near(at(x))?.let { energy.note(at(x), it) }
        assertEquals(alice, energy.near(at(20), reach = 0)?.actor)
    }

    @Test
    fun `a note older than one step answers for nothing`() {
        energy.note(at(0), Attributed(alice, Confidence.FACT))
        clock += ENERGY_MILLIS + 1
        assertNull(energy.near(at(0)))
        energy.sweep()
        assertEquals(0, energy.size)
    }

    // A clock somebody started keeps its own notes fresh; a newer press next to it is still newer.
    @Test
    fun `the freshest note wins and of two equally fresh the surer one`() {
        energy.note(at(0), Attributed(alice, Confidence.FACT))
        clock += 100
        energy.note(at(1), Attributed(bob, Confidence.INFERRED))
        assertEquals(bob, energy.near(at(0))?.actor)

        energy.note(at(2), Attributed(alice, Confidence.FACT))
        assertEquals(Attributed(alice, Confidence.FACT), energy.near(at(1)))
    }

    @Test
    fun `an explosion is whoever set it off, and everything else whoever put the item there`() {
        assertEquals(bob, endedBy(Cause.ITEM_DESTROY_EXPLOSION, blaster = bob, thrower = alice, dispensedBy = null))
        assertEquals(alice, endedBy(Cause.ITEM_DESTROY_EXPLOSION, blaster = null, thrower = alice, dispensedBy = bob))
        assertEquals(bob, endedBy(Cause.ITEM_DESTROY_EXPLOSION, blaster = null, thrower = null, dispensedBy = bob))
        // Fire burning an item names who threw it into the fire, not who blew something up nearby.
        assertEquals(alice, endedBy(Cause.ITEM_DESTROY_FIRE, blaster = bob, thrower = alice, dispensedBy = null))
        assertNull(endedBy(Cause.ITEM_DESPAWN, blaster = null, thrower = null, dispensedBy = null))
    }

    // Every writer of the block plane goes through submit, so the log is where rows become notes.
    @Test
    fun `the block log shows every accepted row to its watcher`(@TempDir dir: Path) {
        val seen = ArrayList<Pair<UUID, BlockChange>>()
        RocksItemLog(dir.resolve("items")).use { shared ->
            BlockLogs(dir.resolve("blocks"), shared) { world, changes -> changes.forEach { seen += world to it } }.use { logs ->
                val change = BlockChange(1, 2, 3, "minecraft:air", "minecraft:stone", Cause.BLK_PLAYER_PLACE, actor = alice)
                logs.open(world).submit(listOf(change))
                assertEquals(listOf(world to change), seen)
            }
        }
    }
}
