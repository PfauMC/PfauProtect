# Результаты прогона TESTING-v4

Дата: 2026-09-29. Canvas 26.3 build 956 (alpha), плагин 1.0.0 на коммите 2fcb6a7. Сервер в контейнере
(`scripts/test-server.sh`), плоский мир, выживание, мирная сложность. Игрок — SPY_me, клиент 26.3.
Строки журнала сняты с консоли сервера (`test-server.sh cmd`).

Формат: пункт — что делали — что ожидали — что вышло — строки журнала.

Перед прогоном: первая попытка A1 была в творческом режиме и не засчитана. Инвентарь очищен, ресурсы
выданы `/give`, сервер перезапущен, чтобы выдача не попала в счётчик непокрытого. Счётчик той
сессии: `quick_move=36 item_spawn=33` — творческий режим, `/clear` и выдача, к прогону не относится.

---

## A. Критерий 1: цепочка проходит крафт насквозь

### A1. Алмазы → блок → алмазы на верстаке — ПРОЙДЕН

Девять алмазов (по два в слот) в сетку верстака, блок на курсор и в инвентарь, затем блок обратно
в сетку и девять алмазов шифт-кликом в инвентарь.

```
17:00:01  craft_result   +1 minecraft:diamond_block  SPY_me cursor  from nowhere
17:00:01  craft_consume  -1 minecraft:diamond  entity SPY_me slot 9  to nowhere
          ... по одной строке craft_consume на слоты 1–8 ...
17:00:05  container_remove  -1 minecraft:diamond  entity SPY_me slot 1  to SPY_me slot 34   (остаток из сетки)
17:00:09  craft_consume  -1 minecraft:diamond_block  entity SPY_me slot 5  to nowhere
17:00:09  craft_result   +9 minecraft:diamond  SPY_me slot 34  from nowhere
```

- Расход и результат — одним временем, в одной транзакции, сетка названа `entity SPY_me slot N`.
- `/pp reconcile` — `matches the ledger`.
- `/pp verify recent` — 222 проводки, 0 разрывов; две плоскости — 0 разрывов, 0 перерасхода.

Наблюдение, не дефект: в выдаче `player:` перемещение между двумя держателями игрока видно дважды,
половиной «−» и половиной «+». Кандидат на схлопывание в выдаче.

### A2. Та же цепочка через станок — ПРОЙДЕН

Наковальня на `-9 -60 -5`, в неё два повреждённых алмазных меча шифт-кликом, результат на курсор.

```
17:01:57  anvil_combine  -1 minecraft:diamond_sword  container -9 -60 -5 slot 1  to nowhere  (changed in place)
17:01:57  anvil_combine  -1 minecraft:diamond_sword  container -9 -60 -5 slot 0  to nowhere  (changed in place)
17:01:57  anvil_combine  +1 minecraft:diamond_sword  SPY_me cursor  from nowhere  (changed in place)
17:01:55  quick_move     +1 minecraft:diamond_sword  container -9 -60 -5 slot 1  from SPY_me slot 5
17:01:54  quick_move     +1 minecraft:diamond_sword  container -9 -60 -5 slot 0  from SPY_me slot 4
```

- Три строки `anvil_combine` одним временем; строку результата на курсоре выдача по позиции наковальни
  подтянула из транзакции.
- Из слота результата (слот 2) строк нет.
- `action:transform` — ровно эти три строки.
- `/pp verify recent` — 247 проводок, 0 разрывов; две плоскости — 0 разрывов.

### A3. Крафт по шифт-клику — ПРОЙДЕН

По 16 досок в две клетки одного столбца, шифт-клик по палкам.

```
17:04:23  craft_result   +64 minecraft:stick  SPY_me slot 5  from nowhere
17:04:23  craft_consume  -16 minecraft:oak_planks  entity SPY_me slot 5  to nowhere
17:04:23  craft_consume  -16 minecraft:oak_planks  entity SPY_me slot 2  to nowhere
```

- 16 применений рецепта — одна транзакция, числа сходятся (16 × 4 = 64).
- `/pp reconcile` — `matches`; `/pp verify recent` — 272 проводки, 0 разрывов.

---

## B. Станки

### B1. Наковальня — ПРОЙДЕН

Ремонт кирки алмазом, переименование железного меча, книга «Острота III» на переименованный меч.

```
17:05:25  anvil_combine  -1 minecraft:diamond  container -9 -60 -5 slot 1  to nowhere  (changed in place)
17:05:25  anvil_combine  -1 minecraft:diamond_pickaxe  container -9 -60 -5 slot 0  to nowhere  (changed in place)
17:05:25  anvil_combine  +1 minecraft:diamond_pickaxe  SPY_me cursor  from nowhere  (changed in place)
17:05:36  anvil_combine  -1 minecraft:iron_sword  container -9 -60 -5 slot 0  to nowhere  (changed in place)
17:05:36  anvil_combine  +1 minecraft:iron_sword "Железный меч!"  SPY_me slot 7  from nowhere  (changed in place)
17:05:48  anvil_combine  -1 minecraft:enchanted_book  container -9 -60 -5 slot 1  to nowhere  (changed in place)
17:05:48  anvil_combine  -1 minecraft:iron_sword "Железный меч!"  container -9 -60 -5 slot 0  to nowhere  (changed in place)
17:05:48  anvil_combine  +1 minecraft:iron_sword "Железный меч!"  SPY_me cursor  from nowhere  (changed in place)
```

