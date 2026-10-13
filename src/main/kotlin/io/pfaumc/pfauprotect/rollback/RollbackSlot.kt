package io.pfaumc.pfauprotect.rollback

import io.pfaumc.pfauprotect.command.LookupQuery
import io.pfaumc.pfauprotect.command.LookupTarget
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// A chunk task that never runs — its world unloaded under it — would hold the one rollback slot for
// good. Past this a running rollback is taken as lost and the slot handed on.
internal const val STALE_MILLIS = 10 * 60_000L

internal class Asked(val target: LookupTarget, val query: LookupQuery, val at: Long)

internal sealed interface Taking {
    data object NothingPending : Taking
    data object Busy : Taking
    class Started(val asked: Asked, val at: Long) : Taking
}

/** The previews waiting for apply, and the one rollback that may run at a time. */
internal class RollbackSlot(private val clock: () -> Long = System::currentTimeMillis) {
    private val pending = ConcurrentHashMap<String, Asked>()
    // When the rollback running now started, or zero.
    private val running = AtomicLong()
    // Asked by /pp cancel while one runs: the chunks not yet begun are left as they are.
    private val stopping = AtomicBoolean()

    val stopRequested: Boolean get() = stopping.get()

    fun runningSince(): Long? = running.get().takeIf { it != 0L }

    fun preview(key: String, target: LookupTarget, query: LookupQuery) {
        pending[key] = Asked(target, query, clock())
    }

    fun take(key: String): Taking {
        val asked = pending.remove(key)?.takeIf { clock() - it.at <= PENDING_MILLIS } ?: return Taking.NothingPending
        // Two rollbacks over one place would each read the other's work as not done yet.
        val started = running.get()
        val now = clock()
        if (started != 0L && now - started < STALE_MILLIS || !running.compareAndSet(started, now)) {
            pending[key] = asked
            return Taking.Busy
        }
        // Cleared here, not when the chunks start: a /pp cancel while it still reads must hold.
        stopping.set(false)
        return Taking.Started(asked, now)
    }

    fun drop(key: String): Boolean = pending.remove(key) != null

    fun stop(): Boolean = running.get() != 0L && stopping.compareAndSet(false, true)

    // Only its own slot: a run taken as lost that finishes after all must not free the next one's.
    fun release(startedAt: Long) {
        running.compareAndSet(startedAt, 0)
    }
}
