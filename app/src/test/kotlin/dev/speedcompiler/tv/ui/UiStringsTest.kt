package dev.speedcompiler.tv.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.speedcompiler.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.io.File

/** User-visible text lives in strings.xml (B-10). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class UiStringsTest {
    private val textLiteral = Regex("""\bText\(\s*"[^"]*\p{L}""")
    private val sentence = Regex(""""[A-Z][a-z']+( [A-Za-z']+)+""")
    // Stored engine text is matched as a key in storedText(), not shown as written here.
    private val allowed = Regex(""" to R\.string\.stored_""")

    @Test fun noHardcodedEnglishInUi() {
        // Tests run in app/.
        val hits = File("src/main/kotlin/dev/speedcompiler/tv/ui").walk().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, raw ->
                val line = raw.substringBefore("//").trim()
                val code = !line.startsWith("*") && !line.startsWith("/*")
                if (code && !allowed.containsMatchIn(line) && (textLiteral.containsMatchIn(line) || sentence.containsMatchIn(line))) "${f.name}:${i + 1}: $line" else null
            }
        }.toList()
        assertTrue("Move these to strings.xml:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test fun pluralForms() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        assertEquals("1 app", res.getQuantityString(R.plurals.apps_count, 1, 1))
        assertEquals("3 apps", res.getQuantityString(R.plurals.apps_count, 3, 3))
        assertEquals("Nuvio and 1 other", res.getQuantityString(R.plurals.setup_subject_others, 1, "Nuvio", 1))
        assertEquals("Nuvio and 2 others", res.getQuantityString(R.plurals.setup_subject_others, 2, "Nuvio", 2))
        assertEquals("Cancel the 1 app in the queue?", res.getQuantityString(R.plurals.activity_confirm_cancel, 1, 1))
        assertEquals("Cancel all 4 apps in the queue?", res.getQuantityString(R.plurals.activity_confirm_cancel, 4, 4))
    }
}