- Каждая операция — одна транзакция; переименование — две строки, без второго слота.
- Имя формы видно в выдаче (B11 v3 подтверждён вживую).
- `/pp verify recent` — 301 проводка, 0 разрывов.

Наблюдение: зачарование на выдаче не видно — меч до книги и после читается одинаково, хотя это
разные формы. Выдача называет только тип и имя.

### B2. Точило — ПРОЙДЕН

```
17:07:29  grindstone  -1 minecraft:iron_pickaxe  container -8 -60 -5 slot 1  to nowhere  (changed in place)
17:07:29  grindstone  -1 minecraft:iron_pickaxe  container -8 -60 -5 slot 0  to nowhere  (changed in place)
17:07:29  grindstone  +1 minecraft:iron_pickaxe  SPY_me cursor  from nowhere  (changed in place)
17:07:34  grindstone  -1 minecraft:golden_sword  container -8 -60 -5 slot 0  to nowhere  (changed in place)
17:07:34  grindstone  +1 minecraft:golden_sword  SPY_me cursor  from nowhere  (changed in place)
```

- Две изношенные кирки — три строки одной транзакции, а не перенос одной плюс беспричинная пропажа
  второй (правка 123351a подтверждена вживую).
- Снятие чар — пара строк.
- `/pp verify recent` — 322 проводки, 0 разрывов.

### B3. Кузнечный стол — ПРОЙДЕН

```
17:09:01  smithing_transform  -1 minecraft:netherite_ingot  container -7 -60 -5 slot 2  to nowhere  (changed in place)
17:09:01  smithing_transform  -1 minecraft:diamond_pickaxe  container -7 -60 -5 slot 1  to nowhere  (changed in place)
17:09:01  smithing_transform  -1 minecraft:netherite_upgrade_smithing_template  container -7 -60 -5 slot 0  to nowhere  (changed in place)
17:09:01  smithing_transform  +1 minecraft:netherite_pickaxe  SPY_me cursor  from nowhere  (changed in place)
17:09:11  smithing_trim  -1 minecraft:iron_ingot  container -7 -60 -5 slot 2  to nowhere  (changed in place)
17:09:11  smithing_trim  -1 minecraft:iron_chestplate  container -7 -60 -5 slot 1  to nowhere  (changed in place)
17:09:11  smithing_trim  -1 minecraft:coast_armor_trim_smithing_template  container -7 -60 -5 slot 0  to nowhere  (changed in place)
17:09:11  smithing_trim  +1 minecraft:iron_chestplate  SPY_me cursor  from nowhere  (changed in place)
17:09:13  container_remove  -3 minecraft:iron_ingot  container -7 -60 -5 slot 2  to SPY_me slot 9
```

- Превращение и отделка различены; каждая — транзакция из четырёх проводок.
- `/pp verify recent` — 354 проводки, 0 разрывов.

Наблюдение: пометка `(changed in place)` стоит на всех проводках станка, включая шаблон и слиток,
которые израсходованы, а не изменены. Так устроено по SPEC-v4 §4.1 — вид один на транзакцию.

### B4. Камнерез — ПРОЙДЕН

```
17:10:13  stonecutter  -1 minecraft:stone  container -6 -60 -5 slot 0  to nowhere  (changed in place)
17:10:13  stonecutter  +1 minecraft:stone_stairs  SPY_me cursor  from nowhere  (changed in place)
17:10:24  stonecutter  -63 minecraft:stone  container -6 -60 -5 slot 0  to nowhere  (changed in place)
17:10:24  stonecutter  +63 minecraft:stone_bricks  SPY_me slot 8  from nowhere  (changed in place)
```

- Шифт-клик — одна пара, количество совпадает с полученным.
- `/pp reconcile` — `matches`; `/pp verify recent` — 376 проводок, 0 разрывов.

Наблюдение: камнерез помечен `(changed in place)`, хотя по смыслу это рецепт (камень израсходован,
ступенька сделана), как у верстака. `shiftOf` относит его к станкам, возвращающим тот же предмет
изменённым (`ContainerCapture.kt:265`). Кандидат на пересмотр вида вместе с ткацким станком и
картографией.

### B5. Ткацкий станок — ПРОЙДЕН

```
17:11:58  loom  -1 minecraft:red_dye  container -5 -60 -5 slot 1  to nowhere  (changed in place)
17:11:58  loom  -1 minecraft:white_banner  container -5 -60 -5 slot 0  to nowhere  (changed in place)
17:11:58  loom  +1 minecraft:white_banner  SPY_me cursor  from nowhere  (changed in place)
17:12:00  menu_close_return  -3 minecraft:red_dye  container -5 -60 -5 slot 1  to SPY_me slot 1
```

- Попутно подтверждена правка 2701143: остаток красителя при закрытии окна вернулся как
  `menu_close_return`.

### B6. Картография — ПРОЙДЕН, найден дефект D1

