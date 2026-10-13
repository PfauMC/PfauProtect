package io.pfaumc.pfauprotect.capture.entity

import io.pfaumc.pfauprotect.BothPlanes
import io.pfaumc.pfauprotect.ServerRegistries
import io.pfaumc.pfauprotect.Settings
import io.pfaumc.pfauprotect.Spawns
import io.pfaumc.pfauprotect.Vanilla
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.attribution.Attribution
import io.pfaumc.pfauprotect.attribution.EntityOrigins
import io.pfaumc.pfauprotect.bob
import io.pfaumc.pfauprotect.capture.block.blockStub
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.EntityKind
import io.pfaumc.pfauprotect.storage.BlockChange
import io.pfaumc.pfauprotect.storage.EntityRow
import io.pfaumc.pfauprotect.stub
import net.minecraft.core.RegistryAccess
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.ListTag
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.storage.ValueOutput
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.craftbukkit.CraftServer
import org.bukkit.craftbukkit.entity.CraftEntity
import org.bukkit.craftbukkit.entity.CraftLivingEntity
import org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer
import org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry
import org.bukkit.damage.DamageSource
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.Wither
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import sun.misc.Unsafe
import java.util.UUID
import net.minecraft.world.entity.EntityType as NmsEntityType
import net.minecraft.world.entity.LivingEntity as NmsLivingEntity

@ExtendWith(Vanilla::class)
class EntityCaptureTest : BothPlanes() {
    private val tasks = ArrayList<() -> Unit>()
    private val summoned = EntityOrigins()

    private fun capture() = EntityCapture(
        logs, Spawns().origins, Attribution(shared.registries, logs), summoned,
        later = { _, task -> tasks += task }, laterOn = { _, task, _ -> tasks += task }, offThread = { tasks += it },
    )

    private fun cow(x: Double, health: Float, name: String?, ticks: Int, resting: Boolean = false, touched: Boolean = false): ByteArray {
        val tag = CompoundTag()
        if (touched) tag.put("BukkitValues", CompoundTag().apply { putByte("pfauprotect:touched", 1) })
        tag.putBoolean("EatingHaystack", resting)
        tag.putBoolean("Sitting", resting)
        tag.put("Pos", ListTag().apply { add(DoubleTag.valueOf(x)); add(DoubleTag.valueOf(64.0)); add(DoubleTag.valueOf(0.0)) })
        tag.putFloat("Health", health)
        tag.putInt("Spigot.ticksLived", ticks)
        if (name != null) tag.putString("CustomName", name)
        return bytesOf(tag)
    }

    // A cow that walked a step, healed and lived another tick is the cow it was. A cow somebody named is not.
    @Test
    fun `only what a hand can change counts as a change`() {
        val before = cow(1.0, 5f, null, 100)
        assertFalse(changedBetween(before, cow(1.7, 10f, null, 101)))
        // A horse stops grazing when it is mounted; that is the horse, not the hand. A pet sits down
        // only for its owner.
        assertFalse(changedBetween(before, cow(1.0, 5f, null, 100, resting = true)))
        assertTrue(changedBetween(before, cow(1.0, 5f, "Burenka", 100)))
        // The plugin's own mark of a hand is not the hand's change.
        assertFalse(changedBetween(before, cow(1.0, 5f, null, 100, touched = true)))
    }

    // CoreProtect marks an entity on every click: its bookkeeping, not the player's change, and left
    // as it is by a rollback. The slots this plugin booked on the entity still count.
    @Test
    fun `what other plugins keep on an entity is theirs`() {
        fun marked(vararg keys: String) = CompoundTag().apply {
            put("BukkitValues", CompoundTag().apply { for (key in keys) putByte(key, 1) })
        }
        val before = marked("pfauprotect:held_16")
        assertFalse(changedBetween(bytesOf(before), bytesOf(marked("pfauprotect:held_16", "coreprotect:pending_entity_identity"))))
        assertTrue(changedBetween(bytesOf(before), bytesOf(marked("coreprotect:pending_entity_identity"))))

        val back = keepingOthersMarks(marked("pfauprotect:held_3", "coreprotect:old"), marked("pfauprotect:held_16", "coreprotect:pending_entity_identity"))
        assertEquals(setOf("pfauprotect:held_3", "coreprotect:pending_entity_identity"), back.getCompoundOrEmpty("BukkitValues").keySet())
    }

