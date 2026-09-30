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

## 5.2 Снаряды

### P1–P6 — ПРОЙДЕНЫ

```
13:05:22  thrown_consumed  snowball / egg / ender_pearl / splash_potion / experience_bottle / wind_charge / ender_eye
13:05:50  proj_shot        -1 arrow  SPY_me slot 8  to entity f8150fb9… slot 0;  proj_pickup оттуда же
13:05:53  proj_hit_void    -1 arrow  entity 71631761… slot 0  to nowhere            (стрела в свинье)
13:06:59  proj_despawn     -1 arrow  entity af85c236… slot 0  to nowhere            (минута в земле)
13:06:14  proj_shot / proj_pickup  trident  entity 4cb4457d… slot 0
13:06:21  proj_shot / trident_loyalty_return  trident  entity b6a46e93… slot 0      (×3)
13:06:38  crossbow_load    -1 arrow, crossbow → crossbow  (changed in place)
13:06:42  crossbow_shoot   crossbow → crossbow;  +1 arrow  entity 62d3a7d8… slot 0  from nowhere
13:06:51  firework_launch  -1 firework_rocket  (с земли и на элитрах)
13:05:40  eye_survive      +1 ender_eye  dropped item 0f4a3a9a…;  pickup
```

P4 с перезапуском: стрела выпущена в 13:08:35 (`proj_shot … to entity e0c20932… slot 0`), сервер
остановлен и поднят, подбор в 13:09:37 — `proj_pickup … from entity e0c20932… slot 0`: метка в PDC
стрелы пережила перезапуск.

## 5.3 Рождения из мира

Сложность «лёгкая». Первый заход сорван: игрока, стоявшего без дела, убил пиглин (золотой шлем не был
надет). Смерть записана верно — `death_drop` из каждого слота в свой `dropped item`. Мобы и предметы
убраны `/kill`, игроку дано сопротивление 255.

### W1–W9 — ПРОЙДЕНЫ

```
13:13:43  gift_drop              +1 egg   dropped item e9523ffa…                                   (W1)
13:14:35  item_pickup_by_mob     iron_sword  dropped item → entity ce38adae… slot 0 (зомби)
13:14:38  mob_equipment_drop     -1 iron_sword  entity ce38adae… slot 0 → dropped item  by SPY_me   (W2)
13:14:38  mob_drop               +1 rotten_flesh  by SPY_me
13:16:45  mob_transform          iron_sword  entity cce5e5f1… slot 0 → entity 428d62cf… slot 0      (W3, зомби → утопленник)
13:18:57  shearing_drop          +1 white_wool ×3                                                  (W4)
13:19:14  leash_drop             +1 lead  (забор сломан)
13:17:48  fishing_catch          +1 …  by SPY_me                                                   (W5)
13:16:21  give_item_to_mob       gold_ingot  SPY_me slot 8 → entity 8082bad5… slot 1 (пиглин)
13:16:26  piglin_barter          -1 gold_ingot  entity 8082bad5… slot 1;  +14 …  dropped item      (W6, ×4)
13:17:06  container_add / trade_payment  -20 wheat  entity 30fffd59… slot 0                         (W7)
13:17:57  block_interact_drop    +1 sweet_berries ×2  by SPY_me                                    (W8)
13:18:07  brushable_reveal       +1 tnt  by SPY_me
13:18:18  drop_from_hand         -8 cobblestone → dropped item ae99614e…                           (W9, в портал)
```

W9: после прохода через портал у `ae99614e…` нет ни конца, ни нового рождения — сущность сохраняет
UUID при смене измерения, строки не рвутся. `ITEM_DIMENSION_CHANGE` (SPEC-v5 §3) не нужна.