```
17:12:16  quick_move  -1 minecraft:map  SPY_me slot 3  to nowhere
17:12:16  quick_move  +1 minecraft:filled_map  SPY_me slot 3  from nowhere
17:12:29  cartography  -1 minecraft:paper  container -4 -60 -5 slot 1  to nowhere  (changed in place)
17:12:29  cartography  -1 minecraft:filled_map  container -4 -60 -5 slot 0  to nowhere  (changed in place)
17:12:29  cartography  +1 minecraft:filled_map  SPY_me cursor  from nowhere  (changed in place)
17:12:34  cartography  -1 minecraft:glass_pane  container -4 -60 -5 slot 1  to nowhere  (changed in place)
17:12:34  cartography  -1 minecraft:filled_map  container -4 -60 -5 slot 0  to nowhere  (changed in place)
17:12:34  cartography  +1 minecraft:filled_map  SPY_me cursor  from nowhere  (changed in place)
```

- Увеличение и блокировка — по транзакции на операцию.
- `/pp reconcile` — `matches`; `/pp verify recent` — 428 проводок, 0 разрывов.

**D1.** Заполнение пустой карты использованием в руке (17:12:16) не опознано как превращение: убыль
пустой карты и рождение заполненной записаны порознь, как необъяснённые. Причины для этого в словаре
нет. Та же категория, что подпись книги до правки 123351a.

**D2.** Необъяснённый конец в слотах игрока получает причину `quick_move` — запасную ветку `causeOf`
(`ContainerCapture.kt:322`). Так же легла выдача `/give` (16:57:48). В счётчике непокрытого
`quick_move=N` поэтому значит «необъяснённое у игрока», а не шифт-клик; раздел E читать с этим.

### B7. Стол зачарований — ПРОЙДЕН

```
17:14:57  enchant_apply  +1 minecraft:enchanted_book  container -3 -60 -5 slot 0  from nowhere  (changed in place)
17:14:57  enchant_apply  -1 minecraft:lapis_lazuli  container -3 -60 -5 slot 1  to nowhere  (changed in place)
17:14:57  enchant_apply  -1 minecraft:book  container -3 -60 -5 slot 0  to nowhere  (changed in place)
17:14:58  container_remove  +1 minecraft:enchanted_book  SPY_me cursor  from container -3 -60 -5 slot 0
```

- Книга и лазурит — под `enchant_apply` одной транзакцией; результат встаёт в слот стола и забирается
  обычным `container_remove`. `enchant_lapis_consume` нет — ожидаемо (§G).

### B8. Подпись книги — ПРОЙДЕН

```
17:15:13  book_sign  +1 minecraft:written_book  SPY_me slot 3  from nowhere  (changed in place)
17:15:13  book_sign  -1 minecraft:writable_book  SPY_me slot 3  to nowhere  (changed in place)
```

- Строка видна через `/pp lookup player:SPY_me`.
- `/pp reconcile` — `matches`; `/pp verify recent` — 462 проводки, 0 разрывов.

---

## C. Верстак: края

Ингредиенты выданы `/give` в 17:17:18 — легли как `quick_move … from nowhere` (D2).

### C1. Предпросмотр ничего не пишет — ПРОЙДЕН

Доски в сетку (17:17:24), результат не взят, доски обратно (17:17:40): только `container_add` и
`container_remove`, ни одной строки `craft_*`.

### C2. Остаток в сетке — ПРОЙДЕН

```
17:18:12  craft_result     +1 minecraft:cake    SPY_me cursor  from nowhere
17:18:12  craft_remainder  +1 minecraft:bucket  entity SPY_me slot 3  from nowhere
17:18:12  craft_remainder  +1 minecraft:bucket  entity SPY_me slot 2  from nowhere
17:18:12  craft_remainder  +1 minecraft:bucket  entity SPY_me slot 1  from nowhere
17:18:12  craft_consume    -1 minecraft:milk_bucket  entity SPY_me slot 1  to nowhere
          ... craft_consume на молоко, сахар, яйцо и пшеницу, всего девять строк ...
```

- Вёдра — `craft_remainder`, торт — `craft_result`, одной транзакцией (правка d8d0621 подтверждена).

### C3. Закрытие окна с непустой сеткой — ПРОЙДЕН

Две палки в сетку (17:18:25), окно закрыто: `menu_close_return` на каждую (17:18:28).

### C4. Сетка 2×2 в инвентаре — ПРОЙДЕН

```
17:18:36  craft_consume  -1 minecraft:oak_log  entity SPY_me slot 4  to nowhere
17:18:36  craft_result   +4 minecraft:oak_planks  SPY_me slot 31  from nowhere
17:18:37  craft_consume  -1 minecraft:oak_log  entity SPY_me slot 4  to nowhere
17:18:37  craft_result   +4 minecraft:oak_planks  SPY_me cursor  from nowhere
```

### C5. Сверка при открытой сетке — ПРОЙДЕН

Три палки в сетке верстака, окно открыто; `/pp reconcile SPY_me` с консоли — `matches the ledger`.
После закрытия (17:20:26) — три `menu_close_return`.

- `/pp verify recent` — 610 проводок, 0 разрывов; `/pp reconcile` — `matches`.

---

## D. Печь и варочная стойка

### D1, D2, D3 — ПРОЙДЕНЫ в своей части, найден дефект D3

Печь `-10 -60 -3`: 64 сырого железа, уголь, затем ведро лавы. Варочная стойка `-10 -60 -2`: огненный
порошок, три бутылки воды, адский нарост. Окна обоих станков всё время были открыты.

