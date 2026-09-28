# Verified server behaviour (read before writing any listener)

Every line below was read out of the real sources under `~/src/github.com/pfaumc/Canvas/`
(`paper-api/`, `paper-server/`, `canvas-api/`, `canvas-server/src/minecraft/java/`).
Do not contradict this file from memory. If something here looks wrong, re-read the source and say so.

## Birth of an item entity

- `ItemSpawnEvent` is a genuine single funnel: `callItemSpawnEvent` ← `doEntityAddEventCalling`
  ← `ServerLevel.addEntity`, and every public add path funnels there.
- Known bypasses (no event): worldgen, cross-world teleport (`spawnReason == null`), empty stack,
  and anything still inside an open `captureDrops` window.
- **Order for a block break:** `BlockBreakEvent` → (block removed) → `BlockDropItemEvent`
  → one `ItemSpawnEvent` per surviving drop. Guaranteed by the `captureDrops` diversion.
  The `ItemEntity` objects already exist with their final UUIDs at `BlockDropItemEvent`.
- **Order for a Q-drop:** entity constructed → `PlayerDropItemEvent` → `addFreshEntity`
  → `ItemSpawnEvent`. `event.getItemDrop().getUniqueId()` is final at the drop event.
  A cancelled drop never reaches `addFreshEntity`, so no spawn event follows.
- **`/give` ghost item cannot be recognised at `ItemSpawnEvent` time.** `makeFakeItem()`
  (`pickupDelay = 32767`, `age = despawnRate - 1`) runs *after* the event returns. The ghost dies
  one tick later with `DESPAWN`, so a birth row and a death row cancel each other out.
- `Item#getThrower()` is a nullable UUID and is set **only** for Q-drops and mob throws.
  It is NOT set for death drops, block drops, or container spills. Useless as a general origin.

## Death of an item entity

- `EntityRemoveEvent.Cause` constants: `DEATH, DESPAWN, DROP, ENTER_BLOCK, EXPLODE, HIT, MERGE,
  OUT_OF_WORLD, PICKUP, PLAYER_QUIT, PLUGIN, TRANSFORMATION, UNLOAD, DISCARD`.
- Reachable for an item: `DESPAWN, DEATH, OUT_OF_WORLD, MERGE, PICKUP, PLUGIN, UNLOAD, DISCARD`,
  and `PLAYER_QUIT` only when the item rides a player's vehicle.
  **Not** reachable for an item: `EXPLODE` (an exploded item dies with `DEATH`), `ENTER_BLOCK`,
  `HIT`, `TRANSFORMATION`, `DROP`.
- `callEntityRemoveEvent` returns silently when the cause is null: dimension change, worldgen,
  cancelled spawn, and an empty item stack found at chunk load.
- **The event can fire twice for the same entity** — it is called before the already-removed check.
  On a duplicate the entity is already removed, so `event.getEntity().isDead()` is `true`;
  on the first, genuine removal it is `false`. Guard on that.
- `UNLOAD` and `PLAYER_QUIT` are not deaths: the entity is persisted and comes back.
- `getLastDamageCause()` is populated at `DEATH` (same call stack as the damage event) but is
  stale for `OUT_OF_WORLD` (no damage happens there) and may be left over from survived damage on
  a `MERGE`/`PICKUP`/`DESPAWN` removal. Read it only when the cause is `DEATH`.
  Mapping: lava → `LAVA`; fire block → `FIRE`; burning ticks → `FIRE_TICK`; cactus → `CONTACT`;
  explosion → `BLOCK_EXPLOSION` / `ENTITY_EXPLOSION`; a sourceless explosion falls through to `CUSTOM`.
- `age == -32768` means the item never despawns. Despawn rate is per item type and configurable.

## Merge and pickup

- `callItemMergeEvent(fromItem, toItem)` → `getEntity()` is the **donor** that shrinks and is
  discarded with `MERGE`; `getTarget()` is the **survivor**. The smaller stack is always the donor,
  so which entity survives can flip between ticks. Neither `thrower` nor `target` is carried over.
- Three events fire for a player pickup; **only `EntityPickupItemEvent`** should be listened to.
  It also covers mobs.
- **Player path:** the ground stack is trimmed to `canHold` *before* the event, so
  `event.getItem().getItemStack().getAmount()` is the amount actually taken and `getRemaining()`
  is what stays behind. The Bukkit stack is a live mirror — read it inside the handler.
- **Mob path:** the stack is NOT trimmed. Moved amount is `amount - remaining`.
  `Fox`/`PiglinAi` take exactly one; `Mob`/`Panda`/`Dolphin`/`Raider` hardcode `remaining = 0`.
