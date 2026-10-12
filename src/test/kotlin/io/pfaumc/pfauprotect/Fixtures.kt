package io.pfaumc.pfauprotect

import io.pfaumc.pfauprotect.check.PlaneGap
import io.pfaumc.pfauprotect.check.PlaneReport
import io.pfaumc.pfauprotect.check.PlaneSync
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.Holder
import io.pfaumc.pfauprotect.model.Kind
import io.pfaumc.pfauprotect.model.LedgerEntry
import io.pfaumc.pfauprotect.storage.BlockLog
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.ItemKey
import io.pfaumc.pfauprotect.storage.MemoryRegistryStore
import io.pfaumc.pfauprotect.storage.Registries
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

internal fun ledgerEntry(
    holder: Holder,
    counterparty: Holder,
    timestamp: Long = T0,
    txId: Long = 7L,
    kind: Kind = Kind.TRANSFER,
    cause: Cause = Cause.PICKUP,
    confidence: Confidence = Confidence.FACT,
    itemFormId: Long = 12L,
    qty: Int = 1,
    damage: Int? = null,
    actor: UUID? = null,
) = LedgerEntry(
    holder = holder,
    timestamp = timestamp,
    txId = txId,
    kind = kind,
    cause = cause,
    confidence = confidence,
    counterparty = counterparty,
    itemFormId = itemFormId,
    qty = qty,
    damage = damage,
    actor = actor,
)

internal fun testCodec() = ItemFormCodec(Registries(MemoryRegistryStore()), ServerRegistries.access)

abstract class BothPlanes {
    protected open val world: UUID = io.pfaumc.pfauprotect.world

    protected lateinit var dir: Path
    protected lateinit var shared: RocksItemLog
    protected lateinit var logs: BlockLogs
    protected lateinit var log: BlockLog

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

    protected fun assertPlanesAgree(now: Long, limit: Int = 100): PlaneReport {
        val report = PlaneSync(shared, logs).pass(limit, now)
        assertEquals(emptyList<PlaneGap>(), report.gaps)
        return report
    }

    @AfterEach
    fun closeAll() {
        logs.close()
        shared.close()
    }
}
