package io.pfaumc.pfauprotect.attribution
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.bob
import io.pfaumc.pfauprotect.BothPlanes
import io.pfaumc.pfauprotect.T0
import io.pfaumc.pfauprotect.Vanilla
import io.pfaumc.pfauprotect.stub
import io.pfaumc.pfauprotect.storage.BlockChange
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.worldStub
import net.minecraft.world.item.Items
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.damage.DamageSource
import org.bukkit.entity.Cow
import org.bukkit.entity.Creeper
import org.bukkit.entity.EnderCrystal
import org.bukkit.entity.IronGolem
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID
import net.minecraft.world.item.ItemStack as NmsItemStack

private const val AIR = "minecraft:air"
private const val TNT = "minecraft:tnt"
private const val STONE = "minecraft:stone"
private const val WATER = "minecraft:water[level=1]"
private const val SAND = "minecraft:sand"
private const val CHEST_RIGHT = "minecraft:chest[facing=north,type=right]"
private const val CHEST_SINGLE = "minecraft:chest[facing=north,type=single]"

@ExtendWith(Vanilla::class)
class AttributionTest : BothPlanes() {
    private var clock = T0
    private val attribution by lazy { Attribution(shared.registries, logs) { clock } }

    private fun at(x: Int, y: Int, z: Int) = WorldBlock(world, x, y, z)

    // Every read enforces its own window, so a note the sweep left behind reads as nobody all the same.
    // Read from the moment the notes were written, only a note that is really gone answers with nobody.
    private fun <T> asOf(time: Long, read: () -> T): T {
        val now = clock
        clock = time
        return try {
            read()
        } finally {
            clock = now
        }
    }