```
17:21:52  furnace_fuel_consume  -1 minecraft:coal  container -10 -60 -3 slot 1  to nowhere
17:22:02  smelt  -1 minecraft:raw_iron  container -10 -60 -3 slot 0  to nowhere  (changed in place)
17:22:02  smelt  +1 minecraft:iron_ingot  container -10 -60 -3 slot 2  from nowhere  (changed in place)
          ... по паре на каждый из шести слитков, каждые 10 с ...
17:23:12  furnace_fuel_consume    -1 minecraft:lava_bucket  container -10 -60 -3 slot 1  to nowhere
17:23:12  furnace_fuel_remainder  +1 minecraft:bucket       container -10 -60 -3 slot 1  from nowhere
17:23:56  brewing_fuel_consume  -1 minecraft:blaze_powder  container -10 -60 -2 slot 4  to nowhere
17:24:21  brewing_ingredient_consume  -1 minecraft:nether_wart  container -10 -60 -2 slot 3  to nowhere
17:24:21  brew  -1 minecraft:potion  container -10 -60 -2 slot 0  to nowhere  (changed in place)
17:24:21  brew  +1 minecraft:potion  container -10 -60 -2 slot 0  from nowhere  (changed in place)
          ... по паре на каждую из трёх бутылок ...
```

- D1: на каждый слиток ровно пара, списывается один предмет, а не входной стек (af83bbe подтверждён).
- D2: уголь и лава — `furnace_fuel_consume`, пустое ведро — `furnace_fuel_remainder`.
- D3: топливо, ингредиент и по паре `brew` на бутылку.
- `/pp reconcile` — `matches`; `/pp verify recent` — 742 проводки, 0 разрывов; две плоскости — 0.

**D3 (дефект).** Пока окно станка открыто, пересчёт игрока записывает работу станка второй раз.
Снимок окна включает слоты контейнера, станок меняет их без клика, и пересчёт, не найдя пары,
пишет разницу в никуда как выведенную:

```
17:21:53  container_remove  -1 minecraft:coal      container -10 -60 -3 slot 1  to nowhere
17:22:23  container_add     +1 minecraft:iron_ingot container -10 -60 -3 slot 2  from nowhere
17:22:23  container_remove  -1 minecraft:raw_iron  container -10 -60 -3 slot 0  to nowhere
17:22:53  container_remove  -2 minecraft:raw_iron  container -10 -60 -3 slot 0  to nowhere
17:23:44  container_remove  -1 minecraft:lava_bucket  container -10 -60 -3 slot 1  to nowhere
17:23:44  container_add     +1 minecraft:bucket    container -10 -60 -3 slot 1  from nowhere
17:23:44  container_remove  -3 minecraft:raw_iron  container -10 -60 -3 slot 0  to nowhere
17:24:00  container_remove  -1 minecraft:blaze_powder  container -10 -60 -2 slot 4  to nowhere
17:24:55  container_remove  -1 minecraft:potion  container -10 -60 -2 slot 0..2  to nowhere   (×3)
17:24:55  container_add     +1 minecraft:potion  container -10 -60 -2 slot 0..2  from nowhere (×3)
17:24:55  container_remove  -1 minecraft:nether_wart  container -10 -60 -2 slot 3  to nowhere
```

Сырое железо слота 0 по журналу: +64 − 6 (smelt) − 3 (дубль) − 58 (игроку) = −3, в натуре 0. Ни одна
самопроверка этого не видит: инвариант смотрит пары внутри транзакции, сверка плоскостей — позиции
блоков, а не слоты, сверка с натурой — только игрока. Будущий балансовый детект читал бы это как дюп.
Та же дыра должна срабатывать для воронки, наполняющей открытый сундук, и для второго игрока у того же
сундука — проверить после правки.

---

## E. Критерий 2: класс 0x40 не даёт `INFERRED` — НЕ ПРОВЕРЕН, заблокирован дефектом D4

`stop` в 17:26:36. Строки `movements the capture could not explain` в логе нет:

```
17:26:36  [PfauProtect] Disabling PfauProtect v1.0.0
17:26:36  [PfauProtect] the ledger lost entries while shutting down
java.lang.NullPointerException: Cannot read field "captureTreeGeneration" because the return value of
  "net.minecraft.world.level.Level.getCurrentWorldData()" is null
    at net.minecraft.world.level.Level.getBlockState(Level.java:1406)
    at org.bukkit.craftbukkit.block.CraftBlock.getState(CraftBlock.java:316)
    at net.minecraft.world.level.block.entity.BlockEntity.getOwner(BlockEntity.java:388)
    at org.bukkit.craftbukkit.inventory.CraftInventory.getHolder(CraftInventory.java:549)
    at io.pfaumc.pfauprotect.ContainerCaptureKt.containerHolders(ContainerCapture.kt:202)
    at io.pfaumc.pfauprotect.ContainerCaptureListener.topHolders(ContainerCapture.kt:871)
    at io.pfaumc.pfauprotect.ContainerCaptureListener.snapshot(ContainerCapture.kt:811)
    at io.pfaumc.pfauprotect.ContainerCaptureListener.recomputeAll(ContainerCapture.kt:379)
    at io.pfaumc.pfauprotect.PfauProtectPlugin.onDisable(PfauProtectPlugin.kt:221)
```

