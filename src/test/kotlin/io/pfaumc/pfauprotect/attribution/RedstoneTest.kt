package io.pfaumc.pfauprotect.attribution
import io.pfaumc.pfauprotect.BothPlanes
import io.pfaumc.pfauprotect.T0
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.bob
import io.pfaumc.pfauprotect.Vanilla
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.storage.BlockChange
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.world.level.block.Blocks
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.Tag
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.entity.EntityType
import org.bukkit.entity.Zombie
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityInteractEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.extension.ExtendWith
import net.minecraft.core.registries.Registries as VanillaRegistries
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.stub
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
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
import java.util.UUID

@ExtendWith(Vanilla::class)
class RedstoneTest : BothPlanes() {
    companion object {
        private val server = Bukkit::class.java.getDeclaredField("server").apply { isAccessible = true }

        // `Tag` reads its tags off the server once, the first time anything touches it, and there is no
        // server under the tests: vanilla's own block tags answer for it, and the server is gone again
        // before the next class runs.
        @JvmStatic
        @BeforeAll
        fun tags() {
            if (server.get(null) != null) return
            server.set(null, stub(Server::class.java, mapOf("getTag" to { args: Array<out Any?> -> blockTag(args[0] as String, args[1] as NamespacedKey) })))
        }

        @JvmStatic
        @AfterAll
        fun noServer() = server.set(null, null)

        private fun blockTag(registry: String, key: NamespacedKey): Tag<Material>? {
            if (registry != "blocks") return null
            val tag = TagKey.create(VanillaRegistries.BLOCK, Identifier.parse(key.toString()))
            return stub(Tag::class.java, mapOf(
                "getKey" to key,
                "isTagged" to { args: Array<out Any?> ->
                    BuiltInRegistries.BLOCK.getValue(Identifier.parse((args[0] as Material).key.toString())).defaultBlockState().`is`(tag)
                },
            )) as Tag<Material>
        }
    }

    private var clock = T0
    private val energy = Energy { clock }
    private var nanos = 0L

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
    fun `a chain carries its note further than one reach, as a guess`() {
        val listener = RedstoneListener(energy, logs, origins, nudges, Attribution(shared.registries, logs))
        energy.note(at(0), Attributed(alice, Confidence.FACT))
        for (x in 1..20) listener.onRedstone(BlockRedstoneEvent(repeater(at(x)), 0, 15))
        assertEquals(Attributed(alice, Confidence.INFERRED), energy.near(at(20), reach = 0))
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

    private class Switch(
        val name: String,
        val data: BlockData,
        val expected: Triple<Cause, UUID?, Confidence>?,
        val placedBy: UUID? = null,
        val act: Switching.() -> Unit,
    )

    private class Switching(val listener: RedstoneListener, val block: Block, val players: Map<UUID, Player>) {
        fun press(by: UUID, action: Action) =
            listener.onInteract(PlayerInteractEvent(players.getValue(by), action, null, block, BlockFace.UP))

        fun step(entity: Entity) = listener.onEntityInteract(EntityInteractEvent(entity, block))

        fun redstone() = listener.onRedstone(BlockRedstoneEvent(block, 0, 15))
    }

    // A switch's row is written from the redstone change its own press raises, and names who stands
    // behind the press: the presser, the one who laid a trap somebody else stepped into, or for a mob
    // nobody stands behind, the nearest player, as a witness only.
    @Test
    fun `a switch is written on whoever stands behind the press that moved it`() {
        val button = Blocks.STONE_BUTTON.defaultBlockState().asBlockData()
        val plate = Blocks.STONE_PRESSURE_PLATE.defaultBlockState().asBlockData()
        val zombie = stub(Zombie::class.java, mapOf("getUniqueId" to UUID.randomUUID(), "getType" to EntityType.ZOMBIE), lenient = true)
        val cases = listOf(
            Switch("button clicked", button, Triple(Cause.BLK_PLAYER_SWITCH, alice, Confidence.FACT)) {
                press(alice, Action.RIGHT_CLICK_BLOCK); redstone()
            },
            Switch("plate stepped on", plate, Triple(Cause.BLK_PLAYER_SWITCH, alice, Confidence.FACT)) {
                press(alice, Action.PHYSICAL); redstone()
            },
            Switch("button stepped on", button, null) { press(alice, Action.PHYSICAL); redstone() },
            Switch("plate clicked", plate, null) { press(alice, Action.RIGHT_CLICK_BLOCK); redstone() },
            Switch("Alice's trap, Bob steps in", plate, Triple(Cause.BLK_PLAYER_SWITCH, alice, Confidence.INFERRED), placedBy = alice) {
                press(bob, Action.PHYSICAL); redstone()
            },
            Switch("Alice on her own plate", plate, Triple(Cause.BLK_PLAYER_SWITCH, alice, Confidence.FACT), placedBy = alice) {
                press(alice, Action.PHYSICAL); redstone()
            },
            Switch("a mob nobody stands behind", plate, Triple(Cause.BLK_ENTITY_SWITCH, bob, Confidence.NEARBY)) {
                step(zombie); redstone()
            },
            Switch("a press whose change never came", button, null) {
                press(alice, Action.RIGHT_CLICK_BLOCK)
                nanos += PRESS_NANOS + 1
                redstone()
            },
        )
        val found = cases.mapIndexed { i, case ->
            // Far enough apart that no case's energy note reaches another's switch.
            val x = i * 10
            case.placedBy?.let {
                log.submit(listOf(BlockChange(x, 64, 0, "minecraft:air", case.data.asString, Cause.BLK_PLAYER_PLACE, actor = it)))
                log.drain()
            }
            lateinit var ground: World
            fun player(id: UUID, dx: Double) = stub(Player::class.java, mapOf(
                "getUniqueId" to id,
                "getLocation" to { _: Array<out Any?> -> Location(ground, x + 0.5 + dx, 64.5, 0.5) },
            ))
            val players = mapOf(alice to player(alice, 10.0), bob to player(bob, 3.0))
            ground = stub(World::class.java, mapOf(
                "getUID" to world,
                "isChunkLoaded" to false,
                "getNearbyPlayers" to { _: Array<out Any?> -> players.values.toList() },
            ))
            val block = stub(Block::class.java, mapOf(
                "getWorld" to ground,
                "getX" to x,
                "getY" to 64,
                "getZ" to 0,
                "getType" to case.data.material,
                "getBlockData" to case.data,
                "getLocation" to { _: Array<out Any?> -> Location(ground, x.toDouble(), 64.0, 0.0) },
            ))
            val listener = RedstoneListener(energy, logs, origins, nudges, Attribution(shared.registries, logs)) { nanos }
            Switching(listener, block, players).(case.act)()
            log.drain()
            val row = log.at(x, 64, 0, 0, Long.MAX_VALUE, 10, reverse = false).lastOrNull()
                ?.takeIf { it.cause != Cause.BLK_PLAYER_PLACE }
            case.name to row?.let { Triple(it.cause, it.actor, it.confidence) }
        }
        assertEquals(cases.map { it.name to it.expected }, found)

        // The witness's distance goes with the mob's type, to the tenth of a block.
        val mob = cases.indexOfFirst { it.data == plate && it.expected?.first == Cause.BLK_ENTITY_SWITCH } * 10
        val witnessed = log.at(mob, 64, 0, 0, Long.MAX_VALUE, 10, reverse = false).single()
        assertEquals("minecraft:zombie 3.0", String(shared.payload(witnessed.payloadAfter!!)!!))
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
