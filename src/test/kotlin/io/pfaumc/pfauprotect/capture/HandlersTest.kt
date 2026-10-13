package io.pfaumc.pfauprotect.capture

import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class HandlersTest {
    // A click's cancelled flag speaks for its block half alone, and a right click in the air is raised
    // already denied. These ask the half they act on, or only read back what the click left.
    private val allowed = setOf(
        "HolderListener.onBlockUse",
        "ItemUseListener.onUse",
        "BlockDestructionListener.onTouch",
    )

    private fun listeners(): List<Class<*>> {
        val path = "io/pfaumc/pfauprotect/capture"
        val loader = javaClass.classLoader
        return loader.getResources(path).toList()
            .filter { it.protocol == "file" }
            .flatMap { root -> File(root.toURI()).walk().filter { it.extension == "class" }.map { root to it }.toList() }
            .map { (root, file) ->
                val name = path.replace('/', '.') + "." + file.relativeTo(File(root.toURI())).path.removeSuffix(".class").replace(File.separatorChar, '.')
                Class.forName(name, false, loader)
            }
            .filter { Listener::class.java.isAssignableFrom(it) && !it.name.endsWith("Test") }
    }

    // A capture writes what happened, so it runs last and never for a change another plugin refused.
    @Test
    fun `every capture handler runs last and behind the cancellation`() {
        val found = listeners().flatMap { type ->
            type.declaredMethods.mapNotNull { method -> method.getAnnotation(EventHandler::class.java)?.let { Triple(type, method, it) } }
        }
        assertTrue(found.size > 100, "${found.size}")

        val uncovered = ArrayList<String>()
        for ((type, method, handler) in found) {
            val name = "${type.simpleName}.${method.name}"
            assertEquals(EventPriority.MONITOR, handler.priority, name)
            if (Cancellable::class.java.isAssignableFrom(method.parameterTypes.single()) && !handler.ignoreCancelled && name !in allowed) {
                uncovered += name
            }
        }
        assertEquals(emptyList<String>(), uncovered)
        assertEquals(allowed, found.map { (type, method) -> "${type.simpleName}.${method.name}" }.filter { it in allowed }.toSet())
    }
}
