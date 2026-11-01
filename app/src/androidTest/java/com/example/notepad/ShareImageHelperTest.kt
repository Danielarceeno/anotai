package com.example.notepad

import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.notepad.data.ChecklistItem
import com.example.notepad.data.Note
import com.example.notepad.ui.ShareImageHelper
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ShareImageHelper.share] itself launches a real system share sheet via `startActivity`, which
 * Espresso has no way to dismiss -- so this exercises [ShareImageHelper.renderToBitmap] and
 * [ShareImageHelper.saveToCache] directly instead (both made non-private for exactly this, see
 * their own doc), the same two steps `share()` chains before ever touching an Intent.
 */
@RunWith(AndroidJUnit4::class)
class ShareImageHelperTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun textNote_rendersAndSavesANonEmptyFile() {
        val note = Note(title = "A text note", body = "Some body text", type = Note.TYPE_TEXT)
        assertRendersToAValidFile(note)
    }

    @Test
    fun checklistNote_rendersAndSavesANonEmptyFile() {
        val note = Note(
            title = "A checklist",
            body = ChecklistItem.serialize(listOf(ChecklistItem("first", false), ChecklistItem("second", true))),
            type = Note.TYPE_CHECKLIST
        )
        assertRendersToAValidFile(note)
    }

    @Test
    fun longNote_rendersAndSavesANonEmptyFile() {
        val note = Note(
            title = "A very long note",
            body = "Lorem ipsum dolor sit amet. ".repeat(500),
            type = Note.TYPE_TEXT
        )
        assertRendersToAValidFile(note)
    }

    @Test
    fun blankTitleNote_stillRendersFromBodyAlone() {
        val note = Note(title = "", body = "Just a body, no title", type = Note.TYPE_TEXT)
        assertRendersToAValidFile(note)
    }

    private fun assertRendersToAValidFile(note: Note) {
        val bitmap = ShareImageHelper.renderToBitmap(context, note)
        assertTrue("Rendering must not come back null for a normal note", bitmap != null)
        assertTrue(bitmap!!.width > 0 && bitmap.height > 0)

        val file = ShareImageHelper.saveToCache(context, bitmap)
        assertTrue("The rendered bitmap must actually get saved to a file", file != null)
        assertTrue("The saved file must exist on disk", file!!.exists())
        assertTrue("The saved file must not be empty", file.length() > 0)

        // Same call share() makes right before handing the Uri to the system chooser -- must not
        // throw (a path outside file_paths.xml's declared roots would throw IllegalArgumentException).
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        assertTrue(uri.toString().startsWith("content://"))
        context.contentResolver.openInputStream(uri)?.use { stream ->
            assertTrue("The FileProvider Uri must actually be readable", stream.read() != -1)
        } ?: throw AssertionError("Could not open an input stream for the shared file's Uri")
    }
}
