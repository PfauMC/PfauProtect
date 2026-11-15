package io.pfaumc.pfauprotect.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.rocksdb.Options
import org.rocksdb.RocksDB
import org.rocksdb.RocksDBException
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class WriterProgressTest {
    // 15.11 a full disk failed one write, and the writer took it as the end: nothing was written again
    // until a restart, long after the disk had room. A writer has to hold on to what it was handed, try
    // again, and drop only what does not fit.
    @Test
    fun `a writer that cannot write keeps its batch, drops what does not fit, and writes the rest once it can`(@TempDir dir: Path) {
        RocksDB.loadLibrary()
        Options().setCreateIfMissing(true).use { options ->
            RocksDB.open(options, dir.toString()).use { db ->
                val queue = LinkedBlockingQueue<List<Int>>()
                val progress = WriterProgress("test", "items", queue, capacity = 5) { it.size }
                val wrote = Collections.synchronizedList(ArrayList<Int>())
                val refusing = AtomicBoolean(true)
                val running = AtomicBoolean(true)
                val writer = thread {
                    progress.run(db, stopping = { !running.get() }) { batch ->
                        if (refusing.get()) throw RocksDBException("No space left on device")
                        batch.forEach { wrote += it }
                    }
                }
                try {
                    assertTrue(progress.offer(queue, listOf(1, 2), 2))
                    awaitTrue { progress.failure != null }
                    // At once, not after the drain timeout.
                    assertThrows(IllegalStateException::class.java) { progress.drain() }
                    assertTrue(progress.offer(queue, listOf(3, 4, 5), 3))
                    assertFalse(progress.offer(queue, listOf(6), 1))
                    assertEquals(1, progress.dropped.get())

                    refusing.set(false)
                    awaitTrue { progress.failure == null && progress.backlog == 0L }
                    progress.drain()
                    assertEquals(listOf(1, 2, 3, 4, 5), wrote)
                } finally {
                    running.set(false)
                    writer.join()
                }
            }
        }
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "not reached in 10 s" }
            Thread.sleep(5)
        }
    }
}
