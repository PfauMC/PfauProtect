package io.pfaumc.pfauprotect.attribution
import io.pfaumc.pfauprotect.T0
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.bob
import io.pfaumc.pfauprotect.world
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.storage.RocksItemLog
import io.pfaumc.pfauprotect.stub
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.event.block.BlockRedstoneEvent
import org.bukkit.entity.Arrow
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.Minecart
import org.bukkit.entity.Pig
import org.bukkit.entity.Player
import org.bukkit.entity.Skeleton
import org.bukkit.entity.Wolf
import java.nio.file.Path
import java.util.UUID

class RedstoneTest {
    private var clock = T0
    private val energy = Energy { clock }

    private fun at(x: Int, y: Int = 0, z: Int = 0) = WorldBlock(world, x, y, z)

    // A lever through a solid block into a dispenser is two steps away, which is as far as it reaches.
    @Test
    fun `a component finds the note two blocks away and no further`() {
        energy.note(at(0), Attributed(alice, Confidence.FACT))
        assertEquals(alice, energy.near(at(2, 2, -2))?.actor)
        assertNull(energy.near(at(3)))
    }

    // Each component that changes copies the note onto itself, so the chain outruns the reach of its
    // first note; past the press itself every step is worked out.
    @Test
    fun `a chain carries its note further than one reach, as a guess`(@TempDir dir: Path) {
        RocksItemLog(dir.resolve("items")).use { shared ->
            BlockLogs(dir.resolve("blocks"), shared).use { logs ->
                val listener = RedstoneListener(energy, logs, origins, nudges, Attribution(shared.registries, logs))
                energy.note(at(0), Attributed(alice, Confidence.FACT))
                for (x in 1..20) listener.onRedstone(BlockRedstoneEvent(repeater(at(x)), 0, 15))
                assertEquals(Attributed(alice, Confidence.INFERRED), energy.near(at(20), reach = 0))
            }
        }
    }

    // Unloaded around it, so a component that finds no note does not go walking wire.
    private fun repeater(at: WorldBlock): Block = stub(
        Block::class.java,
        mapOf(
            "getWorld" to stub(World::class.java, mapOf("getUID" to at.world), lenient = true),
            "getX" to at.x,
            "getY" to at.y,
            "getZ" to at.z,
            "getType" to Material.REPEATER,
        ),
    )

    @Test
    fun `a note older than one step answers for nothing`() {
        val noted = clock
        energy.note(at(0), Attributed(alice, Confidence.FACT))
        clock += ENERGY_MILLIS + 1
        assertNull(energy.near(at(0)))
        energy.sweep()
        // The read enforces the window itself; only a read from the moment of the note can tell it is gone.
        clock = noted
        assertNull(energy.near(at(0)))
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

    // An entity stands in for itself with only what the ladder asks of it answered.
    private fun <T : Entity> entity(type: Class<T>, id: UUID, answers: Map<String, Any?> = emptyMap()): T =
        stub(type, mapOf("getUniqueId" to id) + answers, lenient = true)

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

    // The newest thing a player did to the entity outranks where it came from, and both outrank an owner
    // who may be nowhere near.
    @Test
    fun `a push outranks a summoner, and a summoner the owner`() {
        val pusher = UUID.randomUUID()
        val id = UUID.randomUUID()
        val wolf = entity(Wolf::class.java, id, mapOf("getOwnerUniqueId" to bob))
        origins.appeared(id, alice)
        assertEquals(alice, behind(wolf, nudges, origins)?.actor)

        nudges.nudged(wolf, Attributed(pusher, Confidence.FACT))
        assertEquals(pusher, behind(wolf, nudges, origins)?.actor)
    }

    @Test
    fun `an arrow a mob shot answers to whoever stands behind the mob, as a guess`() {
        val skeleton = entity(Skeleton::class.java, UUID.randomUUID())
        origins.appeared(skeleton.uniqueId, alice, Confidence.FACT)
        val arrow = entity(Arrow::class.java, UUID.randomUUID(), mapOf("getShooter" to skeleton))

        assertEquals(Attributed(alice, Confidence.INFERRED), behind(arrow, nudges, origins))
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
