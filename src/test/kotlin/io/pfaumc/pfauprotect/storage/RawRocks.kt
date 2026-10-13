package io.pfaumc.pfauprotect.storage

import org.rocksdb.ColumnFamilyDescriptor
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.ColumnFamilyOptions
import org.rocksdb.DBOptions
import org.rocksdb.Options
import org.rocksdb.RocksDB
import java.io.RandomAccessFile
import java.nio.file.Path

// The only way to a row the code under test would never write. Whoever owns the base has to have shut
// it first: RocksDB takes one writer per directory. `families` opens (creating if missing) a base with
// exactly those families, as an older build would have left it; by default the ones already on disk.
internal fun rawEdit(
    at: Path,
    families: List<String>? = null,
    body: (raw: RocksDB, family: (String) -> ColumnFamilyHandle) -> Unit,
) {
    RocksDB.loadLibrary()
    val path = at.toAbsolutePath().toString()
    val names = families?.map { it.toByteArray() } ?: Options().use { RocksDB.listColumnFamilies(it, path) }
    val handles = ArrayList<ColumnFamilyHandle>()
    ColumnFamilyOptions().use { cfOptions ->
        DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true).use { options ->
            RocksDB.open(options, path, names.map { ColumnFamilyDescriptor(it, cfOptions) }, handles).use { raw ->
                try {
                    body(raw) { name -> handles[names.indexOfFirst { it.contentEquals(name.toByteArray()) }] }
                } finally {
                    handles.forEach { it.close() }
                }
            }
        }
    }
}

internal fun rawPut(at: Path, family: String, key: ByteArray, value: ByteArray) =
    rawEdit(at) { raw, families -> raw.put(families(family), key, value) }

internal fun familiesOf(at: Path): List<String> =
    Options().use { RocksDB.listColumnFamilies(it, at.toAbsolutePath().toString()).map { name -> String(name) } }

// Overwrites the start of every table file of one family, which is where its first data block lies: the
// footer, the index and the filter at the end still read, so the base opens and only a read of the rows
// meets the damage. The base has to be closed.
internal fun damageTables(at: Path, family: String) {
    val files = ArrayList<Path>()
    rawEdit(at) { raw, _ ->
        raw.liveFilesMetaData.filter { String(it.columnFamilyName()) == family }.mapTo(files) { Path.of(it.path(), it.fileName()) }
    }
    check(files.isNotEmpty()) { "family $family has no table file to damage" }
    for (file in files) {
        RandomAccessFile(file.toFile(), "rw").use { raw ->
            raw.seek(16)
            raw.write(ByteArray(32) { 0x5A })
        }
    }
}
