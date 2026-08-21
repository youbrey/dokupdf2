package com.example.core.pdf

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal object OfficeFileParser {
    private const val MAX_XML_BYTES = 24 * 1024 * 1024
    private const val MAX_ROWS = 20_000
    private const val MAX_COLUMNS = 256

    fun readWordLines(file: File): List<String> {
        PdfFileUtils.requireReadableFile(file, "Dokumen Word/Teks", PdfFileUtils.MAX_OFFICE_INPUT_BYTES)
        return when (file.extension.lowercase(Locale.US)) {
            "txt" -> readPlainTextLines(file)
            "docx" -> readDocxParagraphs(file)
            else -> throw IllegalArgumentException("Format '${file.extension}' tidak didukung; pilih DOCX atau TXT")
        }
    }

    fun readSpreadsheet(file: File): List<List<String>> {
        PdfFileUtils.requireReadableFile(file, "Spreadsheet", PdfFileUtils.MAX_OFFICE_INPUT_BYTES)
        return when (file.extension.lowercase(Locale.US)) {
            "csv" -> file.inputStream().use { input ->
                parseCsv(input.readLimited(MAX_XML_BYTES).toString(Charsets.UTF_8))
            }
            "xlsx" -> readXlsxSheets(file)
            "xls" -> throw IllegalArgumentException("Format XLS lama belum didukung; simpan ulang sebagai XLSX atau CSV")
            else -> throw IllegalArgumentException("Format '${file.extension}' tidak didukung; pilih XLSX atau CSV")
        }
    }

    fun parseCsv(content: String): List<List<String>> {
        require(content.length <= MAX_XML_BYTES) { "CSV terlalu besar untuk diproses" }
        val normalizedContent = content.removePrefix("\uFEFF")
        val delimiter = detectDelimiter(normalizedContent)
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0

        fun finishField() {
            require(row.size < MAX_COLUMNS) { "CSV melebihi batas $MAX_COLUMNS kolom" }
            row += field.toString()
            field.setLength(0)
        }

        fun finishRow() {
            finishField()
            if (row.any { it.isNotBlank() }) {
                require(rows.size < MAX_ROWS) { "CSV melebihi batas $MAX_ROWS baris" }
                rows += row.toList()
            }
            row.clear()
        }

        while (index < normalizedContent.length) {
            val character = normalizedContent[index]
            when {
                inQuotes && character == '"' && index + 1 < normalizedContent.length && normalizedContent[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                character == '"' -> inQuotes = !inQuotes
                !inQuotes && character == delimiter -> finishField()
                !inQuotes && (character == '\n' || character == '\r') -> {
                    finishRow()
                    if (character == '\r' && index + 1 < normalizedContent.length && normalizedContent[index + 1] == '\n') index++
                }
                else -> field.append(character)
            }
            index++
        }
        require(!inQuotes) { "CSV tidak valid: tanda kutip tidak ditutup" }
        if (field.isNotEmpty() || row.isNotEmpty()) finishRow()
        return rows
    }

    private fun readPlainTextLines(file: File): List<String> {
        val result = mutableListOf<String>()
        var totalCharacters = 0L
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                require(result.size < MAX_ROWS) { "Dokumen teks melebihi batas $MAX_ROWS baris" }
                totalCharacters += line.length
                require(totalCharacters <= MAX_XML_BYTES) { "Dokumen teks terlalu besar untuk diproses" }
                result += line
            }
        }
        return result
    }

    private fun detectDelimiter(content: String): Char {
        val counts = linkedMapOf(',' to 0, ';' to 0, '\t' to 0)
        var inQuotes = false
        for (character in content) {
            if (character == '"') inQuotes = !inQuotes
            else if (!inQuotes && (character == '\n' || character == '\r')) break
            else if (!inQuotes && character in counts) counts[character] = counts.getValue(character) + 1
        }
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }

    private fun readDocxParagraphs(file: File): List<String> = ZipFile(file).use { zip ->
        val documentEntry = requireNotNull(zip.getEntry("word/document.xml")) {
            "DOCX tidak valid: word/document.xml tidak ditemukan"
        }
        val parser = parserFor(zip, documentEntry)
        val paragraphs = mutableListOf<String>()
        var paragraph = StringBuilder()
        var insideText = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.localName()) {
                    "t" -> insideText = true
                    "tab" -> paragraph.append('\t')
                    "br" -> paragraph.append('\n')
                }
                XmlPullParser.TEXT -> if (insideText) paragraph.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.localName()) {
                    "t" -> insideText = false
                    "p" -> {
                        val value = paragraph.toString().trimEnd()
                        if (value.isNotBlank()) {
                            require(paragraphs.size < MAX_ROWS) { "DOCX melebihi batas $MAX_ROWS paragraf" }
                            paragraphs += value
                        }
                        paragraph = StringBuilder()
                    }
                }
            }
            parser.next()
        }
        if (paragraph.isNotBlank()) {
            require(paragraphs.size < MAX_ROWS) { "DOCX melebihi batas $MAX_ROWS paragraf" }
            paragraphs += paragraph.toString().trimEnd()
        }
        require(paragraphs.isNotEmpty()) { "DOCX tidak mengandung teks yang dapat dikonversi" }
        paragraphs
    }

    private fun readXlsxSheets(file: File): List<List<String>> = ZipFile(file).use { zip ->
        val sharedStrings = zip.getEntry("xl/sharedStrings.xml")?.let { readSharedStrings(zip, it) }.orEmpty()
        val sheetEntries = zip.entries().asSequence()
            .filter { !it.isDirectory && Regex("xl/worksheets/sheet\\d+\\.xml").matches(it.name) }
            .sortedBy { sheetNumber(it.name) }
            .toList()
        require(sheetEntries.isNotEmpty()) { "XLSX tidak memiliki worksheet" }

        val allRows = mutableListOf<List<String>>()
        sheetEntries.forEach { sheetEntry ->
            val sheetRows = readXlsxSheet(zip, sheetEntry, sharedStrings)
            if (sheetRows.isNotEmpty()) {
                if (allRows.isNotEmpty()) {
                    require(allRows.size < MAX_ROWS) { "XLSX melebihi batas $MAX_ROWS baris" }
                    allRows += listOf("— Lembar ${sheetNumber(sheetEntry.name)} —")
                }
                require(allRows.size + sheetRows.size <= MAX_ROWS) {
                    "XLSX melebihi batas $MAX_ROWS baris"
                }
                allRows += sheetRows
            }
        }
        require(allRows.isNotEmpty()) { "XLSX tidak memiliki worksheet berisi data" }
        allRows
    }

    private fun readXlsxSheet(
        zip: ZipFile,
        sheetEntry: ZipEntry,
        sharedStrings: List<String>
    ): List<List<String>> {
        val parser = parserFor(zip, sheetEntry)
        val rows = mutableListOf<List<String>>()
        var currentRow = sortedMapOf<Int, String>()
        var currentColumn = 0
        var nextSequentialColumn = 0
        var currentType: String? = null
        var currentValue: String? = null
        var inlineText = StringBuilder()
        var insideValue = false
        var insideInlineText = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.localName()) {
                    "row" -> {
                        currentRow = sortedMapOf()
                        nextSequentialColumn = 0
                    }
                    "c" -> {
                        val reference = parser.getAttributeValue(null, "r")
                        currentColumn = if (reference.isNullOrBlank()) {
                            nextSequentialColumn
                        } else {
                            columnIndex(reference)
                        }
                        require(currentColumn < MAX_COLUMNS) { "XLSX melebihi batas $MAX_COLUMNS kolom" }
                        currentType = parser.getAttributeValue(null, "t")
                        currentValue = null
                        inlineText = StringBuilder()
                    }
                    "v" -> insideValue = true
                    "t" -> if (currentType == "inlineStr") insideInlineText = true
                }
                XmlPullParser.TEXT -> when {
                    insideValue -> currentValue = (currentValue.orEmpty() + parser.text)
                    insideInlineText -> inlineText.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.localName()) {
                    "v" -> insideValue = false
                    "t" -> insideInlineText = false
                    "c" -> {
                        if (currentColumn in 0 until MAX_COLUMNS) {
                            currentRow[currentColumn] = decodeCellValue(currentType, currentValue, inlineText.toString(), sharedStrings)
                        }
                        nextSequentialColumn = (currentColumn + 1).coerceAtMost(MAX_COLUMNS)
                    }
                    "row" -> {
                        val lastColumn = currentRow.lastKeyOrNull() ?: -1
                        if (lastColumn >= 0) {
                            val values = MutableList(lastColumn + 1) { "" }
                            currentRow.forEach { (column, value) -> values[column] = value }
                            if (values.any { it.isNotBlank() }) {
                                require(rows.size < MAX_ROWS) { "XLSX melebihi batas $MAX_ROWS baris" }
                                rows += values
                            }
                        }
                    }
                }
            }
            parser.next()
        }
        return rows
    }

    private fun readSharedStrings(zip: ZipFile, entry: ZipEntry): List<String> {
        val parser = parserFor(zip, entry)
        val values = mutableListOf<String>()
        var current = StringBuilder()
        var insideText = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.localName() == "si") current = StringBuilder()
                    if (parser.localName() == "t") insideText = true
                }
                XmlPullParser.TEXT -> if (insideText) current.append(parser.text)
                XmlPullParser.END_TAG -> {
                    if (parser.localName() == "t") insideText = false
                    if (parser.localName() == "si") {
                        require(values.size < 100_000) { "XLSX memiliki terlalu banyak shared string" }
                        values += current.toString()
                    }
                }
            }
            parser.next()
        }
        return values
    }

    private fun decodeCellValue(
        type: String?,
        rawValue: String?,
        inlineValue: String,
        sharedStrings: List<String>
    ): String = when (type) {
        "s" -> rawValue?.toIntOrNull()?.let(sharedStrings::getOrNull).orEmpty()
        "inlineStr", "str" -> inlineValue.ifBlank { rawValue.orEmpty() }
        "b" -> if (rawValue == "1") "TRUE" else "FALSE"
        else -> rawValue.orEmpty()
    }

    private fun parserFor(zip: ZipFile, entry: ZipEntry): XmlPullParser {
        require(entry.size < 0 || entry.size <= MAX_XML_BYTES) { "Isi Office terlalu besar untuk diproses" }
        val bytes = zip.getInputStream(entry).use { it.readLimited(MAX_XML_BYTES) }
        return Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(ByteArrayInputStream(bytes), "UTF-8")
        }
    }

    private fun InputStream.readLimited(maximumBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= maximumBytes) { "Isi Office melebihi batas keamanan" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun XmlPullParser.localName(): String = name.substringAfter(':')

    private fun columnIndex(cellReference: String?): Int {
        if (cellReference.isNullOrBlank()) return 0
        var result = 0
        for (character in cellReference) {
            if (!character.isLetter()) break
            result = result * 26 + (character.uppercaseChar() - 'A' + 1)
        }
        return (result - 1).coerceAtLeast(0)
    }

    private fun sheetNumber(name: String): Int =
        Regex("sheet(\\d+)\\.xml").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE

    private fun <K, V> java.util.SortedMap<K, V>.lastKeyOrNull(): K? = if (isEmpty()) null else lastKey()
}
