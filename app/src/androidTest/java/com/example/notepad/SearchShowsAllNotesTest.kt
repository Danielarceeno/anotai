package com.example.notepad

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.ui.SearchActivity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Regression test for a bug reported by the user: opening Search used to show nothing until
 * something was typed (SearchViewModel.results returned an empty list for a blank query), so the
 * screen was unusable as a way to just browse every note. Now a blank query shows everything.
 */
@RunWith(AndroidJUnit4::class)
class SearchShowsAllNotesTest {

    // A plain @Before runs *after* ActivityScenarioRule has already launched the Activity (see
    // CalendarActivityTest's doc comment for the same fix) -- SearchActivity's LiveData could
    // already be observing by the time these notes are inserted, racing SearchViewModel's
    // registration against Room's invalidation callback instead of reliably seeing them from the
    // start. Seeding notes is itself a rule (not resetStateRule + a later @Before) so it runs
    // strictly before SearchActivity launches.
    private val seedNotesRule = object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val db = AppDatabase.getInstance(context)
            val repository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
            runBlocking {
                repository.insert(Note(title = "Alpha note", body = "first"))
                repository.insert(Note(title = "Zebra note", body = "second"))
            }
        }
    }
    private val activityRule = ActivityScenarioRule(SearchActivity::class.java)

    @get:Rule
    val ruleChain: RuleChain =
        RuleChain.outerRule(TestPrereqs.resetStateRule()).around(seedNotesRule).around(activityRule)

    @Test
    fun openingSearchWithNoQuery_showsEveryNote() {
        onView(withId(R.id.recyclerView)).check(matches(hasDescendant(withText("Alpha note"))))
        onView(withId(R.id.recyclerView)).check(matches(hasDescendant(withText("Zebra note"))))
    }
}
