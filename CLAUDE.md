# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

PfauProtect is a Canvas (Folia fork) plugin for Minecraft 26.3, Canvas build 956 (alpha channel). It keeps a
double-entry ledger of every item movement and a per-world log of block changes, so an admin can trace where items
went, spot items with no explained origin, and eventually roll back a culprit. Kotlin 2.4, JVM toolchain 25.

## Commands

```
./gradlew build                                                  # compile + tests + plugin jar
./gradlew test                                                   # all tests
./gradlew test --tests 'io.pfaumc.pfauprotect.storage.StorageTest'       # one class
./gradlew test --tests 'io.pfaumc.pfauprotect.storage.StorageTest.*name*'# one method (glob)
./gradlew runServer                                              # Canvas server with the plugin (not on Windows, see below)
scripts/test-server.sh start|stop|logs                           # the same server in a Linux container, for manual runs
```

- Nothing is shaded into the jar. Runtime dependencies go through `library(...)` (plugin-yml writes them into
  `plugin.yml` `libraries`). `kotlin.stdlib.default.dependency=false`, so a new dependency usually has to be
  declared twice: `library(...)` for the server and `testImplementation(...)` for tests.
- The Canvas build is pinned in two places: `canvasBuild` in `build.gradle.kts` and `CANVAS_BUILD` in
  `scripts/test-server.sh`.
- RocksDB is `compileOnly`. Its natives come in classifier jars, which the server gets from the literal strings in
  `bukkit { libraries }` and the tests from `testImplementation`. The server gets `osx` and `linux64`. The tests
  also get `win64`, for Windows dev machines. A platform the server has to run on goes into both lists.
  `runServer` on Windows therefore cannot load the plugin; `scripts/test-server.sh` runs it in Docker.
- The first test run is slow. `ServerRegistries` (src/test/kotlin) bootstraps vanilla registries and
  `GlobalConfiguration` through reflection and writes `config/` and `logs/` into the repo root (gitignored).
- Do not rebuild or run `gradle test` while a `runServer` server is running from this build: it swaps the jar. The
  Docker server runs a copy in `run/plugins`, taken at `start`.

## Architecture

Packages under `io.pfaumc.pfauprotect`; tests sit in the package of the code they test. `PfauProtectPlugin.onEnable`
(root package) wires everything into a `Running` object.

| Package | What |
| --- | --- |
| `model` | holders, transfers, `Cause`, `Kind`, `Confidence` |
| `storage` | both RocksDB stores, codecs, registries, item forms |
| `capture.item` | item movements: windows and the recompute pass, intents, entities, commands |
| `capture.block` | block changes, mechanisms, `TickCoalescer` |
| `capture.entity` | the entity plane's capture: entities removed (full NBT), created, changed or led away by a player, players killed |
| `attribution` | the culprit ladder, entity origins, redstone energy (phase 5.7) |
| `check` | plane sync and reconciliation |
| `command` | `/pp lookup` and inspect |
| `rollback` | `/pp rollback`: the per-position rule, the reading of both planes, the per-chunk application |

**Item plane (`Storage.kt`, `RocksItemLog`).** Single RocksDB at `ledger/`, with column families entries,
item_forms, registry, meta, nested_owners, tx, placed_forms, block_payloads, compensated (which postings a
rollback gave back) and confiscations (what offline players owe a rollback), all written by one writer thread
(`pfauprotect-ledger-writer`). Every row is a `Transfer` of `qty > 0` from one `Holder` to another (`Model.kt`):
player inv/equip/cursor/ender, menu slot (not addressable), container, entity slot, item entity, nested
(shulker/bundle contents), `VOID`, world block. A row also carries a `Kind` (TRANSFER/MUTATE/CLONE) and a
`Confidence` (FACT/INFERRED).

**Block plane (`BlockStore.kt`, `BlockLogs`).** One RocksDB per world (`blocks/<uuid>`), opened and closed on
world load and unload, with column families rows, meta and by_actor (every row that names an actor, keyed by the
actor). Block-state ids and block-entity payloads are interned in the shared ledger, and referenced
data is written first so a block row never points at nothing.

