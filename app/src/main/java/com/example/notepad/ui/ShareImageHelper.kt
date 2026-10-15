package com.example.notepad.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.notepad.R
import com.example.notepad.data.ChecklistItem
import com.example.notepad.data.Note
import com.example.notepad.databinding.ShareNoteCardBinding
import java.io.File
import java.io.FileOutputStream

/**
 * Renders a note as a PNG (title + full body, tinted to its note color) and hands it to whatever
 * app the user picks via the system share sheet. Unlike [NoteEditorActivity]'s plain-text share,
 * this is for posting a note somewhere a screenshot of it would otherwise go.
 */
object ShareImageHelper {

    private const val CARD_WIDTH_DP = 640

    fun share(context: Context, note: Note) {
        val bitmap = renderToBitmap(context, note)
        val file = bitmap?.let { saveToCache(context, it) }
        if (file == null) {
            Toast.makeText(context, R.string.share_image_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_share_image)))
    }

    /** Inflates the card layout off-screen, measures/lays it out by hand (it's never attached to
     *  a real window), and draws it into a same-size [Bitmap]. */
    private fun renderToBitmap(context: Context, note: Note): Bitmap? {
        val binding = ShareNoteCardBinding.inflate(LayoutInflater.from(context))
        val root = binding.root

        val colorInt = context.getColor(NoteColors.colorResFor(note.colorIndex))
        val textColor = if (NoteColors.isLight(colorInt)) 0xFF1A1A1A.toInt() else 0xFFF5F5F5.toInt()
        root.background = GradientDrawable().apply {
            setColor(colorInt)
            cornerRadius = 20 * context.resources.displayMetrics.density
        }

        binding.shareTitle.text = note.title
        binding.shareTitle.setTextColor(textColor)
        binding.shareTitle.visibility = if (note.title.isBlank()) View.GONE else View.VISIBLE

        val body = bodyText(note)
        binding.shareBody.text = body
        binding.shareBody.setTextColor(textColor)
        binding.shareBody.visibility = if (body.isBlank()) View.GONE else View.VISIBLE

        binding.shareWatermark.setTextColor(textColor)

        val widthPx = (CARD_WIDTH_DP * context.resources.displayMetrics.density).toInt()
        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        root.measure(widthSpec, heightSpec)
        if (root.measuredWidth <= 0 || root.measuredHeight <= 0) return null
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)

        val bitmap = Bitmap.createBitmap(root.measuredWidth, root.measuredHeight, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        return bitmap
    }

    private fun bodyText(note: Note): String = if (note.type == Note.TYPE_CHECKLIST) {
        ChecklistItem.parse(note.body).joinToString("\n") { (if (it.checked) "☑ " else "☐ ") + it.text }
    } else {
        NoteFormatting.plainText(note.body, note.isFormatted)
    }

    /** A fresh file per share (timestamped name) rather than overwriting one fixed file, so a
     *  share sheet target that reads the Uri lazily/asynchronously can't ever race a newer share
     *  overwriting the bytes out from under it. */
    private fun saveToCache(context: Context, bitmap: Bitmap): File? = try {
        val dir = File(context.cacheDir, "shared_images").apply { mkdirs() }
        val file = File(dir, "note_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        file
    } catch (e: Exception) {
        null
    }
}