Наблюдение O1 — не дефект (разобрано по журналу после прогона). Одиннадцать `item_despawn` в 13:13:33 —
призраки `/give` повторной выдачи набора (десять команд, два железных меча дают два призрака): каждый
родился `cmd_give` и исчез в ту же секунду. Из одиннадцати предметов смертного выброса восемь закончились
`cmd_kill_item` в 13:13:21, а три ещё в 13:10:40–13:11:10 подобрал пиглин (`item_pickup_by_mob`).
Регион не останавливался: Canvas не приостанавливает регион с загруженными чанками, а отложенный `/kill`
выполняется до тика самой сущности.

`summon item` рождает `item_spawn`: команды, порождающие предметы в мире, не покрыты (SPEC-v5 §5).

## 5.4 Сущности-держатели и полки

### H1–H9 — ПРОЙДЕНЫ после D21–D25

Первый заход (сборка 7744507…5569368):
- проигрыватель: вставка `record_into_jukebox` и выброс кликом `container_remove … to dropped item` —
  верно; кафедра, стойка, кристалл Энда, седло и волчья броня кликом, тихоня (дать и забрать),
  кормление, приручение, краситель, бирка, поводок — верно;
- найдены D21 (содержимое блоков без окна при сломе), D22 (лодка, вагонетка), D23 (рамка),
  D24 (поводок на тихоне), D25 (слоты окна лошади).

Повтор после e861945 и 57c9a42:

```
13:51:05  container_break_drop  -1 music_disc_cat  container 22 -60 160 slot 0 → dropped item      (проигрыватель)
13:51:06  container_break_drop  -1 book  container 24 -60 160 slot 2 / slot 3 → dropped item        (резная книжная полка)
13:51:07  container_break_drop  -2 book  container 26 -60 160 slot 1 → dropped item                 (дубовая полка)
13:51:07  container_break_drop  -1 diamond  container 28 -60 160 slot 0 → dropped item              (узорчатая ваза)
13:51:09  container_break_drop  -8 cobblestone  container 32 -60 160 slot 13                        (одиночный сундук)
13:51:10  container_break_drop  -4 cobblestone  container 33 -60 160 slot 13; 34 -60 160 slot 13     (половины двойного)
13:50:59  campfire_cook_drop    -1 beef  container 30 -60 160 slot 0/1;  +1 cooked_beef             (приготовилась до слома)
13:51:13  container_remove      -1 diamond  entity dacd88d5… slot 0 → dropped item  by SPY_me       (выбит из рамки)
13:51:13  entity_break_drop     -1 item_frame  entity dacd88d5… slot 16 → dropped item  by SPY_me
13:51:32  container_break_drop  -13/-3 cobblestone  entity f5d7e94a… slot 13;  entity_break_drop slot 16  (грузовая вагонетка)
13:51:43  entity_break_drop     -1 oak_boat  entity 6305b428… slot 16 → dropped item
13:51:19  equip_mob             +1 saddle  entity 34c57949… slot 7;  13:51:21 container_remove из slot 7 (окно)
```

- Цветочный горшок пишется в Void в обе стороны (`item_into_single_block` / `container_remove`): растение
  в горшке — отдельный блок, так задумано в коде (`Holders.kt:142`); SPEC-v5 §4 5.4 приведена к этому.
- Обмен дубовой полки с хотбаром — строки `item_into_single_block` / `container_remove` по её слотам.

---

## 5.5 Команды и творческий режим

### C1 — ПРОЙДЕН в доступной части

```
13:52:55  cmd_clear   … все слоты  (консоль)
13:52:56  cmd_give    +1 iron_sword (консоль);  13:53:01 +1 golden_sword (RCON)
13:53:25  cmd_enchant iron_sword → iron_sword  (changed in place)  (чат)
13:53:31  cmd_give    +3 stone (чат);  13:53:38 cmd_clear -1 stone (чат)
13:53:49  cmd_enchant (RCON);  13:53:51 cmd_enchant (консоль)
```

Призрачный предмет `/give` на земле — `cmd_give` и сразу `item_despawn` (баланс ноль), не
`item_spawn`. `/item`, `/data` и `/loot` на сервере отключены (`Unknown or incomplete command`) —
`/item replace|modify` и `/loot give` не проверены.