**D4 (дефект).** На выключении `recomputeAll` пересчитывает открытые окна, и для окна блока (у игрока
было открыто окно станка) спрашивает владельца-блок. На потоке выключения Folia данных мира нет,
`getHolder` падает. Исключение обрывает весь `try` в `onDisable`: не сливаются проводки механизмов
последнего тика, не дописывается очередь, не печатается счётчик непокрытого. `finally` базы закрывает,
и после рестарта самопроверки чистые (747 проводок, 0 разрывов), но потеря строк возможна и счётчик
этого сеанса утерян.

По строкам журнала, без счётчика: причин класса 0x40 среди выведенного не видно; выведенное —
D1 (карта), D2 (выдача `/give`), D3 (двойная запись станков). Пересдать после правок D3 и D4.

## F. Критерий 3: сверка не регрессирует — ПРОЙДЕН в проверенной части

- `/pp reconcile` после каждого раздела A–D и при открытой сетке — `matches`.
- Плановая сверка по всем игрокам (30 мин) за сеанс 17:00–17:26 не наступила — не проверена.
- Оговорка: сверка смотрит только игрока, баланс станков, испорченный D3, она не видит.

---

## R. Хвосты v3 и перепроверки правок

### R2. Кактус (C14) — НЕ ПОДТВЕРЖДЁН, подозрение на дефект D5

Песок `-7 -60 1`, на нём два кактуса (`-7 -59 1`, `-7 -58 1`). Песок сломан лопатой в 17:32:06.

```
17:32:06  blk_player_break  minecraft:sand -> minecraft:air  block -7 -60 1  by SPY_me
17:32:06  blk_fade  minecraft:cactus[age=0] -> minecraft:air  block -7 -59 1  by SPY_me (worked out)
17:32:06  blk_fade  -1 minecraft:cactus  block -7 -59 1  to nowhere  by SPY_me
17:32:06  blk_fade  minecraft:cactus[age=0] -> minecraft:air  block -7 -58 1  by SPY_me (worked out)
17:32:06  blk_fade  -1 minecraft:cactus  block -7 -58 1  to nowhere  by SPY_me
```

- Позиционная часть верна: оба кактуса — `blk_fade` с именем сломавшего, выведенным.
- Рождение выпавших кактусов по позиции не видно (отдельная транзакция на сущности). Счётчик на
  остановке 17:32:55: `quick_move=3 item_spawn=3`. `quick_move=3` — три выдачи `/give` (D2);
  `item_spawn=3` — три необъяснённых рождения при двух выпавших кактусах и песке, у которого своя строка.

**D5 (подозрение).** Похоже, заметка о дропе кактуса не доживает до самого дропа. Кактус ломается не в
тике физики, а в следующем тике региона, по запланированному тику блока; заметки чистит глобальный тик
Folia (`origins.sweep()` каждый тик, два прохода), который идёт параллельно тику региона. Нужна
диагностика с отладочным логом: какие рождения не нашли заметку и почему третье.

### R3. Поршни (F6, F7, F8) — ПРОЙДЕН

- **F7** — липкий поршень `0 -60 -2` тянет пустоту, шесть циклов 17:39:17–17:39:20: на каждое
  выдвижение и втягивание — ровно по строке на основание и голову, дублей нет.
- **F6** — тот же поршень с камнем: выдвижение 17:39:26 (`-3 → -4`), втягивание 17:39:28–29: голова
  `-3` → воздух, затем камень `-4 → -3`, предметная сторона — перенос `block → block`.
- **F8** — липкий поршень `1 -60 -2`, зазор, обсидиан `1 -60 -4` (первая попытка с обсидианом вплотную
  ничего не дала: поршень не выдвигается — ошибка в задании, не в плагине):

```
17:42:05  blk_piston_extend   minecraft:air -> minecraft:piston_head[facing=north,short=false,type=sticky]  block 1 -60 -3
17:42:05  blk_piston_retract  minecraft:piston_head[facing=north,short=false,type=sticky] -> minecraft:air  block 1 -60 -3
17:42:06  blk_piston_extend   minecraft:air -> minecraft:piston_head[facing=north,short=false,type=sticky]  block 1 -60 -3
17:42:06  blk_piston_retract  minecraft:piston_head[facing=north,short=false,type=sticky] -> minecraft:air  block 1 -60 -3
```

- Тихое втягивание теперь пишется (ce19018), а там, где событие есть (F6, F7), проверка «уже записано в
  этом тике» не даёт второй строки.
- `/pp verify recent` — 997 проводок, 0 разрывов; две плоскости — 45 позиций, 0 разрывов.

### R4. Шалкер, сломанный не рукой (F3) — поршень ПРОЙДЕН, взрыв — найдены дефекты D6 и D7

**Поршень.** Новый шалкер `2 -60 -3`, пять алмазов, поршень:

```
17:48:01  blk_piston_extend  minecraft:shulker_box[facing=up] -> minecraft:air  block 2 -60 -3  by nobody named  +contents
17:48:01  container_break_pack  -5 minecraft:diamond  container 2 -60 -3 slot 13  to inside 17c2e9cc-… at 13
17:48:01  blk_piston_extend  -1 minecraft:shulker_box  block 2 -60 -3  to nowhere
17:48:05  container_place_unpack  +5 minecraft:diamond  container 2 -60 -5 slot 13  from inside 17c2e9cc-… at 13  by SPY_me
```

