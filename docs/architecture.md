# Architecture


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
rollback gave back), confiscations (what offline players owe a rollback) and by_chunk. Capture transactions are
serialized by one writer thread (`pfauprotect-ledger-writer`); `purgeBefore` and registry persistence write directly
(`db.write`, `writeSynced`) under the shared database lock. Logically, every change is a `Transfer` of `qty > 0`
from one `Holder` to another (`Model.kt`); `entries` stores it as two `LedgerEntry` postings, a negative one on the
source and a positive one on the target, and skips holders that are not addressable. Holders are:
player inv/equip/cursor/ender, menu slot (not addressable), container, entity slot, item entity, nested
(shulker/bundle contents), `VOID`, world block. A row also carries a `Kind` (TRANSFER/MUTATE/CLONE) and a
`Confidence` (FACT/INFERRED/NEARBY: a player who was only near when a mechanism fired).

**Block plane (`BlockStore.kt`, `BlockLogs`).** One RocksDB per world (`blocks/<uuid>`), opened and closed on
world load and unload, with column families rows, meta, by_actor (every row that names an actor, keyed by the
actor), entities and by_chunk. Block-state ids and block-entity payloads are interned in the shared ledger, and referenced
data is written first so a block row never points at nothing.

**Write path.**
- Block and entity capture (`BlockCaptureListener`, `EntityCapture`) write through `BlockLogs` directly. Mechanism
  and item listeners feed `TickCoalescer`, which merges changes within a tick, and `Uncovered`, which counts
  what could not be explained, by reason.
- Player slots are written only by the recompute pass. Events leave an `Intent` (`Intents.kt`), and the pass
  diffs the inventory and assigns causes.
- Listener registration order matters: `NestedCaptureListener` must be registered before `BlockMechanismListener`.

**Attribution (`Attribution.kt`, plus `SpawnOrigins`/`EntityOrigins`).** Attribution climbs a ladder: direct
source, then in-memory trackers, then the ledger. Anything that was not observed directly is `INFERRED`, except a
player who was only nearby, which is `NEARBY`. Rows are
never rewritten after the fact.

**Self-checks, scheduled from `PfauProtectPlugin`.**
- Sweep: per-transaction invariant.
- `PlaneSync`: item plane vs block plane, settled after `SETTLE_MILLIS`.
- `Reconciliation`: live inventories vs ledger.

`/pp verify` and `/pp reconcile` run them on demand.

**Commands.** `/pfauprotect` (alias `/pp`) is registered through Brigadier (`LifecycleEvents.COMMANDS`), with
subcommands `lookup|l`, `near|n`, `inspect|i`, `reconcile|r`, `verify|v [recent]`, `rollback|rb`, `apply`,
`cancel`, `chat`, `status`, `debug [on|off]` (`Debug.kt`: what the reads walked and kept, into the server log), `purge <age> [confirm]`, `tp <x y z> [world]` (what a line's ⌖ runs), `view
[chat|dialog]` and `help` (also the bare `/pp`). Permissions are declared in `build.gradle.kts` `bukkit {}`.
A page of `lookup`, `near` or `chat` goes through `showPage` (`Lookup.kt`): a Paper dialog with the arrows as
buttons for a player who reads in one (`lookup.dialog` in `config.yml`, overridden per player by `/pp view` and
kept in their PDC), chat lines otherwise. The console, the inspector and the short answers stay in chat.

**Settings, texts, chat, API.** `Settings.kt` reads `config.yml` (language, disabled worlds and causes, limits,
the mob death thresholds). Messages are written in English and go out through `CommandSender.say()`
(`Texts.kt`), which translates whole lines by regex when the language is `ru`; a new message needs a line
there, and `TextsCoverageTest` fails until it has one. A line made of parts (a lookup row, a rollback's counts,
help, chat) is drawn as Adventure components through `Ui.kt` and put together in the language already with `tr(en,
ru)`; the game's own names go out as translatable components, in the client's language. `Verbs.kt` holds the
words of every `Cause`, and the cause's code waits on the hover. `PPT_LANG=ru scripts/test-server.sh start` starts the
test server in Russian for a live check. `ChatLog` is a base of its own (`chat/`): chat, commands, joins and quits, and every hit of a player on another (`action:pvp` shows only those; no rollback reads them), no IP addresses.
`api/PfauProtectApi` is registered in the ServicesManager; `PfauProtectPreLogEvent` can veto a block-plane or
entity-plane row before it is written (item-plane transfers are not vetoed) and is raised only while someone listens.

**Rollback (`rollback/`).** `/pp rollback` takes the lookup's words and previews; `/pp apply` reads everything
again and runs it, a chunk per region task, eight at a time; `/pp cancel` stops the chunks not begun yet.
The previewing player is shown the blocks as they would stand (client-side only, `Ghosts.kt`: the nearest first, sent again when the client gets their chunk or clicks one) until apply, cancel or expiry.
`event:<token>` from a lookup line rolls back that one event: it reads a window around the event (default radius 8
blocks, ±1 second) and keeps the event's own rows. It writes compensating rows (`Cause.ROLLBACK`) in both planes and never
edits old ones. A rollback's own rows are only rolled back when `action:rollback` names them, which is also how a
rollback is undone. What it put back is then taken back from whoever carried it off (`Confiscation.kt`): from an
online player through an `Intent`, from an offline one at their next join, from a pile still lying where it lies.
`radius:global` with `user:` finds a player's positions through `by_actor` and their own item rows. Entities go
back to what their oldest row in the window says they were (brought back from NBT with the same UUID, taken away,
changed back, returned to where they stood), and a player killed by the one rolled back gets back what fell out of
them.

## `/pp lookup` and `/pp purge` semantics

- `/pp lookup` reads by position, or with `player:<name>` by a player's own holders (inventory, equipment,
  cursor, ender chest, crafting grid). `user:` is something else: a filter on the actor of positional rows;
  with `radius:global` it reads every position the player touched, through `by_actor`. `time:` takes a span
  (`2h-1h`), `page:` pages through the answer, `at:x,y,z` pins the place (the page buttons add it). Runs of
  the same thing within a minute fold into one `×N` line; `#all` shows each. `action:steal`, `amount:`,
  `rolledback:`, `#count`/`#sum` narrow it.
- In the list parameters (`user:`, `player:`, `action:`, `include:`, `exclude:`) `,` is either and `&` is all of
  them in one row, binding tighter: `u:A&B,C`. `filter:` in `/pp chat` takes only `&`; a comma is text there. The groups stay strings in
  `LookupQuery` and `RowFilter` splits them (`allOf`). A block row names its two states and one actor; an entity
  row names its actor, and the entity itself only within a group that holds the actor (`u:A&B` is what A did to
  B, `u:B` alone stays what B did); a chat line names the speaker or both ends of a hit. `action:` groups are
  checked per row against each action's causes and kinds; `causes`/`kinds` stay the union for everything else.
  An `exclude:` word is a player if one goes by it, an item, block or entity either way, and an action only when
  it names none of those (`THING_NAMES`): `e:tnt` must not drop every explosion from a rollback, `e:explosion`
  does. `steal` is refused there, being worked out per withdrawal. In `/pp chat` it takes players and `pvp`.
- `/pp purge` works per plane: the item ledger (`Storage.purgeBefore`) deletes every posting older than the cutoff and, in the same write, records what each holder held then as `Cause.PURGE_OPENING` openings, so balances hold; the block log (`BlockStore.purgeBefore`) deletes old rows except the newest of each position no later row covers, their index entries, and entity rows older than the cutoff.