### C2 — ПРОЙДЕН

```
13:55:16  creative_set    +64 gold_ingot  SPY_me slot 8  from nowhere          (из меню в инвентарь)
13:55:23  creative_set    +64 …  dropped item a6cea57d…  from nowhere          (выброшено из меню)
13:55:28  creative_pick   +1 emerald_block  SPY_me slot 5  from nowhere        (pick-block)
13:55:30  drop_from_menu  -1 emerald_block  SPY_me slot 5 → dropped item
```

## 5.6 Остатки

### R1–R3 — ПРОЙДЕНЫ

```
13:56:55  bundle_insert    -16 gold_ingot  SPY_me cursor → inside 590027e7… at 0
13:56:55  container_named  bundle → bundle  (changed in place)                  (R1: одна строка, без quick_move)
13:57:02  bundle_dump      -16 gold_ingot  Nested(590027e7…, 0) → dropped item  by SPY_me   (R2, ×3)
13:57:11  blk_sign_edit    oak_sign  block 33 -60 160  by SPY_me  text "текст"
13:57:15  blk_sign_edit    …  text "текст" -> "поправ…"                        (R3)
```

## 5.7 Кто запустил механизм

Сборки 27d5ec6 (часть 1), d263f60 (часть 2), 7744507 (энергорельсы).

### S1–S3 — ПРОЙДЕНЫ

```
11:27:01  blk_player_switch  stone_button powered=false -> true  block 80 -59 50  by SPY_me
11:27:01  dispenser_eject    -1 minecraft:diamond  container 80 -60 50 slot 4  to dropped item d279b652…  by SPY_me
11:27:16  dispenser_behavior -1 minecraft:tnt      container 80 -60 50 slot 4  to nowhere  by SPY_me
11:27:20  blk_tnt            dispenser -> air      block 80 -60 50  by SPY_me (worked out)  +contents
11:27:52  blk_player_switch  lever powered=true -> false  block 80 -60 60  by SPY_me
11:27:53  dispenser_eject    -1 minecraft:diamond  container 104 -60 60 slot 4  …  by SPY_me     (S2: 19 блоков провода, 2 повторителя, факел)
11:28:09  blk_fall_land      air -> sand           block 80 -60 69  by SPY_me (worked out)
11:28:09  dispenser_eject    -1 minecraft:diamond  container 80 -60 71 slot 4  …  by SPY_me     (S3: песок в воздухе → наблюдатель)
```

Алмаз, выброшенный в S1 и взорванный динамитом из того же раздатчика, — `ITEM_DESTROY_EXPLOSION`,
`actor=SPY_me` (офлайн). Вариант S2 с Alternate Current не прогонялся.

### S4–S7 — ПРОЙДЕНЫ

```
12:36:58  blk_player_switch  stone_pressure_plate  block 90 -60 80  by SPY_me                              (пешком)
12:37:07  blk_entity_switch  stone_pressure_plate  block 90 -60 80  by SPY_me  pressed by minecraft:pig     (верхом)
12:37:16  blk_entity_switch  stone_pressure_plate  block 90 -60 80  by SPY_me  pressed by minecraft:horse
12:37:29  blk_player_switch  stone_pressure_plate  block 90 -60 80  by SPY_me                              (в лодке)
12:37:37  blk_entity_switch  oak_pressure_plate    block 96 -60 80  by SPY_me  pressed by minecraft:item
12:38:46  blk_entity_switch  stone_pressure_plate  block 102 -60 80  SPY_me was nearby (1.5 blocks away)  pressed by minecraft:sheep
12:39:00  blk_entity_switch  stone_pressure_plate  block 102 -60 80  by SPY_me (worked out)  pressed by minecraft:sheep   (удочка)
12:39:44  blk_entity_switch  stone_pressure_plate  block 102 -60 80  by SPY_me (worked out)  pressed by minecraft:sheep   (удар)
```

