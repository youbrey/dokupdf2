package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import com.example.core.crop.AutoCropDetector
import com.example.core.filter.FilterProcessor
import com.example.core.model.CropGeometry
import com.example.core.model.FilterSettings
import com.example.core.model.FilterType
import com.example.core.pdf.PdfSecurity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoCropAndFilterTest {

  @Test
  fun `auto crop detects a bright perspective document on dark background`() {
    val bitmap = Bitmap.createBitmap(800, 1100, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.rgb(40, 45, 50))
    val document = Path().apply {
      moveTo(105f, 90f)
      lineTo(715f, 135f)
      lineTo(675f, 1015f)
      lineTo(135f, 965f)
      close()
    }
    canvas.drawPath(document, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })

    val result = AutoCropDetector.detect(bitmap)

    assertFalse("Detektor seharusnya tidak memakai fallback", result.usedFallback)
    assertTrue(AutoCropDetector.isValidGeometry(result.geometry))
    assertTrue(result.geometry.topLeft.x < 0.30f)
    assertTrue(result.geometry.topRight.x > 0.70f)
    assertTrue(result.geometry.bottomRight.y > 0.75f)
    assertTrue(result.geometry.bottomLeft.y > 0.75f)
    bitmap.recycle()
  }

  @Test
  fun `perspective crop rejects self intersecting geometry without blank output`() {
    val source = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.WHITE)
    }
    val invalid = CropGeometry(
      topLeft = Offset(0.1f, 0.1f),
      topRight = Offset(0.9f, 0.9f),
      bottomRight = Offset(0.9f, 0.1f),
      bottomLeft = Offset(0.1f, 0.9f)
    )

    val cropped = FilterProcessor.cropPerspective(source, invalid)

    assertTrue(cropped.width == source.width && cropped.height == source.height)
    assertTrue(Color.red(cropped.getPixel(160, 240)) > 245)
    cropped.recycle()
    source.recycle()
  }

  @Test
  fun `professional adjustments produce an independent rendered bitmap`() {
    val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.rgb(120, 140, 160))
    }
    val rendered = FilterProcessor.applyFilter(
      source,
      FilterType.ORIGINAL,
      FilterSettings(brightness = 1.2f, contrast = 1.1f, warmth = 0.5f, sharpness = 0.3f)
    )

    assertTrue(rendered !== source)
    assertFalse(rendered.getPixel(32, 32) == source.getPixel(32, 32))
    rendered.recycle()
    source.recycle()
  }

  @Test
  fun `encrypted container authenticates password and restores original pdf`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val security = PdfSecurity(context)
    val source = File.createTempFile("security-source", ".pdf", context.cacheDir)
    val encrypted = File.createTempFile("security-output", ".dokupdf", context.cacheDir)
    val decrypted = File.createTempFile("security-restored", ".pdf", context.cacheDir)
    val wrongPasswordOutput = File.createTempFile("security-wrong", ".pdf", context.cacheDir)
    val sourceBytes = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".toByteArray()
    source.writeBytes(sourceBytes)

    try {
      assertTrue(security.lockPdf(source, encrypted, "kata-sandi-kuat").isSuccess)
      assertTrue(encrypted.readText(Charsets.ISO_8859_1).startsWith("DOKUPDF_ENCRYPTED_V3:"))
      assertTrue(security.unlockPdf(encrypted, decrypted, "kata-sandi-kuat").isSuccess)
      assertArrayEquals(sourceBytes, decrypted.readBytes())
      assertTrue(security.unlockPdf(encrypted, wrongPasswordOutput, "salah").isFailure)
    } finally {
      source.delete()
      encrypted.delete()
      decrypted.delete()
      wrongPasswordOutput.delete()
    }
  }
}
