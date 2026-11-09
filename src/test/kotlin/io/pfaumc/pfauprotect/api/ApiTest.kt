package io.pfaumc.pfauprotect.api

import io.pfaumc.pfauprotect.AIR
import io.pfaumc.pfauprotect.BothPlanes
import io.pfaumc.pfauprotect.STONE
import io.pfaumc.pfauprotect.ServerRegistries
import io.pfaumc.pfauprotect.T0
import io.pfaumc.pfauprotect.Vanilla
import io.pfaumc.pfauprotect.alice
import io.pfaumc.pfauprotect.bob
import io.pfaumc.pfauprotect.command.LookupQuery
import io.pfaumc.pfauprotect.command.LookupTarget
import io.pfaumc.pfauprotect.command.Lookups
import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.model.Confidence
import io.pfaumc.pfauprotect.model.Container
import io.pfaumc.pfauprotect.model.EntityKind
import io.pfaumc.pfauprotect.model.PlayerInv
import io.pfaumc.pfauprotect.model.Transfer
import io.pfaumc.pfauprotect.storage.BlockChange
import io.pfaumc.pfauprotect.storage.ChatKind
import io.pfaumc.pfauprotect.storage.ChatLine
import io.pfaumc.pfauprotect.storage.ChatLog
import io.pfaumc.pfauprotect.storage.EntityChange
import io.pfaumc.pfauprotect.storage.Hit
import io.pfaumc.pfauprotect.storage.ItemFormCodec
import io.pfaumc.pfauprotect.stub
import io.papermc.paper.threadedregions.scheduler.EntityScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

@ExtendWith(Vanilla::class)
class ApiTest : BothPlanes() {
    private val carol: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000c0")
    private val players = mapOf("Alice" to alice, "Bob" to bob, "Carol" to carol)
    private val form = byteArrayOf(3)
    private var chat: ChatLog? = null

    private fun api(
        ask: (Access, String) -> Boolean? = { _, _ -> true },
        playerOf: (UUID) -> Player? = { null },
        preview: (CommandSender, LookupTarget, LookupQuery, (PreviewResult) -> Unit) -> Unit = { _, _, _, _ -> },
        pendingSince: (UUID) -> Long? = { null },
    ): Api {
        val names = players.entries.associate { (name, id) -> id to name }
        val lookups = Lookups(stub(Plugin::class.java), shared, logs, ItemFormCodec(shared.registries, ServerRegistries.access), players::get, names::get)
        val log = chat ?: ChatLog(dir.resolve("chat")).also { chat = it }
        return Api(stub(Plugin::class.java), lookups, log, logs, preview, pendingSince, playerOf, ask)
    }

    // The chat log writes on a thread of its own and has nothing to wait on but its close.
    private fun said(vararg lines: ChatLine) {
        ChatLog(dir.resolve("chat")).use { log -> lines.forEach(log::submit) }
    }

    @AfterEach
    fun closeChat() {
        chat?.close()
    }

    private fun <T> CompletableFuture<T>.answer(): T = get(30, TimeUnit.SECONDS)

    private fun CompletableFuture<*>.refusal(): PfauProtectException =
        assertThrows<ExecutionException> { answer() }.cause as PfauProtectException

    private fun at(words: String = "", since: Long? = null, until: Long? = null) = LookupRequest(world, 10, 64, -3, words, since, until)

    @Test
    fun `a lookup row says who did what to which thing where`() {
        log.submit(listOf(BlockChange(10, 64, -3, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        repeat(3) { log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_TNT, T0 + 1_000 + it, actor = bob, confidence = Confidence.INFERRED))) }
        log.drain()

        val page = api().lookup(at(), null).answer()

        val (blasts, placed) = page.rows
        assertEquals(PlayerRef(bob, "Bob"), blasts.actor)
        assertEquals(listOf("removed", "blk_tnt", "inferred", "minecraft:stone", 3), listOf(blasts.action, blasts.cause, blasts.confidence, blasts.thing, blasts.repeats))
        // A folded line is many events and has no one to roll back by.
        assertEquals(null, blasts.event)
        assertEquals(listOf("added", "blk_player_place", "fact", "player", 1), listOf(placed.action, placed.cause, placed.confidence, placed.actorKind, placed.repeats))
        assertEquals(listOf(10, 64, -3), listOf(placed.x, placed.y, placed.z))
        assertTrue(placed.event!!.startsWith("event:"))
        assertFalse(page.more)
        assertTrue(page.complete)
    }

