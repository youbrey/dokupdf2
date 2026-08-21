package com.example.core.layout

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.example.core.model.PageModel

data class PageLayoutInfo(
    val pageIndex: Int,
    val bounds: Rect, // In document coordinate space
    val pageSize: Size
)

data class DocumentLayout(
    val pages: List<PageLayoutInfo>,
    val totalWidth: Float,
    val totalHeight: Float
)

/**
 * Layout engine calculating document geometries, multi-page offsets, and viewport transforms
 */
class LayoutEngine(
    var pageSpacing: Float = 24f,
    var pagePadding: Float = 16f
) {

    fun computeLayout(pages: List<PageModel>, viewportWidth: Float): DocumentLayout {
        require(viewportWidth.isFinite() && viewportWidth >= 0f) { "Lebar viewport tidak valid" }
        require(pageSpacing.isFinite() && pageSpacing >= 0f) { "Jarak halaman tidak valid" }
        require(pagePadding.isFinite() && pagePadding >= 0f) { "Padding halaman tidak valid" }
        pages.forEachIndexed { index, page ->
            require(
                page.width.isFinite() && page.height.isFinite() &&
                    page.width > 0f && page.height > 0f
            ) { "Ukuran halaman ${index + 1} tidak valid" }
        }
        var currentY = pageSpacing
        val pageLayouts = mutableListOf<PageLayoutInfo>()
        val widestPage = pages.maxOfOrNull { it.width + pagePadding * 2f } ?: 0f
        val maxWidth = maxOf(viewportWidth, widestPage, 600f)

        for ((index, page) in pages.withIndex()) {
            val pageWidth = page.width
            val pageHeight = page.height
            val x = (maxWidth - pageWidth) / 2f

            val bounds = Rect(
                offset = Offset(x.coerceAtLeast(pagePadding), currentY),
                size = Size(pageWidth, pageHeight)
            )

            pageLayouts.add(
                PageLayoutInfo(
                    pageIndex = index,
                    bounds = bounds,
                    pageSize = Size(pageWidth, pageHeight)
                )
            )

            currentY += pageHeight + pageSpacing
        }

        return DocumentLayout(
            pages = pageLayouts,
            totalWidth = maxWidth,
            totalHeight = currentY
        )
    }

    /**
     * Hit tests a screen coordinate (after inverse viewport matrix) to find which page was tapped
     */
    fun hitTestPage(docPoint: Offset, layout: DocumentLayout): PageLayoutInfo? {
        return layout.pages.firstOrNull { it.bounds.contains(docPoint) }
    }

    /**
     * Converts a document coordinate to normalized page coordinate [0..1, 0..1]
     */
    fun toNormalizedPageCoord(docPoint: Offset, pageLayout: PageLayoutInfo): Offset {
        require(pageLayout.pageSize.width > 0f && pageLayout.pageSize.height > 0f) {
            "Ukuran layout halaman tidak valid"
        }
        val relX = (docPoint.x - pageLayout.bounds.left) / pageLayout.pageSize.width
        val relY = (docPoint.y - pageLayout.bounds.top) / pageLayout.pageSize.height
        return Offset(relX.coerceIn(0f, 1f), relY.coerceIn(0f, 1f))
    }

    /**
     * Converts normalized page coordinates back to absolute document coordinates
     */
    fun fromNormalizedPageCoord(normPoint: Offset, pageLayout: PageLayoutInfo): Offset {
        require(normPoint.x.isFinite() && normPoint.y.isFinite()) { "Koordinat halaman tidak valid" }
        val absX = pageLayout.bounds.left + normPoint.x.coerceIn(0f, 1f) * pageLayout.pageSize.width
        val absY = pageLayout.bounds.top + normPoint.y.coerceIn(0f, 1f) * pageLayout.pageSize.height
        return Offset(absX, absY)
    }
}
