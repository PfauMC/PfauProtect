package io.pfaumc.pfauprotect

import io.pfaumc.pfauprotect.model.WorldBlock
import io.pfaumc.pfauprotect.storage.PlacedForms
import org.bukkit.World
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

internal fun disabledPlugin(): Plugin = stub(Plugin::class.java, mapOf("isEnabled" to false))

internal object NoPlacedForms : PlacedForms {
    override fun formAt(world: UUID, x: Int, y: Int, z: Int): ByteArray? = null
    override fun formsAt(positions: List<WorldBlock>): Map<WorldBlock, ByteArray> = emptyMap()
    override fun setFormAt(world: UUID, x: Int, y: Int, z: Int, form: ByteArray) = Unit
    override fun clearFormAt(world: UUID, x: Int, y: Int, z: Int) = Unit
    override fun clearFormsAt(positions: List<WorldBlock>) = Unit
}
