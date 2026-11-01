# CLAUDE.md

PfauProtect: Canvas (Folia fork) plugin, Minecraft 26.3, Canvas build 956. Item ledger + per-world block log + rollback. Kotlin 2.4, JVM 25.

## Commands

```
./gradlew build                                                    # compile + tests + plugin jar
./gradlew test                                                     # all tests
./gradlew test --tests 'io.pfaumc.pfauprotect.storage.StorageTest' # one class
./gradlew test --tests 'io.pfaumc.pfauprotect.storage.StorageTest.*name*' # one method
./gradlew runServer                                                # Canvas server (not on Windows)
scripts/test-server.sh start|stop|logs                             # same server in a Linux container
PPT_LANG=ru scripts/test-server.sh start                           # Russian live check
```

- Nothing is shaded. Runtime deps go through `library(...)`; `kotlin.stdlib.default.dependency=false`, so declare a new dependency twice: `library(...)` and `testImplementation(...)`.
- Canvas build is pinned in `canvasBuild` (`build.gradle.kts`) and `CANVAS_BUILD` (`scripts/test-server.sh`): change both.
- RocksDB is `compileOnly`; native classifier jars: server gets `osx`, `linux64` (literals in `bukkit { libraries }`), tests also get `win64` (`testImplementation`). A platform the server must run on goes into both lists. `runServer` on Windows cannot load the plugin: use `scripts/test-server.sh`.
- First test run is slow (`ServerRegistries` bootstraps registries, writes gitignored `config/`, `logs/` to repo root).
- Never rebuild or run `gradle test` while a `runServer` server runs from this build (jar swap). The Docker server uses a copy in `run/plugins` made at `start`.

## Rules

- On-disk numbers (`Cause` codes, `HolderType`, `Kind`, registry ids) never change or get reused: add new values, never renumber.
- Bump `SCHEMA_VERSION` (`Storage.kt`) or `BLOCK_SCHEMA_VERSION` (`BlockStore.kt`) when a key layout, CF set or meaning of stored numbers changes.
- Folia: touch world/player state only on the owning region thread; RocksDB I/O off it, never on a region thread. Only exceptions: `Attribution.journalRemoverAt`, `journalPlacerAt` (one newest block-row read per event).
- `onDisable` closes per-world databases before the ledger.
- Register `NestedCaptureListener` before `BlockMechanismListener`.
- Rows are never rewritten; rollback only adds compensating rows (`Cause.ROLLBACK`).
- A new user message needs a line in `Texts.kt` (`TextsCoverageTest` fails otherwise). Messages are written in English.
- Tests live in the package of the code they test.

## Docs

- Working on architecture (packages, storage, capture, attribution, commands, rollback, settings) or `/pp lookup` / `/pp purge` semantics – read `docs/architecture.md` first.
- Working on specs, phases or test plans – read `.planning/README.md` first.
- Before writing an event listener – read `.planning/PHASE2-FACTS.md` (verified Canvas event behaviour).

## Conventions

- Commit messages in Russian with `feat:`/`fix:`/`test:`/`refactor:`/`chore:`/`docs:`; body explains why.
- Code comments in English, explain why not what; match existing density.
