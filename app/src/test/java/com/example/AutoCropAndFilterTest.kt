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
import com.example.core.crop.DocumentDewarpProcessor
import com.example.core.filter.FilterProcessor
import com.example.core.model.CropGeometry
import com.example.core.model.FilterSettings
import com.example.core.model.FilterType
import com.example.core.pdf.PdfSecurity
import com.example.ui.screens.ScannedPageItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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

    try {
      val result = AutoCropDetector.detect(bitmap)
      val diagnostic =
        "reason=${result.failureReason}, confidence=${result.confidence}, geometry=${result.geometry}"

      assertFalse("Detektor seharusnya tidak memakai fallback: $diagnostic", result.usedFallback)
      assertTrue("Geometri deteksi tidak valid: $diagnostic", AutoCropDetector.isValidGeometry(result.geometry))
      assertTrue("Sudut kiri atas tidak terdeteksi: $diagnostic", result.geometry.topLeft.x < 0.30f)
      assertTrue("Sudut kanan atas tidak terdeteksi: $diagnostic", result.geometry.topRight.x > 0.70f)
      assertTrue("Sudut kanan bawah tidak terdeteksi: $diagnostic", result.geometry.bottomRight.y > 0.75f)
      assertTrue("Sudut kiri bawah tidak terdeteksi: $diagnostic", result.geometry.bottomLeft.y > 0.75f)
    } finally {
      bitmap.recycle()
    }
  }

  @Test
  fun `auto crop prefers the outer page over stronger internal table rules`() {
    val bitmap = Bitmap.createBitmap(820, 1120, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.rgb(34, 38, 42))
    val page = Path().apply {
      moveTo(82f, 76f)
      lineTo(744f, 118f)
      lineTo(706f, 1048f)
      lineTo(116f, 1002f)
      close()
    }
    canvas.drawPath(page, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(238, 238, 232) })

    // Dense, high-contrast rules deliberately mimic the failure case in the supplied form:
    // their raw gradient is stronger than parts of the paper/background boundary.
    val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.BLACK
      style = Paint.Style.STROKE
      strokeWidth = 7f
    }
    for (row in 0 until 8) {
      val y = 270f + row * 58f
      canvas.drawLine(150f, y, 670f, y + 18f, rulePaint)
    }
    canvas.drawLine(310f, 245f, 300f, 735f, rulePaint)
    canvas.drawLine(515f, 255f, 530f, 742f, rulePaint)

    try {
      val result = AutoCropDetector.detect(bitmap)
      val diagnostic = "reason=${result.failureReason}, confidence=${result.confidence}, geometry=${result.geometry}"

      assertFalse("Detektor memilih fallback: $diagnostic", result.usedFallback)
      assertTrue("Sisi atas halaman terpotong oleh garis tabel: $diagnostic", result.geometry.topLeft.y < 0.18f)
      assertTrue("Sisi bawah halaman terpotong oleh garis tabel: $diagnostic", result.geometry.bottomRight.y > 0.82f)
      assertTrue("Sisi kiri halaman terpotong oleh garis tabel: $diagnostic", result.geometry.topLeft.x < 0.22f)
      assertTrue("Sisi kanan halaman terpotong oleh garis tabel: $diagnostic", result.geometry.topRight.x > 0.78f)
    } finally {
      bitmap.recycle()
    }
  }

  @Test
  fun `auto crop reports a deterministic fallback for a flat image`() {
    val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.rgb(120, 120, 120))
    }

    try {
      val result = AutoCropDetector.detect(bitmap)

      assertTrue("Gambar tanpa tepi harus memakai fallback", result.usedFallback)
      assertEquals("insufficient_edge_coverage", result.failureReason)
      assertEquals(0f, result.confidence, 0f)
      assertTrue(AutoCropDetector.isValidGeometry(result.geometry))
    } finally {
      bitmap.recycle()
    }
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
  fun `mesh dewarp straightens bowed document rules`() {
    val source = Bitmap.createBitmap(720, 960, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(source)
    canvas.drawColor(Color.WHITE)
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.BLACK
      style = Paint.Style.STROKE
      strokeWidth = 4f
    }
    val rows = listOf(130f, 280f, 430f, 580f, 730f, 860f)
    rows.forEachIndexed { index, y ->
      val bend = if (index % 2 == 0) 20f else 15f
      val path = Path().apply {
        moveTo(32f, y)
        quadTo(360f, y + bend, 688f, y)
      }
      canvas.drawPath(path, linePaint)
    }

    val beforeSpread = rows.map { curvedLineSpread(source, it.toInt(), 34) }.average()
    val result = DocumentDewarpProcessor.flatten(source)
    try {
      val afterSpread = rows.map { curvedLineSpread(result.bitmap, it.toInt(), 44) }.average()
      val diagnostic =
        "applied=${result.applied}, confidence=${result.confidence}, lines=${result.controlLineCount}, before=$beforeSpread, after=$afterSpread"

      assertTrue("Dewarp tidak aktif pada struktur garis yang jelas: $diagnostic", result.applied)
      assertTrue("Dewarp tidak cukup meratakan garis: $diagnostic", afterSpread < beforeSpread * 0.62)
    } finally {
      if (result.bitmap !== source) result.bitmap.recycle()
      source.recycle()
    }
  }

  @Test
  fun `mesh dewarp leaves an unstructured photo untouched`() {
    val source = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(source.width * source.height)
    for (y in 0 until source.height) {
      for (x in 0 until source.width) {
        val red = (70 + x * 120 / source.width).coerceIn(0, 255)
        val green = (55 + y * 130 / source.height).coerceIn(0, 255)
        val blue = (90 + (x + y) * 70 / (source.width + source.height)).coerceIn(0, 255)
        pixels[y * source.width + x] = Color.rgb(red, green, blue)
      }
    }
    source.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)

    try {
      val result = DocumentDewarpProcessor.flatten(source)
      assertFalse("Foto tanpa garis dokumen tidak boleh didewarp", result.applied)
      assertTrue("Jalur tanpa dewarp harus allocation-free", result.bitmap === source)
    } finally {
      source.recycle()
    }
  }

  @Test
  fun `automatic document filter removes uneven paper shadow and deepens ink`() {
    val width = 420
    val height = 620
    val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val shade = (158 + x * 58 / width + y * 12 / height).coerceIn(0, 255)
        pixels[y * width + x] = Color.rgb(shade, (shade - 4).coerceAtLeast(0), (shade - 18).coerceAtLeast(0))
      }
    }
    source.setPixels(pixels, 0, width, 0, 0, width, height)
    val canvas = Canvas(source)
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 36, 38); strokeWidth = 4f }
    for (y in listOf(120f, 220f, 320f, 420f, 520f)) canvas.drawLine(35f, y, 385f, y, ink)

    val filtered = FilterProcessor.applyFilter(source, FilterType.AUTO, FilterSettings())
    try {
      val paperLeft = luminance(filtered.getPixel(70, 80))
      val paperRight = luminance(filtered.getPixel(350, 80))
      val inkLuma = luminance(filtered.getPixel(210, 320))
      val diagnostic = "paperLeft=$paperLeft, paperRight=$paperRight, ink=$inkLuma"

      assertTrue("Latar kertas belum dinormalisasi ke putih: $diagnostic", paperLeft > 235 && paperRight > 235)
      assertTrue("Bayangan kertas masih tidak merata: $diagnostic", kotlin.math.abs(paperLeft - paperRight) < 12)
      assertTrue("Garis tinta belum cukup kontras: $diagnostic", minOf(paperLeft, paperRight) - inkLuma > 150)
    } finally {
      if (filtered !== source) filtered.recycle()
      source.recycle()
    }
  }

  @Test
  fun `professional adjustments produce an independent rendered bitmap`() {
    val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.rgb(120, 140, 160))
    }
    val sourcePixel = source.getPixel(32, 32)
    val rendered = FilterProcessor.applyFilter(
      source,
      FilterType.ORIGINAL,
      FilterSettings(brightness = 1.2f, contrast = 1.1f, warmth = 0.5f, sharpness = 0.3f)
    )

    try {
      val renderedPixel = rendered.getPixel(32, 32)
      val diagnostic =
        "source=${sourcePixel.toUInt().toString(16)}, rendered=${renderedPixel.toUInt().toString(16)}"
      assertTrue("Filter harus menghasilkan bitmap independen: $diagnostic", rendered !== source)
      assertFalse("Penyesuaian profesional harus mengubah warna piksel: $diagnostic", renderedPixel == sourcePixel)
      assertTrue("Brightness harus meningkatkan kanal merah: $diagnostic", Color.red(renderedPixel) > Color.red(sourcePixel))
      assertTrue("Brightness harus meningkatkan kanal hijau: $diagnostic", Color.green(renderedPixel) > Color.green(sourcePixel))
      assertTrue("Brightness harus meningkatkan kanal biru: $diagnostic", Color.blue(renderedPixel) > Color.blue(sourcePixel))
    } finally {
      if (rendered !== source) rendered.recycle()
      source.recycle()
    }
  }

  @Test
  fun `neutral original filter remains lossless and allocation free`() {
    val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.rgb(80, 110, 140))
    }

    try {
      val rendered = FilterProcessor.applyFilter(source, FilterType.ORIGINAL, FilterSettings())

      assertTrue("ORIGINAL netral harus mengembalikan bitmap sumber", rendered === source)
      assertEquals(source.getPixel(16, 16), rendered.getPixel(16, 16))
    } finally {
      source.recycle()
    }
  }

  @Test
  fun `scanner rendering never recycles bitmap owned by Compose state`() {
    val source = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.WHITE)
    }
    val page = ScannedPageItem(
      originalBitmap = source,
      filterType = FilterType.ORIGINAL,
      filterSettings = FilterSettings()
    )

    val rendered = page.getRenderedBitmap(maxDimension = 240)
    try {
      assertFalse("Bitmap sumber UI tidak boleh di-recycle", source.isRecycled)
      assertTrue("Hasil render harus memiliki ownership terpisah", rendered !== source)
      assertTrue(rendered.width <= 240 && rendered.height <= 240)
    } finally {
      rendered.recycle()
      source.recycle()
    }
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

  private fun curvedLineSpread(bitmap: Bitmap, approximateY: Int, radius: Int): Int {
    var minimum = Int.MAX_VALUE
    var maximum = Int.MIN_VALUE
    for (x in 48 until bitmap.width - 48 step 24) {
      var darkestY = approximateY
      var darkest = Int.MAX_VALUE
      for (y in (approximateY - radius).coerceAtLeast(1)..(approximateY + radius).coerceAtMost(bitmap.height - 2)) {
        val value = luminance(bitmap.getPixel(x, y))
        if (value < darkest) {
          darkest = value
          darkestY = y
        }
      }
      minimum = minOf(minimum, darkestY)
      maximum = maxOf(maximum, darkestY)
    }
    return (maximum - minimum).coerceAtLeast(0)
  }

  private fun luminance(color: Int): Int =
    (299 * Color.red(color) + 587 * Color.green(color) + 114 * Color.blue(color) + 500) / 1000
}