Содержимое упаковано под имя позиции и распаковано из того же имени — правка ebe7fb8 работает.

**Взрыв.** Два шалкера рядом (`15 -60 -8` — пять алмазов, `16 -60 -7` — восемь золотых), динамит,
подожжённый огнивом:

```
17:48:43  blk_tnt  minecraft:shulker_box[facing=up] -> minecraft:air  block 15 -60 -8  by SPY_me  +contents
17:48:43  container_break_pack  -5 minecraft:diamond  container 15 -60 -8 slot 13  to inside d1342fef-… at 13  by SPY_me
17:48:43  container_break_pack  -8 minecraft:gold_ingot  container 16 -60 -7 slot 13  to inside 178323aa-… at 13  by SPY_me
17:49:05  container_place_unpack  +8 minecraft:gold_ingot  container 2 -60 -7 slot 13  from inside 178323aa-… at 13  by SPY_me
17:49:05  container_place_unpack  +5 minecraft:diamond  container 2 -60 -6 slot 13  from inside b6aebb98-… at 13  by SPY_me
```

- Золото: упаковано и распаковано под одним именем.
- Взрыв назван по игроку как факт (`by SPY_me` без «выведено»): динамит подожжён огнивом.

**D6 (дефект).** Шалкер с алмазами после взрыва не получил имя, под которым упаковано содержимое
(`d1342fef` при упаковке, `b6aebb98` при распаковке). При подборе ему выдали новое имя: пять алмазов
родились под `b6aebb98` из ничего, а под `d1342fef` навсегда остались пять, которых нет. Отличие от
удачного случая: этот шалкер был повторно использованным — до того его ломали рукой, и тег владельца на
нём уже стоял. Вероятно, сверка выпавшей коробки с ожидаемой (`SpawnOrigins.ownerFor`, сравнение по
всем компонентам) расходится из-за старого тега в одном из двух стаков. Проверить отладочным логом.

**D7 (дефект).** Взрыв сводит выпавшее из разных блоков в общие стопки (`ServerExplosion.addOrAppendStack`)
и роняет каждую там, где стоял первый блок её вида. Заметки о дропе ставятся по блоку и сопоставляются
только в радиусе 2 блоков (`SPAWN_REACH`, `BlockMechanisms.kt:47`), поэтому часть сведённой стопки
остаётся необъяснённой. Счётчик на остановке 17:51:16: `item_spawn=25 quick_move=14` — основная часть
`item_spawn` приходится на дёрн и землю из воронки. `quick_move` — около 11 выдач `/give` (D2), около 3
не объяснены.

- Две плоскости после взрыва: `91 overdrawn` — природные блоки воронки, которым никто не начислял;
  ожидаемо (раздел J TESTING-v3).
- `/pp reconcile` — `matches`; инвариант — 1277 проводок, 0 разрывов.

### R5. Предмет, уничтоженный на земле — ПРОЙДЕН, кроме мешка из руки; найдены D8–D10

Строки уничтожения стоят на сущности-предмете, а ни одна команда их не показывает: прочитаны офлайн
с копии журнала после остановки (утилита в scratchpad: `RocksItemLog.holderEntries`).

```
меч в лаву
20:56:03  DROP_FROM_HAND     +1  ItemEntityRef(96ee6eb4…)  other=PlayerInv(SPY_me, 3)
20:56:04  ITEM_DESTROY_FIRE  -1  ItemEntityRef(96ee6eb4…)  other=Void  actor=SPY_me  FACT
мешок (4 золотых, 3 алмаза) в лаву
20:56:27  ITEM_DESTROY_FIRE     -1  ItemEntityRef(68f1d716…)  other=Void  actor=SPY_me  FACT
20:56:27  CONTAINER_BREAK_DROP  -4  Nested(d01f5916…, 0)  other=ItemEntityRef(b12e566b…)  actor=SPY_me  FACT
20:56:27  CONTAINER_BREAK_DROP  -3  Nested(d01f5916…, 1)  other=ItemEntityRef(f82d818a…)  actor=SPY_me  FACT
мешок (4 золотых, 3 алмаза) в пустоту
20:56:58  ITEM_DESTROY_VOID  -1  ItemEntityRef(eb144f0b…)  other=Void  actor=SPY_me  FACT
20:56:58  ITEM_DESTROY_VOID  -3  Nested(a4063bca…, 1)  other=Void  actor=SPY_me  FACT
20:56:58  ITEM_DESTROY_VOID  -4  Nested(a4063bca…, 0)  other=Void  actor=SPY_me  FACT
мешок из руки ПКМ
20:57:16  ITEM_SPAWN  +20  ItemEntityRef(475ccb73…)  other=Void  INFERRED
```

- Бросивший стоит в строке уничтожения как факт; содержимое мешка высыпается под `container_break_drop`
  или уходит с мешком в пустоту — правка 375fd78 подтверждена.
- Счётчик на остановке 17:59:23: `quick_move=14 item_spawn=8 cursor_place=3 cursor_take=1
  bundle_extract=1`.

**Наблюдение: строки сущности-предмета недоступны командам.** Уничтожение предмета, его бросившего и
всё, что с ним было между броском и концом, видно только офлайн. Кандидат на `/pp lookup item:<uuid>` или
на показ продолжения цепочки в выдаче `player:`.

