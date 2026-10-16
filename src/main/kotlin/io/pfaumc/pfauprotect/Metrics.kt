package io.pfaumc.pfauprotect

import io.prometheus.metrics.core.metrics.Counter
import io.prometheus.metrics.core.metrics.GaugeWithCallback
import io.prometheus.metrics.core.metrics.Histogram
import io.prometheus.metrics.exporter.httpserver.HTTPServer
import io.prometheus.metrics.model.registry.Collector
import io.prometheus.metrics.model.registry.PrometheusRegistry
import java.nio.file.Files
import java.nio.file.Path

/**
 * What Prometheus scrapes. The timings and counts are kept from the start, whether anybody scrapes them or
 * not, so the code that does the work can note it without being handed anything; the readings of the
 * bases and the endpoint itself come up with the plugin, see [MetricsEndpoint].
 */
object Metrics {
    val registry = PrometheusRegistry()

    // The default buckets, 5 ms to 10 s, which have 100 ms, the most a lookup should take, as an edge.
    private fun seconds(name: String, help: String, vararg labels: String): Histogram =
        Histogram.builder().name(name).help(help).labelNames(*labels).classicOnly().register(registry)

    val lookups = seconds("pfauprotect_lookup_seconds", "Time to read and draw one page of a lookup", "kind")
    val writes = seconds("pfauprotect_write_batch_seconds", "Time a writer took over one batch", "writer")
    val rollbacks = seconds("pfauprotect_rollback_seconds", "Time from a rollback's read to its report", "mode")
    val checks = seconds("pfauprotect_check_seconds", "Time of one self-check pass", "check")
    val recomputes = seconds("pfauprotect_recompute_seconds", "Time of one player's inventory recompute")

    val written: Counter = Counter.builder().name("pfauprotect_written").help("Transfers and block submissions written")
        .labelNames("writer").register(registry)

    // The capture's unexplained movements, which the daily log line resets and this does not.
    val inferred: Counter = Counter.builder().name("pfauprotect_inferred").help("Movements booked without being seen, by cause")
        .labelNames("cause").register(registry)
}

/** Bytes of every file under [dir], or 0 for none: what a base takes on disk, its log and its WAL included. */
fun bytesUnder(dir: Path): Long {
    if (!Files.exists(dir)) return 0
    return Files.walk(dir).use { paths -> paths.filter { Files.isRegularFile(it) }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0) }.sum() }
}

/**
 * `/metrics` on [address]:[port], with the readings taken at each scrape: how big each base is, how many
 * rows it holds, how far its writer is behind. [bases] answers them by the base's name.
 */
class MetricsEndpoint(
    address: String,
    port: Int,
    bases: () -> Map<String, Base>,
    rollbackRunning: () -> Boolean,
) : AutoCloseable {
    class Base(val dir: Path, val unwritten: Long, val rows: Map<String, Long>, val compactionDebt: Long = 0)

    private val readings: List<Collector> = listOf(
        GaugeWithCallback.builder().name("pfauprotect_base_bytes").help("Bytes a base takes on disk").labelNames("base")
            .callback { out -> for ((name, base) in bases()) out.call(bytesUnder(base.dir).toDouble(), name) }.build(),
        GaugeWithCallback.builder().name("pfauprotect_base_rows").help("Rows in a column family, as RocksDB estimates them")
            .labelNames("base", "family")
            .callback { out -> for ((name, base) in bases()) for ((family, n) in base.rows) out.call(n.toDouble(), name, family) }.build(),
        GaugeWithCallback.builder().name("pfauprotect_unwritten").help("What a base's writer has been handed and not written yet")
            .labelNames("base").callback { out -> for ((name, base) in bases()) out.call(base.unwritten.toDouble(), name) }.build(),
        GaugeWithCallback.builder().name("pfauprotect_compaction_pending_bytes").help("Bytes RocksDB still has to compact in a base")
            .labelNames("base").callback { out -> for ((name, base) in bases()) out.call(base.compactionDebt.toDouble(), name) }.build(),
        GaugeWithCallback.builder().name("pfauprotect_rollback_running").help("1 while a rollback is being applied")
            .callback { out -> out.call(if (rollbackRunning()) 1.0 else 0.0) }.build(),
    ).onEach { Metrics.registry.register(it) }

    private val server = try {
        HTTPServer.builder().hostname(address).port(port).registry(Metrics.registry).buildAndStart()
    } catch (failure: Exception) {
        readings.forEach(Metrics.registry::unregister)
        throw failure
    }

    override fun close() {
        server.close()
        readings.forEach(Metrics.registry::unregister)
    }
}
