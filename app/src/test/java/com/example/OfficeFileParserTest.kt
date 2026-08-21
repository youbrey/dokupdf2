package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.core.pdf.OfficeFileParser
import com.example.core.pdf.PdfConverterEngine
import com.example.core.pdf.PdfFileUtils
import com.example.core.pdf.PdfRendererEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class OfficeFileParserTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun `csv parser supports BOM semicolon escaped quotes and embedded newlines`() {
    val rows = OfficeFileParser.parseCsv(
      "\uFEFFNama;Catatan;Nilai\r\n" +
        "Andi;\"teks; dengan pemisah\";10\r\n" +
        "Sari;\"baris satu\nbaris dua dan \"\"kutip\"\"\";20"
    )

    assertEquals(listOf("Nama", "Catatan", "Nilai"), rows[0])
    assertEquals(listOf("Andi", "teks; dengan pemisah", "10"), rows[1])
    assertEquals(listOf("Sari", "baris satu\nbaris dua dan \"kutip\"", "20"), rows[2])
  }

  @Test
  fun `docx parser reads real paragraph text`() {
    val docx = File.createTempFile("office-parser", ".docx", context.cacheDir)
    try {
      writeZip(
        docx,
        mapOf(
          "word/document.xml" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t xml:space="preserve">Halo </w:t></w:r><w:r><w:t>Dunia</w:t></w:r></w:p>
                <w:p><w:r><w:t>Baris kedua</w:t></w:r></w:p>
              </w:body>
            </w:document>
          """.trimIndent()
        )
      )

      assertEquals(listOf("Halo Dunia", "Baris kedua"), OfficeFileParser.readWordLines(docx))
    } finally {
      docx.delete()
    }
  }

  @Test
  fun `xlsx parser keeps sparse columns and decodes shared strings`() {
    val xlsx = File.createTempFile("office-parser", ".xlsx", context.cacheDir)
    try {
      writeZip(
        xlsx,
        mapOf(
          "xl/sharedStrings.xml" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <si><t>Nama</t></si><si><t>Nilai</t></si>
            </sst>
          """.trimIndent(),
          "xl/worksheets/sheet1.xml" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="s"><v>1</v></c></row>
                <row r="2"><c r="A2" t="inlineStr"><is><t>Andi</t></is></c><c r="B2"><v>95</v></c><c r="C2" t="b"><v>1</v></c></row>
                <row r="3"><c t="inlineStr"><is><t>Urut A</t></is></c><c t="inlineStr"><is><t>Urut B</t></is></c></row>
              </sheetData>
            </worksheet>
          """.trimIndent(),
          "xl/worksheets/sheet2.xml" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1"><c r="A1" t="inlineStr"><is><t>Data lembar kedua</t></is></c></row>
              </sheetData>
            </worksheet>
          """.trimIndent()
        )
      )

      val rows = OfficeFileParser.readSpreadsheet(xlsx)
      assertEquals(listOf("Nama", "", "Nilai"), rows[0])
      assertEquals(listOf("Andi", "95", "TRUE"), rows[1])
      assertEquals(listOf("Urut A", "Urut B"), rows[2])
      assertEquals(listOf("— Lembar 2 —"), rows[3])
      assertEquals(listOf("Data lembar kedua"), rows[4])
    } finally {
      xlsx.delete()
    }
  }

  @Test
  fun `atomic writer preserves previous output when generation fails`() = runTest {
    val output = File.createTempFile("atomic-output", ".pdf", context.cacheDir)
    output.writeText("hasil-lama")

    try {
      val failure = runCatching {
        PdfFileUtils.writeAtomically(output) { temporary ->
          temporary.writeText("hasil-baru-sementara")
          error("simulasi kegagalan")
        }
      }

      assertTrue(failure.isFailure)
      assertEquals("hasil-lama", output.readText())
    } finally {
      output.delete()
    }
  }

  @Test
  fun `atomic writer does not publish a new output when generation fails`() = runTest {
    val directory = File(context.cacheDir, "atomic-new-failure-${System.nanoTime()}").apply { mkdirs() }
    val output = File(directory, "result.pdf")
    try {
      val failure = runCatching {
        PdfFileUtils.writeAtomically(output) { temporary ->
          temporary.writeText("keluaran-belum-lengkap")
          error("simulasi kegagalan sebelum commit")
        }
      }

      assertTrue(failure.isFailure)
      assertFalse(output.exists())
      assertTrue(directory.listFiles().orEmpty().isEmpty())
    } finally {
      output.delete()
      directory.delete()
    }
  }

  @Test
  fun `atomic writer replaces a completed output without leaving temporary files`() = runTest {
    val directory = File(context.cacheDir, "atomic-success-${System.nanoTime()}").apply { mkdirs() }
    val output = File(directory, "result.pdf").apply { writeText("hasil-lama") }
    try {
      PdfFileUtils.writeAtomically(output) { temporary ->
        temporary.writeText("hasil-baru-lengkap")
      }

      assertEquals("hasil-baru-lengkap", output.readText())
      assertEquals(listOf(output), directory.listFiles().orEmpty().toList())
    } finally {
      output.delete()
      directory.delete()
    }
  }

  @Test
  fun `lazy bitmap PDF conversion releases each generated page`() = runTest {
    val output = File.createTempFile("lazy-pages", ".pdf", context.cacheDir)
    val converter = PdfConverterEngine(context)
    var previous: Bitmap? = null
    try {
      val result = converter.generatedBitmapsToPdf(6, output) { index ->
        previous?.let { assertTrue("Halaman sebelumnya harus sudah dilepas", it.isRecycled) }
        Bitmap.createBitmap(220, 320, Bitmap.Config.ARGB_8888).apply {
          eraseColor(if (index % 2 == 0) Color.WHITE else Color.LTGRAY)
          previous = this
        }
      }

      assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
      assertTrue(previous?.isRecycled == true)
      assertEquals(6, PdfRendererEngine(context).getPageCount(output))
    } finally {
      converter.close()
      previous?.let { if (!it.isRecycled) it.recycle() }
      output.delete()
    }
  }

  @Test
  fun `word and wide spreadsheet conversion paginate instead of truncating`() = runTest {
    val wordPdf = File.createTempFile("word-pages", ".pdf", context.cacheDir)
    val sheetPdf = File.createTempFile("sheet-pages", ".pdf", context.cacheDir)
    val converter = PdfConverterEngine(context)
    val renderer = PdfRendererEngine(context)
    try {
      val wordLines = (1..140).map { "Baris $it berisi teks panjang yang tetap harus masuk ke dokumen hasil." }
      assertTrue(converter.wordLinesToPdf(wordLines, "Uji Word", wordPdf).isSuccess)
      assertTrue("Dokumen panjang harus lebih dari satu halaman", renderer.getPageCount(wordPdf) > 1)

      val rows = (0..90).map { row -> (0..11).map { column -> "R${row}C$column" } }
      assertTrue(converter.excelRowsToPdf(rows, "Uji Spreadsheet", sheetPdf).isSuccess)
      assertTrue("Baris dan kelompok kolom harus dipaginasi", renderer.getPageCount(sheetPdf) > 2)
    } finally {
      converter.close()
      wordPdf.delete()
      sheetPdf.delete()
    }
  }

  private fun writeZip(file: File, entries: Map<String, String>) {
    ZipOutputStream(FileOutputStream(file)).use { zip ->
      entries.forEach { (name, contents) ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(contents.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
      }
    }
    assertFalse(file.length() == 0L)
  }
}
