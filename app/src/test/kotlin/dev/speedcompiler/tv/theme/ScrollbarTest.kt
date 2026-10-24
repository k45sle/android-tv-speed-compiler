package dev.speedcompiler.tv.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollbarTest {
    @Test fun noThumbWhenEverythingFits() {
        assertNull(scrollThumb(500f, 400f, 0f))
        assertNull(scrollThumb(500f, 500f, 0f))
        assertNull(scrollThumb(0f, 900f, 0f)) // not laid out yet
    }

    @Test fun thumbSizeAndPositionFollowTheScroll() {
        assertEquals(0.25f to 0f, scrollThumb(100f, 400f, 0f))
        assertEquals(0.25f to 0.75f, scrollThumb(100f, 400f, 300f)) // at the end
        assertEquals(0.25f to 0.75f, scrollThumb(100f, 400f, 900f)) // overshoot from the estimate stays on the track
    }

    @Test fun longListsKeepAVisibleThumb() {
        assertEquals(0.05f, scrollThumb(100f, 100_000f, 0f)!!.first)
    }
}
