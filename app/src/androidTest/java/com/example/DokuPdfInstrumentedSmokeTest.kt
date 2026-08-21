package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device-level navigation smoke test for the real application surface. */
@RunWith(AndroidJUnit4::class)
class DokuPdfInstrumentedSmokeTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun homeOpensBlankDocumentEditor() {
    composeRule.onNodeWithText("DokuPDF").assertIsDisplayed()
    composeRule.onNodeWithTag("hero_editor_banner").performClick()
    composeRule.onNodeWithTag("document_canvas_container").assertIsDisplayed()
    composeRule.onNodeWithTag("editor_export_pdf_btn").assertIsDisplayed()
  }
}