**Write path.**
- Capture listeners feed `TickCoalescer`, which merges changes within a tick, and `Uncovered`, which counts
  what could not be explained, by reason.
- Player slots are written only by the recompute pass. Events leave an `Intent` (`Intents.kt`), and the pass
  diffs the inventory and assigns causes.
- Listener registration order matters: `NestedCaptureListener` must be registered before `BlockMechanismListener`.

**Attribution (`Attribution.kt`, plus `SpawnOrigins`/`EntityOrigins`).** Attribution climbs a ladder: direct
source, then in-memory trackers, then the ledger. Anything that was not observed directly is `INFERRED`. Rows are
never rewritten after the fact.

**Self-checks, scheduled from `PfauProtectPlugin`.**
- Sweep: per-transaction invariant.
- `PlaneSync`: item plane vs block plane, settled after `SETTLE_MILLIS`.
- `Reconciliation`: live inventories vs ledger.

`/pp verify` and `/pp reconcile` run them on demand.

**Commands.** `/pfauprotect` (alias `/pp`) is registered through Brigadier (`LifecycleEvents.COMMANDS`), with
subcommands `lookup|l`, `near|n`, `inspect|i`, `reconcile|r`, `verify|v [recent]`, `rollback|rb`, `apply`,
`cancel`, `chat`, `status`, `purge <age> [confirm]`, `tp <x y z> [world]` (what a line's ⌖ runs) and `help` (also
the bare `/pp`). Permissions are declared in `build.gradle.kts` `bukkit {}`.

**Settings, texts, chat, API.** `Settings.kt` reads `config.yml` (language, disabled worlds and causes, limits,
the mob death thresholds). Messages are written in English and go out through `CommandSender.say()`
(`Texts.kt`), which translates whole lines by regex when the language is `ru`; a new message needs a line
there, and `TextsCoverageTest` fails until it has one. A line made of parts (a lookup row, a rollback's counts,
help, chat) is drawn as Adventure components through `Ui.kt` and put together in the language already with `tr(en,
ru)`; the game's own names go out as translatable components, in the client's language. `Verbs.kt` holds the
words of every `Cause`, and the cause's code waits on the hover. `PPT_LANG=ru scripts/test-server.sh start` starts the
test server in Russian for a live check. `ChatLog` is a base of its own (`chat/`): chat, commands, joins and quits, no IP addresses.
`api/PfauProtectApi` is registered in the ServicesManager; `PfauProtectPreLogEvent` can veto a row before it
is written and is raised only while someone listens.

**Rollback (`rollback/`).** `/pp rollback` takes the lookup's words and previews; `/pp apply` reads everything
again and runs it, a chunk per region task, eight at a time; `/pp cancel` stops the chunks not begun yet.
The previewing player is shown the blocks as they would stand (client-side only) until apply, cancel or expiry.
`event:<token>` from a lookup line rolls back that one event. It writes compensating rows (`Cause.ROLLBACK`) in both planes and never
edits old ones. A rollback's own rows are only rolled back when `action:rollback` names them, which is also how a
rollback is undone. What it put back is then taken back from whoever carried it off (`Confiscation.kt`): from an
online player through an `Intent`, from an offline one at their next join, from a pile still lying where it lies.
`radius:global` with `user:` finds a player's positions through `by_actor` and their own item rows. Entities go
back to what their oldest row in the window says they were (brought back from NBT with the same UUID, taken away,
changed back, returned to where they stood), and a player killed by the one rolled back gets back what fell out of
them.

## Invariants

- On-disk numbers never change and are never reused: `Cause` codes, `HolderType`, `Kind` and registry ids.
  Add new values; never renumber.
- Bump `SCHEMA_VERSION` (`Storage.kt`) or `BLOCK_SCHEMA_VERSION` (`BlockStore.kt`) when a key layout, the CF set
  or the meaning of stored numbers changes.
- Folia threading: touch world and player state only on the owning region thread (entity/region schedulers). Do
  RocksDB I/O off it, never on a region thread. One exception: `Attribution.journalRemoverAt` reads the
  newest block row at a position on the region thread, once per hanging entity that falls, because who
  emptied its wall has to be known as it falls and the tracker note is gone by then.
