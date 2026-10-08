package com.fenyx.jtv.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTextTest {
    @Test fun keepsOnlyTheNewSection_withoutMarkdown() {
        val md = """
            <p align="center"><img src="x" /></p>

            # JTV 2.0 beta 8

            > ⚠️ **Pre-release.** blurb

            ## New in beta 8
            - **Clearer message.** text
            - **No leftover picture.**

            ## Known limits
            - limit
        """.trimIndent()
        assertEquals("- Clearer message. text\n- No leftover picture.", releaseNotesText(md))
    }

    @Test fun keepsIssueNumbers_dropsHeadingMarks() {
        assertEquals("Fix\nSuggested in #5.", releaseNotesText("### Fix\nSuggested in #5."))
    }

    @Test fun noSection_fallsBackToWholeText() {
        assertEquals("Fixed a bug.", releaseNotesText("**Fixed** a bug.".replace("**Fixed**", "Fixed")))
    }
}
