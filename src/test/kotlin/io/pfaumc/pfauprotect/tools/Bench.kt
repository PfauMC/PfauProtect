package io.pfaumc.pfauprotect.tools

import io.pfaumc.pfauprotect.ServerRegistries
import io.pfaumc.pfauprotect.Settings
import io.pfaumc.pfauprotect.command.LookupTarget
import io.pfaumc.pfauprotect.command.Lookups
import io.pfaumc.pfauprotect.command.parseLookupQuery
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntityKind
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.senderInto
import io.pfaumc.pfauprotect.storage.BlockChange
import io.pfaumc.pfauprotect.storage.BlockLogs
import io.pfaumc.pfauprotect.storage.EntityChange
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.storage.RocksItemLog
import io.pfaumc.pfauprotect.stub
import org.bukkit.plugin.Plugin
import io.pfaumc.pfauprotect.storage.RegistryNamespace
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.UUID
import kotlin.math.abs

/**
 * Fills a base with a server's worth of history and times the lookups a moderator runs on it.
 *
 * `gen <dir> <events>` writes the events through the plugin's own writers, so the bytes on disk are the
 * ones a server writes. `run <dir> [samples]` asks each kind of lookup about random players and places
 * and prints the percentiles of how long the answer took.
 */
// The server's bootstrap takes System.out over for its own log, which drops what is printed to it.
private val out = PrintStream(FileOutputStream(FileDescriptor.out), true)

fun main(args: Array<String>) {
    // Read once, when the storage code is first touched, which has to come after this.
    if (args[0] == "gen") System.setProperty("pfauprotect.bulk", "true")
    ServerRegistries.access
    val dir = Path.of(args[1])
    when (args[0]) {
        "gen" -> generate(dir, args[2].toLong())
        "run" -> measure(dir, args.getOrNull(2)?.toInt() ?: 200, args.getOrNull(3))
        "probe" -> probe(dir)
        else -> error("unknown command ${args[0]}")
    }
}

private val WORLD = UUID(0, 1)
private const val PLAYERS = 5000
private const val HOMES = 3
private const val SEED = 42L
private const val SPAN_MILLIS = 365L * 24 * 3600 * 1000
// How far the writers may fall behind before the generator waits: an unbounded queue would hold the
// whole billion in memory.
private const val BACKLOG = 50_000L
private const val SAMPLED = 10_000

private val STATES = listOf("minecraft:stone", "minecraft:dirt", "minecraft:oak_planks", "minecraft:cobblestone", "minecraft:glass")
private val FORMS = (1..8).map { byteArrayOf(it.toByte()) }
private val PAYLOADS = (1..4).map { byteArrayOf(10, it.toByte(), 0, 0) }

private fun player(n: Int) = UUID(1, n.toLong())
private fun name(n: Int) = "p$n"

// Who did the next thing: a few players do most of it, as on a server, so a griefer with millions of
// rows is in the sample as well as a player with a handful.
private class Zipf(n: Int) {
    private val cdf = DoubleArray(n).also { c ->
        var sum = 0.0
        for (k in 0 until n) { sum += 1.0 / (k + 1); c[k] = sum }
        for (k in 0 until n) c[k] /= sum
    }

    fun next(random: Random): Int {
        val i = cdf.binarySearch(random.nextDouble())
        return if (i >= 0) i else -i - 1
    }
}

private class Homes(random: Random) {
    val at = Array(PLAYERS) { IntArray(HOMES * 2) { random.nextInt(20_000) - 10_000 } }
}

private fun generate(dir: Path, events: Long) {
    Files.createDirectories(dir)
    val random = Random(SEED)
    val zipf = Zipf(PLAYERS)
    val homes = Homes(random)
    val sample = ArrayList<String>()
    val start = System.currentTimeMillis() - SPAN_MILLIS
    val began = System.nanoTime()
    RocksItemLog(dir.resolve("items")).use { shared ->
        val logs = BlockLogs(dir.resolve("blocks"), shared)
        try {
            val log = logs.open(WORLD)
            // A player's number is fsynced the first time it is handed out; one batch for all of them
            // rather than five thousand syncs in the first minute.
            shared.registries.batched { repeat(PLAYERS) { shared.registries.id(RegistryNamespace.PLAYER, player(it)) } }
            for (i in 0 until events) {
                val p = zipf.next(random)
                val home = random.nextInt(HOMES) * 2
                val x = homes.at[p][home] + (random.nextGaussian() * 32).toInt()
                val z = homes.at[p][home + 1] + (random.nextGaussian() * 32).toInt()
                val y = 50 + abs(random.nextGaussian() * 20).toInt()
                // In doubles: a billion times a year in milliseconds is past a Long.
                val ts = start + (i.toDouble() / events * SPAN_MILLIS).toLong()
                val who = player(p)
                val roll = random.nextInt(100)
                when {
                    roll < 50 -> {
                        val state = STATES[random.nextInt(STATES.size)]
                        val placed = random.nextBoolean()
                        log.submit(
                            BlockChange(
                                x, y, z, if (placed) "minecraft:air" else state, if (placed) state else "minecraft:air",
                                if (placed) Cause.BLK_PLAYER_PLACE else Cause.BLK_PLAYER_BREAK, ts, actor = who,
                            )
                        )
                    }
                    roll < 65 -> log.submit(
                        BlockChange(x, y, z, "minecraft:water", "minecraft:air", if (roll < 58) Cause.BLK_LIQUID_FLOW else Cause.BLK_LEAF_DECAY, ts)
                    )
                    roll < 95 -> {
                        val chest = Container(WORLD, x, y, z, random.nextInt(27))
                        val slot = PlayerInv(who, random.nextInt(36))
                        val form = FORMS[random.nextInt(FORMS.size)]
                        val qty = 1 + random.nextInt(64)
                        shared.submit(
                            if (roll < 80) Transfer(Cause.CONTAINER_ADD, slot, chest, form, null, qty, ts)
                            else Transfer(Cause.CONTAINER_REMOVE, chest, slot, form, null, qty, ts)
                        )
                    }
                    else -> log.submit(
                        EntityChange(
                            x, y, z, EntityKind.REMOVED, Cause.ENTITY_KILLED, "minecraft:cow", UUID(random.nextLong(), random.nextLong()),
                            ts, actor = who, before = PAYLOADS[random.nextInt(PAYLOADS.size)],
                        )
                    )
                }
                // Reservoir: every event has the same chance of being a place the benchmark asks about.
                val line = "$p $x $y $z"
                if (sample.size < SAMPLED) sample += line
                else random.nextLong(i + 1).let { if (it < SAMPLED) sample[it.toInt()] = line }
                if (i % 4096 == 0L) {
                    while (log.backlog > BACKLOG || shared.backlog > BACKLOG) Thread.sleep(1)
                }
                if (i % 10_000_000 == 0L && i > 0) {
                    val seconds = (System.nanoTime() - began) / 1e9
                    out.println("%,d events, %.0f s, %,.0f/s".format(i, seconds, i / seconds))
                }
            }
            logs.drain()
            shared.drain()
            // A server's base has long since been compacted; measured while compaction still runs, the
            // lookups would be timed against files nobody reads in practice and threads busy rewriting them.
            while (true) {
                val debt = shared.compactionDebt() + log.compactionDebt()
                if (debt == 0L) break
                out.println("compacting, %,d bytes to go".format(debt))
                Thread.sleep(60_000)
            }
        } finally {
            logs.close()
        }
    }
    Files.write(dir.resolve("sample.txt"), sample)
    out.println("%,d events in %.0f s".format(events, (System.nanoTime() - began) / 1e9))
}