- `onDisable` closes the per-world databases before the ledger.

## Specs (`.planning/`)

- SPEC-v1 is the foundation and long-term goals (rollback, dupe detection by balance; the second not built yet).
- SPEC-v2 covers item entities and player inventory. SPEC-v3 covers the block plane, attribution and plane sync.
  SPEC-v4 covers crafting and stations. Later specs override earlier ones, and SPEC-v3 supersedes BLOCKS-notes.
  PLAN-v1-iteration-1 is outdated.
- SPEC-v5 is full coverage: every cause class the earlier phases deferred (0x30 rest, 0x50, 0x60, 0x70,
  0xF0, creative), what they missed, and the phase order 5.1–5.8. It overrides the "deferred" lists of
  SPEC-v2 and SPEC-v4. Projectiles are `EntitySlot(uuid, 0)`, not a new holder type. Phase 5.7 carries
  the player who started a mechanism through any redstone chain, and resolves who stands behind an
  entity on a switch; `Confidence.NEARBY` marks a player who was only nearby. Phase 5.8 records
  everything else: hand changes to blocks (a read-back of every touched block), buckets, dispensers,
  portals, block commands (wrapped Brigadier executors), mob pockets (`EntitySlot(uuid, 100 + i)`), the
  copper golem, offline edits and container reconciliation. SPEC-v5 §5 is what is still not recorded.
- SPEC-v4 §15 records how phase 4 was actually built and overrides §3–§13. Window slots are booked to
  their real owner: the crafting grid to the player (`EntitySlot`), a station to its block (`Container`).
  `MenuSlot` is left only for ownerless GUIs.
- SPEC-v6 records what a rollback needs beyond blocks and items: an entity plane (`entities` in each world
  base: entities removed with their full NBT, created by a player, changed or led away by one (a boat or a
  cart ridden off included), and the item entities that fell out of a break or a death), and the slots of
  a lectern and a campfire.
- SPEC-v7 is rollback: 7.1 an area by the lookup's filters, 7.2 taking back what was carried off, 7.3 the
  actor index and a player's rollback without a radius, 7.4–7.7 rolling back everything SPEC-v6 records.
  §14 records how each phase was built.
- TESTING-v6 is the live run for SPEC-v6, closed on 2026-10-03. TESTING-v6-RESULTS holds it, with the
  D51–D57 defects and their fixes.
- TESTING-v7 is the live plan for SPEC-v7, with a player; the console-only checks are already in SPEC-v7 §14.
  Since 2026-10-05 the live plans are played by bot clients kept outside this repository.
- PHASE2-FACTS records verified Canvas event behaviour, for example `EntityRemoveEvent` can fire twice and
  `PlayerRespawnEvent` never fires. Read it before writing a listener.
- TESTING-v5 is the live run for SPEC-v5, closed on 2026-10-03. TESTING-v5-RESULTS holds it: the D17–D50
  defects, the O2–O11 observations and the stop tally of every session. TESTING-v4-RESULTS holds the v4
  run, the D1–D16 defects and the v3 leftovers (R1).
- TESTING-v3 / TESTING-v3-RESULTS is the manual test plan and its 2026-08-18 run. Sections B–F must be run in
  survival. TESTING-v4 covers crafting and stations, plus section R with the unrun v3 items and re-checks
  of every fix made since v3.
- `/pp lookup` reads by position, or with `player:<name>` by a player's own holders (inventory, equipment,
  cursor, ender chest, crafting grid). `user:` is something else: a filter on the actor of positional rows;
  with `radius:global` it reads every position the player touched, through `by_actor`. `time:` takes a span
  (`2h-1h`), `page:` pages through the answer, `at:x,y,z` pins the place (the page buttons add it). Runs of
  the same thing within a minute fold into one `×N` line; `#all` shows each. `action:steal`, `amount:`,
  `rolledback:`, `#count`/`#sum` narrow it. `/pp purge` keeps the newest old row per place and writes `purge_opening` rows so balances hold.

## Conventions

- Commit messages in Russian with `feat:`/`fix:`/`test:`/`refactor:`/`chore:`/`docs:`. The body explains why.
- Code comments in English, explaining why rather than what. Match the existing density.
