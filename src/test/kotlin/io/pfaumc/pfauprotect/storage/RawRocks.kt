package io.pfaumc.pfauprotect.storage

import org.rocksdb.ColumnFamilyDescriptor
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.ColumnFamilyOptions
import org.rocksdb.DBOptions
import org.rocksdb.Options
import org.rocksdb.RocksDB
import java.nio.file.Path

// The only way to a row the code under test would never write. Whoever owns the base has to have shut
// it first: RocksDB takes one writer per directory.
internal fun rawEdit(at: Path, body: (raw: RocksDB, family: (String) -> ColumnFamilyHandle) -> Unit) {
    RocksDB.loadLibrary()
    val path = at.toAbsolutePath().toString()
    val names = Options().use { RocksDB.listColumnFamilies(it, path) }
    val handles = ArrayList<ColumnFamilyHandle>()
    ColumnFamilyOptions().use { cfOptions ->
        DBOptions().use { options ->
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
