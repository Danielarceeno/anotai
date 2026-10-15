package com.example.notepad.data

/**
 * A checklist note's `body` is just its items serialized one per line as
 * "[x] text" / "[ ] text" -- simple enough to stay in the same `body`
 * column as a plain text note, with no extra table or migration needed.
 */
data class ChecklistItem(val text: String, val checked: Boolean) {

    companion object {
        private const val CHECKED_PREFIX = "[x] "
        private const val UNCHECKED_PREFIX = "[ ] "

        fun serialize(items: List<ChecklistItem>): String =
            items.joinToString("\n") { (if (it.checked) CHECKED_PREFIX else UNCHECKED_PREFIX) + it.text }

        fun parse(body: String): List<ChecklistItem> =
            body.lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    when {
                        line.startsWith(CHECKED_PREFIX) -> ChecklistItem(line.removePrefix(CHECKED_PREFIX), true)
                        line.startsWith(UNCHECKED_PREFIX) -> ChecklistItem(line.removePrefix(UNCHECKED_PREFIX), false)
                        else -> ChecklistItem(line, false)
                    }
                }
    }
}
