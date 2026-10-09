package io.pfaumc.pfauprotect.capture.block
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.storage.ItemKey
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SpawnOriginsTest {
    private val world = UUID.randomUUID()
    private val written = ArrayList<Transfer>()
    private val coalescer = TickCoalescer(written::add)
    private var clock = 0L
    private val origins = SpawnOrigins(coalescer) { clock }

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
        assertEquals(1, origins.claim(entity, Spot(world, 4.5, 70.2, 8.7), stone, 1))

        val row = rows().single()
        assertEquals(Cause.DROPPER_EJECT, row.cause)
        assertEquals(dropper, row.from)
        assertEquals(ItemEntityRef(entity), row.to)
        assertEquals(1, row.qty)
    }

    // A villager's harvest falls in another count than the roll the note was made from: the note takes
    // the whole stack, and an exact note at the same spot is served first.
    @Test
    fun `a note made from a loot roll takes whatever of its form lands`() {
        val carrot = ItemKey("carrot".toByteArray(), null)
        origins.expect(Void, Cause.BLK_MOB_GRIEF, carrot, at, 2, rolled = true)
        origins.expect(dropper, Cause.CONTAINER_BREAK_DROP, carrot, at, 1)

        assertEquals(4, origins.claim(UUID.randomUUID(), at, carrot, 4))
        assertEquals(setOf(dropper to 1, Void to 3), rows().map { it.from to it.qty }.toSet())
    }

    // A vine drops itself one time in three, and the roll a note was made from need not be the roll the
    // server made. What lands by the block in a form no note names still came out of it, after every
    // note that names its form has had its share, and only near the block.
    @Test
    fun `a note for any form takes what no other note names`() {
        val vine = ItemKey("twisting_vines".toByteArray(), null)
        origins.expect(dropper, Cause.CONTAINER_BREAK_DROP, vine, at, 1)
        origins.expectAny(Void, Cause.BLK_FADE, at, null)

        assertEquals(2, origins.claim(UUID.randomUUID(), at, vine, 2))
        assertEquals(setOf(dropper to 1, Void to 1), rows().map { it.from to it.qty }.toSet())
        assertEquals(Cause.BLK_FADE, rows().single { it.from == Void }.cause)
        assertEquals(0, origins.claim(UUID.randomUUID(), Spot(world, 40.0, 70.0, 8.0), vine, 1))
    }

    // Only what falls in the same tick: a stranger's item dropped by the block a tick later is not its drop.
    @Test
    fun `a note for any form takes nothing a tick later`() {
        val vine = ItemKey("twisting_vines".toByteArray(), null)
        origins.expectAny(Void, Cause.BLK_FADE, at, null)

        clock += 60
        assertEquals(0, origins.claim(UUID.randomUUID(), at, vine, 1))
    }

    // A cart a dispenser put on a rail takes the note the dispenser left for its item, into the slot the
    // cart holds it in, and the dispenser counts the item gone out. A block's drop is never a cart.
    @Test
    fun `a cart a dispenser put down takes the note left for its item`() {
        val cart = ItemKey("minecart".toByteArray(), null)
        val held = EntitySlot(UUID.randomUUID(), 16)
        origins.expectAny(Void, Cause.BLK_FADE, at, null)
        assertFalse(origins.claimInto(held, at, cart))

        val ejected = origins.expect(dropper, Cause.DISPENSER_EJECT, cart, at, 1)
        assertTrue(origins.claimInto(held, Spot(world, 5.6, 70.0, 8.5), cart))
        assertEquals(1, ejected())
        val row = rows().single()
        assertEquals(dropper, row.from)
        assertEquals(held, row.to)
        assertEquals(Cause.DISPENSER_EJECT, row.cause)
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

    // An explosion drops one pile per kind where the first block of that kind stood. The three blocks
    // that fed this pile stood up to five blocks apart; with the crater's reach every one of them
    // accounts for its share, and with a block's own reach only the one it landed on does.
    @Test
    fun `a pile an explosion gathered is explained by every block that fed it`() {
        val first = Spot(world, 0.5, 64.0, 0.5)
        for (x in listOf(0.5, 3.5, 5.5)) origins.expect(Void, Cause.BLK_TNT, stone, Spot(world, x, 64.0, 0.5), 1, reach = 6.0)
        assertEquals(3, origins.claim(UUID.randomUUID(), first, stone, 3))

        for (x in listOf(0.5, 3.5, 5.5)) origins.expect(Void, Cause.BLK_TNT, stone, Spot(world, x, 64.0, 0.5), 1)
        assertEquals(1, origins.claim(UUID.randomUUID(), first, stone, 3))
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

    // A break builds its entities before announcing them, so the note can say which one it means and
    // distance never enters into it.
    @Test
    fun `a note addressed to an entity claims only that entity`() {
        val mine = UUID.randomUUID()
        origins.expect(mine, dropper, Cause.DEATH_DROP, stone, 1)

        assertEquals(0, origins.claim(UUID.randomUUID(), at, stone, 1))
        assertTrue(rows().isEmpty())

        assertEquals(1, origins.claim(mine, at, stone, 1))
        assertEquals(ItemEntityRef(mine), rows().single().to)
    }

    @Test
    fun `a silent note swallows the spawn without writing`() {
        val entity = UUID.randomUUID()
        origins.accounted(entity, 4)

        assertEquals(4, origins.claim(entity, at, stone, 4))
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `claim reports the amount left unexplained`() {
        origins.expect(dropper, Cause.DROPPER_EJECT, stone, at, 2)

        assertEquals(2, origins.claim(UUID.randomUUID(), at, stone, 5))
        assertEquals(0, origins.claim(UUID.randomUUID(), at, stone, 3))
    }

    @Test
    fun `a positional note does not steal a quantity from a note that names the entity`() {
        val named = UUID.randomUUID()
        origins.expect(Void, Cause.CAMPFIRE_COOK_DROP, stone, at, 1)
        origins.expect(named, dropper, Cause.DEATH_DROP, stone, 1)

        assertEquals(1, origins.claim(named, at, stone, 1))
        assertEquals(dropper, rows().single().from)

        assertEquals(1, origins.claim(UUID.randomUUID(), at, stone, 1))
        assertEquals(listOf(dropper, Void), rows().map { it.from })
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

    // What a full inventory throws out after a give lands wherever its player has got to, so the note
    // follows the thrower, outlives the sweeps and is left alone by a drop from anybody else.
    @Test
    fun `a note for a thrower is claimed by that thrower's drop anywhere`() {
        val player = UUID.randomUUID()
        origins.expectThrown(player, Void, Cause.CMD_GIVE, stone, 1, System.currentTimeMillis() + 60_000)
        origins.sweep()
        origins.sweep()
        val far = Spot(at.world, at.x + 500, at.y, at.z)

        assertEquals(0, origins.claim(UUID.randomUUID(), far, stone, 1, UUID.randomUUID()))
        assertEquals(1, origins.claim(UUID.randomUUID(), far, stone, 1, player))
        assertEquals(Cause.CMD_GIVE, rows().single().cause)
    }
}
