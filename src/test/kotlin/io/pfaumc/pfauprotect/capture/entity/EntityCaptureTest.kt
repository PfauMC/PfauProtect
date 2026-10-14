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
import io.pfaumc.pfauprotect.capture.block.Spot
import io.pfaumc.pfauprotect.capture.block.blockStub
import io.pfaumc.pfauprotect.key
import io.pfaumc.pfauprotect.model.PlayerInv
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
import net.minecraft.world.entity.decoration.HangingEntity
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.storage.ValueOutput
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.craftbukkit.CraftServer
import org.bukkit.craftbukkit.entity.CraftEntity
import org.bukkit.craftbukkit.entity.CraftHanging
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
import org.bukkit.event.hanging.HangingBreakEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import sun.misc.Unsafe
import java.util.UUID
import com.google.common.collect.ImmutableList
import net.minecraft.world.entity.Entity as NmsEntity
import net.minecraft.world.entity.EntityType as NmsEntityType
import net.minecraft.world.entity.LivingEntity as NmsLivingEntity

@ExtendWith(Vanilla::class)
class EntityCaptureTest : BothPlanes() {
    private val tasks = ArrayList<() -> Unit>()

    private fun runTasks() {
        while (tasks.isNotEmpty()) tasks.removeFirst()()
    }
    private val summoned = EntityOrigins()

    private fun capture(spawns: Spawns = Spawns()) = EntityCapture(
        logs, spawns.origins, Attribution(shared.registries, logs), summoned,
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
        val mob = dying(ground, 0f, ::starve)
        summoned.appeared(mob.uniqueId, alice)
        die(capture, mob, heard = 2)
        assertEquals(1, rowsHere().count { it.uuid == mob.uniqueId })

        val takenAway = dying(ground, 0f, ::starve)
        summoned.appeared(takenAway.uniqueId, alice)
        capture.forget(takenAway.uniqueId)
        die(capture, takenAway)
        assertEquals(0, rowsHere().count { it.uuid == takenAway.uniqueId })
    }

    // A player who dies of their own arrow or their own dynamite is no grief between players. What fell
    // out of a player killed by another is named on the row, for a rollback of the killer to take back.
    @Test
    fun `a player killed by another is written with their drops and one killed by themselves is not`() {
        val spawns = Spawns()
        val capture = capture(spawns)
        fun player(id: UUID, killer: Player? = null) = stub(Player::class.java, mapOf(
            "getUniqueId" to id,
            "getKiller" to killer,
            "getWorld" to ground,
            "getLocation" to { _: Array<out Any?> -> Location(ground, 3.5, 64.0, 3.5) },
        ))
        fun die(victim: Player, dropping: () -> Unit = {}) {
            capture.onPlayerDeath(PlayerDeathEvent(victim, stub(DamageSource::class.java), mutableListOf(), 0, null, false))
            dropping()
            runTasks()
            log.drain()
        }

        die(player(alice, killer = player(alice)))
        assertTrue(rowsHere().isEmpty())

        val spot = Spot(world, 3.5, 64.0, 3.5)
        val drop = UUID.randomUUID()
        die(player(alice, killer = player(bob))) {
            spawns.origins.expect(PlayerInv(alice, 0), Cause.DEATH_DROP, key("diamond"), spot, 1, tag = alice)
            spawns.origins.expect(PlayerInv(bob, 0), Cause.DEATH_DROP, key("diamond"), spot, 1, tag = bob)
            assertEquals(1, spawns.origins.claim(drop, spot, key("diamond"), 1))
            assertEquals(1, spawns.origins.claim(UUID.randomUUID(), spot, key("diamond"), 1))
        }
        val row = rowsHere().single()
        assertEquals(EntityKind.PLAYER_DIED, row.kind)
        assertEquals(alice, row.uuid)
        assertEquals(bob, row.actor)
        assertEquals(Confidence.FACT, row.confidence)
        assertEquals(listOf(drop), row.drops)
    }