    @Test
    fun `since and until are a window of their own, not one beside time`() {
        log.submit(listOf(BlockChange(10, 64, -3, AIR, STONE, Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.submit(listOf(BlockChange(10, 64, -3, STONE, AIR, Cause.BLK_PLAYER_BREAK, T0 + 10_000, actor = bob)))
        log.drain()

        assertEquals(listOf("blk_player_break"), api().lookup(at(since = T0 + 5_000), null).answer().rows.map { it.cause })
        assertEquals(listOf("blk_player_place"), api().lookup(at(since = T0 - 1, until = T0 + 5_000), null).answer().rows.map { it.cause })
        assertEquals(PfauProtectException.REFUSED, api().lookup(at("t:1h", since = T0), null).refusal().code)
    }

    // Bob took from Alice's chest; Alice taking from her own is no theft.
    @Test
    fun `steal keeps only withdrawals from a chest somebody else placed`() {
        log.submit(listOf(BlockChange(1, 64, 1, AIR, "minecraft:chest", Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.drain()
        val chest = Container(world, 1, 64, 1, 4)
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(bob, 0), form, null, 2, T0 + 1_000, actor = bob))
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(alice, 0), form, null, 1, T0 + 2_000, actor = alice))
        shared.drain()

        val rows = api().lookup(LookupRequest(world, 1, 64, 1, "a:steal r:1"), null).answer().rows

        val taken = rows.single()
        assertEquals(PlayerRef(bob, "Bob"), taken.actor)
        assertEquals(listOf("removed", 2, 4), listOf(taken.action, taken.amount, taken.slot))
        assertEquals(listOf("container", 1, 64, 1), listOf(taken.container?.type, taken.container?.x, taken.container?.y, taken.container?.z))
        assertEquals("inventory", taken.to?.type)
    }

    // A player's actions read a withdrawal from their own slots; beside other actions `steal` is one more of a list.
    @Test
    fun `a player's actions find a theft, and steal in a list leaves the others' rows`() {
        log.submit(listOf(BlockChange(1, 64, 1, AIR, "minecraft:chest", Cause.BLK_PLAYER_PLACE, T0, actor = alice)))
        log.drain()
        val chest = Container(world, 1, 64, 1, 4)
        // No actor: the taker is then whoever's slots the withdrawal went to.
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(bob, 0), form, null, 2, T0 + 1_000))
        shared.submit(Transfer(Cause.CONTAINER_REMOVE, chest, PlayerInv(alice, 0), form, null, 1, T0 + 2_000))
        shared.drain()
        fun taken(words: String) = api().actorActions(LookupRequest(words = words), null).answer().rows.map { it.amount }

        assertEquals(listOf(2), taken("u:Bob a:steal"))
        assertEquals(emptyList<Int>(), taken("u:Alice a:steal"))
        assertEquals(listOf(1), taken("u:Alice a:-container,steal"))
        assertEquals(emptyList<Int>(), taken("u:Alice a:-block,steal"))
    }

    @Test
    fun `a player's actions come from every world, and a uuid names them as a name does`() {
        val other = UUID.fromString("00000000-0000-4000-8000-000000000002")
        val otherLog = logs.open(other)
        log.submit(listOf(BlockChange(1, 64, 1, STONE, AIR, Cause.BLK_PLAYER_BREAK, T0, actor = alice)))
        log.submit(listOf(BlockChange(3, 64, 3, STONE, AIR, Cause.BLK_PLAYER_BREAK, T0 + 5, actor = bob)))
        otherLog.submit(listOf(BlockChange(2, 64, 2, STONE, AIR, Cause.BLK_PLAYER_BREAK, T0 + 10, actor = alice)))
        log.drain()
        otherLog.drain()

        val byName = api().actorActions(LookupRequest(words = "u:Alice #all"), null).answer().rows
        assertEquals(listOf(2, 1), byName.map { it.x })
        assertEquals(byName.map { it.x }, api().actorActions(LookupRequest(words = "u:$alice #all"), null).answer().rows.map { it.x })
    }

