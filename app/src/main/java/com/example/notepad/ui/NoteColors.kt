package com.example.notepad.ui

import android.content.Context
import com.example.notepad.R
import com.example.notepad.data.Note

/** Shared palette used by both the note list cards and the color picker in the editor. */
object NoteColors {
    val values = intArrayOf(
        R.color.note_color_0,
        R.color.note_color_1,
        R.color.note_color_2,
        R.color.note_color_3,
        R.color.note_color_4,
        R.color.note_color_5,
        R.color.note_color_6,
        R.color.note_color_7
    )

    fun colorResFor(colorIndex: Int): Int = values[colorIndex.coerceIn(0, values.lastIndex)]

    /** The actual color to render for [note]: its free custom RGB pick if it has one, otherwise
     *  its [Note.colorIndex] into the fixed palette above. */
    fun resolveArgb(context: Context, note: Note): Int =
        note.customColorArgb ?: context.getColor(colorResFor(note.colorIndex))

    /** True when [colorInt] is light enough that it needs dark text/icons on top of it. */
    fun isLight(colorInt: Int): Boolean {
        val r = android.graphics.Color.red(colorInt)
        val g = android.graphics.Color.green(colorInt)
        val b = android.graphics.Color.blue(colorInt)
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return luminance > 0.6
    }

    /** [colorInt] lightened a bit, for a note card's own press-feedback tint -- each card is a
     *  different, often saturated color, so a plain fixed-color ripple on top either barely
     *  shows or looks muddy; blending the card's *own* color toward white instead keeps the
     *  feedback obviously tied to that specific card. */
    fun pressColor(colorInt: Int): Int =
        androidx.core.graphics.ColorUtils.blendARGB(colorInt, android.graphics.Color.WHITE, 0.2f)
}
