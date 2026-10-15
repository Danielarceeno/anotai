package com.example.notepad.ui

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Holds the calendar's 6 week-rows and reports a month swipe via [onSwipe]. A tap passes
 * straight through to the day-cell child's own click listener; only once the finger moves past
 * touch-slop does this view start intercepting, so a drag used to swipe between months can't
 * also register as a click on whatever day cell it happened to start on. Moved out of
 * CalendarActivity (where this used to be a RecyclerView's OnItemTouchListener) along with the
 * switch away from RecyclerView for the day grid.
 *
 * Lower thresholds than a generic fling detector (half the distance, ~40% of the velocity) so a
 * normal-paced swipe changes the month right away instead of needing an exaggerated flick.
 * Horizontal swipes move between months (left = next, right = previous), and vertical ones do
 * the same (up = next, down = previous), so either direction works.
 */
class CalendarGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** true = next month, false = previous month. */
    var onSwipe: ((next: Boolean) -> Unit)? = null

    private val swipeThresholdPx = 40 * resources.displayMetrics.density
    private val swipeVelocityThresholdPx = 60 * resources.displayMetrics.density

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val startX = e1?.x ?: return false
            val startY = e1.y
            val deltaX = e2.x - startX
            val deltaY = e2.y - startY

            return if (abs(deltaX) >= abs(deltaY)) {
                if (abs(deltaX) > swipeThresholdPx && abs(velocityX) > swipeVelocityThresholdPx) {
                    onSwipe?.invoke(deltaX < 0)
                    true
                } else {
                    false
                }
            } else {
                if (abs(deltaY) > swipeThresholdPx && abs(velocityY) > swipeVelocityThresholdPx) {
                    onSwipe?.invoke(deltaY < 0)
                    true
                } else {
                    false
                }
            }
        }
    })

    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop
    private var isDragging = false
    private var downX = 0f
    private var downY = 0f

    /**
     * Only starts intercepting once the finger has actually moved past touch-slop, so a plain
     * tap still reaches the day cell's own click listener untouched. Once a drag is detected,
     * this view takes over the touch stream (the child gets an ACTION_CANCEL, clearing its
     * pressed state) so a day can't be "clicked" by a finger that was really just swiping
     * through it to change month.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                downX = ev.x
                downY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging && (abs(ev.x - downX) > touchSlopPx || abs(ev.y - downY) > touchSlopPx)) {
                    isDragging = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDragging = false
        }
        gestureDetector.onTouchEvent(ev)
        return isDragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            isDragging = false
        }
        return true
    }
}
