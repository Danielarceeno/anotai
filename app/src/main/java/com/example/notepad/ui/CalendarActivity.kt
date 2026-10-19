package com.example.notepad.ui

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.example.notepad.databinding.ItemCalendarDayBinding
import com.google.android.material.snackbar.Snackbar
import com.example.notepad.R
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.ChecklistItem
import com.example.notepad.data.Note
import com.example.notepad.data.drive.DriveBackupHelper
import com.example.notepad.data.NoteRepository
import com.example.notepad.data.RecurrenceUtils
import com.example.notepad.databinding.ActivityCalendarBinding
import com.example.notepad.notification.NoteReminderScheduler
import com.example.notepad.notification.ReminderNotifier
import com.example.notepad.notification.ReminderScheduler
import com.example.notepad.security.LockState
import com.example.notepad.security.SecurityPrefs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.launch

/** [dotColorIndices] is capped at 3 (as many dots as the grid cell has room for), one per note
 *  due that day, each tinted to that note's own color -- see [CalendarActivity.renderDays]. */
data class CalendarDay(val dayOfMonth: Int, val dateMillis: Long, val inCurrentMonth: Boolean, val isToday: Boolean, val dotColorIndices: List<Int>)

/**
 * Monthly grid with a dot under any day that has a note due, mirroring the
 * reference screenshots. This is the app's launcher activity: it's the first
 * screen shown on open, with a menu to reach the notes list/search and a FAB
 * to add a note directly on the selected day.
 */
class CalendarActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalendarBinding
    private lateinit var repository: NoteRepository

    /** The 42 fixed day-cell views (6 weeks x 7 days), built once by [buildCalendarGrid] and
     *  reused for every month -- only their content changes, never their count or layout. */
    private lateinit var dayCells: List<ItemCalendarDayBinding>
    private var currentDays: List<CalendarDay> = emptyList()

    private val calendar = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private var allReminders: List<Note> = emptyList()
    private var gridStartCalendar: Calendar = Calendar.getInstance()
    private var displayedMonth: Int = -1
    private var selectedDayMillis: Long = startOfDay(Calendar.getInstance())
    /** Set from [EXTRA_OPEN_TODAY] (the widget's tap target) on create, and consumed the first
     *  time [allReminders] arrives -- the day dialog needs that data loaded first, so it can't
     *  just be shown synchronously in onCreate. */
    private var pendingOpenToday = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) runImmediateReminderCheck() else showNotificationPermissionDeniedSnackbar()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (SecurityPrefs.isPinEnabled(this) && LockState.isLocked(this)) {
            startActivity(
                Intent(this, LockActivity::class.java).putExtra(LockActivity.EXTRA_PENDING_ACTION, intent.action)
            )
            finish()
            return
        }

        binding = ActivityCalendarBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDailyReminderNotification()

        // Re-affirms the periodic Drive backup on every launch (e.g. in case WorkManager's
        // schedule was cleared by a device reboot before BootReceiver re-registers it, or the
        // app was reinstalled). schedulePeriodicBackup is safe to call every time.
        if (DriveBackupHelper.lastSignedInAccount(this) != null) {
            DriveBackupHelper.schedulePeriodicBackup(this)
        }

        val db = AppDatabase.getInstance(this)
        repository = NoteRepository(db.noteDao(), applicationContext, db.noteTombstoneDao())

        buildWeekdayHeader()
        buildCalendarGrid()
        binding.calendarGrid.onSwipe = { next, vertical -> changeMonth(if (next) 1 else -1, vertical) }
        binding.calendarGrid.verticalSwipeEnabled = CalendarPrefs.isVerticalSwipeEnabled(this)

        binding.prevMonthButton.setOnClickListener { changeMonth(-1) }
        binding.nextMonthButton.setOnClickListener { changeMonth(1) }
        binding.monthLabel.setOnClickListener { showMonthPicker() }

        binding.addNoteButton.setOnClickListener {
            AddNoteTypeDialog.show(
                this,
                onText = { openEditor(selectedDayMillis, Note.TYPE_TEXT) },
                onChecklist = { openEditor(selectedDayMillis, Note.TYPE_CHECKLIST) }
            )
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_notes -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    true
                }
                R.id.action_search -> {
                    startActivity(Intent(this, SearchActivity::class.java))
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }

        repository.observeAllReminders().observe(this, Observer { notes ->
            allReminders = notes
            buildDays()
            if (pendingOpenToday) {
                pendingOpenToday = false
                showDayDialog(startOfDay(Calendar.getInstance()))
            }
        })

        pendingOpenToday = intent.getBooleanExtra(EXTRA_OPEN_TODAY, false)
        renderMonth()
        handleShortcutIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Already running (and allReminders already loaded), so this can show the dialog right
        // away instead of going through the pendingOpenToday/onCreate dance.
        if (intent.getBooleanExtra(EXTRA_OPEN_TODAY, false)) {
            showDayDialog(startOfDay(Calendar.getInstance()))
        }
        handleShortcutIntent(intent)
    }

    /** Re-checked on every return to this screen, not just on cold start: this is the app's
     *  `singleTop` launcher activity, so coming back from the background after the auto-lock
     *  window elapses (see [LockState]) re-enters here via [onResume], not a fresh [onCreate]. */
    override fun onResume() {
        super.onResume()
        if (SecurityPrefs.isPinEnabled(this) && LockState.isLocked(this)) {
            startActivity(Intent(this, LockActivity::class.java))
            finish()
            return
        }
        // Covers returning here (singleTop, so this is onResume not onCreate) after creating,
        // editing or completing a note elsewhere -- e.g. NoteEditorActivity's save deliberately
        // skips this (see its saveIfNeeded) so creating a note doesn't pop a notification while
        // you're still in the editor, but coming back to the calendar should still reflect it.
        runImmediateReminderCheck()
        // Re-read in case the user just flipped it in Settings and came straight back here.
        binding.calendarGrid.verticalSwipeEnabled = CalendarPrefs.isVerticalSwipeEnabled(this)
    }

    /** Launcher long-press shortcuts (see res/xml/shortcuts.xml) target this already-PIN-gated
     *  activity with one of these two actions rather than NoteEditorActivity directly, then this
     *  immediately forwards into a blank note of the right type for today. */
    private fun handleShortcutIntent(intent: Intent) {
        val type = when (intent.action) {
            ACTION_NEW_TEXT_NOTE -> Note.TYPE_TEXT
            ACTION_NEW_CHECKLIST_NOTE -> Note.TYPE_CHECKLIST
            else -> return
        }
        intent.action = Intent.ACTION_MAIN // consume so it doesn't re-trigger on recreate/back
        openEditor(selectedDayMillis, type)
    }

    /** Builds the 42 day cells once, as 6 rows of 7 equally-weighted columns -- a plain Android
     *  weighted layout, so their size is correct on every single layout pass (including mid
     *  window-open-transition ones some launchers play on a cold start), with no custom
     *  measure-then-patch logic needed. Content gets filled in later by [renderDays]; this only
     *  builds the (reused-for-every-month) view structure and wires each cell's click. */
    private fun buildCalendarGrid() {
        val cells = mutableListOf<ItemCalendarDayBinding>()
        repeat(6) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            repeat(7) {
                val cellBinding = ItemCalendarDayBinding.inflate(layoutInflater, row, false)
                cellBinding.root.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                val index = cells.size
                cellBinding.root.setOnClickListener {
                    // Only days belonging to the displayed month are selectable; the
                    // faded leading/trailing days from adjacent months are display-only.
                    currentDays.getOrNull(index)?.let { if (it.inCurrentMonth) onDayClicked(it) }
                }
                // A light press-down/bounce-back scale on touch, independent of the click
                // itself (returns false so performClick/the listener above still fire) -- a
                // plain ripple alone gave no feedback on a fast tap-and-release, since it's
                // easy to miss against the grid's many identical cells.
                cellBinding.root.setOnTouchListener { view, event -> animateDayPress(view, event) }
                row.addView(cellBinding.root)
                cells.add(cellBinding)
            }
            binding.calendarGrid.addView(row)
        }
        dayCells = cells
    }

    /** Scales a day cell down slightly on touch-down and bounces it back on release, as instant
     *  feedback a tap landed -- independent of [onDayClicked]'s dialog, which doesn't open until
     *  the full click completes. Returns false (never consumes the event) so the view's own
     *  OnClickListener and ripple background still fire normally. A disabled cell (a faded
     *  leading/trailing day from an adjacent month) is left alone, matching that it isn't
     *  clickable either. */
    private fun animateDayPress(view: View, event: android.view.MotionEvent): Boolean {
        if (!view.isClickable) return false
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN ->
                view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                view.animate().scaleX(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
        }
        return false
    }

    /**
     * Schedules the 9am alarm, but also runs an immediate check every time the app is opened:
     * daily reminders were being silently lost because OEM battery managers routinely delay or
     * kill background alarms, so this check doesn't depend on AlarmManager actually firing.
     * Also flags the two other common causes of "the notification never showed up": denied
     * notification permission, and battery optimization being on for this app.
     */
    private fun setupDailyReminderNotification() {
        ReminderScheduler.scheduleNext(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ReminderNotifier.isNotificationPermissionGranted(this)) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runImmediateReminderCheck()
        }

        maybeSuggestDisablingBatteryOptimization()
    }

    private fun runImmediateReminderCheck() {
        lifecycleScope.launch { ReminderNotifier.checkAndNotify(applicationContext) }
    }

    // Only reachable via the POST_NOTIFICATIONS request callback, which is itself gated on
    // SDK_INT >= TIRAMISU (33) at the call site, so ACTION_APP_NOTIFICATION_SETTINGS (API 26+)
    // is always available here despite this module's minSdk of 24.
    @SuppressLint("InlinedApi")
    private fun showNotificationPermissionDeniedSnackbar() {
        Snackbar.make(binding.root, R.string.notifications_disabled_message, Snackbar.LENGTH_LONG)
            .setAction(R.string.notifications_disabled_action) {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
            .show()
    }

    // This is a daily-reminder app whose whole job is firing a notification at an exact time;
    // Doze/App Standby routinely kills that silently, which is the #1 user complaint this
    // screen's manual testing uncovered. The prompt is shown once, only if not already
    // exempted, and only via explicit user tap -- the acceptable-use case Play's battery
    // policy carves out for alarm/reminder apps.
    @SuppressLint("BatteryLife")
    private fun maybeSuggestDisablingBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BATTERY_PROMPT_SHOWN, false)) return
        prefs.edit().putBoolean(KEY_BATTERY_PROMPT_SHOWN, true).apply()

        Snackbar.make(binding.root, R.string.battery_optimization_message, Snackbar.LENGTH_LONG)
            .setAction(R.string.battery_optimization_action) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }
            .show()
    }

    private fun buildWeekdayHeader() {
        val symbols = SimpleDateFormat("EEE", Locale.getDefault())
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        for (i in 0 until 7) {
            // Sunday (i=0) and Saturday (i=6) get the same tint as their day-number column
            // below, instead of the plain dimmed label every other weekday gets.
            val weekendColorRes = when (i) {
                0 -> R.color.calendar_sunday_number
                6 -> R.color.calendar_saturday_number
                else -> null
            }
            val label = android.widget.TextView(this).apply {
                text = symbols.format(cal.time).uppercase(Locale.getDefault())
                gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (weekendColorRes != null) {
                    setTextColor(getColor(weekendColorRes))
                    alpha = 1f
                } else {
                    alpha = 0.7f
                }
            }
            binding.weekdayRow.addView(label)
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
    }

    /** True while [changeMonth]'s slide/fade is mid-flight, so a fast double-tap on the
     *  prev/next buttons (or a swipe right after one) can't start a second animation that
     *  stomps on the first one's end action. */
    private var isAnimatingMonthChange = false
    // by lazy, not a plain field initializer: Activity field initializers run in the
    // constructor, before the base Context is attached, so resources is still null there --
    // this crashed on launch with "Attempt to invoke virtual method ... getResources() on a
    // null object reference" until deferred to first use (well after onCreate).
    private val monthSlideDistancePx by lazy { 24 * resources.displayMetrics.density }

    /** Slides the grid out in the direction of travel while fading it, swaps in the new
     *  month's content at the lowest-opacity point, then slides/fades it back in from the
     *  opposite side -- a lighter version of a ViewPager page transition, instead of the
     *  previous instant content swap. [vertical] picks the axis: a vertical swipe (only
     *  reachable when [CalendarPrefs.isVerticalSwipeEnabled]) animates top/bottom instead of the
     *  usual left/right, matching whichever direction the user actually swiped. */
    private fun changeMonth(delta: Int, vertical: Boolean = false) {
        if (isAnimatingMonthChange) return
        isAnimatingMonthChange = true
        val grid = binding.calendarGrid
        val outDistance = if (delta > 0) -monthSlideDistancePx else monthSlideDistancePx
        val outAnimator = grid.animate()
            .alpha(0f)
            .setDuration(120)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
        if (vertical) outAnimator.translationY(outDistance) else outAnimator.translationX(outDistance)
        outAnimator
            .withEndAction {
                calendar.add(Calendar.MONTH, delta)
                renderMonth()
                if (vertical) grid.translationY = -outDistance else grid.translationX = -outDistance
                val inAnimator = grid.animate()
                    .alpha(1f)
                    .setDuration(180)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                if (vertical) inAnimator.translationY(0f) else inAnimator.translationX(0f)
                inAnimator.withEndAction { isAnimatingMonthChange = false }.start()
            }
            .start()
    }

    /** Tapping the "October 2026" label jumps straight to any day: a plain system date picker,
     *  defaulting to whatever day is currently selected, that both moves the grid to that month
     *  and opens that day's dialog -- a shortcut past however many swipes/taps it'd otherwise
     *  take to get to a date months away. No slide animation here (unlike [changeMonth]): a
     *  jump isn't a swipe in either direction, so there's no natural axis to animate along. */
    private fun showMonthPicker() {
        val initial = Calendar.getInstance().apply { timeInMillis = selectedDayMillis }
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                renderMonth()
                val picked = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                showDayDialog(picked.timeInMillis)
            },
            initial.get(Calendar.YEAR),
            initial.get(Calendar.MONTH),
            initial.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    /** Called only when the displayed month changes: recomputes the grid range. */
    private fun renderMonth() {
        binding.monthLabel.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(calendar.time)
        displayedMonth = calendar.get(Calendar.MONTH)

        val monthStart = calendar.clone() as Calendar
        val leading = monthStart.get(Calendar.DAY_OF_WEEK) - 1 // SUNDAY = 1

        val gridStart = monthStart.clone() as Calendar
        gridStart.add(Calendar.DAY_OF_MONTH, -leading)
        gridStartCalendar = gridStart

        buildDays()
    }

    /** Rebuilds the 42 day cells from [gridStartCalendar]/[displayedMonth], expanding [allReminders] via [RecurrenceUtils]. */
    private fun buildDays() {
        val cursor = gridStartCalendar.clone() as Calendar
        val todayMillis = startOfDay(Calendar.getInstance())
        val days = mutableListOf<CalendarDay>()
        repeat(42) {
            val dayStart = startOfDay(cursor)
            days.add(
                CalendarDay(
                    dayOfMonth = cursor.get(Calendar.DAY_OF_MONTH),
                    dateMillis = dayStart,
                    inCurrentMonth = cursor.get(Calendar.MONTH) == displayedMonth,
                    isToday = dayStart == todayMillis,
                    dotColorIndices = allReminders.filter { RecurrenceUtils.occursOn(it, dayStart) }
                        .map { it.colorIndex }.take(3)
                )
            )
            cursor.add(Calendar.DAY_OF_MONTH, 1)
        }
        renderDays(days)
    }

    private fun renderDays(days: List<CalendarDay>) {
        currentDays = days
        days.forEachIndexed { index, day ->
            val cell = dayCells[index]
            val weekdayColumn = index % 7 // grid always starts on Sunday: see renderMonth()
            cell.dayNumber.text = day.dayOfMonth.toString()
            cell.dayNumber.alpha = if (day.inCurrentMonth) 1f else 0.35f
            cell.todayIndicator.visibility = if (day.isToday) View.VISIBLE else View.INVISIBLE
            cell.root.isClickable = day.inCurrentMonth
            cell.root.isEnabled = day.inCurrentMonth
            cell.dayNumber.setTextColor(
                getColor(
                    when (weekdayColumn) {
                        0 -> R.color.calendar_sunday_number
                        6 -> R.color.calendar_saturday_number
                        else -> R.color.on_surface
                    }
                )
            )
            // Up to 3 dots, one per note due that day (reference screenshots show 1-3
            // small squares stacked for multiple notes rather than a single dot), each tinted
            // to that note's own color instead of one fixed color for every note.
            val dots = listOf(cell.dot1, cell.dot2, cell.dot3)
            dots.forEachIndexed { dotIndex, dot ->
                val colorIndex = day.dotColorIndices.getOrNull(dotIndex)
                if (colorIndex != null) {
                    dot.visibility = View.VISIBLE
                    // mutate() first: the drawable's constant state is otherwise shared across
                    // every dot inflated from the same @drawable/shape_dot resource, so without
                    // it tinting one dot would tint all of them.
                    dot.background.mutate().setTint(getColor(NoteColors.colorResFor(colorIndex)))
                } else {
                    dot.visibility = View.INVISIBLE
                }
            }
        }
    }

    private fun onDayClicked(day: CalendarDay) {
        showDayDialog(day.dateMillis)
    }

    /** Takes a raw millis value (not a [CalendarDay]) so the prev/next arrows keep working
     *  across month boundaries, where the adjacent day isn't necessarily a cell in the
     *  currently-rendered grid. */
    private fun showDayDialog(dateMillis: Long) {
        selectedDayMillis = dateMillis
        val notesForDay = allReminders.filter { RecurrenceUtils.occursOn(it, dateMillis) }
        val dayLabel = SimpleDateFormat("EEE, MMMM d", Locale.getDefault()).format(java.util.Date(dateMillis))
        val oneDayMillis = 24 * 60 * 60 * 1000L

        DayNotesDialog.show(
            this,
            dayLabel = dayLabel,
            dayMillis = dateMillis,
            notes = notesForDay,
            onNoteClick = { note -> openEditorForExisting(note.id, note.type) },
            onToggleComplete = { note, completed -> toggleCompleted(note, dateMillis, completed) },
            onRemove = { note -> removeNote(note) },
            onAddText = { openEditor(dateMillis, Note.TYPE_TEXT) },
            onAddChecklist = { openEditor(dateMillis, Note.TYPE_CHECKLIST) },
            onPrevDay = { showDayDialog(dateMillis - oneDayMillis) },
            onNextDay = { showDayDialog(dateMillis + oneDayMillis) },
            onToggleChecklistItem = { note, index, checked -> toggleChecklistItem(note, index, checked) }
        )
    }

    private fun toggleChecklistItem(note: Note, itemIndex: Int, checked: Boolean) {
        val items = ChecklistItem.parse(note.body).toMutableList()
        if (itemIndex !in items.indices) return
        items[itemIndex] = items[itemIndex].copy(checked = checked)
        lifecycleScope.launch { repository.update(note.copy(body = ChecklistItem.serialize(items))) }
    }

    private fun toggleCompleted(note: Note, dayMillis: Long, completed: Boolean) {
        lifecycleScope.launch {
            repository.update(RecurrenceUtils.withCompletion(note, dayMillis, completed))
            // Updates (or cancels, once nothing's left) the ongoing notification right away
            // instead of waiting for the next app open or the 9am alarm.
            ReminderNotifier.checkAndNotify(applicationContext)
        }
    }

    private fun removeNote(note: Note) {
        lifecycleScope.launch {
            repository.delete(note.id)
            ReminderNotifier.checkAndNotify(applicationContext)
            NoteReminderScheduler.cancel(applicationContext, note.id)
        }
        Snackbar.make(binding.root, R.string.note_deleted_message, Snackbar.LENGTH_LONG)
            .setAction(R.string.action_undo) {
                lifecycleScope.launch {
                    repository.restore(note.id)
                    NoteReminderScheduler.schedule(applicationContext, note)
                }
            }
            .show()
    }

    private fun openEditor(dueDate: Long, type: Int) {
        val intent = Intent(this, NoteEditorActivity::class.java)
        intent.putExtra(NoteEditorActivity.EXTRA_NOTE_TYPE, type)
        intent.putExtra(NoteEditorActivity.EXTRA_DUE_DATE, dueDate)
        startActivity(intent)
    }

    private fun openEditorForExisting(noteId: Long, type: Int) {
        val intent = Intent(this, NoteEditorActivity::class.java)
        intent.putExtra(NoteEditorActivity.EXTRA_NOTE_ID, noteId)
        intent.putExtra(NoteEditorActivity.EXTRA_NOTE_TYPE, type)
        startActivity(intent)
    }

    private fun startOfDay(cal: Calendar): Long = (cal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    companion object {
        private const val PREFS_NAME = "calendar_prefs"
        private const val KEY_BATTERY_PROMPT_SHOWN = "battery_prompt_shown"
        const val EXTRA_OPEN_TODAY = "open_today"
        private const val ACTION_NEW_TEXT_NOTE = "com.example.notepad.action.NEW_TEXT_NOTE"
        private const val ACTION_NEW_CHECKLIST_NOTE = "com.example.notepad.action.NEW_CHECKLIST_NOTE"
    }
}
