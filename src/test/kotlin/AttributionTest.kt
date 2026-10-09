package io.pfaumc.pfauprotect

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

private const val START = 1_700_000_000_000L

private const val AIR = "minecraft:air"
private const val TNT = "minecraft:tnt"
private const val STONE = "minecraft:stone"
private const val WATER = "minecraft:water[level=1]"
private const val SAND = "minecraft:sand"
private const val CHEST_RIGHT = "minecraft:chest[facing=north,type=right]"
private const val CHEST_SINGLE = "minecraft:chest[facing=north,type=single]"

class AttributionTest {
    private val world = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val alice = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val bob = UUID.fromString("00000000-0000-4000-8000-0000000000b0")

    private lateinit var shared: RocksItemLog
    private lateinit var logs: BlockLogs
    private lateinit var log: BlockLog
    private lateinit var attribution: Attribution
    private var clock = START

    @BeforeEach
    fun open(@TempDir tempDir: Path) {
        shared = RocksItemLog(tempDir.resolve("items"))
        logs = BlockLogs(tempDir.resolve("blocks"), shared)
        log = logs.open(world)
        attribution = Attribution(shared.registries, logs) { clock }
    }

    @AfterEach
    fun closeAll() {
        logs.close()
        shared.close()
    }

    private fun at(x: Int, y: Int, z: Int) = WorldBlock(world, x, y, z)

    private fun journalled(
        at: WorldBlock,
        before: String,
        after: String,
        actor: UUID?,
        ts: Long = START,
        cause: Cause = Cause.BLK_PLAYER_PLACE,
    ) {
        log.submit(
            listOf(
                BlockChange(
                    x = at.x,
                    y = at.y,
                    z = at.z,
                    before = before,
                    after = after,
                    cause = cause,
                    timestamp = ts,
                    actor = actor,
                )
            )
        )
        log.drain()
    }

    @Test
    fun `the tracker answers on its own and the journal only where it is asked for by name`() {
        val here = at(10, 64, 10)
        assertNull(attribution.placerAt(here, TNT))
        assertNull(attribution.journalPlacerAt(here, TNT))

        journalled(here, AIR, TNT, bob)
        assertNull(attribution.placerAt(here, TNT))
        assertEquals(bob, attribution.journalPlacerAt(here, TNT)?.actor)

        attribution.placed(here, TNT, alice)
        assertEquals(alice, attribution.placerAt(here, TNT)?.actor)

        assertNull(attribution.placerAt(at(11, 64, 10), TNT))
        assertNull(attribution.journalPlacerAt(at(11, 64, 10), TNT))
    }

    @Test
    fun `nothing this answers with is a fact`() {
        val here = at(10, 64, 10)
        journalled(here, AIR, TNT, bob)
        assertEquals(Confidence.INFERRED, attribution.journalPlacerAt(here, TNT)?.confidence)

        attribution.placed(here, TNT, alice)
        assertEquals(Confidence.INFERRED, attribution.placerAt(here, TNT)?.confidence)
    }

    @Test
    fun `the journal rung stops at what stands there today`() {
        val here = at(10, 64, 10)
        journalled(here, AIR, TNT, alice, START)
        journalled(here, TNT, AIR, bob, START + 1, Cause.BLK_PLAYER_BREAK)
        journalled(here, AIR, STONE, bob, START + 2)

        assertNull(attribution.journalPlacerAt(here, TNT))
        assertEquals(bob, attribution.journalPlacerAt(here, STONE)?.actor)
    }

    @Test
    fun `a variant of the same block still matches itself`() {
        val here = at(10, 64, 10)
        journalled(here, AIR, "minecraft:oak_stairs[facing=north,waterlogged=false]", alice)
        assertEquals(
            alice,
            attribution.journalPlacerAt(here, "minecraft:oak_stairs[facing=east,waterlogged=true]")?.actor,
        )
    }

    @Test
    fun `a row that named nobody lends nobody`() {
        val here = at(10, 64, 10)
        journalled(here, AIR, TNT, null)
        assertNull(attribution.journalPlacerAt(here, TNT))
    }

    // Bob breaks the left half of Alice's double chest: the capture rewrites the surviving half to
    // type=single and names Bob on that row, and a chest still stands there.
    @Test
    fun `the journal refuses a row that did not put the block there`() {
        val broken = at(10, 64, 10)
        journalled(broken, AIR, CHEST_RIGHT, alice, START)
        journalled(broken, CHEST_RIGHT, CHEST_SINGLE, bob, START + 1, Cause.BLK_PLAYER_BREAK)
        assertNull(attribution.journalPlacerAt(broken, CHEST_SINGLE))

        val rewritten = at(20, 64, 20)
        journalled(rewritten, AIR, CHEST_RIGHT, alice, START)
        journalled(rewritten, CHEST_RIGHT, CHEST_SINGLE, bob, START + 1)
        assertNull(attribution.journalPlacerAt(rewritten, CHEST_SINGLE))

        // A block that arrived by landing was put down by nobody standing here, and the actor of such
        // a row was himself worked out. One guess is not made to carry another.
        val landed = at(30, 64, 30)
        journalled(landed, AIR, TNT, bob, START, Cause.BLK_FALL_LAND)
        assertNull(attribution.journalPlacerAt(landed, TNT))
    }

