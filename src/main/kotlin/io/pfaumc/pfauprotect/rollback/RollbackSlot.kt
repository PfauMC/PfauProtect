package io.pfaumc.pfauprotect.rollback

import io.pfaumc.pfauprotect.command.LookupQuery
import io.pfaumc.pfauprotect.command.LookupTarget
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// A chunk task that never runs — its world unloaded under it — would hold the one rollback slot for
// good. Past this a running rollback is taken as lost and the slot handed on.
internal const val STALE_MILLIS = 10 * 60_000L

// [at] is when it was asked until it is [shown], then when it was shown.
internal class Asked(val target: LookupTarget, val query: LookupQuery, val at: Long, val shown: Boolean = false)

internal sealed interface Taking {
    data object NothingPending : Taking
    data object Reading : Taking
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

    fun preview(key: String, target: LookupTarget, query: LookupQuery): Asked {
        val asked = Asked(target, query, clock())
        pending[key] = asked
        return asked
    }

    /**
     * [asked] is read and shown now: its time for apply starts here, not when it was asked, as a long read
     * would use it up. Null when it was dropped or replaced while it read.
     */
    fun ready(key: String, asked: Asked): Long? {
        val now = clock()
        var marked = false
        pending.computeIfPresent(key) { _, current ->
            if (current !== asked) return@computeIfPresent current
            marked = true
            Asked(asked.target, asked.query, now, shown = true)
        }
        return now.takeIf { marked }
    }

    fun pendingAt(key: String): Long? = pending[key]?.takeIf { it.shown }?.at?.takeIf { clock() - it <= PENDING_MILLIS }

    fun take(key: String): Taking {
        // One still reading is not taken: its player has not seen what it would do.
        var taken: Asked? = null
        pending.computeIfPresent(key) { _, current -> if (current.shown) null.also { taken = current } else current }
        val asked = taken ?: return if (pending.containsKey(key)) Taking.Reading else Taking.NothingPending
        if (clock() - asked.at > PENDING_MILLIS) return Taking.NothingPending
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
