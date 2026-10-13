package io.pfaumc.pfauprotect.capture.item
import io.pfaumc.pfauprotect.disabledPlugin
import io.pfaumc.pfauprotect.NoPlacedForms
import io.pfaumc.pfauprotect.Spawns
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.bob
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.storage.MemoryRegistryStore
import io.pfaumc.pfauprotect.model.Nested
import io.pfaumc.pfauprotect.storage.Registries
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.storage.RocksItemLog
import io.pfaumc.pfauprotect.attribution.Attribution
import io.pfaumc.pfauprotect.capture.block.SpawnOrigins
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.stub
import io.pfaumc.pfauprotect.testCodec
import io.pfaumc.pfauprotect.Vanilla
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.BundleContents
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Item
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.EntityRemoveEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import net.minecraft.world.item.ItemStack as NmsItemStack

@ExtendWith(Vanilla::class)
class WorldItemsTest {
    private val spawns = Spawns()

    // An item that is hurt and lives keeps the damage that hurt it, and only an item whose health ran
    // out was killed by what it carries, so the two are given separately.
    private fun end(cause: EntityRemoveEvent.Cause, damage: DamageCause? = null, health: Int = 0) =
        itemEnd(cause, damage, health)

    @Test
    fun `the kind of destruction is read off the damage that caused it`() {
        assertEquals(
            Cause.ITEM_DESTROY_FIRE to Confidence.FACT,
            end(EntityRemoveEvent.Cause.DEATH, DamageCause.LAVA),
        )
        assertEquals(
            Cause.ITEM_DESTROY_FIRE to Confidence.FACT,
            end(EntityRemoveEvent.Cause.DEATH, DamageCause.FIRE_TICK),
        )
        assertEquals(
            Cause.ITEM_DESTROY_FIRE to Confidence.FACT,
            end(EntityRemoveEvent.Cause.DEATH, DamageCause.FIRE),
        )
        assertEquals(
            Cause.ITEM_DESTROY_CACTUS to Confidence.FACT,
            end(EntityRemoveEvent.Cause.DEATH, DamageCause.CONTACT),
        )
        assertEquals(
            Cause.ITEM_DESTROY_EXPLOSION to Confidence.FACT,
            end(EntityRemoveEvent.Cause.DEATH, DamageCause.BLOCK_EXPLOSION),
        )
        assertEquals(
            Cause.ITEM_DESTROY_EXPLOSION to Confidence.FACT,
            end(EntityRemoveEvent.Cause.DEATH, DamageCause.ENTITY_EXPLOSION),
        )
    }

    @Test
    fun `a death nobody can name is still written and marked a guess`() {
        assertEquals(Cause.ITEM_SPAWN to Confidence.INFERRED, end(EntityRemoveEvent.Cause.DEATH, DamageCause.CUSTOM))
        assertEquals(Cause.ITEM_SPAWN to Confidence.INFERRED, end(EntityRemoveEvent.Cause.DEATH, null))
    }