- В лодке пластину нажал сам игрок: его хитбокс в пластине, строка `blk_player_switch`, `FACT`.
- Выброс раздатчика после толкания телом (`NEARBY`) — без игрока: строки предметов свидетеля не
  называют.

### S8–S12 — ПРОЙДЕНЫ

```
12:48:52  blk_entity_switch  detector_rail  block 94 -60 90  by SPY_me  pressed by minecraft:minecart               (сидел внутри)
12:48:56  blk_entity_switch  detector_rail  block 94 -60 90  by SPY_me (worked out)  pressed by minecraft:minecart  (толкнул)
12:49:14  blk_entity_switch  detector_rail  block 94 -60 90  by SPY_me (worked out)  pressed by minecraft:minecart  (энергорельсы)
12:49:41  dispenser_eject    container 90 -60 97   by SPY_me     (заряд ветра по каменной кнопке)
12:49:49  blk_entity_switch  oak_button  block 94 -59 97  by SPY_me  pressed by minecraft:arrow
12:49:53  dispenser_eject    container 98 -60 97   by SPY_me     (стрела в мишень)
12:50:13  dispenser_eject    container 102 -60 97  by SPY_me     (скалковый сенсор, брошенный булыжник; раскачка — тоже на игрока)
12:50:29  dispenser_eject    container 106 -60 97  by SPY_me     (сундук → компаратор)
12:50:37  blk_piston_extend  air -> stone  block 110 -60 96  by SPY_me (worked out)
12:50:38  dispenser_eject    container 110 -60 98  by SPY_me     (поршень → наблюдатель)
```

Вагонетку в третьем заходе поставил тот же игрок, так что её происхождение (`EntityPlaceEvent`) дало
бы тот же ответ; разгон энергорельсами отдельно от этого не различить.

### S13 — ПРОЙДЕН

Пять булыжников, брошенных рукой рядом с динамитом, который игрок поджёг огнивом: рождение
`drop_from_hand … actor=SPY_me`, конец `ITEM_DESTROY_EXPLOSION … actor=SPY_me` (офлайн), блоки —
`blk_tnt … by SPY_me`.

### S14 — ПРОЙДЕН

Часы из двух наблюдателей, запущенные игроком, питают лист редстоунового провода 20×20 (400 блоков).
Сразу после запуска: TPS 20,00, 11,51 мс на тик, 23,0 % загрузки региона. Через 10 минут: TPS 20,00,
11,69 мс, 23,4 %; память контейнера 1,4 ГиБ. Роста нет; заметки энергии ограничены числом позиций
по построению (карта по позиции).

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

### D20. Раздатчик и взрыв не называют игрока — ЗАКРЫТ фазой 5.7

Раздатчик не передавал виновника, конец предмета от взрыва писался на бросившего. Закрыто целиком:
энергия по любой цепи редстоуна и лестница «кто стоит за сущностью» — SPEC-v5 §4 5.7 и §7 5.7,
TESTING-v5 S1–S14 (27d5ec6, d263f60, 7744507, 5569368).

### D21. Слом проигрывателя, полок, вазы, костра рождает содержимое из Void — ИСПРАВЛЕН (57c9a42)

Снимок, который несёт `BlockDropItemEvent`, у этих блоков приходит с пустым инвентарём (проверено
отладочным выводом: `CraftJukebox contents=[null]`, пластинка — среди выпавшего). Содержимое читается в
`BlockBreakEvent` и копируется.

### D22. Лодка и вагонетка: слом не связан с выпавшим предметом — ИСПРАВЛЕН (e861945)

Canvas удаляет сущность раньше, чем роняет её предмет; метки держатся до следующего тика.

### D23. Рамка: выбитое и сломанное рождается `item_spawn` — ИСПРАВЛЕН (57c9a42)

`HangingEntity.spawnAtLocation` роняет без `EntityDropItemEvent`; удар по рамке и `HangingBreakEvent`
ставят ожидания сами.

