package io.pfaumc.pfauprotect

import org.bukkit.command.CommandSender

/** Says a line in the language config.yml names. */
fun CommandSender.say(text: String) = sendMessage(Texts.translate(text))

/**
 * What the plugin says, in Russian when config.yml asks for it. Every message is written in English where
 * it is made, and translated here as a whole line on its way out: the sentences by pattern, the rows of a
 * lookup word by word. A line no rule knows goes out in English, which is a missing rule and no error.
 */
object Texts {
    fun translate(text: String): String {
        if (Settings.language != "ru") return text
        for ((pattern, russian) in SENTENCES) if (pattern.matches(text)) return words(pattern.replace(text, russian))
        return words(text)
    }

    // What is left once a sentence is in Russian: names of places and of rows, which come in from many
    // sentences and every lookup line.
    private fun words(text: String): String {
        var out = text
        for ((pattern, russian) in WORDS) out = pattern.replace(out, russian)
        return out
    }

    private fun r(pattern: String) = Regex(pattern)

    private val SENTENCES: List<Pair<Regex, String>> = listOf(
        // Rollback
        r("A rollback needs time: how far back to undo, for example time:1h\\.") to "Откату нужно time: насколько назад отменять, например time:1h.",
        r("player: reads what a player carries and has no place to roll back; use user:\\.") to "player: читает то, что игрок несёт, и откатывать там нечего; используй user:.",
        r("A rollback needs radius: the blocks around you it covers, or global with user:\\.") to "Откату нужен radius: сколько блоков вокруг тебя он охватывает, или 'global' вместе с user:.",
        r("radius:global undoes what named players did; give user: as well\\.") to "radius:global отменяет сделанное названными игроками; укажи и user:.",
        r("\\[apply\\]") to "[применить]",
        r("\\[cancel\\]") to "[отменить]",
        r("Rollback preview dropped\\.") to "Предпросмотр отката сброшен.",
        r("  you see the blocks as they would stand; nothing changes before /pp apply\\.") to "  вы видите блоки такими, какими они станут; до /pp apply в мире ничего не меняется.",
        r("No rollback preview to drop\\.") to "Нет предпросмотра отката.",
        r("Stopping the rollback that runs now: the chunks it has not reached yet stay as they are\\.") to
            "Останавливаю идущий откат: чанки, до которых он не дошёл, останутся как есть.",
        r("Nothing to apply: preview a rollback with /pp rollback first\\.") to "Нечего применять: сначала предпросмотр через /pp rollback.",
        r("Another rollback is still running; apply again once it has reported\\.") to "Другой откат ещё идёт; примени снова, когда он отчитается.",
        r("Rollback refused: (.*)\\.") to "Откат отклонён: $1.",
        r("The rollback failed; the server log has the details\\.") to "Откат не удался; подробности в логе сервера.",
        r("Taking back what the rollback gave back failed; the server log has the details\\.") to "Изъятие возвращённого откатом не удалось; подробности в логе сервера.",
        r("Nothing to roll back: (.*)\\.") to "Откатывать нечего: $1.",
        r("Rollback preview for (.*?): (\\d+) blocks would change, (\\d+) already as they were, (\\d+) stopped by a later change; (\\d+) slot postings to give back; (\\d+) entities to bring back, (\\d+) to take away, (\\d+) to change back, (\\d+) already as they were \\((.*)\\)\\.") to
            "Предпросмотр отката: $1. Блоков изменится: $2, уже как были: $3, остановлено поздней переменой: $4; слотов вернуть: $5; сущностей вернуть: $6, убрать: $7, изменить обратно: $8, уже как были: $9 ($10).",
        r("Rolled back (.*?): (\\d+) blocks put back, (\\d+) already as they were, (\\d+) stopped by a later change; (\\d+) slot postings given back; (\\d+) entities brought back, (\\d+) taken away, (\\d+) changed back, (\\d+) already as they were\\.") to
            "Откачено: $1. Блоков возвращено: $2, уже как были: $3, остановлено поздней переменой: $4; слотов возвращено: $5; сущностей возвращено: $6, убрано: $7, изменено обратно: $8, уже как были: $9.",
        r("  /pp apply within 5 minutes runs it, /pp cancel drops it(.*)\\.") to "  /pp apply в течение 5 минут применит, /pp cancel сбросит$1.",
        r("  would take back from (.*)\\.") to "  будет изъято: $1.",
        r("  taking back from (.*):") to "  изымаю: $1:",
        r("  would give back to (\\S+) what they lost: (.*)") to "  будет возвращено игроку $1 потерянное: $2",
        r("  giving back to (\\S+) what they lost: (.*)") to "  возвращаю игроку $1 потерянное: $2",
        r("  would swap back (.*) from (\\S+) for (.*)\\.") to "  будет обменено обратно у $2: $1 на $3.",
        r("  swapping back (.*) from (\\S+) for (.*):") to "  обмениваю обратно у $2: $1 на $3:",
        r("  took back (\\d+) (\\S+) from the container at (.*) it was put into\\.") to "  изъято $1 $2 из контейнера $3, куда их положили.",
        r("  took back (\\d+) (\\S+) lying in the world\\.") to "  изъято $1 $2, лежавших в мире.",
        r("  took back (\\d+) (\\S+) from (\\S+)\\.") to "  изъято $1 $2 у $3.",
        r("  (\\S+) held only (\\d+) of (\\d+) (\\S+); the rest is beyond reach\\.") to "  у $1 было только $2 из $3 $4; остальное недосягаемо.",
        r("  (\\S+) held only (\\d+) of (\\d+) (\\S+); the rest is looked for where they put it and what they made of it\\.") to
            "  у $1 было только $2 из $3 $4; остальное ищется там, куда он это положил, и в том, что он из этого сделал.",
        r("  (\\d+) (\\S+) are beyond reach\\.") to "  $1 $2 недосягаемы.",
        r("  (\\d+) (\\S+) were lying in the world and are gone since\\.") to "  $1 $2 лежали в мире и с тех пор пропали.",
        r("  gave back (\\d+) (\\S+) to (\\S+); (\\d+) more at their next join\\.") to "  возвращено $1 $2 игроку $3; ещё $4 при следующем входе.",
        r("  gave back (\\d+) (\\S+) to (\\S+)\\.") to "  возвращено $1 $2 игроку $3.",
        r("  (\\d+) entities to change back are gone since\\.") to "  сущностей, которые надо было изменить обратно, больше нет: $1.",
        r("  (\\d+) slot postings found no room or nothing left to take out\\.") to "  слотов без места или без того, что можно забрать: $1.",
        r("  (\\d+) positions failed; the server log has the details\\.") to "  позиций с ошибкой: $1; подробности в логе сервера.",
        r("  stopped by /pp cancel: (\\d+) positions in chunks it had not reached are left as they were\\.") to
            "  остановлено через /pp cancel: позиций в нетронутых чанках осталось как было: $1.",
        r("  the window may be shorter than a full rollback needs: (\\d+) of these positions stood as the same player had left them when it opened, and go back to that; a longer time: reaches further\\.") to
            "  окно может быть короче, чем нужно: позиций, которые на начало окна стояли так, как их оставил тот же игрок, и вернутся к этому: $1; больший time: возьмёт дальше.",
        // Lookup
        r("The lookup failed; the server log has the details\\.") to "Поиск не удался; подробности в логе сервера.",
        r("A world-wide lookup needs a player, user:<name>, or a radius\\.") to "Поиску по всему миру нужен игрок user:<имя> или радиус.",
        r("This world's block log is not open\\.") to "Журнал блоков этого мира не открыт.",
        r("Unknown player: (.*)") to "Неизвестный игрок: $1",
        r("Unknown world: (.*)") to "Неизвестный мир: $1",
        // Inspector, purge, status, self-checks, reconcile
        r("Only a player can use the inspector\\.") to "Инспектором может пользоваться только игрок.",
        r("Purge refused: give an age of at least a day, for example 90d\\.") to "Очистка отклонена: укажи возраст не меньше дня, например 90d.",
        r("Purge refused: a rollback is running\\.") to "Очистка отклонена: идёт откат.",
        r("Purging everything older than (\\S+); this walks the whole journal\\.") to "Удаляю всё старше $1; это проход по всему журналу.",
        r("Counting what a purge of everything older than (\\S+) would delete\\.") to "Считаю, что удалит очистка всего старше $1.",
        r("Purged (\\d+) item rows, (\\d+) block rows, (\\d+) entity rows; (\\d+) opening balances written\\.") to
            "Удалено строк: предметов $1, блоков $2, сущностей $3; записано начальных остатков: $4.",
        r("A purge would delete (\\d+) item rows, (\\d+) block rows, (\\d+) entity rows; (\\d+) opening balances to write\\. /pp purge (\\S+) confirm runs it\\.") to
            "Очистка удалит строк: предметов $1, блоков $2, сущностей $3; запишет начальных остатков: $4. /pp purge $5 confirm запустит её.",
        r("The purge failed; the server log has the details\\.") to "Очистка не удалась; подробности в логе сервера.",
        r("  ledger (.*), (\\d+) transactions waiting to be written") to "  журнал предметов $1, ждут записи транзакций: $2",
        r("  (\\S+): not recorded \\(config\\.yml\\)") to "  $1: не записывается (config.yml)",
        r("  (\\S+): (.*), (\\d+) changes waiting to be written") to "  $1: $2, ждут записи изменений: $3",
        r("  unexplained since the last report: nothing") to "  необъяснённое с прошлого отчёта: ничего",
        r("  unexplained since the last report: (.*)") to "  необъяснённое с прошлого отчёта: $1",
        r("  rollback: none running") to "  откат: не идёт",
        r("  rollback: one running for (\\d+) s") to "  откат: идёт уже $1 с",
        r("Running both self-checks to the end; this reads the whole journal\\.") to "Запускаю обе самопроверки до конца; это читает весь журнал.",
        r("The self-checks failed; the server log has the details\\.") to "Самопроверки не удались; подробности в логе сервера.",
        r("Transaction invariant: (\\d+) entries, (\\d+) gaps, (\\d+) unreadable\\.") to "Инвариант транзакций: записей $1, разрывов $2, нечитаемых $3.",
        r("  stopped on the round limit; run it again to cover the rest\\.") to "  остановлено на пределе раундов; запусти ещё раз, чтобы пройти остальное.",
        r("Name the player to reconcile\\.") to "Назови игрока для сверки.",
        r("(\\S+) matches the ledger; nothing differs\\.") to "$1 совпадает с журналом; расхождений нет.",
        r("(\\S+) differs from the ledger on (\\d+) forms:") to "$1 расходится с журналом по предметам: $2",
        r("'(.*)' is not a parameter, expected one of (.*)") to "'$1' — не параметр; ожидается один из: $2",
        r("unknown parameter '(.*)', expected one of (.*)") to "неизвестный параметр '$1'; ожидается один из: $2",
        r("'(.*)' has no value") to "у '$1' нет значения",
        r("'(.*)' is not a time span, expected something like 30m, 2h or 1d6h") to "'$1' — не промежуток времени; ожидается вроде 30m, 2h или 1d6h",
        r("'(.*)' is not a radius, expected 0 to (\\d+) blocks or 'global'") to "'$1' — не радиус; ожидается от 0 до $2 блоков или 'global'",
        r("'(.*)' is not an action, expected one of (.*)") to "'$1' — не действие; ожидается одно из: $2",
        r("'(.*)' is not a page number") to "'$1' — не номер страницы",
        r("'(.*)' is neither yes nor no") to "'$1' — ни 'yes', ни 'no'",
        r("'(.*)' is not an amount, expected 5, >=5, <10 or 5-10") to "'$1' — не количество; ожидается 5, >=5, <10 или 5-10",
        r("'(.*)' is not an event, click a lookup line to fill one in") to "'$1' — не событие; нажми на строку поиска, чтобы подставить его",
        r("unknown flag '(.*)', expected #count, #sum or #all") to "неизвестный флаг '$1'; ожидается #count, #sum или #all",
        r("'(.*)' is not a position, expected x,y,z") to "'$1' — не позиция; ожидается x,y,z",
        r("'(.*)' is not a row count between 1 and (\\d+)") to "'$1' — не число строк от 1 до $2",
        r("  \\.\\.\\. and (\\d+) more") to "  ... и ещё $1",
        r("Two planes: (\\d+) positions compared, (\\d+) gaps, (\\d+) overdrawn, (\\d+) the block plane never recorded, (\\d+) too recent to judge, (\\d+) unreadable\\.") to "Две плоскости: сравнено позиций $1, разрывов $2, перерасходов $3, не записано плоскостью блоков $4, слишком свежих $5, нечитаемых $6.",
        r("  gap in (\\S+) at (-?\\d+) (-?\\d+) (-?\\d+): (\\S+) stands there, the item plane holds (-?\\d+) confirmed and (-?\\d+) inferred") to "  разрыв в $1 на $2 $3 $4: стоит $5, в плоскости предметов подтверждённых $6 и выведенных $7",
        r("  (\\d+) positions were touched too recently; 'verify recent' judges them too\\.") to "  позиций, тронутых слишком недавно: $1; их проверяет и 'verify recent'.",
        r("Anything held before the ledger was opened differs by exactly that much for ever; a difference that stays put is that constant rather than a leak\\.") to "Всё, что было до открытия журнала, навсегда расходится ровно на эту величину; неизменная разница — это она, а не утечка.",
        r("The ledger is not open\\.") to "Журнал не открыт.",
        r("Nothing said or run matches\\.") to "Ничего сказанного или выполненного не найдено.",
        r("Last (\\d+) lines said and run:") to "Последние строки чата и команд ($1):",
    )

