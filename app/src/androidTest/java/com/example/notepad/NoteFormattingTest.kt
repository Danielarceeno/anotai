package com.example.notepad

import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.notepad.ui.NoteFormatting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [NoteFormatting] is backed by real [android.text.Html]/[android.text.Spannable] classes, which
 * the unit-test `android.jar` stub throws on -- see `TESTING.md`'s "Known gaps". No Activity
 * needed: a plain [EditText] built off the application context is enough to exercise the spans.
 */
@RunWith(AndroidJUnit4::class)
class NoteFormattingTest {

    private lateinit var editText: EditText

    @Before
    fun setUp() {
        editText = EditText(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun save_plainText_staysUnformatted() {
        editText.setText("just plain text")
        val (body, isFormatted) = NoteFormatting.save(editText)

        assertEquals("just plain text", body)
        assertFalse(isFormatted)
    }

    @Test
    fun save_emptyBody_staysUnformatted() {
        editText.setText("")
        val (body, isFormatted) = NoteFormatting.save(editText)

        assertEquals("", body)
        assertFalse(isFormatted)
    }

    @Test
    fun toggleBold_withNoSelection_isANoOp() {
        editText.setText("hello")
        editText.setSelection(2, 2) // collapsed selection -- nothing to apply a style to
        NoteFormatting.toggleBold(editText)

        val (_, isFormatted) = NoteFormatting.save(editText)
        assertFalse(isFormatted)
    }

    @Test
    fun toggleBold_onSelection_marksFormattedAndRoundTrips() {
        editText.setText("hello world")
        editText.setSelection(0, 5) // "hello"
        NoteFormatting.toggleBold(editText)

        val (html, isFormatted) = NoteFormatting.save(editText)
        assertTrue(isFormatted)
        assertTrue("Expected a bold tag in: $html", html.contains("<b>", ignoreCase = true))

        val reloaded = EditText(ApplicationProvider.getApplicationContext())
        NoteFormatting.load(reloaded, html, true)
        val spans = reloaded.text.getSpans(0, reloaded.text.length, StyleSpan::class.java)
        assertTrue("Reloading the saved HTML must restore the bold span", spans.any { it.style == android.graphics.Typeface.BOLD })
    }

    @Test
    fun toggleBold_twiceOnSameSelection_removesTheSpanAgain() {
        editText.setText("hello")
        editText.setSelection(0, 5)
        NoteFormatting.toggleBold(editText)
        NoteFormatting.toggleBold(editText)

        val (_, isFormatted) = NoteFormatting.save(editText)
        assertFalse("Toggling bold on, then off, on the same selection must leave no span", isFormatted)
    }

    @Test
    fun toggleItalic_onSelection_marksFormatted() {
        editText.setText("hello")
        editText.setSelection(0, 5)
        NoteFormatting.toggleItalic(editText)

        val (html, isFormatted) = NoteFormatting.save(editText)
        assertTrue(isFormatted)
        assertTrue("Expected an italic tag in: $html", html.contains("<i>", ignoreCase = true))
    }

    @Test
    fun toggleUnderline_onSelection_marksFormattedAndRoundTrips() {
        editText.setText("hello")
        editText.setSelection(0, 5)
        NoteFormatting.toggleUnderline(editText)

        val (html, isFormatted) = NoteFormatting.save(editText)
        assertTrue(isFormatted)

        val reloaded = EditText(ApplicationProvider.getApplicationContext())
        NoteFormatting.load(reloaded, html, true)
        val spans = reloaded.text.getSpans(0, reloaded.text.length, UnderlineSpan::class.java)
        assertTrue("Reloading the saved HTML must restore the underline span", spans.isNotEmpty())
    }

    @Test
    fun load_plainText_doesNotParseAsHtml() {
        // A literal '<' must stay literal when isFormatted is false -- that's the entire point of
        // the explicit flag instead of sniffing the body for tags (see NoteFormatting's own doc).
        NoteFormatting.load(editText, "price < 10 & rising", isFormatted = false)
        assertEquals("price < 10 & rising", editText.text.toString())
    }

    @Test
    fun load_malformedHtml_doesNotCrash_andKeepsReadableText() {
        // An unclosed tag is exactly the kind of malformed input Html.fromHtml must tolerate
        // (Android's parser is lenient, not strict XML) -- this must not throw.
        NoteFormatting.load(editText, "<b>bold but never closed", isFormatted = true)
        assertTrue(editText.text.toString().contains("bold but never closed"))
    }

    @Test
    fun plainText_stripsHtmlTags_whenFormatted() {
        val plain = NoteFormatting.plainText("<b>bold</b> and <i>italic</i>", isFormatted = true)
        assertEquals("bold and italic", plain)
    }

    @Test
    fun plainText_passesThroughUnchanged_whenNotFormatted() {
        val plain = NoteFormatting.plainText("<b>not actually html</b>", isFormatted = false)
        assertEquals("<b>not actually html</b>", plain)
    }

    @Test
    fun plainText_emptyBody_isEmpty() {
        assertEquals("", NoteFormatting.plainText("", isFormatted = false))
        assertEquals("", NoteFormatting.plainText("", isFormatted = true))
    }
}
