package io.pfaumc.pfauprotect.capture.item
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.ItemEntityRef
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.MemoryRegistryStore
import io.pfaumc.pfauprotect.model.Nested
import io.pfaumc.pfauprotect.storage.PlacedForms
import io.pfaumc.pfauprotect.storage.Registries
import io.pfaumc.pfauprotect.ServerRegistries
import io.pfaumc.pfauprotect.capture.block.SpawnOrigins
import io.pfaumc.pfauprotect.capture.block.TickCoalescer
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.BundleContents
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Item
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID
import net.minecraft.world.item.ItemStack as NmsItemStack

class WorldItemsTest {
    private val written = ArrayList<Transfer>()
    private val coalescer = TickCoalescer(written::add)

    private fun rows(): List<Transfer> {
        coalescer.flush()
        return written
    }

    // An item that is hurt and lives keeps the damage that hurt it, and only an item whose health ran
    // out was killed by what it carries, so the two are given separately.
    private fun end(cause: EntityRemoveEvent.Cause, damage: DamageCause? = null, health: Int = 0) =
        itemEnd(cause, damage, health)

    @Test
    fun `an item that runs out of time or falls out of the world is destroyed`() {
        assertEquals(Cause.ITEM_DESPAWN to Confidence.FACT, end(EntityRemoveEvent.Cause.DESPAWN))
        assertEquals(Cause.ITEM_DESTROY_VOID to Confidence.FACT, end(EntityRemoveEvent.Cause.OUT_OF_WORLD))
    }

    // Both of these are the same movement seen from the other side, and their own events say more
    // about it than this one can. A row here would be the second copy of it.
    @Test
    fun `being picked up or merged is not an end`() {
        assertNull(end(EntityRemoveEvent.Cause.PICKUP))
        assertNull(end(EntityRemoveEvent.Cause.MERGE))
    }

    // The entity goes into the region file still holding its item and comes back holding it, so a
    // death written here would turn into a second copy of the item on the next load.
    @Test
    fun `an unloaded item has not stopped existing`() {
        assertNull(end(EntityRemoveEvent.Cause.UNLOAD))
        assertNull(end(EntityRemoveEvent.Cause.PLAYER_QUIT))
    }

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

    // An explosion with nothing behind it arrives as CUSTOM, and calling that fire would be inventing
    // the fire. It is still an end, and it is still written — as a guess, so it can be counted.
    @Test
    fun `a death nobody can name is still written and marked a guess`() {
        val unnamed = end(EntityRemoveEvent.Cause.DEATH, DamageCause.CUSTOM)
        assertEquals(Confidence.INFERRED, unnamed?.second)
        assertEquals(end(EntityRemoveEvent.Cause.DEATH, null), unnamed)
    }

    // Killing an item removes it with the same cause a fatal blow does, and hurts it not at all: it
    // dies in full health with nothing behind it. Read as an unnamed end it would be counted among
    // the paths the capture cannot see, on a path it can see perfectly well.
    @Test
    fun `an item killed outright is a kill and not an end nobody can name`() {
        assertEquals(
            Cause.CMD_KILL_ITEM to Confidence.FACT,
            end(EntityRemoveEvent.Cause.DEATH, health = 5),
        )
    }

    // The last damage stays on an entity for the rest of its life, so an item that caught fire, lived
    // through it and was killed later still reads as burnt unless its health is asked about too.
    @Test
    fun `damage an item survived does not name the death that comes later`() {
        assertEquals(
            Cause.CMD_KILL_ITEM to Confidence.FACT,
            end(EntityRemoveEvent.Cause.DEATH, DamageCause.FIRE, health = 4),
        )
    }

    // The whole point of the table: every way an item can stop existing writes exactly one row, so
    // its balance closes or names the path that broke it. A cause nobody has thought about must not
    // fall through into silence.
    @Test
    fun `every removal either writes a row or is deliberately silent`() {
        val silent = setOf(
            EntityRemoveEvent.Cause.PICKUP,
            EntityRemoveEvent.Cause.MERGE,
            EntityRemoveEvent.Cause.UNLOAD,
            EntityRemoveEvent.Cause.PLAYER_QUIT,
        )
        for (cause in EntityRemoveEvent.Cause.entries) {
            if (cause in silent) continue
            assertNotNull(end(cause)) { "$cause writes nothing at all" }
        }
    }

    // Health is whole and the hit is cut down to a whole number, so a hit that leaves less than one
    // point is the end of the item.
    @Test
    fun `a hit is lethal when what it leaves rounds down to nothing`() {
        assertTrue(lethal(5, 5.0))
        assertTrue(lethal(5, 4.5))
        assertFalse(lethal(5, 3.9))
    }

