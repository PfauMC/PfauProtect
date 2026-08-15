package io.pfaumc.pfauprotect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SpawnOriginsTest {
    private val world = UUID.randomUUID()
    private val written = ArrayList<Transfer>()
    private val coalescer = TickCoalescer(written::add)
    private val origins = SpawnOrigins(coalescer)

    private val dropper = Container(world, 4, 70, 8, 2)
    private val honeycomb = ItemKey("honeycomb".toByteArray(), null)
    private val stone = ItemKey("stone".toByteArray(), null)
    private val at = Spot(world, 4.0, 70.0, 8.0)

    private fun rows(): List<Transfer> {
        coalescer.flush()
        return written
    }

    @Test
    fun `the entity that appears carries the reason the block gave`() {
        val entity = UUID.randomUUID()
        origins.expect(dropper, Cause.DROPPER_EJECT, stone, at, 1)
        origins.claim(entity, Spot(world, 4.5, 70.2, 8.7), stone, 1)

        val row = rows().single()
        assertEquals(Cause.DROPPER_EJECT, row.cause)
        assertEquals(dropper, row.from)
        assertEquals(ItemEntityRef(entity), row.to)
        assertEquals(1, row.qty)
    }

    @Test
    fun `a harvest keeps the player who caused it`() {
        val actor = UUID.randomUUID()
        val entity = UUID.randomUUID()
        origins.expect(Void, Cause.BEEHIVE_HARVEST, honeycomb, at, 3, actor)
        origins.claim(entity, at, honeycomb, 3)

        val row = rows().single()
        assertEquals(Void, row.from)
        assertEquals(actor, row.actor)
        assertEquals(3, row.qty)
    }

    @Test
    fun `another form spawning nearby claims nothing`() {
        origins.expect(dropper, Cause.DROPPER_EJECT, stone, at, 1)
        origins.claim(UUID.randomUUID(), at, honeycomb, 1)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `a spawn out of reach claims nothing`() {
        origins.expect(dropper, Cause.DROPPER_EJECT, stone, at, 1)
        origins.claim(UUID.randomUUID(), Spot(world, 12.0, 70.0, 8.0), stone, 1)
        origins.claim(UUID.randomUUID(), Spot(UUID.randomUUID(), 4.0, 70.0, 8.0), stone, 1)
        assertTrue(rows().isEmpty())
    }

    // A campfire hands out its result in whatever piles the game feels like.
    @Test
    fun `a note is claimed piece by piece and then retired`() {
        origins.expect(Void, Cause.CAMPFIRE_COOK_DROP, stone, at, 3)
        origins.claim(UUID.randomUUID(), at, stone, 1)
        origins.claim(UUID.randomUUID(), at, stone, 5)
        origins.claim(UUID.randomUUID(), at, stone, 1)

        val rows = rows()
        assertEquals(setOf(1, 2), rows.map { it.qty }.toSet())
        assertEquals(2, rows.map { it.to }.toSet().size)
    }

    // A dispenser that shears or fills a bucket keeps its item, so a note nobody claims must leave no
    // trace rather than invent a movement into the void.
    @Test
    fun `an unclaimed note is dropped without writing anything`() {
        origins.expect(dropper, Cause.DISPENSER_EJECT, stone, at, 1)
        origins.sweep()
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `a note survives the sweep of the tick it was written in`() {
        origins.expect(dropper, Cause.DISPENSER_EJECT, stone, at, 1)
        origins.sweep()
        origins.claim(UUID.randomUUID(), at, stone, 1)
        assertEquals(1, rows().size)

        origins.sweep()
        assertTrue(origins.isEmpty)
    }
}
