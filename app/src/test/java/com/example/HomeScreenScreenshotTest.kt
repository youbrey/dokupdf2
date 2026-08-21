package com.example

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.ui.screens.HomeScreen
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class HomeScreenScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun empty_home_screen_screenshot() {
    composeTestRule.setContent {
      MyApplicationTheme {
        HomeScreen(
          documents = emptyList(),
          searchQuery = "",
          onSearchChange = {},
          onOpenDocument = {},
          onOpenEditor = {},
          onOpenScanner = {},
          onOpenPdfTools = {},
          onOpenAiTools = {},
          onDeleteDocument = {},
          onQuickAction = {}
        )
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "build/outputs/roborazzi/home-empty.png")
  }
}
