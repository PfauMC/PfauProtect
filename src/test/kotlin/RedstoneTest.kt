package io.pfaumc.pfauprotect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.Minecart
import org.bukkit.entity.Pig
import org.bukkit.entity.Player
import org.bukkit.entity.Wolf
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
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

    // An entity stands in for itself with only what the ladder asks of it answered.
    @Suppress("UNCHECKED_CAST")
    private fun <T : Entity> entity(type: Class<T>, id: UUID, answers: Map<String, Any?> = emptyMap()): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            when {
                method.name == "getUniqueId" -> id
                method.name in answers -> answers[method.name]
                method.returnType == List::class.java -> emptyList<Entity>()
                method.returnType == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        } as T

    private val nudges = Nudges { clock }
    private val origins = EntityOrigins { clock }

    @Test
    fun `an item answers to its thrower and a mob to whoever rides it`() {
        val item = entity(Item::class.java, UUID.randomUUID(), mapOf("getThrower" to alice))
        assertEquals(Attributed(alice, Confidence.FACT), behind(item, nudges, origins))

        val rider = entity(Player::class.java, bob)
        val pig = entity(Pig::class.java, UUID.randomUUID(), mapOf("getPassengers" to listOf(rider)))
        assertEquals(Attributed(bob, Confidence.FACT), behind(pig, nudges, origins))
    }

    // A push lasts while the thing pushed can still be rolling from it: seconds for a mob, a minute for
    // a cart.
    @Test
    fun `a push names the pusher for as long as the entity can still be moving from it`() {
        val pig = entity(Pig::class.java, UUID.randomUUID())
        val cart = entity(Minecart::class.java, UUID.randomUUID())
        nudges.nudged(pig, Attributed(alice, Confidence.FACT))
        nudges.nudged(cart, Attributed(alice, Confidence.FACT))
        assertEquals(Attributed(alice, Confidence.INFERRED), behind(pig, nudges, origins))

        clock += 30_000
        assertNull(behind(pig, nudges, origins))
        assertEquals(alice, behind(cart, nudges, origins)?.actor)
    }

    @Test
    fun `a tame animal answers to its owner and a wild one to nobody`() {
        val wolf = entity(Wolf::class.java, UUID.randomUUID(), mapOf("getOwnerUniqueId" to bob))
        assertEquals(Attributed(bob, Confidence.INFERRED), behind(wolf, nudges, origins))
        assertNull(behind(entity(Pig::class.java, UUID.randomUUID()), nudges, origins))
    }

    // A witness is not a culprit: an item row, which cannot say how sure it is of its actor, leaves them
    // off, and a step down the chain keeps them a witness.
    @Test
    fun `a witness stays a witness down the chain and off the item rows`() {
        val witness = Attributed(alice, Confidence.NEARBY)
        assertEquals(witness, witness.inferred())
        assertNull(witness.culprit())
        assertEquals(Attributed(alice, Confidence.INFERRED), Attributed(alice, Confidence.FACT).inferred())
        assertEquals(alice, Attributed(alice, Confidence.INFERRED).culprit())
    }
}
