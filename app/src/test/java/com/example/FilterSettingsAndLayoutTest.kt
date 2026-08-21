package com.example

import com.example.core.command.AddPageCommand
import com.example.core.command.CommandManager
import com.example.core.layout.LayoutEngine
import com.example.core.model.DocumentModel
import com.example.core.model.FilterSettings
import com.example.core.model.PageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FilterSettingsAndLayoutTest {
  @Test
  fun `settings are clamped to supported professional ranges`() {
    val normalized = FilterSettings(
      brightness = 4f,
      contrast = -2f,
      saturation = 9f,
      warmth = -4f,
      sharpness = 3f
    ).normalized()

    assertEquals(1.35f, normalized.brightness, 0.001f)
    assertEquals(0.65f, normalized.contrast, 0.001f)
    assertEquals(2f, normalized.saturation, 0.001f)
    assertEquals(-1f, normalized.warmth, 0.001f)
    assertEquals(1.5f, normalized.sharpness, 0.001f)
  }

  @Test
  fun `command history retains only the newest one hundred edits`() {
    val manager = CommandManager(DocumentModel())
    repeat(150) { manager.execute(AddPageCommand(PageModel())) }

    var undoCount = 0
    while (manager.undo()) undoCount++

    assertEquals(100, undoCount)
    assertEquals(50, manager.documentState.value.pages.size)
    assertFalse(manager.canUndo.value)
  }

  @Test
  fun `mixed page widths are centered against the final document width`() {
    val layout = LayoutEngine(pagePadding = 16f).computeLayout(
      pages = listOf(
        PageModel(width = 500f, height = 700f),
        PageModel(width = 800f, height = 500f)
      ),
      viewportWidth = 600f
    )

    assertEquals(832f, layout.totalWidth, 0.001f)
    assertEquals(layout.totalWidth / 2f, layout.pages[0].bounds.center.x, 0.001f)
    assertEquals(layout.totalWidth / 2f, layout.pages[1].bounds.center.x, 0.001f)
  }
}
