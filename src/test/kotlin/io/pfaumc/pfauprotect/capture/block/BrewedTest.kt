package io.pfaumc.pfauprotect.capture.block
import io.pfaumc.pfauprotect.key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BrewedTest {
    // One brew can leave a slot empty, leave a bottle the recipe ignores alone, turn one into a potion,
    // and hand back fewer results than the stand has bottles; reading past those would pair a bottle
    // with a result that is not there. Every key is its own instance, so an unchanged bottle has to be
    // told by its contents.
    @Test
    fun `a brew names only the bottles that changed`() {
        assertEquals(
            listOf(Triple(0, key("water bottle"), key("awkward potion"))),
            brewed(
                listOf(key("water bottle"), null, key("water bottle"), key("water bottle")),
                listOf(key("awkward potion"), key("awkward potion"), key("water bottle")),
            ),
        )
    }
}