    // A death nobody stands behind: a mob somebody had a hand in always, the place's own mob unless it is
    // one of a farm's crowd, a zombie the night spawned never.
    @Test
    fun `a death with nobody behind it is kept for a mob somebody owns`() {
        assertTrue(worthRecording(touched = true, keepsItsPlace = false, sameKindInChunk = 50))
        assertTrue(worthRecording(touched = false, keepsItsPlace = true, sameKindInChunk = Settings.crowd - 1))
        assertFalse(worthRecording(touched = false, keepsItsPlace = true, sameKindInChunk = Settings.crowd))
        assertFalse(worthRecording(touched = false, keepsItsPlace = false, sameKindInChunk = 1))
    }

    // A frame broken by a hit is heard by its break and by its removal, and a rollback that takes an
    // entity away writes the removal itself: either way the next sighting must not write a second row.
    @Test
    fun `an entity's removal is written once however often it is heard`() {
        val capture = capture()
        val framed = UUID.randomUUID()
        assertTrue(capture.firstSighting(framed, now))
        assertFalse(capture.firstSighting(framed, now + 1))

        val takenAway = UUID.randomUUID()
        capture.forget(takenAway)
        assertFalse(capture.firstSighting(takenAway, now))
    }

    // A player who dies of their own arrow or their own dynamite is no grief between players.
    @Test
    fun `a player killed by another is written and one killed by themselves is not`() {
        val capture = capture()
        val ground = stub(World::class.java, mapOf(
            "getUID" to world,
            "getBlockAt" to { _: Array<out Any?> -> blockStub(world, 3, 64, 3, Blocks.AIR.defaultBlockState().asBlockData()) },
        ))
        fun player(id: UUID, killer: Player? = null) = stub(Player::class.java, mapOf(
            "getUniqueId" to id,
            "getKiller" to killer,
            "getWorld" to ground,
            "getLocation" to { _: Array<out Any?> -> Location(ground, 3.5, 64.0, 3.5) },
        ))
        fun die(victim: Player) {
            capture.onPlayerDeath(PlayerDeathEvent(victim, stub(DamageSource::class.java), mutableListOf(), 0, null, false))
            tasks.forEach { it() }
            tasks.clear()
            log.drain()
        }

        die(player(alice, killer = player(alice)))
        assertTrue(log.entitiesAt(3, 64, 3, 0, Long.MAX_VALUE, 100).rows.isEmpty())

        die(player(alice, killer = player(bob)))
        val row = log.entitiesAt(3, 64, 3, 0, Long.MAX_VALUE, 100).rows.single()
        assertEquals(EntityKind.PLAYER_DIED, row.kind)
        assertEquals(alice, row.uuid)
        assertEquals(bob, row.actor)
    }

    private val ground by lazy {
        stub(World::class.java, mapOf(
            "getUID" to world,
            "getBlockAt" to { _: Array<out Any?> -> blockStub(world, 3, 64, 3, Blocks.AIR.defaultBlockState().asBlockData()) },
            "getChunkAt" to { _: Array<out Any?> -> stub(Chunk::class.java, mapOf("getEntities" to emptyArray<Entity>())) },
        ))
    }

