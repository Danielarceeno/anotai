package com.example.notepad

import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.notepad.data.Note
import com.example.notepad.ui.NoteColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [NoteColors.isLight]/[NoteColors.pressColor]/[NoteColors.resolveArgb] all take or produce real
 * [android.graphics.Color] ints -- the unit-test `android.jar` stub throws on those, so this has
 * to be instrumented (see `TESTING.md`'s "Known gaps").
 */
@RunWith(AndroidJUnit4::class)
class NoteColorsTest {

    @Test
    fun isLight_isTrueForWhite() {
        assertTrue(NoteColors.isLight(Color.WHITE))
    }

    @Test
    fun isLight_isTrueForYellow() {
        assertTrue(NoteColors.isLight(Color.rgb(0xFB, 0xE6, 0x3B))) // note_color_2
    }

    @Test
    fun isLight_isFalseForBlack() {
        assertFalse(NoteColors.isLight(Color.BLACK))
    }

    @Test
    fun isLight_isFalseForASaturatedDarkBlue() {
        assertFalse(NoteColors.isLight(Color.rgb(0x2F, 0x72, 0xC5))) // note_color_4
    }

    @Test
    fun pressColor_blendsTowardWhite_lighteningTheOriginal() {
        val original = Color.rgb(0x2F, 0x72, 0xC5)
        val pressed = NoteColors.pressColor(original)

        assertNotEquals("Press feedback must actually change the color", original, pressed)
        // Blending toward white can only raise (or hold steady at 255) each channel, never lower it.
        assertTrue(Color.red(pressed) >= Color.red(original))
        assertTrue(Color.green(pressed) >= Color.green(original))
        assertTrue(Color.blue(pressed) >= Color.blue(original))
    }

    @Test
    fun pressColor_onWhite_staysWhite() {
        // Already fully white -- blending further toward white has nowhere left to go.
        assertEquals(Color.WHITE, NoteColors.pressColor(Color.WHITE))
    }

    @Test
    fun resolveArgb_usesCustomColor_whenSet() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val custom = Color.rgb(0x12, 0x34, 0x56)
        val note = Note(title = "t", body = "b", colorIndex = 0, customColorArgb = custom)

        assertEquals(custom, NoteColors.resolveArgb(context, note))
    }

    @Test
    fun resolveArgb_fallsBackToPalette_whenCustomColorIsNull() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val note = Note(title = "t", body = "b", colorIndex = 3, customColorArgb = null)

        assertEquals(context.getColor(NoteColors.colorResFor(3)), NoteColors.resolveArgb(context, note))
    }

    @Test
    fun colorResFor_clampsOutOfRangeIndices() {
        // A colorIndex from a future app version this one doesn't know about yet must not crash
        // with an ArrayIndexOutOfBoundsException -- it should just clamp to the nearest valid one.
        assertEquals(NoteColors.colorResFor(NoteColors.values.lastIndex), NoteColors.colorResFor(999))
        assertEquals(NoteColors.colorResFor(0), NoteColors.colorResFor(-5))
    }
}