    private val WORDS: List<Pair<Regex, String>> = listOf(
        r("  by nobody named") to "  никем",
        r("  chat  ") to "  чат  ",
        r("  command  ") to "  команда  ",
        r("  join  ") to "  вход  ",
        r("  quit  ") to "  выход  ",
        r(" \\(worked out\\)") to " (вычислено)",
        r("(\\S+) was nearby") to "рядом был $1",
        r("  by ") to "  — ",
        r("died of ") to "умер: ",
        r("turned \\(") to "превращён (",
        r("  via ") to "  через ",
        r("\\((\\d+) items fell out\\)") to "(выпало предметов: $1)",
        r("dropped (\\d+) items") to "выронено предметов: $1",
        r("\\(rolled back\\)") to "(откачено)",
        r("\\(changed in place\\)") to "(изменён на месте)",
        r("\\(other half\\)") to "(вторая половина)",
        r("\\+contents") to "+содержимое",
        r("  text ") to "  текст ",
        r("  gone  ") to "  исчез  ",
        r("  brought in  ") to "  появился  ",
        r("  changed  ") to "  изменён  ",
        r("  led away  ") to "  уведён  ",
        r("  died: ") to "  погиб: ",
        // Before " slot ", which would take a word out of each.
        r("block rows, ") to "строк блоков, ",
        r("slot rows read") to "строк слотов прочитано",
        r(" equipment slot ") to " слот снаряжения ",
        r(" ender chest slot ") to " эндер-сундук, слот ",
        r(" cursor") to " курсор",
        r(" slot ") to " слот ",
        r("container ") to "контейнер ",
        r("dropped item ") to "предмет на земле ",
        r("block (-?\\d+) (-?\\d+) (-?\\d+)") to "блок $1 $2 $3",
        r(" from nowhere") to " из ниоткуда",
        r(" to nowhere") to " в никуда",
        r(" from ") to " из ",
        r(" to ") to " в ",
        r(" at (-?\\d+) (-?\\d+) (-?\\d+)") to " в $1 $2 $3",
        r("(\\d+) blocks around ") to "$1 блоков вокруг ",
        r("everything (.*) did in this world") to "всё, что сделал $1 в этом мире",
        r("everything (.*) did since ") to "всё, что сделал $1, с ",
        r(" since ") to " с ",
        r(" until ") to " до ",
        r("event (\\d+) в ") to "событие $1 в ",
        r("player (\\S+)") to "игрок $1",
        r("(\\d+) items lying in the world: ") to "предметов в мире ($1): ",
        r("gone for good: ") to "пропало насовсем: ",
        r(" \\(offline, at their next join\\)") to " (не в сети, при следующем входе)",
        r("; /pp lookup with the same words shows the rows") to "; /pp lookup с теми же словами покажет строки",
        r("the block history of this world is not open") to "журнал блоков этого мира не открыт",
        r("those players did more in that window than one rollback reads; narrow the time") to "эти игроки сделали за окно больше, чем читает один откат; сузь время",
        r("the chunks it works in did not load within (\\d+) s") to "чанки отката не загрузились за $1 с",
    )
}
