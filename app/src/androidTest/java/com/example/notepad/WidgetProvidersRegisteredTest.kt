package com.example.notepad

import android.appwidget.AppWidgetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.widget.TodayNotes1x1WidgetProvider
import com.example.notepad.widget.TodayNotes2x2WidgetProvider
import com.example.notepad.widget.TodayNotesSummaryWidgetProvider
import com.example.notepad.widget.TodayNotesWidgetProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression guard for the three fixed-size widgets added alongside the original resizable one:
 * each is its own `<receiver>` + `appwidget-provider` XML in the manifest, which is easy to get
 * subtly wrong (a typo in the class name, a missing meta-data resource, a malformed XML file)
 * without it showing up anywhere except the launcher's widget picker silently not listing one.
 * [AppWidgetManager.getInstalledProviders] is what that picker itself reads from, so checking it
 * here catches a manifest/resource mistake without a human having to open the picker by hand.
 */
@RunWith(AndroidJUnit4::class)
class WidgetProvidersRegisteredTest {

    @Test
    fun allFourWidgetProviders_areInstalledAndSized() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = AppWidgetManager.getInstance(context)
        // getInstalledProvidersForPackage(String, UserHandle) needs API 26; getInstalledProviders
        // (filtered by hand below) has worked since API 1 and this app's minSdk is 24.
        val providers = manager.installedProviders.filter { it.provider.packageName == context.packageName }

        val expectedClasses = setOf(
            TodayNotesWidgetProvider::class.java.name,
            TodayNotes1x1WidgetProvider::class.java.name,
            TodayNotes2x2WidgetProvider::class.java.name,
            TodayNotesSummaryWidgetProvider::class.java.name
        )
        val installedClasses = providers.map { it.provider.className }.toSet()

        expectedClasses.forEach { expected ->
            assertTrue(
                "expected widget provider $expected to be installed, found: $installedClasses",
                expected in installedClasses
            )
        }

        // Each fixed-size widget has a non-zero minimum size parsed from its own
        // widget_today_*_info.xml -- catches a malformed one that AppWidgetManager silently
        // dropped instead of failing loudly.
        providers.filter { it.provider.className != TodayNotesWidgetProvider::class.java.name }
            .forEach { info ->
                assertTrue("expected a positive minWidth for ${info.provider.className}", info.minWidth > 0)
                assertTrue("expected a positive minHeight for ${info.provider.className}", info.minHeight > 0)
            }
    }
}