    // Killing an item removes it with the same cause a fatal blow does, and hurts it not at all: it
    // dies with health left and nothing behind it. Read as an unnamed end it would be counted among the
    // paths the capture cannot see; read off the damage it survived earlier, it would be a fire.
    @Test
    fun `an item that dies with health left was killed outright, whatever hurt it before`() {
        assertEquals(Cause.CMD_KILL_ITEM to Confidence.FACT, end(EntityRemoveEvent.Cause.DEATH, health = 5))
        assertEquals(Cause.CMD_KILL_ITEM to Confidence.FACT, end(EntityRemoveEvent.Cause.DEATH, DamageCause.FIRE, health = 4))
        assertEquals(Cause.CMD_KILL_ITEM to Confidence.FACT, end(EntityRemoveEvent.Cause.DISCARD))
        assertEquals(Cause.CMD_KILL_ITEM to Confidence.FACT, end(EntityRemoveEvent.Cause.PLUGIN))
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

    // Picked up, merged or unloaded, the item goes on existing somewhere else; a row for any of those
    // would book it twice, and silence for anything else would leave its balance open.
    @Test
    fun `only a removal the item outlives is silent`() {
        assertEquals(
            setOf(
                EntityRemoveEvent.Cause.PICKUP,
                EntityRemoveEvent.Cause.MERGE,
                EntityRemoveEvent.Cause.UNLOAD,
                EntityRemoveEvent.Cause.PLAYER_QUIT,
            ),
            EntityRemoveEvent.Cause.entries.filter { end(it) == null }.toSet(),
        )
        assertEquals(Cause.ITEM_DESPAWN to Confidence.FACT, end(EntityRemoveEvent.Cause.DESPAWN))
        assertEquals(Cause.ITEM_DESTROY_VOID to Confidence.FACT, end(EntityRemoveEvent.Cause.OUT_OF_WORLD))
    }

    @Test
    fun `a hit is lethal when what it leaves rounds down to nothing`() {
        assertTrue(lethal(5, 5.0))
        assertTrue(lethal(5, 4.5))
        assertFalse(lethal(5, 3.9))
    }

    @Test
    fun `only an item whose health ran out spills its contents`() {
        assertTrue(spills(EntityRemoveEvent.Cause.DEATH, 0))
        assertFalse(spills(EntityRemoveEvent.Cause.DEATH, 4))
        assertFalse(spills(EntityRemoveEvent.Cause.OUT_OF_WORLD, 5))
        assertFalse(spills(EntityRemoveEvent.Cause.DESPAWN, 5))
    }

    private val codec by lazy { testCodec() }

    private fun bundle(owner: UUID?) = NmsItemStack(Items.BUNDLE).apply {
        set(
            DataComponents.BUNDLE_CONTENTS,
            BundleContents(listOf(ItemStackTemplate(Items.DIAMOND, 3), ItemStackTemplate(Items.STONE, 16))),
        )
        owner?.let { NestedItems.mark(this, it) }
    }

    // Thrown over the edge: the end of the bundle and of everything in it, and the player who threw it
    // is on every one of those rows.
    @Test
    fun `a thrown bundle lost to the void names its thrower and takes its contents with it`(@TempDir dir: Path) = RocksItemLog(dir).use { ledger ->
        val owner = UUID.randomUUID()
        val thrower = UUID.randomUUID()
        val entity = UUID.randomUUID()
        val origins = SpawnOrigins(spawns.coalescer)
        val capture = ContainerCaptureListener(
            disabledPlugin(), {}, codec,
            Registries(MemoryRegistryStore()), origins, NoPlacedForms,
        )
        val item = stub(
            Item::class.java,
            mapOf(
                "isDead" to false,
                "getHealth" to 5,
                "getUniqueId" to entity,
                "getThrower" to thrower,
                "getItemStack" to CraftItemStack.asBukkitMirror(bundle(owner)),
            ),
        )

        val attribution = Attribution(Registries(MemoryRegistryStore()), BlockLogs(dir.resolve("blocks"), ledger))
        WorldItemListener(codec, spawns.coalescer, origins, capture, attribution)
            .onRemove(EntityRemoveEvent(item, EntityRemoveEvent.Cause.OUT_OF_WORLD))

        val rows = spawns.rows()
        assertEquals(
            mapOf(ItemEntityRef(entity) to 1, Nested(owner, 0) to 3, Nested(owner, 1) to 16),
            rows.associate { it.from to it.qty },
        )
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.to == Void && it.cause == Cause.ITEM_DESTROY_VOID && it.actor == thrower }, "$rows")
        // Contents are only filed under a name the bundle carries, so an unnamed one has none to take.
        assertTrue(namedContents(bundle(null), codec).isEmpty())
    }

    // Recording the whole donor would book items that never moved.
    @Test
    fun `a merge moves only as much as the survivor has room for`() {
        assertEquals(10, mergedAmount(donor = 10, target = 50, targetMax = 64))
        assertEquals(14, mergedAmount(donor = 30, target = 50, targetMax = 99))
        assertEquals(0, mergedAmount(donor = 30, target = 64, targetMax = 99))
        assertEquals(0, mergedAmount(donor = 30, target = 80, targetMax = 99))
    }

    @Test
    fun `a drop the pass could not spend is born anyway`() {
        val entity = UUID.randomUUID()
        val form = "diamond".toByteArray()
        unspentDrop(spawns.coalescer, Intent(Cause.DROP_FROM_MENU, to = ItemEntityRef(entity), form = form, qty = 5), 5)

        val row = spawns.rows().single()
        assertEquals(Cause.ITEM_SPAWN, row.cause)
        assertEquals(Void, row.from)
        assertEquals(ItemEntityRef(entity), row.to)
        assertEquals(5, row.qty)
        assertEquals(Confidence.INFERRED, row.confidence)
        assertTrue(form.contentEquals(row.form))
    }

    // An intent that never named an entity of its own explains something other than a birth, and the
    // spawn it would invent here belongs to nothing.
    @Test
    fun `an intent that names no dropped entity is left alone`() {
        unspentDrop(spawns.coalescer, Intent(Cause.DEATH_DROP, qty = 3, recorded = true), 3)
        unspentDrop(spawns.coalescer, Intent(Cause.PICKUP, from = ItemEntityRef(UUID.randomUUID()), qty = 1), 1)
        // An entity with no form to give it says nothing of what was born.
        unspentDrop(spawns.coalescer, Intent(Cause.DROP_FROM_MENU, to = ItemEntityRef(UUID.randomUUID()), qty = 2), 2)
        assertTrue(spawns.rows().isEmpty())
    }
}
