package com.example.notepad.ui

import android.graphics.Typeface
import android.text.Html
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.widget.EditText

/**
 * Bold/italic/underline for a [Note][com.example.notepad.data.Note]'s body, backed by
 * [android.text.Html] rather than a custom markup so Android's own span-repositioning handles
 * edits (inserts/deletes) for free while the note is open. [com.example.notepad.data.Note.isFormatted]
 * says whether [com.example.notepad.data.Note.body] should be read as that HTML or as plain text
 * -- an explicit flag rather than sniffing the text for tags, so a plain note whose text happens
 * to contain a literal '<' or '&' is never misread as markup.
 */
object NoteFormatting {

    /** Loads [body] into [editText], as HTML if [isFormatted], otherwise as plain text. */
    fun load(editText: EditText, body: String, isFormatted: Boolean) {
        editText.text = if (isFormatted) {
            SpannableStringBuilder(Html.fromHtml(body, Html.FROM_HTML_MODE_COMPACT))
        } else {
            SpannableStringBuilder(body)
        }
    }

    /** [editText]'s current content, encoded for [com.example.notepad.data.Note.body] -- HTML (and
     *  `true`) only if some bold/italic/underline span is actually present, otherwise the same
     *  plain string this always saved as before formatting existed. */
    fun save(editText: EditText): Pair<String, Boolean> {
        val editable = editText.text
        val hasFormatting = editable.getSpans(0, editable.length, Any::class.java)
            .any { it is StyleSpan || it is UnderlineSpan }
        return if (hasFormatting) {
            Html.toHtml(editable, Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL) to true
        } else {
            editable.toString() to false
        }
    }

    /** The note's body as plain text regardless of [isFormatted], for anything that can't render
     *  spans: the notes list preview, search, share, and the shared-image export. */
    fun plainText(body: String, isFormatted: Boolean): String =
        if (isFormatted) Html.fromHtml(body, Html.FROM_HTML_MODE_COMPACT).toString() else body

    fun toggleBold(editText: EditText) = toggleStyle(editText, Typeface.BOLD)

    fun toggleItalic(editText: EditText) = toggleStyle(editText, Typeface.ITALIC)

    fun toggleUnderline(editText: EditText) {
        val (start, end) = selectionRange(editText) ?: return
        val editable = editText.text
        val existing = editable.getSpans(start, end, UnderlineSpan::class.java)
            .filter { editable.getSpanStart(it) <= start && editable.getSpanEnd(it) >= end }
        if (existing.isNotEmpty()) {
            existing.forEach { editable.removeSpan(it) }
        } else {
            editable.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun toggleStyle(editText: EditText, styleFlag: Int) {
        val (start, end) = selectionRange(editText) ?: return
        val editable = editText.text
        val existing = editable.getSpans(start, end, StyleSpan::class.java)
            .filter { it.style == styleFlag && editable.getSpanStart(it) <= start && editable.getSpanEnd(it) >= end }
        if (existing.isNotEmpty()) {
            existing.forEach { editable.removeSpan(it) }
        } else {
            editable.setSpan(StyleSpan(styleFlag), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Null (a no-op) for an empty/no selection -- there's nothing to apply a style to. */
    private fun selectionRange(editText: EditText): Pair<Int, Int>? {
        val start = editText.selectionStart.coerceAtLeast(0)
        val end = editText.selectionEnd.coerceAtLeast(0)
        val lo = minOf(start, end)
        val hi = maxOf(start, end)
        return if (lo < hi) lo to hi else null
    }
}
