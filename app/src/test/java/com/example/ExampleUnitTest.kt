package com.example

import com.example.core.model.FilterSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterSettingsTest {
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
}