    // A&B is their fight whoever struck; a line of either with somebody else, or a word of chat, is not.
    @Test
    fun `the chat of a pair is their blows and deaths`() {
        said(
            ChatLine(T0, ChatKind.CHAT, alice, "hi"),
            ChatLine(T0 + 1, ChatKind.HIT, alice, "minecraft:iron_sword", "world", 1, 64, 1, Hit(bob, 6f, Confidence.FACT)),
            ChatLine(T0 + 2, ChatKind.HIT, bob, "", "world", 1, 64, 1, Hit(alice, 2f, Confidence.FACT)),
            ChatLine(T0 + 3, ChatKind.HIT, alice, "", "world", 1, 64, 1, Hit(carol, 1f, Confidence.FACT)),
        )
        log.submit(listOf(EntityChange(1, 64, 1, EntityKind.PLAYER_DIED, Cause.PLAYER_KILLED, "minecraft:player", bob, T0 + 4, actor = alice, via = "minecraft:iron_sword")))
        log.drain()

        val lines = api().chat(ChatRequest(words = "u:Alice&Bob #all"), null).answer().lines

        assertEquals(listOf("death", "hit", "hit"), lines.map { it.kind })
        val (death, back, first) = lines
        assertEquals(listOf(PlayerRef(alice, "Alice"), PlayerRef(bob, "Bob")), listOf(death.hit?.attacker, death.hit?.victim))
        assertEquals(listOf(bob, alice), listOf(back.hit?.attacker?.uuid, back.hit?.victim?.uuid))
        assertEquals(listOf(alice, bob, "minecraft:iron_sword"), listOf(first.hit?.attacker?.uuid, first.hit?.victim?.uuid, first.hit?.weapon))
        assertEquals(listOf("death"), api().chat(ChatRequest(words = "u:Alice&Bob", kinds = setOf("death")), null).answer().lines.map { it.kind })
        assertEquals(PfauProtectException.REFUSED, api().chat(ChatRequest(radius = 5), null).refusal().code)
    }

    @Test
    fun `a call for a player answers only as far as their permissions go`() {
        val asked = ArrayList<String>()
        val forbidden = api(ask = { _, permission -> asked += permission; false }).lookup(at(), alice).refusal()
        assertEquals(PfauProtectException.FORBIDDEN, forbidden.code)
        assertEquals(listOf("pfauprotect.lookup"), asked)
        assertEquals(PfauProtectException.OFFLINE, api(ask = { _, _ -> null }).chat(ChatRequest(), alice).refusal().code)
        // Trusted code is asked nothing.
        api(ask = { _, _ -> error("asked") }).lookup(at(), null).answer()
    }

    @Test
    fun `a preview needs the rollback permission and the player online`() {
        val rollback = { _: Access, permission: String -> permission != "pfauprotect.rollback" }
        assertEquals(PfauProtectException.FORBIDDEN, api(ask = rollback, playerOf = { online() }).preview(alice, at("t:1h r:5")).refusal().code)
        assertEquals(PfauProtectException.OFFLINE, api().preview(alice, at("t:1h r:5")).refusal().code)
    }

    // What the preview reads comes back as counts, and stays the status only while it waits for apply.
    @Test
    fun `a preview answers its counts and its status lapses with it`() {
        var pending: Long? = 1_000
        val ready = PreviewResult("ready", null, PreviewBlocks(4, 1, 2), 3, PreviewEntities(0, 0, 0, 0), 1_000 + 300_000)
        var asked: LookupQuery? = null
        val api = api(
            playerOf = { online() },
            preview = { _, target, query, done -> asked = query; assertEquals(world, target.world); done(ready) },
            pendingSince = { pending },
        )

        assertEquals(ready, api.preview(alice, at("r:5", since = T0)).answer())
        assertEquals(T0, asked?.fromMillis)
        assertEquals(ready, api.previewStatus(alice))
        pending = null
        assertEquals("none", api.previewStatus(alice).status)
        assertEquals("none", api.previewStatus(bob).status)
    }

    // The status is the newest preview's, even when an older one asked just before finishes after it.
    @Test
    fun `an older preview finishing late leaves the newer one's status`() {
        val ready = PreviewResult("ready", null, PreviewBlocks(1, 0, 0), 0, PreviewEntities(0, 0, 0, 0), 1_000 + 300_000)
        val started = LinkedBlockingQueue<(PreviewResult) -> Unit>()
        val api = api(playerOf = { online() }, preview = { _, _, _, done -> started.put(done) }, pendingSince = { 1_000 })

        val older = api.preview(alice, at("r:5", since = T0))
        val finishOlder = started.poll(30, TimeUnit.SECONDS)!!
        val newer = api.preview(alice, at("r:5", since = T0))
        started.poll(30, TimeUnit.SECONDS)!!(ready)
        finishOlder(PreviewResult("refused", "late"))

        assertEquals("refused", older.answer().status)
        assertEquals(ready, newer.answer())
        assertEquals(ready, api.previewStatus(alice))
    }

    // An online player whose own thread runs what is given to it at once.
    private fun online(): Player {
        val task = stub(ScheduledTask::class.java)
        val scheduler = stub(EntityScheduler::class.java, mapOf("run" to { args: Array<out Any?> ->
            @Suppress("UNCHECKED_CAST")
            (args[1] as Consumer<ScheduledTask>).accept(task)
            task
        }))
        return stub(Player::class.java, mapOf("getScheduler" to scheduler))
    }
}
