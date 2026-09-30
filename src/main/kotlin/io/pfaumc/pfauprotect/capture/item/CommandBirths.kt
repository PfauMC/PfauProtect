package io.pfaumc.pfauprotect.capture.item

import io.pfaumc.pfauprotect.model.Cause
import java.util.UUID

/**
 * A block command running on this thread. What drops inside its area while it runs — the blocks a
 * `fill … destroy` breaks — is the command's doing, and the spawn funnel names it so rather than
 * booking a birth nobody explained.
 */
object CommandBirths {
    class Area(
        val world: UUID,
        val minX: Int, val minY: Int, val minZ: Int,
        val maxX: Int, val maxY: Int, val maxZ: Int,
        val cause: Cause,
        val actor: UUID?,
    ) {
        // A drop is thrown from the middle of its block and bounces, so a block's width of slack.
        fun holds(world: UUID, x: Double, y: Double, z: Double) = world == this.world &&
            x >= minX - 1 && x <= maxX + 2 && y >= minY - 1 && y <= maxY + 2 && z >= minZ - 1 && z <= maxZ + 2
    }

    private val running = ThreadLocal<Area?>()

    fun <T> during(area: Area, body: () -> T): T {
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
        return world == area.world && x in area.minX..area.maxX && y in area.minY..area.maxY && z in area.minZ..area.maxZ
    }
}
