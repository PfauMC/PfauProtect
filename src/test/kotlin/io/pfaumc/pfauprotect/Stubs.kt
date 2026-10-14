package io.pfaumc.pfauprotect

import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.storage.PlacedForms
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin
import java.lang.reflect.Proxy
import java.util.UUID

// An answer may be a function of the call's arguments. Anything unanswered reads as null, so a handler
// that reads what it must not fails on the unboxing or the iteration; a lenient stub answers false, zero
// or empty instead, for code that asks more than the test cares about.
internal fun <T : Any> stub(type: Class<T>, answers: Map<String, Any?> = emptyMap(), lenient: Boolean = false): T {
    @Suppress("UNCHECKED_CAST")
    return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
        when (val answer = answers[method.name]) {
            is Function1<*, *> -> (answer as (Array<out Any?>) -> Any?)(args ?: emptyArray())
            null -> if (!lenient) null else when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                List::class.java -> emptyList<Any>()
                else -> null
            }
            else -> answer
        }
    } as T
}

internal fun worldStub(uid: UUID): World = stub(World::class.java, mapOf("getUID" to uid))

internal fun blockAt(world: UUID, x: Int, y: Int, z: Int): Block =
    stub(Block::class.java, mapOf("getWorld" to worldStub(world), "getX" to x, "getY" to y, "getZ" to z))

internal fun disabledPlugin(): Plugin = stub(Plugin::class.java, mapOf("isEnabled" to false))

// Anything but a single String or Component sent fails loudly, so a message sent some other way is not
// silently missing from what the test reads.
internal fun senderInto(
    lines: MutableList<String>,
    flat: (Component) -> String = PlainTextComponentSerializer.plainText()::serialize,
): CommandSender = Proxy.newProxyInstance(CommandSender::class.java.classLoader, arrayOf(CommandSender::class.java)) { _, method, args ->
    val sent = args?.singleOrNull()
    when {
        method.name != "sendMessage" -> throw UnsupportedOperationException("sender.${method.name}")
        sent is String -> lines += sent
        sent is Component -> lines += flat(sent)
        else -> throw UnsupportedOperationException("sendMessage(${method.parameterTypes.joinToString { it.simpleName }})")
    }
    null
} as CommandSender

internal object NoPlacedForms : PlacedForms {
    override fun formAt(at: WorldBlock): ByteArray? = null
    override fun setFormAt(at: WorldBlock, form: ByteArray) = Unit
    override fun clearFormAt(at: WorldBlock) = Unit
    override fun takeFormsAt(positions: List<WorldBlock>): Map<WorldBlock, ByteArray> = emptyMap()
}