### D24. Поводок на тихоне — `item_vanished` — ИСПРАВЛЕН (e861945)

### D25. Седло: окно лошади и клик — разные номера слотов — ИСПРАВЛЕН (e861945)

### D26. `clear` из RCON сразу после входа — `item_vanished` — ИСПРАВЛЕН (9aafdd5)

Счётчик сессии 13:49–14:31: `item_vanished=24 item_spawn=1`, все — в секунду 13:49:42, в RCON-пачке
`clear` + `give` сразу после входа игрока: `give` записались `cmd_give`, очистка 24 слотов — нет.
Намерение команды жило один пересчёт, и пересчёт между «причина легла» и «команда применилась»
тратил его впустую. Теперь намерения команд живут до 250 мс и переходят в следующий пересчёт, пока
не потрачены. Проверено: три пачки `tp` + `clear` + `give`, первая — через секунду после входа, —
27 строк `cmd_clear`/`cmd_give`, ни одной `item_vanished`.

### D27. Регион с поршневыми часами висит по пять секунд — ИСПРАВЛЕН (c613d3d)

Ночная сессия R4 (сборка f8f4de0). В 19:19 и 20:14 watchdog Folia: регион вокруг чанка [1, 10] не
отвечал 5,1 и 5,6 с. Оба стека — синхронная запись RocksDB на потоке региона из обработчика поршня:
`RocksItemLog.clearFormsAt` и `putNote` ← `BlockDestructionListener.file` ← `piston`. Запись вставала в
очередь за `flushWal(true)` писателя журнала, а fsync на томе Docker занимает секунды. Нарушение
инварианта «никакого ввода-вывода RocksDB на потоке региона»; таких мест — два десятка, часть добавила
5.8.

Исправление: запомненные формы позиций и имена вложенных контейнеров ставятся в оверлей в памяти,
откуда их видят все чтения, и уходят писателю журнала, который пишет их пакетом и снимает с оверлея.
Вызывающий код не менялся. Проверка — X16.

---

## Вечерняя сессия 2026-09-30 (сборка f8f4de0, Alternate Current)

Лог сервера — по московскому времени (a966dba).

### S2 с Alternate Current — ПРОЙДЕН

`redstone-implementation: ALTERNATE_CURRENT`, стенд на z 95: рычаг на 121 -60 95, провод 20 блоков, два
повторителя, редстоуновый факел-инвертор, раздатчик на 146 -60 95.

```
18:45:24  container_add      +1 minecraft:diamond  container 146 -60 95 slot 4  from SPY_me cursor
18:45:32  blk_player_switch  lever powered=true -> false  block 121 -60 95  by SPY_me
18:45:33  dispenser_eject    -1 minecraft:diamond  container 146 -60 95 slot 4  to dropped item c719673d…  by SPY_me
```

### O2 — ПРОЙДЕН

Две пачки `tp` + `give` из RCON в 18:46:14: оба призрака записаны `cmd_give` на новой позиции игрока, ни
одного `item_spawn`.

### R4 — ПРОЙДЕН

При работающих поршневых часах, вклад которых в журнал растёт каждую секунду:

```
18:42:26  planes compared to the end of the item plane, 282 positions in this pass,
          0 the block plane never recorded, 0 too recent to judge, 0 gaps
```

Предупреждения `… gave up more than the item plane ever booked` (307, 95) — природные блоки после
взрывов, как раньше.

Счётчик на остановке — `item_spawn=2`: два редстоуна, выпавшие в 18:45:01, когда стенд строился
командами `setblock` на этой сборке, ещё без обёртки команд 5.8E.

---

## Итог

Счётчики непокрытого на остановках (каждый — за свою сессию, контейнер удаляется при остановке):

| Сессия | Сборка | Счётчик |
| --- | --- | --- |
| 13:49–14:31 | 57c9a42 | `item_vanished=24 item_spawn=1` — всё D26 |
| 14:40–14:42 | 9aafdd5 | `item_spawn=3` — O2 |
| 17:52–20:17 | f8f4de0 | `item_spawn=2` — редстоун от `setblock` до 5.8E |

