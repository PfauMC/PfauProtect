# Investigating with PfauProtect

PfauProtect records every block change, item movement, entity change, chat line, command and hit between
players on this server. Rows are never rewritten. A rollback adds rows that undo older ones, and only a
moderator in the game applies it.

## What a row says

- `actor` did it, `cause` says how (its code, such as `blk_player_break`, `container_remove`, `blk_creeper`,
  `entity_killed`), and `action` says which way: added, removed or changed.
- `confidence`:
  - `fact`: seen directly.
  - `inferred`: worked out, for example who lit the TNT or whose dog bit. Marked `?` in the game.
  - `nearby`: the actor only stood near when a mechanism fired. This is never proof on its own.
  - `aggro`: a creeper went off at that player, who did not light it. Marked `*` in the game. A creeper
    hole marked aggro is not that player's griefing.
- `repeats` > 1: the row stands for that many rows of the same thing within a minute. Pass `all` to see each.
- `rolled_back`: a rollback has already put the row back.
- `complete` false: the read stopped before it saw everything, so an empty or short answer clears nobody.
  Narrow the radius or the time and ask again.

## Limits

- The radius is at most `max_radius` blocks (see `server_info`), and a page holds at most 200 rows.
- A preview waits `preview_minutes` (5) for the moderator's `[apply]`. Each moderator has one preview; a new
  one replaces it.
- A rollback is refused past `max_rollback_positions`; cut it into smaller squares.

## Theft from a container

1. `container_history` at the chest, with a time that covers it. Read who put what in and who took it out.
2. Run it again with `steal: true`. That keeps only the withdrawals by players other than whoever placed the
   chest. A chest whose placement is older than the read says nothing either way.
3. For each taker, `actor_actions` with `actions: ["+container", "drop"]` shows where the loot went.

## A griefing chain

1. `lookup` around the damage (`radius` 10–30, `time` 1d) with `actions: ["-block", "explosion", "fire"]`.
   Note the actors that are `fact` or `inferred`. Leave out `nearby` and `aggro` ones unless other rows back
   them up.
2. For each participant, `actor_actions` over the same window shows the other places they hit. Repeat for
   anyone new who turns up there.
3. Group what you found into rollbacks:
   - `users` + `time` (no radius) for everything a griefer did;
   - `radius` + `time` around one place, for damage several players did together.
   Leave out what they did to their own builds by narrowing with `actions`.

## A conflict between two players

1. `chat_pvp_log` with `users: ["A&B"]`, `all: true` and a window around it. The oldest `hit` shows who
   struck first. `death` lines show kills; `totem` lines show deaths a totem prevented.
2. Then `chat_pvp_log` with `users: ["A", "B"]` and `kinds: ["chat", "command"]` shows what each said and ran
   around the fight.
3. `lookup` around where they fought, if blocks or items were part of it (a trap, lava, a stolen kit).

## Preparing a rollback

1. Tell the moderator the place: `/pp tp <x> <y> <z> <world>`. Wait until they say they are there.
2. `start_preview` with the filters you settled on. Read the counts back to them: blocks to change,
   conflicts (blocks somebody else changed later, which stay as they are), slots, entities.
3. If it says `running`, ask `preview_status` until it is ready.
4. The moderator checks the drawn blocks and clicks `[apply]` or `[cancel]` themselves. You cannot apply
   a rollback.
