package io.pfaumc.pfauprotect.capture.block
import io.pfaumc.pfauprotect.Spawns
import io.pfaumc.pfauprotect.key
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntitySlot
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.model.Void
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SpawnOriginsTest {
    private val world = UUID.randomUUID()
    private val spawns = Spawns()
    private val origins = spawns.origins

    private val dropper = Container(world, 4, 70, 8, 2)
    private val honeycomb = key("honeycomb")
    private val stone = key("stone")
    private val at = Spot(world, 4.0, 70.0, 8.0)

    @Test
    fun `the entity that appears carries the reason and the player the block gave`() {
        val actor = UUID.randomUUID()
        val entity = UUID.randomUUID()
        origins.expect(dropper, Cause.DROPPER_EJECT, stone, at, 1, actor)
        assertEquals(1, origins.claim(entity, Spot(world, 4.5, 70.2, 8.7), stone, 1))

        val row = spawns.rows().single()
        assertEquals(Cause.DROPPER_EJECT, row.cause)
        assertEquals(dropper, row.from)
        assertEquals(ItemEntityRef(entity), row.to)
        assertEquals(1, row.qty)
        assertEquals(actor, row.actor)
    }

    // A dead cow drops beef and leather into two piles; its row has to name both, and only once, and a
    // pile that came out of something else nearby is not among them.
    @Test
    fun `the items that came out of an event are named under it once`() {
        val cow = UUID.randomUUID()
        val beef = UUID.randomUUID()
        val leather = UUID.randomUUID()
        val stranger = UUID.randomUUID()
        val leatherKey = key("leather")
        origins.expect(Void, Cause.MOB_DROP, stone, at, 1, tag = cow)
        origins.expect(Void, Cause.MOB_DROP, leatherKey, at, 1, tag = cow)
        origins.expect(dropper, Cause.DROPPER_EJECT, honeycomb, at, 1)

        origins.claim(beef, at, stone, 1)
        origins.claim(leather, at, leatherKey, 1)
        origins.claim(stranger, at, honeycomb, 1)

        assertEquals(setOf(beef, leather), origins.droppedFor(cow).toSet())
        assertTrue(origins.droppedFor(cow).isEmpty(), "taken once")
    }

    // A villager's harvest falls in another count than the roll the note was made from: the note takes
    // the whole stack, and an exact note at the same spot is served first.
    @Test
    fun `a note made from a loot roll takes whatever of its form lands`() {
        val carrot = key("carrot")
        origins.expect(Void, Cause.BLK_MOB_GRIEF, carrot, at, 2, rolled = true)
        origins.expect(dropper, Cause.CONTAINER_BREAK_DROP, carrot, at, 1)

        assertEquals(4, origins.claim(UUID.randomUUID(), at, carrot, 4))
        assertEquals(setOf(dropper to 1, Void to 3), spawns.rows().map { it.from to it.qty }.toSet())
    }

    @Test
    fun `a note for any form takes what no other note names`() {
        val vine = key("twisting_vines")
        origins.expect(dropper, Cause.CONTAINER_BREAK_DROP, vine, at, 1)
        origins.expectAny(Void, Cause.BLK_FADE, at, null)

        assertEquals(2, origins.claim(UUID.randomUUID(), at, vine, 2))
        assertEquals(setOf(dropper to 1, Void to 1), spawns.rows().map { it.from to it.qty }.toSet())
        assertEquals(Cause.BLK_FADE, spawns.rows().single { it.from == Void }.cause)
        assertEquals(0, origins.claim(UUID.randomUUID(), Spot(world, 40.0, 70.0, 8.0), vine, 1))
    }

    // A command's drop is waited for until a moment; after it, a stack the player throws is their own.
    @Test
    fun `a thrown note takes nothing past its deadline`() {
        val player = UUID.randomUUID()
        origins.expectThrown(player, Void, Cause.CMD_GIVE, stone, 1, until = 100)

        spawns.clock = 101
        assertEquals(0, origins.claim(UUID.randomUUID(), at, stone, 1, thrower = player))
        spawns.clock = 100
        assertEquals(1, origins.claim(UUID.randomUUID(), at, stone, 1, thrower = player))
    }

    @Test
    fun `a note for any form takes nothing a tick later`() {
        val vine = key("twisting_vines")
        origins.expectAny(Void, Cause.BLK_FADE, at, null)

        spawns.clock += 60
        assertEquals(0, origins.claim(UUID.randomUUID(), at, vine, 1))
    }

    // A cart a dispenser put on a rail takes the note the dispenser left for its item, into the slot the
    // cart holds it in, and the dispenser counts the item gone out. A block's drop is never a cart.
    @Test
    fun `a cart a dispenser put down takes the note left for its item`() {
        val cart = key("minecart")
        val held = EntitySlot(UUID.randomUUID(), 16)
        origins.expectAny(Void, Cause.BLK_FADE, at, null)
        assertFalse(origins.claimInto(held, at, cart))

        val ejected = origins.expect(dropper, Cause.DISPENSER_EJECT, cart, at, 1)
        assertTrue(origins.claimInto(held, Spot(world, 5.6, 70.0, 8.5), cart))
        assertEquals(1, ejected())
        val row = spawns.rows().single()
        assertEquals(dropper, row.from)
        assertEquals(held, row.to)
        assertEquals(Cause.DISPENSER_EJECT, row.cause)
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
        assertTrue(spawns.rows().isEmpty())
    }

    @Test
    fun `a spawn out of reach claims nothing`() {
        origins.expect(dropper, Cause.DROPPER_EJECT, stone, at, 1)
        origins.claim(UUID.randomUUID(), Spot(world, 12.0, 70.0, 8.0), stone, 1)
        origins.claim(UUID.randomUUID(), Spot(UUID.randomUUID(), 4.0, 70.0, 8.0), stone, 1)
        assertTrue(spawns.rows().isEmpty())
    }

    // A campfire hands out its result in whatever piles the game feels like.
    @Test
    fun `a note is claimed piece by piece and then retired`() {
        origins.expect(Void, Cause.CAMPFIRE_COOK_DROP, stone, at, 3)
        assertEquals(1, origins.claim(UUID.randomUUID(), at, stone, 1))
        assertEquals(2, origins.claim(UUID.randomUUID(), at, stone, 5))
        assertEquals(0, origins.claim(UUID.randomUUID(), at, stone, 1))

        val rows = spawns.rows()
        assertEquals(listOf(1, 2), rows.map { it.qty }.sorted())
        assertEquals(2, rows.map { it.to }.toSet().size)
    }

    @Test
    fun `a note addressed to an entity claims only that entity`() {
        val mine = UUID.randomUUID()
        origins.expect(mine, dropper, Cause.DEATH_DROP, stone, 1)

        assertEquals(0, origins.claim(UUID.randomUUID(), at, stone, 1))
        assertTrue(spawns.rows().isEmpty())

        assertEquals(1, origins.claim(mine, at, stone, 1))
        assertEquals(ItemEntityRef(mine), spawns.rows().single().to)
    }

    @Test
    fun `a silent note swallows the spawn without writing`() {
        val entity = UUID.randomUUID()
        origins.accounted(entity, 4)

        assertEquals(4, origins.claim(entity, at, stone, 4))
        assertTrue(spawns.rows().isEmpty())
    }

    @Test
    fun `a positional note does not steal a quantity from a note that names the entity`() {
        val named = UUID.randomUUID()
        origins.expect(Void, Cause.CAMPFIRE_COOK_DROP, stone, at, 1)
        origins.expect(named, dropper, Cause.DEATH_DROP, stone, 1)

        assertEquals(1, origins.claim(named, at, stone, 1))
        assertEquals(dropper, spawns.rows().single().from)

        assertEquals(1, origins.claim(UUID.randomUUID(), at, stone, 1))
        assertEquals(listOf(dropper, Void), spawns.rows().map { it.from })
    }

    @Test
    fun `an event read off leaves the next one's drops to it`() {
        val first = Any()
        val second = Any()
        origins.expect(Void, Cause.BLK_TNT, stone, at, 1, rolled = true, tag = first)
        val early = UUID.randomUUID()
        origins.claim(early, at, stone, 3)
        assertEquals(listOf(early), origins.droppedFor(first))

        origins.expect(Void, Cause.BLK_TNT, stone, at, 1, rolled = true, tag = second)
        val late = UUID.randomUUID()
        origins.claim(late, at, stone, 5)
        assertEquals(listOf(late), origins.droppedFor(second))
    }

    @Test
    fun `a note waits out sweeps that come quicker than its spawn`() {
        origins.expect(Void, Cause.BLK_TNT, stone, at, 2)
        repeat(10) { origins.sweep() }
        assertEquals(1, origins.claim(UUID.randomUUID(), at, stone, 1))

        spawns.clock += 1_000
        origins.sweep()
        assertEquals(0, origins.claim(UUID.randomUUID(), at, stone, 1))
    }

    @Test
    fun `a note for a thrower is claimed by that thrower's drop anywhere`() {
        val player = UUID.randomUUID()
        origins.expectThrown(player, Void, Cause.CMD_GIVE, stone, 1, until = spawns.clock + 5_000)
        // Past the two sweeps and the second that drop a note matched by where it landed.
        origins.sweep()
        spawns.clock += 1_000
        origins.sweep()
        spawns.clock += 1_000
        origins.sweep()
        val far = Spot(at.world, at.x + 500, at.y, at.z)

        assertEquals(0, origins.claim(UUID.randomUUID(), far, stone, 1, UUID.randomUUID()))
        assertEquals(1, origins.claim(UUID.randomUUID(), far, stone, 1, player))
        assertEquals(Cause.CMD_GIVE, spawns.rows().single().cause)
    }
}