    // Only a death by damage spills what a box held; running out of time, the void and a kill take the
    // contents down with it.
    @Test
    fun `only an item whose health ran out spills its contents`() {
        assertTrue(spills(EntityRemoveEvent.Cause.DEATH, 0))
        assertFalse(spills(EntityRemoveEvent.Cause.DEATH, 4))
        assertFalse(spills(EntityRemoveEvent.Cause.OUT_OF_WORLD, 5))
        assertFalse(spills(EntityRemoveEvent.Cause.DESPAWN, 5))
    }

    private val codec by lazy { ItemFormCodec(Registries(MemoryRegistryStore()), ServerRegistries.access) }

    // `Items` cannot be touched before the registries are up, and the codec is what brings them up.
    private fun bundle(owner: UUID?) = codec.let { NmsItemStack(Items.BUNDLE) }.apply {
        set(
            DataComponents.BUNDLE_CONTENTS,
            BundleContents(listOf(ItemStackTemplate(Items.DIAMOND, 3), ItemStackTemplate(Items.STONE, 16))),
        )
        owner?.let { NestedItems.mark(this, it) }
    }

    @Test
    fun `a bundle accounts for its contents under its own name and an unnamed one for nothing`() {
        val owner = UUID.randomUUID()
        assertTrue(namedContents(bundle(null), codec).isEmpty())

        val contents = namedContents(bundle(owner), codec)
        assertEquals(listOf(Nested(owner, 0), Nested(owner, 1)), contents.map { it.first })
        assertEquals(listOf(3, 16), contents.map { it.second.count })
    }

    // Thrown over the edge: the end of the bundle and of everything in it, and the player who threw it
    // is on every one of those rows.
    @Test
    fun `a thrown bundle lost to the void names its thrower and takes its contents with it`() {
        val owner = UUID.randomUUID()
        val thrower = UUID.randomUUID()
        val entity = UUID.randomUUID()
        val origins = SpawnOrigins(coalescer)
        val capture = ContainerCaptureListener(
            stub(Plugin::class.java, mapOf("isEnabled" to false)), {}, codec,
            Registries(MemoryRegistryStore()), origins, noPlacedForms(),
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

        WorldItemListener(codec, coalescer, origins, capture)
            .onRemove(EntityRemoveEvent(item, EntityRemoveEvent.Cause.OUT_OF_WORLD))

        val rows = rows()
        assertEquals(setOf(ItemEntityRef(entity), Nested(owner, 0), Nested(owner, 1)), rows.map { it.from }.toSet())
        assertTrue(rows.all { it.to == Void && it.cause == Cause.ITEM_DESTROY_VOID && it.actor == thrower }, "$rows")
    }

    private fun noPlacedForms() = object : PlacedForms {
        override fun formAt(world: UUID, x: Int, y: Int, z: Int): ByteArray? = null
        override fun formsAt(positions: List<WorldBlock>): Map<WorldBlock, ByteArray> = emptyMap()
        override fun setFormAt(world: UUID, x: Int, y: Int, z: Int, form: ByteArray) = Unit
        override fun clearFormAt(world: UUID, x: Int, y: Int, z: Int) = Unit
        override fun clearFormsAt(positions: List<WorldBlock>) = Unit
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> stub(type: Class<T>, answers: Map<String, Any?>): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> answers[method.name] } as T

    // The merge is allowed as long as the donor fits under the survivor's own maximum, and then never
    // fills it past 64. An item that stacks to 99 therefore leaves the donor alive holding the rest,
    // and recording the whole donor would book items that never moved.
    @Test
    fun `a merge moves only as much as the survivor has room for`() {
        assertEquals(10, mergedAmount(donor = 10, target = 50, targetMax = 64))
        assertEquals(14, mergedAmount(donor = 30, target = 50, targetMax = 99))
        assertEquals(0, mergedAmount(donor = 30, target = 64, targetMax = 99))
        assertEquals(0, mergedAmount(donor = 30, target = 80, targetMax = 99))
    }

    // A drop out of the creative menu empties no slot, so the pass that was to write the birth in
    // place of the silenced spawn finds nothing to write. Without this the entity is never born, and
    // being picked up or destroyed later is a debit against a credit nobody made.
    @Test
    fun `a drop the pass could not spend is born anyway`() {
        val entity = UUID.randomUUID()
        val form = "diamond".toByteArray()
        unspentDrop(coalescer, Intent(Cause.DROP_FROM_MENU, to = ItemEntityRef(entity), form = form, qty = 5), 5)

        val row = rows().single()
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
        unspentDrop(coalescer, Intent(Cause.DEATH_DROP, qty = 3, recorded = true), 3)
        unspentDrop(coalescer, Intent(Cause.PICKUP, from = ItemEntityRef(UUID.randomUUID()), qty = 1), 1)
        assertTrue(rows().isEmpty())
    }
}