**D8.** Первое присвоение имени контейнеру-предмету (мешок, впервые попавший в снимок) меняет его форму —
тег владельца входит в форму — и пересчёт видит `−1 мешок` и `+1 мешок` из ниоткуда
(`cursor_place`, `cursor_take`, `quick_move`, все выведенные). Баланс не страдает, счётчик шумит.

**D9.** Мешок, опустошённый правой кнопкой из руки, не покрыт: выпавшее рождается как `item_spawn`
(выведенное). Вкладывание 20 слитков в ещё не названный мешок кликом по стопке записалось как
`quick_move −20 … to nowhere` — пара в `Nested` не нашлась (вероятно, то же присвоение имени, что в D8).

**D10 (непокрытый путь, не регрессия).** Слив ведра лавы не пишется ни одной плоскостью: на блоке строки
нет, у игрока — выведенные `−1 lava_bucket` и `+1 bucket` (`quick_move`). Обработчик
`onBucketEmpty` (`BlockDestruction.kt:672`) оставляет только заметку атрибуции; причины `BUCKET_EMPTY`
и `BUCKET_FILL` в словаре есть, писателя нет.

### R6. Атрибуция

#### Чешуйница (f953810) — ПРОЙДЕН

Заражённый камень сломан рукой (18:05:30), вылезшую чешуйницу ударили один раз — она разбудила сородичей.

```
18:05:30  blk_player_break  minecraft:infested_stone -> minecraft:air  block -4 -60 -12  by SPY_me
18:05:31  blk_silverfish  minecraft:infested_stone -> minecraft:air  block -4 -60 -11  by SPY_me (worked out)
18:06:01  blk_silverfish  minecraft:infested_stone -> minecraft:air  block -2 -58 -12  by SPY_me (worked out)
          ... ещё пять заражённых блоков в тот же тик, все by SPY_me (worked out) ...
18:06:01  blk_silverfish  minecraft:stone -> minecraft:infested_stone  block -3 -60 -11  by SPY_me (worked out)
18:06:01  blk_silverfish  minecraft:stone -> minecraft:infested_stone  block -3 -60 -10  by SPY_me (worked out)
18:06:08  blk_silverfish  minecraft:infested_stone -> minecraft:air  block -3 -60 -11  by SPY_me (worked out)
18:06:11  blk_silverfish  minecraft:infested_stone -> minecraft:air  block -3 -60 -10  by SPY_me (worked out)
```

- Происхождение первой чешуйницы найдено по позиции сломанного блока, и вся цепочка — пробуждение,
  уход в камень, выход наружу — названа выведенно. До правки все строки были бы `by nobody named`.
- Попутно: в плоском мире на лёгкой сложности спавнятся слизни и мешают прогону — дальше мирная.

#### Распад листвы (261d571) — ПРОЙДЕН

Берёза у `-33 -60 -8`, ствол срублен целиком, случайные тики ускорены (`random_tick_speed 60`).

```
18:09:02  blk_leaf_decay  minecraft:birch_leaves[...] -> minecraft:air  block -35 -57 -6  by SPY_me (worked out)
          ... всего 53 строки распада, 18:09:02–18:09:12 ...
18:09:12  blk_leaf_decay  minecraft:birch_leaves[...] -> minecraft:air  block -34 -56 -10  by SPY_me (worked out)
```

- 53 из 53 строк названы срубившим, выведенно; ни одной `by nobody named`.
- Два больших дуба, выросших из дубовых саженцев, до конца не срублены и в проверку не вошли.

#### Иссушитель (90e0cf4) — НЕ ПРОВЕРЕН

Пропущен по решению: нужен отдельный стенд вдали от станков и цели для черепов. Логика проверена
автотестом `a skull is asked about as the wither that fired it`.

### R7. Чтение

#### Кратер (4799e57) — ПРОЙДЕН

`/pp lookup radius:1 limit:5` в середине воронки R4 (`15 -61 -8`, все строки одного взрыва — одно время):
пять строк из куба, среди них сама позиция. В прогоне v3 тот же запрос отвечал углом чанка и сообщением
«чтение оборвалось».

#### Текст табличек (e33ab7a) — ПРОЙДЕН в доступной части, найден дефект D11

```
18:10:22  blk_player_place  minecraft:air -> minecraft:oak_sign[rotation=3,waterlogged=false]  block -34 -60 -9  by SPY_me  +contents
18:11:39  blk_player_break  minecraft:oak_sign[rotation=3,waterlogged=false] -> minecraft:air  block -34 -60 -9  by SPY_me  text "вторая"
```

- Строка слома показывает текст таблички.

**D11 (непокрытый путь).** Ввод и правка текста таблички не пишутся: обработчика `SignChangeEvent`
нет, причины в словаре нет. Нагрузка снимается только при установке (табличка ещё пуста — отсюда
`+contents`) и при сломе. Первый текст «первая» в журнал не попал нигде: табличку можно исписать и
переписать без следа, пока её не сломали.

#### `player:` (7d664ae) — ПРОЙДЕН

Использовался весь прогон (A1, B8, C1–C5, R5): инвентарь, курсор и сетка крафта игрока читаются, чужие
строки не попадают.

### R1. Невыполненные пункты TESTING-v3 — НЕ ВЫПОЛНЕНЫ в этом прогоне

