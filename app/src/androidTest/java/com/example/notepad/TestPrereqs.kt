package com.example.notepad

import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.data.AppDatabase
import com.example.notepad.security.LockState
import com.example.notepad.security.SecurityPrefs
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Shared setup so every test starts from a clean, unlocked app state, regardless of what a
 *  previous manual run or test left behind on the device. */
object TestPrereqs {
    fun resetAppState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SecurityPrefs.clearPin(context)
        LockState.markUnlocked()
        AppDatabase.getInstance(context).clearAllTables()
    }

    /** Same reset as [resetAppState], but as a [TestRule] so it can be chained to run *before* an
     *  [androidx.test.ext.junit.rules.ActivityScenarioRule] launches its Activity -- a plain
     *  `@Before` method runs too late for that, since rules wrap `@Before`/`@Test` together. */
    fun resetStateRule(): TestRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                resetAppState()
                base.evaluate()
            }
        }
    }
}
