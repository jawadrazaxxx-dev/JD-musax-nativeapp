package com.jdmusax.app

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiaudioDesignTest {
    @Test
    fun emeraldPaletteUsesExpectedBrandColors() {
        assertEquals(Color(0xFF0B1712), ShiaudioDesign.emeraldNight)
        assertEquals(Color(0xFF11231B), ShiaudioDesign.emeraldSurface)
        assertEquals(Color(0xFFD7B875), ShiaudioDesign.gold)
    }

    @Test
    fun warmLightPaletteAndBrandAccentAreAvailable() {
        assertEquals(Color(0xFFF5F3EA), ShiaudioDesign.ivory)
        assertEquals(Color(0xFFFFFEF8), ShiaudioDesign.paper)
        assertTrue(SettingsStore.ACCENT_OPTIONS.contains("gold"))
        assertTrue(SettingsStore.APPEARANCE_MODES.containsAll(setOf("light", "dark", "amoled")))
    }
}