    private fun mobDies(damage: (Dying) -> EntityDamageEvent, fell: Float = 0f): EntityRow {
        val capture = capture()
        val mob = dying(ground, fell, damage)
        capture.onDeath(EntityDeathEvent(mob, stub(DamageSource::class.java), mutableListOf()))
        while (tasks.isNotEmpty()) tasks.removeFirst()()
        log.drain()
        return log.entitiesAt(3, 64, 3, 0, Long.MAX_VALUE, 100).rows.single { it.uuid == mob.uniqueId }
    }

    // The floor a mob fell from went away while Alice only stood near the piston that pulled it: the
    // journal knows her as a witness, and a witness is not who killed the mob.
    @Test
    fun `a mob killed by a change only witnessed around it names nobody`() {
        log.submit(listOf(BlockChange(3, 83, 3, "minecraft:stone", "minecraft:air", Cause.BLK_PISTON_RETRACT, now, Confidence.NEARBY, actor = alice)))
        log.drain()

        val row = mobDies({ EntityDamageEvent(it, DamageCause.FALL, stub(DamageSource::class.java), 20.0) }, fell = 20f)
        assertEquals(EntityKind.REMOVED, row.kind)
        assertNull(row.actor)
        assertEquals(Confidence.NEARBY, row.confidence)
    }

    @Test
    fun `a mob a witness brought in that dies of nothing anybody did names nobody`() {
        val row = mobDies({ mob ->
            summoned.appeared(mob.uniqueId, alice, Confidence.NEARBY)
            EntityDamageEvent(mob, DamageCause.STARVATION, stub(DamageSource::class.java), 1.0)
        })
        assertNull(row.actor)
    }

    @Test
    fun `a mob killed by a wither names whoever built the wither`() {
        val wither = stub(Wither::class.java, mapOf("getUniqueId" to UUID.randomUUID(), "getType" to EntityType.WITHER))
        summoned.appeared(wither.uniqueId, alice)
        val row = mobDies({ EntityDamageByEntityEvent(wither, it, DamageCause.ENTITY_EXPLOSION, stub(DamageSource::class.java), 1.0) })
        assertEquals(alice, row.actor)
        assertEquals(Confidence.INFERRED, row.confidence)
    }
}

// The capture saves a dying mob's NBT through its CraftEntity, and a real one needs a running server.
// These shells are made without their constructors and answer only what a death reads.
private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe

private class Saved(type: NmsEntityType<out ArmorStand>, level: Level) : ArmorStand(type, level) {
    override fun registryAccess(): RegistryAccess = ServerRegistries.access

    override fun saveAsPassenger(output: ValueOutput, includeAll: Boolean, includeNonSaveable: Boolean, forceSerialization: Boolean): Boolean {
        output.putString("id", "minecraft:cow")
        return true
    }
}

private class Dying(server: CraftServer, handle: NmsLivingEntity) : CraftLivingEntity(server, handle) {
    lateinit var id: UUID
    lateinit var ground: World
    lateinit var saved: NmsLivingEntity
    lateinit var damage: EntityDamageEvent
    var fell = 0f

    override fun getHandle(): NmsLivingEntity = saved
    override fun getUniqueId(): UUID = id
    override fun getWorld(): World = ground
    override fun getLocation(): Location = Location(ground, 3.5, 64.0, 3.5)
    override fun getFallDistance(): Float = fell
    override fun getLastDamageCause(): EntityDamageEvent = damage
    override fun getKiller(): Player? = null
    override fun getPersistentDataContainer() = CraftPersistentDataContainer(CraftPersistentDataTypeRegistry())
}

private fun dying(ground: World, fell: Float, damage: (Dying) -> EntityDamageEvent): Dying {
    val mob = unsafe.allocateInstance(Dying::class.java) as Dying
    CraftEntity::class.java.getDeclaredField("entityType").apply { isAccessible = true }.set(mob, EntityType.COW)
    mob.id = UUID.randomUUID()
    mob.ground = ground
    mob.fell = fell
    mob.saved = unsafe.allocateInstance(Saved::class.java) as Saved
    mob.damage = damage(mob)
    return mob
}
