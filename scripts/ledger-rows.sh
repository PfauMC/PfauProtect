#!/usr/bin/env sh
# The ledger of the Docker test server, read offline (src/test/kotlin/.../tools/LedgerRows.kt):
#
#   scripts/ledger-rows.sh since <millis> [CAUSE...]
#   scripts/ledger-rows.sh holder item:<uuid>|slot:<uuid>:<n>|... [<from millis>]
#
# The copy is taken inside the container: the host sees a running RocksDB's files late.
set -eu
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1
docker exec pfauprotect-test sh -c 'rm -rf /server/snap && cp -r /server/plugins/PfauProtect/ledger /server/snap'
rm -rf build/ledger-snap
cp -r run/snap build/ledger-snap
rm -f build/ledger-snap/LOCK
./gradlew -q ledgerRows --args="build/ledger-snap $*"