    // A break notes the position it emptied, so within the window that note is the newest thing said
    // about the position. Bob mines a tunnel; a piston feeds TNT into it and fires it seconds later.
    @Test
    fun `a note about the block that was taken away answers for nothing that moves in`() {
        val here = at(10, 64, 10)
        attribution.removed(here, bob)
        attribution.placed(here, AIR, bob)

        clock += 5_000
        assertNull(attribution.placerAt(here, TNT))
        assertNull(attribution.carriedTo(here, TNT))
        assertEquals(bob, attribution.placerAt(here, AIR)?.actor)
    }

    @Test
    fun `a tracker note answers for a variant of its own block`() {
        val here = at(10, 64, 10)
        attribution.placed(here, "minecraft:oak_stairs[facing=north,waterlogged=false]", alice)
        assertEquals(
            alice,
            attribution.placerAt(here, "minecraft:oak_stairs[facing=east,waterlogged=true]")?.actor,
        )
    }

    // Three steps of flow with a window that covers one of them: each step writes its own note where
    // it arrived, so the chain outlives every note in it.
    @Test
    fun `ownership carries along a chain the window does not span`() {
        val source = at(0, 64, 0)
        attribution.placed(source, WATER, alice)

        val first = at(1, 64, 0)
        assertEquals(alice, attribution.carriedTo(first, WATER)?.actor)

        clock += NOTE_MILLIS - 1
        val second = at(2, 64, 0)
        assertEquals(alice, attribution.carriedTo(second, WATER)?.actor)

        clock += NOTE_MILLIS - 1
        val third = at(3, 64, 0)
        assertEquals(alice, attribution.carriedTo(third, WATER)?.actor)

        assertNull(attribution.placerAt(source, WATER))
        assertNull(attribution.placerAt(first, WATER))
    }

    // Bob's water spreads east while Alice lays a stone path beyond it. The stone is the newer note
    // and sits on the face the search reaches first, and it is about a block that never flowed.
    @Test
    fun `the carry takes the neighbour that is about what arrived`() {
        attribution.placed(at(10, 64, 10), WATER, bob)

        clock += 5_000
        attribution.placed(at(12, 64, 10), STONE, alice)

        val reached = at(11, 64, 10)
        assertEquals(bob, attribution.carriedTo(reached, WATER)?.actor)
        assertEquals(bob, attribution.placerAt(reached, WATER)?.actor)
    }

    @Test
    fun `the support search reaches a diagonal cell and no further`() {
        val broken = at(5, 64, 5)
        attribution.removed(broken, alice)

        val corner = attribution.supportRemoverAt(at(6, 65, 6))
        assertEquals(alice, corner?.actor)
        assertEquals(Confidence.INFERRED, corner?.confidence)

        assertNull(attribution.supportRemoverAt(at(7, 66, 7)))

        clock += SUPPORT_MILLIS + 1
        assertNull(attribution.supportRemoverAt(at(6, 65, 6)))
    }

    // Two players mining shoulder to shoulder in one tick: the cell under the column is the one that
    // was holding it up, whatever the scan order over the corners would have said.
    @Test
    fun `a face beats a diagonal broken in the same tick`() {
        attribution.removed(at(4, 64, 4), bob)
        attribution.removed(at(5, 64, 5), alice)

        assertEquals(alice, attribution.supportRemoverAt(at(5, 65, 5))?.actor)
    }

    @Test
    fun `the most recent removal of a ring is the one that answers`() {
        attribution.removed(at(6, 65, 5), bob)

        clock += 10
        attribution.removed(at(5, 64, 5), alice)

        assertEquals(alice, attribution.supportRemoverAt(at(5, 65, 5))?.actor)
    }

    // A silverfish appears inside the block that was broken, which is the one cell the support search
    // skips.
    @Test
    fun `the remover of a position answers for that position and not its neighbours`() {
        attribution.removed(at(5, 64, 5), alice)

        assertNull(attribution.supportRemoverAt(at(5, 64, 5)))
        assertEquals(alice, attribution.removerAt(at(5, 64, 5))?.actor)
        assertNull(attribution.removerAt(at(5, 65, 5)))

        clock += SUPPORT_MILLIS + 1
        assertNull(attribution.removerAt(at(5, 64, 5)))
    }

