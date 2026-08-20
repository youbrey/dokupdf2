package com.example.core.model

import android.graphics.Bitmap
import android.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import java.util.UUID

/**
 * Filter presets matching CamScanner image enhancement options
 */
enum class FilterType(val displayName: String) {
    AUTO("Otomatis"),
    ORIGINAL("Asli"),
    LIGHTEN("Cerahkan"),
    SHARPEN("Mempertajam"),
    MAGIC_COLOR("Ajaib Pro"),
    NO_SHADOW("Tanpa Bayangan"),
    MAGIC_BW_HP("Hitam & Putih"),
    GRAYSCALE("Grayscale"),
    PHOTO_ENHANCE("Foto HD"),
    INVERT("Negatif")
}

/**
 * Fine controls applied after a filter preset. Neutral values deliberately produce no
 * additional pass so ORIGINAL can remain lossless. Ranges are enforced by [normalized].
 */
data class FilterSettings(
    val brightness: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val warmth: Float = 0f,
    val sharpness: Float = 0f
) {
    fun normalized() = copy(
        brightness = brightness.coerceIn(0.65f, 1.35f),
        contrast = contrast.coerceIn(0.65f, 1.8f),
        saturation = saturation.coerceIn(0f, 2f),
        warmth = warmth.coerceIn(-1f, 1f),
        sharpness = sharpness.coerceIn(0f, 1.5f)
    )

    fun isNeutral(): Boolean =
        brightness == 1f && contrast == 1f && saturation == 1f && warmth == 0f && sharpness == 0f
}

/**
 * Page dimensions and standard sizes
 */
enum class PaperSize(val displayName: String, val widthPt: Float, val heightPt: Float) {
    A4("A4", 595.28f, 841.89f),
    LETTER("Letter", 612f, 792f),
    LEGAL("Legal", 612f, 1008f),
    ID_CARD("Kartu ID", 242f, 153f),
    CUSTOM("Kustom", 595.28f, 841.89f)
}

/**
 * Represents 4-corner perspective crop geometry normalized (0.0f - 1.0f)
 */
data class CropGeometry(
    val topLeft: Offset = Offset(0f, 0f),
    val topRight: Offset = Offset(1f, 0f),
    val bottomRight: Offset = Offset(1f, 1f),
    val bottomLeft: Offset = Offset(0f, 1f)
)

/**
 * Freehand drawing path for annotation & pen
 */
data class DrawPath(
    val id: String = UUID.randomUUID().toString(),
    val points: List<Offset> = emptyList(),
    val color: Long = 0xFF000000,
    val strokeWidth: Float = 4f,
    val isEraser: Boolean = false,
    val isHighlight: Boolean = false
)

/**
 * Signature placed on a document page
 */
data class SignatureAnnotation(
    val id: String = UUID.randomUUID().toString(),
    val bitmap: Bitmap? = null,
    val normalizedX: Float = 0.5f,
    val normalizedY: Float = 0.8f,
    val widthFraction: Float = 0.35f,
    val heightFraction: Float = 0.15f,
    val color: Long = 0xFF004D40
)

/**
 * Watermark configuration
 */
data class WatermarkAnnotation(
    val id: String = UUID.randomUUID().toString(),
    val text: String = "RAHASIA / CONFIDENTIAL",
    val color: Long = 0x44000000,
    val fontSize: Float = 28f,
    val rotationDegrees: Float = -45f,
    val opacity: Float = 0.3f,
    val isTiled: Boolean = true
)

/**
 * Whiteout / Smart Eraser stroke
 */
data class EraserStroke(
    val id: String = UUID.randomUUID().toString(),
    val points: List<Offset> = emptyList(),
    val strokeWidth: Float = 24f
)

/**
 * Represents a single text run inside a rich paragraph block
 */
data class TextRun(
    val id: String = UUID.randomUUID().toString(),
    val text: String = "",
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val fontSize: Float = 14f,
    val color: Long = 0xFF1E293B
)

/**
 * Block hierarchy in document body
 */
sealed class Block(open val id: String = UUID.randomUUID().toString()) {
    data class ParagraphBlock(
        override val id: String = UUID.randomUUID().toString(),
        val runs: List<TextRun> = emptyList(),
        val alignment: Int = 0 // 0: Left, 1: Center, 2: Right, 3: Justify
    ) : Block(id)

    data class TableBlock(
        override val id: String = UUID.randomUUID().toString(),
        val rows: Int = 2,
        val cols: Int = 2,
        val cells: List<List<String>> = emptyList()
    ) : Block(id)

    data class ImageBlock(
        override val id: String = UUID.randomUUID().toString(),
        val bitmap: Bitmap? = null,
        val width: Float = 100f,
        val height: Float = 100f
    ) : Block(id)
}

/**
 * Represents a single document page
 */
data class PageModel(
    val id: String = UUID.randomUUID().toString(),
    val pageIndex: Int = 0,
    val originalImageUri: String? = null,
    val originalBitmap: Bitmap? = null,
    val processedBitmap: Bitmap? = null,
    val thumbnailBitmap: Bitmap? = null,
    val filterType: FilterType = FilterType.ORIGINAL,
    val filterSettings: FilterSettings = FilterSettings(),
    val cropGeometry: CropGeometry = CropGeometry(),
    val rotationDegrees: Int = 0,
    val brightness: Float = 1.0f,
    val contrast: Float = 1.0f,
    val paperSize: PaperSize = PaperSize.A4,
    val width: Float = 595.28f,
    val height: Float = 841.89f,
    val blocks: List<Block> = emptyList(),
    val drawPaths: List<DrawPath> = emptyList(),
    val signatures: List<SignatureAnnotation> = emptyList(),
    val watermarks: List<WatermarkAnnotation> = emptyList(),
    val eraserStrokes: List<EraserStroke> = emptyList(),
    val ocrText: String? = null
)

/**
 * Document Model: Single Source of Truth
 */
data class DocumentModel(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Dokumen Baru",
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    val filePath: String? = null,
    val isEncrypted: Boolean = false,
    val passwordHash: String? = null,
    val pages: List<PageModel> = emptyList(),
    val tags: List<String> = emptyList(),
    val isFavorite: Boolean = false,
    val isTrashed: Boolean = false,
    val category: String = "Semua"
)
