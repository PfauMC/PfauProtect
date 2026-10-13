package io.pfaumc.pfauprotect

import io.pfaumc.pfauprotect.capture.block.SpawnOrigins
import io.pfaumc.pfauprotect.capture.block.TickCoalescer
import io.pfaumc.pfauprotect.check.PlaneGap
import io.pfaumc.pfauprotect.check.PlaneReport
import io.pfaumc.pfauprotect.check.PlaneSync
import io.pfaumc.pfauprotect.check.SETTLE_MILLIS
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.model.LedgerEntry
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.model.Void
import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.storage.BlockLog
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.ItemKey
import io.pfaumc.pfauprotect.storage.MemoryRegistryStore
import io.pfaumc.pfauprotect.storage.Registries
import io.pfaumc.pfauprotect.storage.RegistryNamespace
import io.pfaumc.pfauprotect.storage.RocksItemLog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

internal const val T0 = 1_700_000_000_000L

internal val world: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")
internal val alice: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
internal val bob: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000b0")

internal fun key(name: String, damage: Int? = null) = ItemKey(name.toByteArray(), damage)

internal fun ledgerEntry(holder: Holder, counterparty: Holder, actor: UUID? = null) = LedgerEntry(
    holder = holder, timestamp = T0, txId = 7L, kind = Kind.TRANSFER, cause = Cause.PICKUP,
    confidence = Confidence.FACT, counterparty = counterparty, itemFormId = 12L, qty = 1, damage = null, actor = actor,
)

internal fun testCodec() = ItemFormCodec(Registries(MemoryRegistryStore()), ServerRegistries.access)

// The coalescer only hands a row on at the end of its tick, so `rows` flushes it first.
internal class Spawns {
    private val written = ArrayList<Transfer>()
    val coalescer = TickCoalescer(written::add)
    var clock = 0L
    val origins = SpawnOrigins(coalescer) { clock }

    fun rows(): List<Transfer> {
        coalescer.flush()
        return written
    }
}

abstract class OneLedger {
    protected lateinit var dir: Path
    protected lateinit var log: RocksItemLog

    @BeforeEach
    fun openLedger(@TempDir tempDir: Path) {
        dir = tempDir
        log = RocksItemLog(dir)
    }

    // Reopening flushes the memtable, so what was written before it is read back from disk.
    protected fun reopen(whileClosed: () -> Unit = {}) {
        log.close()
        whileClosed()
        log = RocksItemLog(dir)
    }

    @AfterEach
    fun closeLedger() {
        log.close()
    }
}

abstract class BothPlanes {
    protected open val world: UUID = io.pfaumc.pfauprotect.world

    protected lateinit var dir: Path
    protected lateinit var shared: RocksItemLog
    protected lateinit var logs: BlockLogs
    protected lateinit var log: BlockLog

    protected val now = System.currentTimeMillis()
    protected val longAgo = now - 10 * SETTLE_MILLIS

    @BeforeEach
    fun open(@TempDir tempDir: Path) {
        dir = tempDir
        openPlanes()
    }

    protected open fun openPlanes() {
        shared = RocksItemLog(dir.resolve("items"))
        logs = BlockLogs(dir.resolve("blocks"), shared)
        log = logs.open(world)
    }

    protected fun drainBoth() {
        log.drain()
        shared.drain()
    }

    protected fun heldAt(
        x: Int,
        y: Int,
        z: Int,
        form: ByteArray,
        ts: Long = longAgo,
        confidence: Confidence = Confidence.FACT,
        at: UUID = world,
    ) {
        shared.submit(Transfer(Cause.BLOCK_PLACE, PlayerInv(alice, 0), WorldBlock(at, x, y, z), form, null, 1, ts, confidence = confidence))
    }

    protected fun releasedAt(
        x: Int,
        y: Int,
        z: Int,
        form: ByteArray,
        cause: Cause = Cause.BLOCK_DROP,
        actor: UUID? = alice,
        ts: Long = longAgo,
        confidence: Confidence = Confidence.FACT,
    ) {
        shared.submit(Transfer(cause, WorldBlock(world, x, y, z), Void, form, null, 1, ts, confidence = confidence, actor = actor))
    }

    protected fun stateOf(blockData: String): Int? =
        shared.registries.lookupKey(RegistryNamespace.BLOCK_STATE, blockData)

    protected fun assertPlanesAgree(now: Long): PlaneReport {
        val report = PlaneSync(shared, logs).pass(100, now)
        assertEquals(emptyList<PlaneGap>(), report.gaps)
        return report
    }

    @AfterEach
    fun closeAll() {
        logs.close()
        shared.close()
    }
}
