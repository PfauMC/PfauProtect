package io.pfaumc.pfauprotect.importer

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

/**
 * One CoreProtect SQLite file, read a page at a time in rowid order. A copy is opened immutable: SQLite then takes
 * no lock and wants no -wal or -shm beside it. The live file of a running CoreProtect is opened read-only and asked
 * in short statements, which in WAL mode never hold its writer up.
 *
 * A copy taken off a running server has pages that do not read («database disk image is malformed»). A page read
 * stops at the first of them and keeps what it read before; [skipFrom] finds where the table reads again.
 */
internal class CoreProtectSource(file: Path, copy: Boolean) : AutoCloseable {
    private val connection: Connection = DriverManager.getConnection(
        "jdbc:sqlite:file://${uriPath(file)}?mode=ro" + if (copy) "&immutable=1" else "",
    )

    class Page<T>(val rows: List<T>, val broken: Boolean)

    /** Up to [limit] rows after [after], in rowid order; `broken` when a damaged page cut it short. */
    fun <T> page(table: String, columns: String, after: Long, limit: Int, read: (ResultSet) -> T): Page<T> {
        val rows = ArrayList<T>()
        try {
            prepare("SELECT rowid, $columns FROM $table WHERE rowid > ? ORDER BY rowid LIMIT ?").use { statement ->
                statement.setLong(1, after)
                statement.setInt(2, limit)
                statement.executeQuery().use { result -> while (result.next()) rows += read(result) }
            }
        } catch (failure: SQLException) {
            if (!failure.corrupt()) throw failure
            return Page(rows, broken = true)
        }
        return Page(rows, broken = false)
    }

    /** The first row a point query finds, or null for none and for one behind a damaged page. */
    fun <T> one(sql: String, args: List<Any>, read: (ResultSet) -> T): T? = try {
        prepare(sql).use { statement ->
            args.forEachIndexed { i, arg -> statement.setObject(i + 1, arg) }
            statement.executeQuery().use { result -> if (result.next()) read(result) else null }
        }
    } catch (failure: SQLException) {
        if (!failure.corrupt()) throw failure
        null
    }

    /** Everything a small table holds, keyed by its id column; a map table is read whole. */
    fun <T> all(table: String, columns: String, read: (ResultSet) -> Pair<Int, T>): Map<Int, T> =
        prepare("SELECT $columns FROM $table").use { statement ->
            statement.executeQuery().use { result ->
                buildMap { while (result.next()) read(result).let { put(it.first, it.second) } }
            }
        }

    /**
     * How far past [after] a damaged stretch of [table] goes: the new `after`, the last of the positions right past
     * [after] that were each tried and do not read, or null when the walk should stop there. Every position up to the
     * answer is tried, so no readable row is ever stepped over. One call tries at most [LINEAR_PROBES] of them; when
     * all of those are damaged, a doubling look further on, up to [MAX_STEP], decides whether the next call goes on:
     * it does when the look finds a row that reads, and when it finds the table's end, which bounds the walk by the
     * table's own rowids. When every look up to [MAX_STEP] is damaged (the end of a copy taken during writes) the
     * answer is null, and the rest of the table waits for a better copy.
     */
    fun skipFrom(table: String, after: Long): Long? {
        var broken = after
        while (broken - after < LINEAR_PROBES) {
            when (probe(table, broken + 1)) {
                null -> return null
                BROKEN -> broken++
                else -> return broken
            }
        }
        var step = 1L
        while (step <= MAX_STEP) {
            when (probe(table, broken + step)) {
                null -> return broken
                BROKEN -> step *= 2
                else -> return broken
            }
        }
        return null
    }

    // The first rowid at or past `from`: null when there is none, BROKEN when the way there does not read.
    private fun probe(table: String, from: Long): Long? = try {
        prepare("SELECT rowid FROM $table WHERE rowid >= ? ORDER BY rowid LIMIT 1").use { statement ->
            statement.setLong(1, from)
            statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
        }
    } catch (failure: SQLException) {
        if (!failure.corrupt()) throw failure
        BROKEN
    }

    private fun prepare(sql: String): PreparedStatement = connection.prepareStatement(sql)

    override fun close() = connection.close()

    private companion object {
        const val BROKEN = Long.MIN_VALUE
        const val MAX_STEP = 1L shl 40
        const val LINEAR_PROBES = 4096L

        // SQLITE_CORRUPT and its extended codes; any other failure is not a page to step over.
        fun SQLException.corrupt(): Boolean =
            errorCode and 0xFF == 11 || message?.contains("malformed") == true

        // The platform's own file URI, percent-encoded: SQLite reads `file:///D:/x` and `file:///data/x` alike.
        fun uriPath(file: Path): String = file.toAbsolutePath().toUri().rawPath
    }
}
