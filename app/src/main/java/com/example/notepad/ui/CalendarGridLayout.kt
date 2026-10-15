package com.example.notepad.ui

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Holds the calendar's 6 week-rows and reports a month swipe via [onSwipe], without stealing
 * taps from the day-cell children: [onInterceptTouchEvent] runs for every touch as it flows
 * down to children regardless of what it returns, so the gesture detector sees every event, but
 * always returning false here means children still get normal click handling. Moved out of
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

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return false
    }
}