    private val ground by lazy {
        stub(World::class.java, mapOf(
            "getUID" to world,
            "getBlockAt" to { _: Array<out Any?> -> blockStub(world, 3, 64, 3, Blocks.AIR.defaultBlockState().asBlockData()) },
            "getChunkAt" to { _: Array<out Any?> -> stub(Chunk::class.java, mapOf("getEntities" to emptyArray<Entity>())) },
        ))
    }

    private fun rowsHere() = log.entitiesAt(3, 64, 3, 0, Long.MAX_VALUE, 100).rows

    private fun die(capture: EntityCapture, mob: Dying, heard: Int = 1) {
        repeat(heard) { capture.onDeath(EntityDeathEvent(mob, stub(DamageSource::class.java), mutableListOf())) }
        runTasks()
        log.drain()
    }

    private fun mobDies(damage: (Dying) -> EntityDamageEvent, fell: Float = 0f): EntityRow {
        val capture = capture()
        val mob = dying(ground, fell, damage)
        die(capture, mob)
        return rowsHere().single { it.uuid == mob.uniqueId }
    }

    // The floor a mob fell from went away while Alice only stood near the piston that pulled it: the
    // journal knows her as a witness, and a witness is not who killed the mob.
    @Test
    fun `a mob killed by a change only witnessed around it names nobody`() {
        log.submit(listOf(BlockChange(3, 83, 3, "minecraft:stone", "minecraft:air", Cause.BLK_PISTON_RETRACT, now, Confidence.NEARBY, actor = alice)))
        log.drain()

        val row = mobDies(::fall, fell = 20f)
        assertEquals(EntityKind.REMOVED, row.kind)
        assertNull(row.actor)
        assertEquals(Confidence.NEARBY, row.confidence)
    }

    @Test
    fun `a mob that fell off a floor somebody broke a moment ago names them, as a guess`() {
        log.submit(listOf(BlockChange(3, 83, 3, "minecraft:stone", "minecraft:air", Cause.BLK_PLAYER_BREAK, now, actor = bob)))
        log.drain()

        val row = mobDies(::fall, fell = 20f)
        assertEquals(bob, row.actor)
        assertEquals(Confidence.INFERRED, row.confidence)
    }

    @Test
    fun `a frame that fell off its wall names whoever took the wall away`() {
        log.submit(listOf(BlockChange(3, 64, 2, "minecraft:stone", "minecraft:air", Cause.BLK_PLAYER_BREAK, now, actor = bob)))
        log.drain()
        val wall = blockStub(world, 3, 64, 2, Blocks.AIR.defaultBlockState().asBlockData())
        val ground = stub(World::class.java, mapOf(
            "getUID" to world,
            "getBlockAt" to { _: Array<out Any?> ->
                blockStub(world, 3, 64, 3, Blocks.AIR.defaultBlockState().asBlockData(), mapOf(BlockFace.NORTH to wall))
            },
        ))
        val frame = hung(ground, BlockFace.NORTH)

        capture().onHangingBreak(HangingBreakEvent(frame, HangingBreakEvent.RemoveCause.PHYSICS))
        runTasks()
        log.drain()

        val row = rowsHere().single()
        assertEquals(EntityKind.REMOVED, row.kind)
        assertEquals(frame.uniqueId, row.uuid)
        assertEquals(bob, row.actor)
        assertEquals(Confidence.INFERRED, row.confidence)
    }

    // What a rollback brings the mob back from; its riders are entities of their own, named by UUID
    // alone, and so is what it sat in, for the rollback to seat them again.
    @Test
    fun `a removed mob keeps its whole NBT without its passengers`() {
        val cart = UUID.randomUUID()
        val pig = UUID.randomUUID()
        var cow: UUID? = null
        val row = mobDies({ mob ->
            cow = mob.uniqueId
            (mob.handle as Saved).seated(on = savedAs(cart), riders = listOf(savedAs(pig)))
            fall(mob)
        }, fell = 20f)
        val saved = nbtOf(shared.payload(row.payloadBefore!!)!!)
        assertEquals("minecraft:cow", saved.getStringOr("id", ""))
        assertFalse(saved.contains("Passengers"))
        assertEquals(listOf(cow to cart, pig to cow), seatsOf(cow!!, saved))
        // Getting out of the cart is no change of the cow's own.
        assertFalse(changedBetween(bytesOf(saved), bytesOf(saved.copy().apply { remove(VEHICLE); remove(RIDERS) })))
    }