    // Leaves drop minutes after the log that held them, long after every other note is gone. Whoever
    // fells last is who they answer to, each leaf answers once, and the note runs out eventually.
    @Test
    fun `a leaf answers to whoever last felled what held it, once and for a while`() {
        val leaf = at(1, 70, 0)
        attribution.felled(listOf(leaf, at(2, 70, 0)), alice)
        clock += 1000
        attribution.felled(listOf(leaf), bob)

        val found = attribution.fellerOf(leaf)
        assertEquals(bob, found?.actor)
        assertEquals(Confidence.INFERRED, found?.confidence)
        assertNull(attribution.fellerOf(leaf))

        clock += FELLED_MILLIS
        assertNull(attribution.fellerOf(at(2, 70, 0)))
        attribution.sweep()
        assertTrue(attribution.isEmpty)
    }

    @Test
    fun `flight state is kept by entity and taken exactly once`() {
        val entity = UUID.randomUUID()
        val falling = Falling(at(1, 70, 1), SAND, Attributed(alice))

        attribution.tookOff(entity, falling)
        assertEquals(falling, attribution.landed(entity))
        assertNull(attribution.landed(entity))
        assertNull(attribution.landed(UUID.randomUUID()))
    }

    @Test
    fun `the sweep drops every note once its own window has passed`() {
        val here = at(10, 64, 10)
        val entity = UUID.randomUUID()
        attribution.placed(here, TNT, alice)
        attribution.removed(here, alice)
        attribution.tookOff(entity, Falling(here, SAND, null))

        attribution.sweep()
        assertFalse(attribution.isEmpty)

        clock += SUPPORT_MILLIS + 1
        attribution.sweep()
        assertNull(attribution.supportRemoverAt(at(11, 64, 10)))
        assertEquals(alice, attribution.placerAt(here, TNT)?.actor)

        clock += NOTE_MILLIS + 1
        attribution.sweep()
        assertNull(attribution.placerAt(here, TNT))
        assertEquals(Falling(here, SAND, null), attribution.landed(entity))

        attribution.tookOff(entity, Falling(here, SAND, null))
        clock += FLIGHT_MILLIS + 1
        attribution.sweep()
        assertTrue(attribution.isEmpty)
    }

    @Test
    fun `the removal table is swept on its own without walking the other two`() {
        val here = at(10, 64, 10)
        val entity = UUID.randomUUID()
        attribution.placed(here, TNT, alice)
        attribution.removed(here, alice)
        attribution.tookOff(entity, Falling(here, SAND, null))

        clock += SUPPORT_MILLIS + 1
        attribution.sweepRemovals()
        assertNull(attribution.supportRemoverAt(at(11, 64, 10)))
        assertEquals(alice, attribution.placerAt(here, TNT)?.actor)

        clock += NOTE_MILLIS + FLIGHT_MILLIS
        attribution.sweepRemovals()
        assertFalse(attribution.isEmpty)

        attribution.sweep()
        assertTrue(attribution.isEmpty)
    }

    @Test
    fun `the builder search takes the most recent placement inside its reach and nothing outside it`() {
        val spawn = at(0, 64, 0)
        attribution.placed(at(0, 66, 0), STONE, alice)
        clock += 1
        attribution.placed(at(3, 65, -1), STONE, bob)

        // Whatever block it was and wherever in the shape it stood: the last one placed completes it.
        assertEquals(bob, attribution.builderNear(spawn, 3)?.actor)
        // A reach that does not span the shape answers with whoever is inside it, not with nobody.
        assertEquals(alice, attribution.builderNear(spawn, 2)?.actor)
        assertNull(attribution.builderNear(spawn, 1))
        // Worked out, never witnessed.
        assertEquals(Confidence.INFERRED, attribution.builderNear(spawn, 3)?.confidence)
    }

    @Test
    fun `a placement older than its window builds nothing`() {
        attribution.placed(at(0, 65, 0), STONE, alice)
        clock += NOTE_MILLIS + 1

        assertNull(attribution.builderNear(at(0, 64, 0), 3))
    }

    @Test
    fun `an entity carries the player it came from until it is gone`() {
        var clock = START
        val origins = EntityOrigins { clock }
        val wither = UUID.randomUUID()

        assertTrue(origins.isEmpty)
        assertNull(origins.summonerOf(wither))

        origins.appeared(wither, alice)
        assertEquals(alice, origins.summonerOf(wither)?.actor)
        // Nothing here is witnessed: the block event that reads it named nobody at all.
        assertEquals(Confidence.INFERRED, origins.summonerOf(wither)?.confidence)

        origins.gone(wither)
        assertNull(origins.summonerOf(wither))
        assertTrue(origins.isEmpty)
    }

    @Test
    fun `an origin nobody saw end stops answering once its window has passed`() {
        var clock = START
        val origins = EntityOrigins { clock }
        val golem = UUID.randomUUID()
        origins.appeared(golem, alice)

        clock += ORIGIN_MILLIS + 1
        assertNull(origins.summonerOf(golem))

        origins.sweep()
        assertTrue(origins.isEmpty)
    }
}
