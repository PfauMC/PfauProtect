package io.pfaumc.pfauprotect

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class MetricsTest {
    // A plugin disabled and enabled again opens a second endpoint in the same registry: the first one's
    // readings have to be gone by then, or registering them again throws and the plugin does not come up.
    @Test
    fun `the endpoint serves the readings and the timings, and can be opened again once closed`(@TempDir dir: Path) {
        Files.write(dir.resolve("000001.sst"), ByteArray(1234))
        Metrics.lookups.labelValues("user").observe(0.05)
        repeat(2) {
            val port = ServerSocket(0).use { it.localPort }
            MetricsEndpoint("127.0.0.1", port, { mapOf("ledger" to MetricsEndpoint.Base(dir, 7, mapOf("entries" to 99))) }) { false }.use {
                val text = URI("http://127.0.0.1:$port/metrics").toURL().readText()
                assertTrue("pfauprotect_base_bytes{base=\"ledger\"} 1234.0" in text, text)
                assertTrue("pfauprotect_base_rows{base=\"ledger\",family=\"entries\"} 99.0" in text, text)
                assertTrue("pfauprotect_unwritten{base=\"ledger\"} 7.0" in text, text)
                assertTrue("pfauprotect_lookup_seconds_bucket{kind=\"user\",le=\"0.1\"}" in text, text)
            }
        }
    }
}
