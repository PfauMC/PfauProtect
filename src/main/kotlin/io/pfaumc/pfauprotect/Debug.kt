package io.pfaumc.pfauprotect

import java.util.concurrent.atomic.AtomicLong

/**
 * `/pp debug`: what the reads behind a lookup or a rollback asked, walked and kept, written into the server log
 * under `[debug]`, so a refusal or an odd answer can be traced from the log alone. Off by default and after a
 * restart, so one left on does not fill the disk; off, a line is never even put together.
 */
object Debug {
    @Volatile
    var on = false

    // Where the lines go: the plugin's logger on the server, a list in a test.
    @Volatile
    var sink: (String) -> Unit = {}

    private val ids = AtomicLong()
    private val request = ThreadLocal<Long>()

    /** Runs [task] under a request number of its own, which every line it logs carries: two reads at once interleave. */
    fun <T> request(task: () -> T): T {
        val outer = request.get()
        request.set(ids.incrementAndGet())
        try {
            return task()
        } finally {
            if (outer == null) request.remove() else request.set(outer)
        }
    }

    inline fun log(line: () -> String) {
        if (on) write(line())
    }

    @PublishedApi
    internal fun write(line: String) = sink("[debug${request.get()?.let { " #$it" } ?: ""}] $line")
}