- A partial pickup never discards the entity, so `EntityRemoveEvent(PICKUP)` is not a pickup signal.
- The destination equipment slot is not on the event. `LivingEntity.getEquipmentSlotForItem` is the
  NMS helper and never returns null, but `Mob.equipItemIfPossible` may downgrade to `MAINHAND`
  before firing, so a recomputed slot can disagree.
- A hopper vacuuming an item fires `InventoryPickupItemEvent` only, never `EntityPickupItemEvent`.

## Block place and break

- `BlockBreakEvent` fires for every player dig in survival and creative, and the block is still
  present at `MONITOR` — `getBlock().getType()` and `getState()` read the block being destroyed.
  It does not fire for explosions, pistons, fluid, fire, or entity griefing.
- A bed/door/double plant produces ONE `BlockBreakEvent` for the clicked half; both halves' drops
  arrive in the single `BlockDropItemEvent`.
- `BlockDropItemEvent` fires even when the drop list is **empty** (every creative break, glass
  broken by hand). It is skipped entirely when a plugin set `BlockBreakEvent#setDropItems(false)`.
- `BlockDropItemEvent` also fires with **no preceding `BlockBreakEvent`** when a player brushes
  suspicious sand or gravel (`BrushableBlockEntity.dropContent`), and the block is still there.
- `event.getBlockState()` is the pre-break snapshot; `event.getBlock().getType()` reads AIR.
- `BlockMultiPlaceEvent extends BlockPlaceEvent` and declares no `HandlerList` of its own, so a
  single `@EventHandler(BlockPlaceEvent)` receives it exactly once.
- `BlockPlaceEvent.getItemInHand()` reads the stack **before** consumption (Paper rolls the count
  back around the event) but it is a **live mirror**: read or clone it inside the handler.

## Player inventory events

- `InventoryAction` constants: `NOTHING, PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE,
  PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR, DROP_ALL_CURSOR, DROP_ONE_CURSOR,
  DROP_ALL_SLOT, DROP_ONE_SLOT, MOVE_TO_OTHER_INVENTORY, HOTBAR_MOVE_AND_READD (unreachable),
  HOTBAR_SWAP, CLONE_STACK, COLLECT_TO_CURSOR, UNKNOWN, PICKUP_FROM_BUNDLE, PICKUP_ALL_INTO_BUNDLE,
  PICKUP_SOME_INTO_BUNDLE, PLACE_FROM_BUNDLE, PLACE_ALL_INTO_BUNDLE, PLACE_SOME_INTO_BUNDLE`.
- A drag is `InventoryDragEvent` and produces no `InventoryClickEvent` (except a drag that ends on
  a single slot, which the server rewrites into a plain pickup).
- Armour equipping is invisible in `getAction()`: use `getSlotType() == SlotType.ARMOR`, and only
  the source slot reports it. Right-click-to-equip fires no inventory event at all.
- `PlayerSwapHandItemsEvent` getters are **post-state**: `getMainHandItem()` is what will be in the
  main hand afterwards. The swap is applied after the event.
- `PlayerDropItemEvent`: the inventory is **already** decremented when it fires.
- `PlayerDeathEvent`: the inventory is still **fully populated** at event time. The drop entities
  are created after all listeners return, with `callEvent = false`, so they fire **no**
  `PlayerDropItemEvent`. The cursor stack is not in `getDrops()` and is dropped separately through
  a normal `PlayerDropItemEvent`. `keepInventory` leaves only loot-table drops in `getDrops()`.
- Vanishing-cursed items never appear in `getDrops()` and produce no event at all; they are wiped
  in the post-event clearing block. The only way to name them is the pre-death snapshot.
- **`org.bukkit.event.player.PlayerRespawnEvent` does NOT fire on this fork for death respawns.**
  `PlayerList.respawn` throws under region threading. Listen to
  `io.canvasmc.canvas.event.PlayerPostRespawnAsyncEvent` instead — it extends
  `io.papermc.paper.event.player.AbstractRespawnEvent`, a sibling of `PlayerRespawnEvent`, and is
  guaranteed to run in the player's own region. The pre-respawn `PlayerRespawnAsyncEvent` runs in
  an unknown region state and must not touch player state.
- `EntityResurrectEvent`: the totem is only ever in a hand, `getHand()` gives which one (nullable),
  and the shrink happens **after** the event.
- `PlayerItemConsumeEvent.getItem()` is a defensive clone of the pre-consumption stack; the shrink
  and any remainder land after the listener returns. Paper adds `getReplacement()`.
- `PlayerItemBreakEvent` fires immediately before `shrink(1)`, only when the count is 1.
  `getBrokenItem()` is a live mirror — clone it inside the listener.

## Bukkit dispatch

- Handlers of equal priority run in registration order, and the class that declares
  `getHandlerList()` owns the list, so a subclass event reaches a superclass handler exactly once.