    @Test
    fun `a mob brought in that dies of nothing anybody did names its summoner and not a witness`() {
        fun diesOf(confidence: Confidence) = mobDies({ mob ->
            summoned.appeared(mob.uniqueId, alice, confidence)
            starve(mob)
        })
        val summoner = diesOf(Confidence.FACT)
        assertEquals(alice, summoner.actor)
        assertEquals(Confidence.INFERRED, summoner.confidence)
        assertNull(diesOf(Confidence.NEARBY).actor)
    }

    @Test
    fun `a mob killed by a wither names whoever built the wither`() {
        val wither = stub(Wither::class.java, mapOf("getUniqueId" to UUID.randomUUID(), "getType" to EntityType.WITHER))
        summoned.appeared(wither.uniqueId, alice)
        val row = mobDies({ EntityDamageByEntityEvent(wither, it, DamageCause.ENTITY_EXPLOSION, stub(DamageSource::class.java), 1.0) })
        assertEquals(Cause.BLK_WITHER, row.cause)
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
        output.child("Passengers").putString("id", "minecraft:pig")
        return true
    }

    // Made without a constructor, so nothing of its own is set until a test sets it.
    private var on: NmsEntity? = null

    override fun getVehicle(): NmsEntity? = on

    fun seated(on: NmsEntity? = null, riders: List<NmsEntity> = emptyList()) = apply {
        this.on = on
        passengers = ImmutableList.copyOf(riders)
    }

    fun named(id: UUID) = apply { uuid = id }
}

private fun savedAs(id: UUID) = (unsafe.allocateInstance(Saved::class.java) as Saved).named(id).seated()

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

private class SavedFrame(type: NmsEntityType<out ItemFrame>, level: Level) : ItemFrame(type, level) {
    override fun registryAccess(): RegistryAccess = ServerRegistries.access

    override fun saveAsPassenger(output: ValueOutput, includeAll: Boolean, includeNonSaveable: Boolean, forceSerialization: Boolean): Boolean {
        output.putString("id", "minecraft:item_frame")
        return true
    }
}

private class Hung(server: CraftServer, handle: HangingEntity) : CraftHanging(server, handle) {
    lateinit var id: UUID
    lateinit var ground: World
    lateinit var saved: HangingEntity
    lateinit var face: BlockFace

    override fun getHandle(): HangingEntity = saved
    override fun getUniqueId(): UUID = id
    override fun getWorld(): World = ground
    override fun getLocation(): Location = Location(ground, 3.5, 64.0, 3.5)
    override fun getAttachedFace(): BlockFace = face
}

private fun <T : CraftEntity> shell(cls: Class<T>, type: EntityType): T = cls.cast(unsafe.allocateInstance(cls)).also {
    CraftEntity::class.java.getDeclaredField("entityType").apply { isAccessible = true }.set(it, type)
}

private fun hung(ground: World, face: BlockFace): Hung {
    val frame = shell(Hung::class.java, EntityType.ITEM_FRAME)
    frame.id = UUID.randomUUID()
    frame.ground = ground
    frame.face = face
    frame.saved = (unsafe.allocateInstance(SavedFrame::class.java) as SavedFrame).also { it.passengers = ImmutableList.of() }
    return frame
}

private fun dying(ground: World, fell: Float, damage: (Dying) -> EntityDamageEvent): Dying {
    val mob = shell(Dying::class.java, EntityType.COW)
    mob.id = UUID.randomUUID()
    mob.ground = ground
    mob.fell = fell
    mob.saved = (unsafe.allocateInstance(Saved::class.java) as Saved).seated()
    mob.damage = damage(mob)
    return mob
}

private fun fall(mob: Dying) = EntityDamageEvent(mob, DamageCause.FALL, stub(DamageSource::class.java), 20.0)

private fun starve(mob: Dying) = EntityDamageEvent(mob, DamageCause.STARVATION, stub(DamageSource::class.java), 1.0)
