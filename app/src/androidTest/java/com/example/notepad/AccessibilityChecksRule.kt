package com.example.notepad

import androidx.test.espresso.accessibility.AccessibilityChecks
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Per the Fase 8 mission: turns on Espresso's built-in accessibility validator
 * ([AccessibilityChecks]) for the duration of a test class -- every subsequent Espresso
 * `ViewAction` (click, typeText, ...) is automatically checked against a standard set of rules
 * (missing `contentDescription` on an icon-only view, a touch target smaller than 48dp, low
 * text contrast, ...) and fails the test if one's hit, without writing a single assertion for it.
 *
 * Applied to a handful of the app's main flows (not every single test class). `AccessibilityChecks`
 * has no `disable()` -- once enabled, it stays enabled for the rest of this test process, so in a
 * full-suite run it also, harmlessly, ends up checking whatever other tests happen to run
 * afterward too (there's no way to scope it tighter than that with this API). That's an accepted
 * side effect, not a bug: any test it newly catches a real violation in is a real finding either
 * way, this class just controls *where that coverage starts* in a full run.
 */
class AccessibilityChecksRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            AccessibilityChecks.enable()
            base.evaluate()
        }
    }
}