// `only` keeps the kinds whose name holds it, for profiling one of them.
private fun measure(dir: Path, samples: Int, only: String? = null) {
    val places = Files.readAllLines(dir.resolve("sample.txt")).map { line -> line.split(' ').map(String::toInt) }
    val random = Random(System.nanoTime())
    val zipf = Zipf(PLAYERS)
    RocksItemLog(dir.resolve("items")).use { shared ->
        val logs = BlockLogs(dir.resolve("blocks"), shared)
        try {
            logs.open(WORLD)
            val codec = ItemFormCodec(shared.registries, ServerRegistries.access)
            val lookups = Lookups(stub(Plugin::class.java), shared, logs, codec, { it.removePrefix("p").toIntOrNull()?.let(::player) }, { "p${it.leastSignificantBits}" })
            // Each kind of question as a moderator types it, and where they stand when they do.
            val kinds = listOf<Pair<String, () -> Pair<LookupTarget, String>>>(
                "position" to { places[random.nextInt(places.size)].let { (_, x, y, z) -> LookupTarget(WORLD, x, y, z, "") to "" } },
                "radius:10" to { places[random.nextInt(places.size)].let { (_, x, y, z) -> LookupTarget(WORLD, x, y, z, "") to "radius:10" } },
                "user: active" to { origin() to "user:${name(zipf.next(random))}" },
                "user: any" to { origin() to "user:${name(random.nextInt(PLAYERS))}" },
                "user: page 2-5" to { origin() to "user:${name(zipf.next(random))} page:${2 + random.nextInt(4)}" },
                "player:" to { origin() to "player:${name(zipf.next(random))}" },
            )
            out.println("%-16s %8s %8s %8s %8s %8s".format("lookup", "p50", "p95", "p99", "max", "lines"))
            for ((kind, ask) in kinds.filter { only == null || only in it.first }) {
                val millis = DoubleArray(samples)
                var lines = 0L
                repeat(samples + samples / 10) { n ->
                    val (target, words) = ask()
                    val said = ArrayList<String>()
                    val began = System.nanoTime()
                    // As a player's window asks it: the default page of the config and the moment pinned.
                    val query = parseLookupQuery(words).copy(rows = Settings.pageRows, asOf = System.currentTimeMillis())
                    lookups.report(senderInto(said), target, query)
                    val took = (System.nanoTime() - began) / 1e6
                    // The first tenth warms the caches up and is not counted.
                    if (n >= samples / 10) {
                        millis[n - samples / 10] = took
                        lines += said.size
                    }
                }
                millis.sort()
                fun at(q: Double) = millis[((samples - 1) * q).toInt()]
                out.println("%-16s %8.1f %8.1f %8.1f %8.1f %8d".format(kind, at(0.5), at(0.95), at(0.99), millis.last(), lines / samples))
            }
        } finally {
            logs.close()
        }
    }
}

// What one point read costs, the same reads twice: the second time everything they touch is in the cache.
private fun probe(dir: Path) {
    RocksItemLog(dir.resolve("items")).use { shared ->
        val entries = (0 until 200).flatMap { shared.holderPage(PlayerInv(player(it), 0), 0, Long.MAX_VALUE, reverse = true, limit = 10).entries }
        repeat(3) { round ->
            val began = System.nanoTime()
            for (entry in entries) shared.transactionEntries(entry, 64)
            out.println("round $round: ${entries.size} transactions, %.1f µs each".format((System.nanoTime() - began) / 1e3 / entries.size))
        }
    }
}

private fun origin() =LookupTarget(WORLD, 0, 64, 0, "")
