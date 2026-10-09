package io.pfaumc.pfauprotect.capture.item
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.storage.FormKey
import io.pfaumc.pfauprotect.storage.ItemKey
import io.pfaumc.pfauprotect.model.Nested
import io.pfaumc.pfauprotect.model.PlayerCursor
import io.pfaumc.pfauprotect.model.PlayerEnder
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.attribution.inferred
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class DiffTest {
    private val world = UUID.randomUUID()
    private val player = UUID.randomUUID()

    private fun key(name: String, damage: Int? = null) = ItemKey(name.toByteArray(), damage)
    private fun stack(name: String, count: Int, damage: Int? = null) = Stack(key(name, damage), count)
    private fun chest(slot: Int) = Container(world, 10, 64, -3, slot)
    private fun edge(from: Holder, to: Holder) = Edge(from, to, key("stone"), 1, Confidence.FACT)
    private fun bag(slot: Int) = PlayerInv(player, slot)
    private val cursor = PlayerCursor(player)

    private fun seen(vararg rows: Pair<Holder, Stack>, containers: Set<UUID> = emptySet()) =
        Snapshot(mapOf(*rows), containers)

    // A grindstone merges two worn tools into one, and wear is not part of a form, so from the diff
    // alone the result is indistinguishable from one of the inputs having simply moved out. Paired
    // that way the transformation vanishes: an ordinary move plus one tool that disappeared for no
    // stated reason. Naming the station's slots keeps both halves unpaired and facing the Void.
    @Test
    fun `a station slot does not pair with the item it became`() {
        val station = setOf<Holder>(chest(0), chest(1))
        val edges = Netting.diff(
            seen(chest(0) to stack("sword", 1, damage = 90), chest(1) to stack("sword", 1, damage = 40)),
            seen(cursor to stack("sword", 1, damage = 10)),
            unpaired = station,
        )

        assertEquals(3, edges.size)
        assertEquals(2, edges.count { it.to == Void })
        assertEquals(1, edges.count { it.from == Void })
        assertTrue(edges.none { it.from != Void && it.to != Void }, "a station half was married off: $edges")
    }

    // The same two snapshots without the station named: the pairing marries a loss to the gain and the
    // second tool is left to vanish on its own. This is what the case above exists to prevent.
    @Test
    fun `without the station named the merge reads as a move and a disappearance`() {
        val edges = Netting.diff(
            seen(chest(0) to stack("sword", 1, damage = 90), chest(1) to stack("sword", 1, damage = 40)),
            seen(cursor to stack("sword", 1, damage = 10)),
        )

        assertEquals(2, edges.size)
        assertEquals(1, edges.count { it.from != Void && it.to != Void })
        assertEquals(1, edges.count { it.to == Void })
    }

    @Test
    fun `a whole stack moves as one edge`() {
        val edges = Netting.diff(
            seen(chest(0) to stack("stone", 64)),
            seen(bag(0) to stack("stone", 64)),
        )
        assertEquals(listOf(Edge(chest(0), bag(0), key("stone"), 64, Confidence.FACT)), edges)
    }

    @Test
    fun `a partial stack moves only the taken amount`() {
        val edges = Netting.diff(
            seen(chest(0) to stack("stone", 64)),
            seen(chest(0) to stack("stone", 40), bag(0) to stack("stone", 24)),
        )
        assertEquals(listOf(Edge(chest(0), bag(0), key("stone"), 24, Confidence.FACT)), edges)
    }

    @Test
    fun `a cursor swap trades both stacks`() {
        val edges = Netting.diff(
            seen(cursor to stack("stone", 1), chest(0) to stack("dirt", 1)),
            seen(cursor to stack("dirt", 1), chest(0) to stack("stone", 1)),
        )
        assertEquals(
            setOf(
                Edge(cursor, chest(0), key("stone"), 1, Confidence.FACT),
                Edge(chest(0), cursor, key("dirt"), 1, Confidence.FACT),
            ),
            edges.toSet(),
        )
        assertEquals(2, edges.size)
    }

    @Test
    fun `a slot left holding a zero counter is empty`() {
        val edges = Netting.diff(
            seen(chest(0) to stack("stone", 64), bag(0) to stack("dirt", 1)),
            seen(bag(0) to stack("stone", 0), bag(1) to stack("dirt", 1)),
        )
        assertEquals(
            setOf(
                Edge(chest(0), Void, key("stone"), 64, Confidence.INFERRED),
                Edge(bag(0), bag(1), key("dirt"), 1, Confidence.FACT),
            ),
            edges.toSet(),
        )
        assertEquals(2, edges.size)
        assertTrue(edges.all { it.qty > 0 })
    }

    @Test
    fun `an unchanged snapshot yields no edges`() {
        val both = seen(chest(0) to stack("stone", 64), bag(0) to stack("dirt", 3))
        assertEquals(emptyList<Edge>(), Netting.diff(both, both))
    }

    // A pass now runs after every block broken, so a pickaxe losing a point of durability between two
    // of them must not read as one pickaxe vanishing and another appearing.
    @Test
    fun `wearing a tool down in place moves nothing`() {
        val edges = Netting.diff(
            seen(bag(0) to stack("pickaxe", 1, damage = 3)),
            seen(bag(0) to stack("pickaxe", 1, damage = 4)),
        )
        assertEquals(emptyList<Edge>(), edges)
    }

    @Test
    fun `a worn tool that moves is one edge carrying the wear it arrived with`() {
        val edges = Netting.diff(
            seen(bag(0) to stack("pickaxe", 1, damage = 3)),
            seen(bag(1) to stack("pickaxe", 1, damage = 4)),
        )
        assertEquals(listOf(Edge(bag(0), bag(1), key("pickaxe", 4), 1, Confidence.FACT)), edges)
    }

    // The price of leaving durability out of identity, named rather than discovered later: two tools
    // of one form trading places cancel each other and leave no trace of the swap.
    @Test
    fun `two tools of one form swapping places cancel out`() {
        val edges = Netting.diff(
            seen(bag(0) to stack("pickaxe", 1, damage = 3), bag(1) to stack("pickaxe", 1, damage = 900)),
            seen(bag(0) to stack("pickaxe", 1, damage = 900), bag(1) to stack("pickaxe", 1, damage = 3)),
        )
        assertEquals(emptyList<Edge>(), edges)
    }

    @Test
    fun `an unpaired worn tool still names its wear`() {
        val edges = Netting.diff(
            seen(bag(0) to stack("pickaxe", 1, damage = 3)),
            seen(),
        )
        assertEquals(listOf(Edge(bag(0), Void, key("pickaxe", 3), 1, Confidence.INFERRED)), edges)
    }

    @Test
    fun `an unpaired loss goes to the void as inferred`() {
        val vanished = Netting.diff(
            seen(bag(0) to stack("stone", 10)),
            seen(),
        )
        assertEquals(listOf(Edge(bag(0), Void, key("stone"), 10, Confidence.INFERRED)), vanished)

        val appeared = Netting.diff(
            seen(),
            seen(bag(0) to stack("stone", 4)),
        )
        assertEquals(listOf(Edge(Void, bag(0), key("stone"), 4, Confidence.INFERRED)), appeared)
    }

    @Test
    fun `the cause of an edge follows the ends it connects`() {
        val ender = PlayerEnder(player, 0)
        assertEquals(Cause.CONTAINER_ADD, causeOf(edge(bag(0), chest(0))))
        assertEquals(Cause.CONTAINER_ADD, causeOf(edge(bag(0), ender)))
        assertEquals(Cause.CONTAINER_REMOVE, causeOf(edge(chest(0), bag(0))))
        assertEquals(Cause.CONTAINER_REMOVE, causeOf(edge(ender, bag(0))))
        assertEquals(Cause.CURSOR_PLACE, causeOf(edge(cursor, bag(0))))
        assertEquals(Cause.CURSOR_TAKE, causeOf(edge(bag(0), cursor)))
        assertEquals(Cause.QUICK_MOVE, causeOf(edge(bag(0), bag(1))))
        val inside = Nested(UUID.randomUUID(), 0)
        assertEquals(Cause.BUNDLE_INSERT, causeOf(edge(bag(0), inside)))
        assertEquals(Cause.BUNDLE_EXTRACT, causeOf(edge(inside, chest(0))))
    }

    @Test
    fun `a quick move spread over several slots nets into real edges`() {
        val edges = Netting.diff(
            seen(
                chest(0) to stack("stone", 64),
                bag(0) to stack("stone", 20),
                bag(1) to stack("stone", 30),
            ),
            seen(
                chest(0) to stack("stone", 0),
                bag(0) to stack("stone", 64),
                bag(1) to stack("stone", 50),
            ),
        )
        assertEquals(
            setOf(
                Edge(chest(0), bag(0), key("stone"), 44, Confidence.FACT),
                Edge(chest(0), bag(1), key("stone"), 20, Confidence.FACT),
            ),
            edges.toSet(),
        )
        assertEquals(2, edges.size)
        assertEquals(64, edges.sumOf { it.qty })
    }

    // Placing one cobblestone out of a stack while three more are picked up into another slot: paired
    // by form first, the loss marries the unrelated gain and asserts a move between two slots that
    // never exchanged anything, while the position is credited nothing and the entity only part of
    // what it handed over.
    @Test
    fun `an intent claims its share before an unrelated gain can be married to the loss`() {
        val entity = ItemEntityRef(UUID.randomUUID())
        val placed = WorldBlock(world, 10, 65, -3)
        val intents = listOf(
            Intent(Cause.BLOCK_PLACE, to = placed, qty = 1),
            Intent(Cause.PICKUP, from = entity, qty = 3),
        )
        val edges = Netting.diff(
            seen(bag(0) to stack("cobble", 5)),
            seen(bag(0) to stack("cobble", 4), bag(9) to stack("cobble", 3)),
            intents,
            player,
        )
        assertEquals(
            listOf(
                Move(bag(0), placed, key("cobble"), 1, Cause.BLOCK_PLACE, Confidence.FACT),
                Move(entity, bag(9), key("cobble"), 3, Cause.PICKUP, Confidence.FACT),
            ),
            Intents.explain(edges, intents, player),
        )
    }

    // An intent explains at most what the pass actually found, because the event only ever saw what it
    // meant to do.
    @Test
    fun `an intent claims no more than the pass found`() {
        val entity = ItemEntityRef(UUID.randomUUID())
        val intents = listOf(Intent(Cause.PICKUP, from = entity, qty = 64))
        val edges = Netting.diff(
            seen(),
            seen(bag(0) to stack("cobble", 3)),
            intents,
            player,
        )
        assertEquals(listOf(Edge(Void, bag(0), key("cobble"), 3, Confidence.INFERRED)), edges)
    }

    // A loss nobody claimed is still married to a gain of its form: that is the ordinary move.
    @Test
    fun `what no intent claimed is still paired loss to gain`() {
        val placed = WorldBlock(world, 10, 65, -3)
        val intents = listOf(Intent(Cause.BLOCK_PLACE, to = placed, qty = 1))
        val edges = Netting.diff(
            seen(bag(0) to stack("cobble", 5)),
            seen(bag(0) to stack("cobble", 1), bag(1) to stack("cobble", 3)),
            intents,
            player,
        )
        assertEquals(
            setOf(
                Edge(bag(0), bag(1), key("cobble"), 3, Confidence.FACT),
                Edge(bag(0), Void, key("cobble"), 1, Confidence.INFERRED),
            ),
            edges.toSet(),
        )
    }

    // One cobblestone laid into the world out of the held slot and one taken off the ground back into
    // it inside a tick. The slot reads 64 on both sides, so the pass finds nothing at all and the two
    // intents left over are the only account of what the slot hid.
    @Test
    fun `two movements that cancelled in one slot still reach the ends they touched`() {
        val entity = ItemEntityRef(UUID.randomUUID())
        val placed = WorldBlock(world, 10, 65, -3)
        val intents = listOf(
            Intent(Cause.BLOCK_PLACE, to = placed, form = "cobble".toByteArray(), qty = 1, holder = bag(0)),
            Intent(Cause.PICKUP, from = entity, form = "cobble".toByteArray(), qty = 1),
        )
        val held = seen(bag(0) to stack("cobble", 64))
        val edges = Netting.diff(held, held, intents, player)
        assertEquals(emptyList<Edge>(), edges)
        assertEquals(
            listOf(
                Move(bag(0), placed, key("cobble"), 1, Cause.BLOCK_PLACE, Confidence.FACT),
                Move(entity, bag(0), key("cobble"), 1, Cause.PICKUP, Confidence.FACT),
            ),
            Intents.explain(edges, intents, player),
        )
    }

    @Test
    fun `a container item leaving the view moves nothing it holds`() {
        val box = UUID.randomUUID()
        val edges = Netting.diff(
            seen(
                bag(0) to stack("shulker", 1),
                Nested(box, 0) to stack("stone", 5),
                containers = setOf(box),
            ),
            seen(),
        )
        assertEquals(listOf(Edge(bag(0), Void, key("shulker"), 1, Confidence.INFERRED)), edges)
    }

    @Test
    fun `a container item coming back into view mints nothing it holds`() {
        val box = UUID.randomUUID()
        val edges = Netting.diff(
            seen(),
            seen(
                bag(0) to stack("shulker", 1),
                Nested(box, 0) to stack("stone", 5),
                containers = setOf(box),
            ),
        )
        assertEquals(listOf(Edge(Void, bag(0), key("shulker"), 1, Confidence.INFERRED)), edges)
    }

    // A box is one item wherever it goes and what it holds rides along inside it. Laid down as a block
    // or taken off the body by a death, the rows filed under it leave the snapshot with it, and read
    // as movements they write off a whole box of stone at every placement and mint it back later.
    @Test
    fun `a full shulker box leaving the player moves nothing that is inside it`() {
        val box = UUID.randomUUID()
        val carried = seen(
            bag(0) to stack("shulker", 1),
            Nested(box, 0) to stack("stone", 27),
            containers = setOf(box),
        )
        val placed = WorldBlock(world, 10, 65, -3)
        val place = Intent(Cause.BLOCK_PLACE, to = placed, form = "shulker".toByteArray(), qty = 1)
        assertEquals(
            listOf(Move(bag(0), placed, key("shulker"), 1, Cause.BLOCK_PLACE, Confidence.FACT)),
            Intents.explain(Netting.diff(carried, seen(), listOf(place), player), listOf(place), player),
        )

        // The death wrote the box's own row where the slot it came out of was still known, so the pass
        // swallows that loss; what was inside the box was never a row of this pass at all.
        val died = Intent(
            Cause.DEATH_DROP,
            form = "shulker".toByteArray(),
            qty = 1,
            recorded = true,
            holder = bag(0),
        )
        assertEquals(
            emptyList<Move>(),
            Intents.explain(Netting.diff(carried, seen(), listOf(died), player), listOf(died), player),
        )
    }

    @Test
    fun `a bundle that stays in view still moves what goes in and out of it`() {
        val bundle = UUID.randomUUID()
        val empty = seen(
            bag(0) to stack("bundle", 1),
            bag(1) to stack("stone", 5),
            containers = setOf(bundle),
        )
        val filled = seen(
            bag(0) to stack("bundle", 1),
            Nested(bundle, 0) to stack("stone", 5),
            containers = setOf(bundle),
        )
        assertEquals(
            listOf(Edge(bag(1), Nested(bundle, 0), key("stone"), 5, Confidence.FACT)),
            Netting.diff(empty, filled),
        )
        assertEquals(
            listOf(Edge(Nested(bundle, 0), bag(1), key("stone"), 5, Confidence.FACT)),
            Netting.diff(filled, empty),
        )
    }

    // What an editor changed while the player was away, and nothing else: a stack moved between two
    // slots unseen stands against itself, and only the surplus or the shortfall of a form is written.
    @Test
    fun `an offline change is written where it shows and a move writes nothing`() {
        val diamond = FormKey("diamond".toByteArray())
        val slot0 = PlayerInv(player, 0)
        val slot7 = PlayerInv(player, 7)

        assertTrue(loadDifferences(mapOf(slot7 to mapOf(diamond to 5)), mapOf(slot0 to mapOf(diamond to 5))).isEmpty())

        val gained = loadDifferences(mapOf(slot0 to mapOf(diamond to 5), slot7 to mapOf(diamond to 2)), mapOf(slot0 to mapOf(diamond to 5)))
        assertEquals(listOf(Triple(slot7, 2, true)), gained.map { Triple(it.holder, it.qty, it.gained) })

        val lost = loadDifferences(mapOf(slot7 to mapOf(diamond to 4)), mapOf(slot0 to mapOf(diamond to 5)))
        assertEquals(listOf(Triple(slot0 as Holder, 1, false)), lost.map { Triple(it.holder, it.qty, it.gained) })
    }
}
