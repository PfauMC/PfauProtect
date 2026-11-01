# Specs index

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
