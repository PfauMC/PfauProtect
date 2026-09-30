# Результаты прогона TESTING-v5

Дата: 2026-09-30. Canvas 26.3 build 956 (alpha), плагин 1.0.0, ветка `mvp` начиная с ed1d5f4. Сервер в
контейнере (`scripts/test-server.sh`), плоский мир, выживание, мирная сложность. Игрок — SPY_me.
Строки сняты с консоли (`test-server.sh cmd`) и офлайн-копией `ledger/` (`Scan.java`, `Forms.java`).

Формат: пункт — что делали — что вышло — строки журнала. Время — UTC сервера.

Счётчик непокрытого снимается на последней остановке. Контейнер сервера удаляется при остановке,
поэтому счётчики промежуточных перезапусков (правки D17–D19) потеряны; к итогу они не относятся.

---

## 5.1 Использование

### U1–U6 — ПРОЙДЕНЫ

Костная мука на саженец, светокамень в якорь, око в рамку портала Энда, соты на медь, яйцо призыва
свиньи, огненный заряд по незераку.

```
10:23:21  bonemeal_use            -1 minecraft:bone_meal       SPY_me slot 0  to nowhere
10:23:26  item_into_single_block  -1 minecraft:glowstone       SPY_me slot 1  to nowhere
10:23:29  eye_into_frame          -1 minecraft:ender_eye       SPY_me slot 2  to nowhere
10:23:34  wax_apply               -1 minecraft:honeycomb       SPY_me slot 3  to nowhere
10:23:43  spawn_egg_use           -1 minecraft:pig_spawn_egg   SPY_me slot 4  to nowhere
10:23:45  item_used               -1 minecraft:fire_charge     SPY_me slot 5  to nowhere
```

Выдача и очистка перед U1 через RCON легли `direct_new_item` и `item_vanished` — дефект D17.

### U7–U9 — ПРОЙДЕНЫ

Ведро воды вылить и набрать, ведро лавы, молоко с коровы, треска в ведро и обратно, бутылочка из
пруда и из котла, вода из бутылочки в пустой котёл, отмывка красной кожаной куртки. Все — превращение
в слоте, без `direct_new_item` и `item_vanished` (D10 закрыт).

```
10:31:48  bucket_empty        -1 water_bucket / +1 bucket       SPY_me slot 1  (changed in place)
10:31:49  bucket_fill         -1 bucket / +1 water_bucket       SPY_me slot 1  (changed in place)
10:32:07  bucket_fill         -1 bucket / +1 milk_bucket        SPY_me slot 2  (changed in place)
10:32:25  bucket_capture_mob  -1 water_bucket / +1 cod_bucket   SPY_me slot 1  (changed in place)
10:32:29  bucket_release_mob  -1 cod_bucket / +1 bucket         SPY_me slot 1  (changed in place)
10:32:34  bottle_fill         -1 glass_bottle / +1 potion       SPY_me slot 3 → 6
10:32:42  bottle_empty        -1 potion / +1 glass_bottle       SPY_me slot 4  (changed in place)
10:32:49  cauldron_wash       -1 / +1 leather_chestplate        SPY_me slot 5  (changed in place)
```

### U10–U12 — ПРОЙДЕНЫ

```
10:33:54  container_add       +1 minecraft:iron_ingot   container 61 -60 61 slot 0  from SPY_me cursor
10:33:59  beacon_payment      -1 minecraft:iron_ingot   container 61 -60 61 slot 0  to nowhere
10:34:14  transmute_on_break  -1 carrot_on_a_stick / +1 fishing_rod  SPY_me slot 1  (changed in place)
10:34:39  hotbar_swap         -1 minecraft:stone  SPY_me slot 31  to SPY_me slot 4        (pick-block)
10:34:49  equip_armor         iron_helmet ↔ diamond_helmet  SPY_me slot 3 ↔ equipment slot 39
```

Обмен шлемов по ПКМ — четыре строки на обмен (по паре на каждую сторону).

### U13 — ПРОЙДЕН после D18, D19; атрибуция — D20

Первый заход: костная мука, вёдра, бутылочка, стрелы сошлись. Динамит списан дважды (D18), алмаз,
выброшенный последним в слоте, родился `item_spawn` без убыли слота (D19).

После правки: алмаз по одному из разных слотов, стак булыжника (тег серного куба — два события),
два динамита — по одной строке на раздачу, баланс слотов раздатчика сходится.

```
10:47:47  dispenser_eject     -1 minecraft:diamond      container 70 -60 50 slot 0  to dropped item 5d8e6dfa…
10:47:54  dispenser_eject     -1 minecraft:diamond      container 70 -60 50 slot 4  to dropped item 427042fb…
10:48:03  dispenser_eject     -1 minecraft:cobblestone  container 70 -60 50 slot 0  to dropped item 80305ffe…
10:48:13  dispenser_behavior  -1 minecraft:tnt          container 70 -60 50 slot 0  to nowhere
10:48:15  dispenser_behavior  -1 minecraft:tnt          container 70 -60 50 slot 0  to nowhere
```

Динамит взорвал раздатчик и выпавшие предметы. Предметы ушли `item_destroy_explosion` (алмаз,
булыжник после слияния), всё выбитое родилось `blk_tnt`. Ни одна строка не назвала игрока — D20.

---

## Дефекты

### D17. `/give`, `/clear` из RCON — `direct_new_item`, `item_vanished` — ИСПРАВЛЕН (35e6365)

Canvas ставит действие команды не из региона игрока в его планировщик на следующий тик
(`AbstractCommandExecution.java:293`). Намерение `cmd_give` ставило пересчёт в ту же очередь раньше
команды, и пересчёт тратил его на нетронутый инвентарь. Причина теперь кладётся тиком позже, если поток
не владеет регионом игрока. Проверено: RCON-выдача и очистка легли `cmd_give` и `cmd_clear`.

### D18. Одна раздача записана дважды — ИСПРАВЛЕН (12f76a5)

Динамит и блоки тега `sulfur_cube_swallowable` поднимают `BlockDispenseEvent` дважды: из проверки
серного куба (`SulfurCubeBlockDispenseItemBehavior.java:22`) и из самого поведения.

### D19. Последний предмет слота — `item_spawn` без убыли — ИСПРАВЛЕН (12f76a5)

Выброс по умолчанию отщепляет предмет от слота до события (`DefaultDispenseItemBehavior.java:28`), и
пустой слот не находился. Разбор раздачи теперь опирается на `BlockPreDispenseEvent`: один раз, до
поведения, со слотом.

### D20. Раздатчик и взрыв не называют игрока — фаза 5.7

Раздатчик не передаёт виновника, конец предмета от взрыва пишется на бросившего. Решено закрыть
целиком: энергия по любой цепи редстоуна и лестница «кто стоит за сущностью» — SPEC-v5 §4 5.7,
TESTING-v5 S1–S14.

---

## Не проверяется на этом сервере

- `/item` и `/data` на сервере отключены (`Unknown or incomplete command`), часть C1 про
  `/item replace` и `/item modify` проверить нельзя.
