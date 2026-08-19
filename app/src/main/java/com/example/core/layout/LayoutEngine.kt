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
        var currentY = pageSpacing
        val pageLayouts = mutableListOf<PageLayoutInfo>()
        var maxWidth = viewportWidth.coerceAtLeast(600f)

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
            if (pageWidth > maxWidth) {
                maxWidth = pageWidth + pagePadding * 2
            }
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
        val relX = (docPoint.x - pageLayout.bounds.left) / pageLayout.pageSize.width
        val relY = (docPoint.y - pageLayout.bounds.top) / pageLayout.pageSize.height
        return Offset(relX.coerceIn(0f, 1f), relY.coerceIn(0f, 1f))
    }

    /**
     * Converts normalized page coordinates back to absolute document coordinates
     */
    fun fromNormalizedPageCoord(normPoint: Offset, pageLayout: PageLayoutInfo): Offset {
        val absX = pageLayout.bounds.left + normPoint.x * pageLayout.pageSize.width
        val absY = pageLayout.bounds.top + normPoint.y * pageLayout.pageSize.height
        return Offset(absX, absY)
    }
}