`direct_new_item` — ноль во всех сессиях после D17.

Наблюдение O2. Призрак `/give` рождался `item_spawn`, если в той же пачке перед `give` игрок
телепортирован: ожидание призрака ставилось по позиции игрока в момент команды. Исправлено в
f8f4de0, проверено в вечерней сессии.

Не закрыто: раздел 5.8 (X1–X16) — после фазы 5.8.

---

## Не проверяется на этом сервере

- `/item` и `/data` на сервере отключены (`Unknown or incomplete command`), часть C1 про
  `/item replace` и `/item modify` проверить нельзя.

---

## Раздел 5.8 — прогон 2026-10-01 (в работе)

Сборки по ходу: 1184b54 → dd29dc6 → 1225e34 → 4777809 → 43fbd5b → 8684e97 → 4e848d3 → 2a79cef → c92e488.

- **X1 — ПРОЙДЕН.** Дверь (обе половины), люк, ворота, повторитель, компаратор, нотный блок, датчик дневного
  света, торт (все куски, последний — в воздух) — `blk_player_use`; рычаг — только `blk_player_switch`. Свеча,
  зажжённая огнивом, — `blk_player_place` (событие установки сервера), затем с dd29dc6 — правка рукой.
- **X2 — ПРОЙДЕН после dd29dc6.** Инструменты писались дважды (`blk_player_place` и `blk_player_use`): сервер
  поднимает на них и смену блока, и установку. Теперь одна строка `blk_player_use`. Трава под снегом, стороны
  забора — не пишутся.
- **X3 — ПРОЙДЕН.** Бочка, кровать, касание редстоуновой руды — строк нет (погасание руды тоже — с dd29dc6).
- **X4 — ПРОЙДЕН.** Вода, лава, рыхлый снег ведром туда и обратно, ступенька залита и осушена — `blk_bucket`.
- **X5 — ПРОЙДЕН после D28 и D29.** Вода и огниво раздатчиком — `blk_dispenser`, `INFERRED`, на нажавшего.
  Шалкеровый ящик — в позицию, алмазы — в его слоты.
- **X6 — ПРОЙДЕН после 6614f0a и 4e848d3.** Зажжение, гибель листа со сломом рамки; парный портал в Незере с
  перезаписанными блоками — на путника, `INFERRED` (Canvas поднимает только `EntityPortalAsyncEvent`).
- **X7 — ПРОЙДЕН.** Губка, наковальня до слома, динамит огнивом (`FACT`) и рычагом (`INFERRED`), взрывы на
  поджёгшего.
- **X8 — частично.** `setblock` и `clone` сундука, `place feature`, `/summon` стойки в шлеме и предмета,
  `execute … run give` — верно. `fill … destroy` писался трижды и списывал форму дважды, груз вагонетки не
  бронировался — исправлено в c92e488, ждёт повтора.

Дефекты:
- **D28** — шалкеровый ящик, поставленный раздатчиком, при перечитывании «упаковывался» как сломанный —
  ИСПРАВЛЕН (4777809). Сверка контейнера после закрытия окна поймала расхождение до правки.
- **D29** — тыква, надетая раздатчиком на игрока, — `direct_new_item`: событие брони приходит вторым в том же
  вызове — ИСПРАВЛЕН (8684e97, 2a79cef).
- Призраки `/give` — теперь не пишутся вовсе: Canvas делает их ненастоящими до появления (43fbd5b).

Наблюдения:
- **O3** — растения Незера (вьющиеся и плакучие лозы, багровые корни) рядом со свежим порталом рождаются
  `item_spawn` без строки о блоке. Не действие игрока; разобрать отдельно.
- **O4** — `World mismatch` в тике прирученного волка после перехода через портал — исключение Canvas, без
  кадров плагина.