    private fun journalled(
        at: WorldBlock,
        before: String,
        after: String,
        actor: UUID?,
        ts: Long = T0,
        cause: Cause = Cause.BLK_PLAYER_PLACE,
        confidence: Confidence = Confidence.FACT,
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
                    confidence = confidence,
                    actor = actor,
                )
            )
        )
        log.drain()
    }

    // Sculk spreads off the newest death a player stood behind within reach, for a minute and no longer.
    @Test
    fun `sculk answers to the newest kill near it`() {
        attribution.killed(WorldBlock(world, 0, 64, 0), alice)
        clock += 1_000
        attribution.killed(WorldBlock(world, 5, 64, 0), bob)
        assertEquals(bob, attribution.killerNear(WorldBlock(world, 3, 64, 0), 12)?.actor)
        assertEquals(null, attribution.killerNear(WorldBlock(world, 40, 64, 0), 12))
        clock += KILL_MILLIS + 1
        assertEquals(null, attribution.killerNear(WorldBlock(world, 3, 64, 0), 12))
    }

    // A fire spreading on from a block the journal found has to find a note there: without one, the
    // second block it reaches has nobody behind it.
    @Test
    fun `the tracker outranks the journal, which is asked by name and noted for the chain that follows`() {
        val here = at(10, 64, 10)
        journalled(here, AIR, TNT, bob)
        assertNull(attribution.placerAt(here, TNT))
        assertEquals(Attributed(bob, Confidence.INFERRED), attribution.journalPlacerAt(here, TNT))
        assertEquals(Attributed(bob, Confidence.INFERRED), attribution.placerOrJournal(here, TNT))
        assertNull(attribution.placerAt(here, TNT), "a find without renote leaves no note")
        attribution.placerOrJournal(here, TNT, renote = true)
        assertEquals(bob, attribution.placerAt(here, TNT)?.actor)

        attribution.placed(here, TNT, alice)
        assertEquals(Attributed(alice, Confidence.INFERRED), attribution.placerAt(here, TNT))
        assertEquals(alice, attribution.placerOrJournal(here, TNT)?.actor)

        assertNull(attribution.placerAt(at(11, 64, 10), TNT))
        assertNull(attribution.journalPlacerAt(at(11, 64, 10), TNT))
    }

    // A frame comes down seconds after its wall: the breaker is still the newest row there, and a row
    // older than a frame ever waits is not asked.
    @Test
    fun `the journal names who emptied a wall a frame has only now noticed`() {
        val wall = at(10, 64, 10)
        journalled(wall, STONE, AIR, bob, T0, Cause.BLK_PLAYER_BREAK)
        clock = T0 + 5_000
        assertEquals(bob, attribution.journalRemoverAt(wall)?.actor)
        assertEquals(Confidence.INFERRED, attribution.journalRemoverAt(wall)?.confidence)
        clock = T0 + HANGING_MILLIS + 1
        assertNull(attribution.journalRemoverAt(wall))
    }

    // Lava sets fire minutes after its bucket, and the source still has the bucket's row, even where the
    // bucket was emptied into lava running there. A dispenser's row that only knew who stood near says
    // no more than that.
    @Test
    fun `the journal names who poured a source`() {
        val here = at(10, 64, 10)
        journalled(here, "minecraft:lava[level=2]", "minecraft:lava[level=0]", bob, cause = Cause.BLK_BUCKET)
        assertEquals(bob, attribution.journalPlacerAt(here, "minecraft:lava[level=0]", POURING_CAUSES)?.actor)
        assertNull(attribution.journalPlacerAt(here, "minecraft:lava[level=0]"))

        val there = at(12, 64, 10)
        journalled(there, AIR, "minecraft:lava[level=0]", alice, cause = Cause.BLK_DISPENSER, confidence = Confidence.NEARBY)
        assertEquals(Confidence.NEARBY, attribution.journalPlacerAt(there, "minecraft:lava[level=0]", POURING_CAUSES)?.confidence)
    }

    // Lava that ran far from its bucket sets fire where no walk back to the source reaches, and the flow row
    // of the lava block itself names who let it run: 6595 ignitions on a real map were nobody's.
    @Test
    fun `the journal names who let a flow run`() {
        val here = at(10, 64, 10)
        journalled(here, AIR, "minecraft:lava[level=6]", bob, cause = Cause.BLK_LIQUID_FLOW)
        assertEquals(bob, attribution.journalPlacerAt(here, "minecraft:lava[level=6]", FLOWING_CAUSES)?.actor)
        assertNull(attribution.journalPlacerAt(here, "minecraft:lava[level=6]", POURING_CAUSES))
    }

    // A fire outlives its note; the row that set it there, by spreading or by a plank burning into it,
    // still names whose fire it is. A break of the fire puts nothing down.
    @Test
    fun `the journal names whose fire is burning`() {
        val fire = "minecraft:fire[age=7]"
        val leapt = at(10, 64, 10)
        journalled(leapt, AIR, fire, bob, cause = Cause.BLK_FIRE_SPREAD)
        assertEquals(bob, attribution.journalPlacerAt(leapt, fire, FIRING_CAUSES)?.actor)

        val burnt = at(11, 64, 10)
        journalled(burnt, "minecraft:oak_planks", fire, bob, cause = Cause.BLK_FIRE_BURN)
        assertEquals(bob, attribution.journalPlacerAt(burnt, fire, FIRING_CAUSES)?.actor)
        journalled(burnt, fire, AIR, alice, T0 + 1, Cause.BLK_PLAYER_BREAK)
        assertNull(attribution.journalPlacerAt(burnt, fire, FIRING_CAUSES))
    }

    @Test
    fun `the journal rung stops at what stands there today`() {
        val here = at(10, 64, 10)
        journalled(here, AIR, TNT, alice, T0)
        journalled(here, TNT, AIR, bob, T0 + 1, Cause.BLK_PLAYER_BREAK)
        journalled(here, AIR, STONE, bob, T0 + 2)

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
        journalled(broken, AIR, CHEST_RIGHT, alice, T0)
        journalled(broken, CHEST_RIGHT, CHEST_SINGLE, bob, T0 + 1, Cause.BLK_PLAYER_BREAK)
        assertNull(attribution.journalPlacerAt(broken, CHEST_SINGLE))

        val rewritten = at(20, 64, 20)
        journalled(rewritten, AIR, CHEST_RIGHT, alice, T0)
        journalled(rewritten, CHEST_RIGHT, CHEST_SINGLE, bob, T0 + 1)
        assertNull(attribution.journalPlacerAt(rewritten, CHEST_SINGLE))

        // A block that arrived by landing was put down by nobody standing here, and the actor of such
        // a row was himself worked out. One guess is not made to carry another.
        val landed = at(30, 64, 30)
        journalled(landed, AIR, TNT, bob, T0, Cause.BLK_FALL_LAND)
        assertNull(attribution.journalPlacerAt(landed, TNT))
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

    // Concrete powder under a griefer's TNT came down 206 ms after the planks under it, the server
    // running behind on the very blast, and was filed on nobody while the window was 100 ms.
    @Test
    fun `a fall late behind a lagging blast still finds who took its support`() {
        attribution.removed(at(5, 64, 5), alice)

        clock += 206
        assertEquals(alice, attribution.supportRemoverAt(at(5, 65, 5))?.actor)
    }

    // Two players mining shoulder to shoulder in one tick: the cell under the column is the one that
    // was holding it up, whatever the scan order over the corners would have said.
    @Test
    fun `a face beats a diagonal broken in the same tick`() {
        attribution.removed(at(4, 64, 4), bob)
        attribution.removed(at(5, 64, 5), alice)

        assertEquals(alice, attribution.supportRemoverAt(at(5, 65, 5))?.actor)
    }

    // The newer note is on the face scanned first, so taking the last match in scan order answers wrong.
    @Test
    fun `the most recent removal of a ring is the one that answers`() {
        attribution.removed(at(5, 64, 5), bob)

        clock += 10
        attribution.removed(at(6, 65, 5), alice)

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
        asOf(T0) { assertNull(attribution.fellerOf(at(2, 70, 0))) }
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

    // Each table is swept by its own window and no other's: kept at the last moment it stands for, gone
    // a millisecond after.
    private fun assertSweptAfter(window: Long, read: () -> Attributed?) {
        clock = T0 + window
        attribution.sweep()
        asOf(T0) { assertEquals(alice, read()?.actor) }
        clock += 1
        attribution.sweep()
        asOf(T0) { assertNull(read()) }
    }

    @Test
    fun `the sweep drops a placement once its window has passed`() {
        val here = at(10, 64, 10)
        attribution.placed(here, TNT, alice)
        assertSweptAfter(NOTE_MILLIS) { attribution.placerAt(here, TNT) }
    }

    @Test
    fun `the sweep drops a removal once its window has passed`() {
        val here = at(10, 64, 10)
        attribution.removed(here, alice)
        assertSweptAfter(SUPPORT_MILLIS) { attribution.removerAt(here) }
    }

    @Test
    fun `the sweep drops a kill once its window has passed`() {
        val here = at(10, 64, 10)
        attribution.killed(here, alice)
        assertSweptAfter(KILL_MILLIS) { attribution.killerNear(here, 1) }
    }

    @Test
    fun `the sweep drops a flight once its window has passed`() {
        // Taken once, so the read that finds it standing and the one that finds it gone ask after two.
        val kept = UUID.randomUUID()
        val dropped = UUID.randomUUID()
        attribution.tookOff(kept, Falling(at(10, 64, 10), SAND, null))
        attribution.tookOff(dropped, Falling(at(10, 64, 10), SAND, null))
        clock = T0 + FLIGHT_MILLIS
        attribution.sweep()
        assertEquals(Falling(at(10, 64, 10), SAND, null), asOf(T0) { attribution.landed(kept) })
        clock += 1
        attribution.sweep()
        assertNull(asOf(T0) { attribution.landed(dropped) })
    }

    @Test
    fun `the builder search takes the most recent placement inside its reach and nothing outside it`() {
        val spawn = at(0, 64, 0)
        attribution.placed(at(0, 66, 0), STONE, alice)
        clock += 1
        attribution.placed(at(-3, 65, -1), STONE, bob)

        // Whatever block it was and wherever in the shape it stood: the last one placed completes it.
        assertEquals(bob, attribution.builderNear(spawn, 3)?.actor)
        // A reach that does not span the shape answers with whoever is inside it, not with nobody.
        assertEquals(alice, attribution.builderNear(spawn, 2)?.actor)
        assertNull(attribution.builderNear(spawn, 1))
        assertEquals(Confidence.INFERRED, attribution.builderNear(spawn, 3)?.confidence)

        clock += NOTE_MILLIS + 1
        assertNull(attribution.builderNear(spawn, 3))
    }

    // An unloaded entity comes back out of the region file with the same id, and a crystal is taken out
    // of the world before its blast asks who struck it.
    @Test
    fun `an origin outlives an unload and a crystal's removal and ends with the entity`() {
        val origins = EntityOrigins { clock }
        val listener = EntityOriginListener(attribution, origins)
        val golem = stub(IronGolem::class.java, mapOf("getUniqueId" to UUID.randomUUID()))
        val crystal = stub(EnderCrystal::class.java, mapOf("getUniqueId" to UUID.randomUUID()))
        origins.appeared(golem.uniqueId, alice)
        origins.appeared(crystal.uniqueId, alice)

        listener.onRemove(EntityRemoveEvent(golem, EntityRemoveEvent.Cause.UNLOAD))
        listener.onRemove(EntityRemoveEvent(crystal, EntityRemoveEvent.Cause.EXPLODE))
        // Nothing here is witnessed: the block event that reads it named nobody at all.
        assertEquals(Attributed(alice, Confidence.INFERRED), origins.summonerOf(golem.uniqueId))
        assertEquals(alice, origins.summonerOf(crystal.uniqueId)?.actor)

        listener.onRemove(EntityRemoveEvent(golem, EntityRemoveEvent.Cause.DEATH))
        assertNull(origins.summonerOf(golem.uniqueId))
    }

    @Test
    fun `a spawn egg names its player on the one mob that comes out of it`() {
        val origins = EntityOrigins { clock }
        val listener = EntityOriginListener(attribution, origins)
        val ground = stub(World::class.java, mapOf(
            "getUID" to world,
            "getBlockAt" to { _: Array<out Any?> -> stub(Block::class.java, mapOf("getWorld" to worldStub(world), "getX" to 3, "getY" to 64, "getZ" to 3)) },
        ))
        fun cow() = stub(Cow::class.java, mapOf("getUniqueId" to UUID.randomUUID(), "getLocation" to { _: Array<out Any?> -> Location(ground, 3.5, 64.0, 3.5) }))
        val egg = CraftItemStack.asBukkitCopy(NmsItemStack(Items.COW_SPAWN_EGG))
        listener.onInteract(PlayerInteractEvent(stub(Player::class.java, mapOf("getUniqueId" to alice)), Action.RIGHT_CLICK_BLOCK, egg, null, BlockFace.UP))

        val hatched = cow()
        listener.onSpawn(CreatureSpawnEvent(hatched, SpawnReason.SPAWNER_EGG))
        assertEquals(alice, origins.summonerOf(hatched.uniqueId)?.actor)

        val next = cow()
        listener.onSpawn(CreatureSpawnEvent(next, SpawnReason.SPAWNER_EGG))
        assertNull(origins.summonerOf(next.uniqueId))
    }

    @Test
    fun `an origin nobody saw end stops answering once its window has passed`() {
        val origins = EntityOrigins { clock }
        val golem = UUID.randomUUID()
        origins.appeared(golem, alice)

        clock += ORIGIN_MILLIS + 1
        assertNull(origins.summonerOf(golem))

        origins.sweep()
        asOf(T0) { assertNull(origins.summonerOf(golem)) }
    }

    @Test
    fun `a crystal struck by a mob keeps how sure the mob's origin was`() {
        val origins = EntityOrigins { clock }
        val creeper = stub(Creeper::class.java, mapOf("getUniqueId" to UUID.randomUUID()))
        val crystalId = UUID.randomUUID()
        val crystal = stub(EnderCrystal::class.java, mapOf("getUniqueId" to crystalId))
        origins.appeared(creeper.uniqueId, alice, Confidence.NEARBY)

        EntityOriginListener(attribution, origins).onStrike(
            EntityDamageByEntityEvent(creeper, crystal, DamageCause.ENTITY_EXPLOSION, stub(DamageSource::class.java), 1.0),
        )

        assertEquals(alice, origins.summonerOf(crystalId)?.actor)
        assertEquals(Confidence.NEARBY, origins.summonerOf(crystalId)?.confidence)
    }
}
