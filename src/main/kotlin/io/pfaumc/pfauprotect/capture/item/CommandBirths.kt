package io.pfaumc.pfauprotect.capture.item

import io.pfaumc.pfauprotect.model.Cause
import io.pfaumc.pfauprotect.capture.block.Area as Box
import java.util.UUID

/**
 * A block command running on this thread. What drops inside its area while it runs — the blocks a
 * `fill … destroy` breaks — is the command's doing, and the spawn funnel names it so rather than
 * booking a birth nobody explained.
 */
object CommandBirths {
    internal class Area(val box: Box, val cause: Cause, val actor: UUID?) {
        val world: UUID = box.level.world.uid

        // A drop is thrown from the middle of its block and bounces, so a block's width of slack.
        fun holds(world: UUID, x: Double, y: Double, z: Double) = world == this.world &&
            x >= box.minX - 1 && x <= box.maxX + 2 && y >= box.minY - 1 && y <= box.maxY + 2 &&
            z >= box.minZ - 1 && z <= box.maxZ + 2
    }

    private val running = ThreadLocal<Area?>()

    internal fun <T> during(area: Area, body: () -> T): T {
        val outer = running.get()
        running.set(area)
        try {
            return body()
        } finally {
            running.set(outer)
        }
    }

    internal fun at(world: UUID, x: Double, y: Double, z: Double): Area? = running.get()?.takeIf { it.holds(world, x, y, z) }

    /**
     * Whether a block command running on this thread is writing this position. The capture of the
     * world stands aside there: the command's own reading files the change, and a destroy or a
     * physics read-back raised inside it would file it a second time.
     */
    fun writing(world: UUID, x: Int, y: Int, z: Int): Boolean {
        val area = running.get() ?: return false
        val box = area.box
        return world == area.world && x in box.minX..box.maxX && y in box.minY..box.maxY && z in box.minZ..box.maxZ
    }
}