A4, A6, B7, C3, C13, E4, E11, G2, G3, G4, G6, H2, I2 — перенесены в следующий прогон: часть требует
перезапуска с иными настройками (B7), ботов (E-пункты) или отдельного стенда (I2).

---

## Итог прогона

| Раздел | Итог |
| --- | --- |
| A — цепочка через крафт | пройден |
| B — станки | пройден; D1, D2 |
| C — края верстака | пройден |
| D — печь и варка | пройден в своей части; D3 |
| E — класс 0x40 без `INFERRED` | не проверен: D4 |
| F — сверка | пройден, кроме плановой сверки по таймеру |
| R2 кактус | не подтверждён: D5 |
| R3 поршни | пройден |
| R4 шалкер | поршень пройден; взрыв — D6, D7 |
| R5 уничтожение предмета | пройден, кроме мешка из руки; D8, D9, D10 |
| R6 атрибуция | чешуйница и листва пройдены; иссушитель не проверен |
| R7 чтение | пройден; D11 |
| R1 хвосты v3 | не выполнены |

Ни одного `ledger gap` и ни одного разрыва инварианта за весь прогон. Один `SEVERE`-уровня сбой — D4.

| Дефект | Суть | Тяжесть |
| --- | --- | --- |
| D1 | заполнение пустой карты в руке — не превращение, а необъяснённые убыль и рождение | средняя |
| D2 | необъяснённый конец у игрока получает причину `quick_move` | низкая, но путает счётчик |
| D3 | открытое окно станка — пересчёт игрока записывает работу станка второй раз | **высокая**: баланс контейнера уходит в минус, самопроверки не видят |
| D4 | окно блока открыто при выключении — `onDisable` падает, хвост очереди и счётчик теряются | **высокая** |
| D5 | рождение выпавшего кактуса не объясняется (подозрение на гонку заметки со сбором) | средняя |
| D6 | повторно использованный шалкер после взрыва теряет имя содержимого | средняя |
| D7 | сведённые взрывом стопки объясняются только в радиусе 2 блоков | средняя |
| D8 | первое присвоение имени мешку даёт выведенные `−1/+1` | низкая |
| D9 | мешок, опустошённый из руки, не покрыт | средняя |
| D10 | слив ведра не пишется ни одной плоскостью | непокрытый путь |
| D11 | ввод и правка текста таблички не пишутся | непокрытый путь |
| D12 | слитки, взятые курсором из результата печи, — необъяснённое рождение на курсоре, а слот печи не списан | **высокая**: баланс печи уходит в плюс |

---

## Перепроверка правок D1–D7

Дата: 2026-09-29, та же сборка Canvas, плагин на коммите 4277792. Одна сессия сервера, закончена
остановкой при открытом окне печи. Строки на выброшенных предметах сняты офлайн с копии `ledger/`.

Счётчик сессии: `direct_new_item=18 item_spawn=17`. `item_spawn=17` — ровно 17 выдач `/give`:
выдача рождает поддельный предмет в мире на одну штуку, для анимации подбора. 17 из 18
`direct_new_item` — те же выдачи; восемнадцатая — D12. Ни одной причины класса 0x40 в счётчике.

| Дефект | Итог | Что видно |
| --- | --- | --- |
| D1 | исправлен | заполнение карты — одно превращение `map_fill`: −1 `map`, +1 `filled_map`, changed in place |
| D2 | исправлен | необъяснённое рождение на курсоре (D12) записано `direct_new_item`, а не `quick_move` |
| D3 | исправлен | пересчёт на клике по результату увидел сырое железо 8→4 и уголь 4→3 — работу печи; второй записи нет, только `smelt` и `furnace_fuel_consume` |
| D4 | исправлен | остановка при открытой печи — `onDisable` прошёл, счётчик напечатан, `SEVERE` нет |
| D5 | исправлен | кактус в три блока на песке: все три `blk_fade by SPY_me (worked out)`, в счётчике нет `item_spawn` от дропа |
| D6 | исправлен | взрыв упаковал 5 алмазов в то же вложение `17c2e9cc`, после подбора распакованы оттуда же |
| D7 | исправлен | воронка от TNT: ни одного `item_spawn` от дропа, земля и шалкер подобраны по `pickup` |
| E | пройден в проверенной части | верстак — `craft_consume`/`craft_result`, наковальня — `anvil_combine`, стол — одно `enchant_apply` на книгу и лазурит; `INFERRED` только у выдач и D12 |

Мелочь: цветок кактуса на вершине записан `by nobody named` — цепочка опор дотягивается через два
блока кактуса, но не до третьего уровня.

### D12. Взятие из результата печи курсором

Положили 8 сырого железа и уголь, печь сделала 4 слитка (`smelt` в слот 2), слитки взяли курсором.
Журнал: `direct_new_item +4 iron_ingot` на курсор, списания из `container -10 -60 -3 slot 2` нет.
По журналу печь держит 4 слитка, которых в ней нет.

Причина: пересчёт сравнивает окно с базой, снятой на прошлом пересчёте (`ContainerCapture.kt:829-834`).
Плавка меняет слот печи своим слушателем и базу не трогает, поэтому к клику слот 2 в базе пуст и после
клика пуст, а на курсоре +4 ниоткуда. Это было и до D3: D3 убрал случай, когда пересчёт успевал пройти
между плавками.
