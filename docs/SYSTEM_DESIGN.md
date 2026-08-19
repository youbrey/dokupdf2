# DokuPDF - System Design & Architecture Document

## 1. System Overview
**DokuPDF** is an advanced enterprise-grade Android document scanning, management, and editing suite designed with a custom Canvas rendering engine, modular PDF processing pipeline, real-time image enhancement filters, on-device OCR, and AI document intelligence.

### Architectural Philosophy
1. **Document Model as Single Source of Truth**: The data layer is decoupled from rendering. UI never directly mutates document state.
2. **Custom Canvas Rendering Engine**: Documents are measured by the `LayoutEngine` and drawn to Jetpack Compose `Canvas` via `RenderTree` and `RenderEngine` — eliminating standard TextView/EditText editing bottlenecks for rich document fidelity.
3. **Command Pattern & Transactional History**: All user edits (filter changes, pen annotations, signature placement, watermark injection, eraser strokes, page rotation, text insertion) are encapsulated as reversible `Command` objects, ensuring robust Multi-level Undo & Redo.
4. **CamScanner-Grade Processing**: High-performance bitmap filters (Adaptive Threshold H&P, Magic Color auto-tone, Shadow Removal, Grayscale, Invert), interactive 4-point perspective cropping, and multi-page batch scanning.

```
+----------------------------------------------------------------+
|                           UI Layer                             |
|  (Compose Screens: Beranda, Alat, File, Scanner, Editor, Tools) |
+-------------------------------+--------------------------------+
                                |
                                v
+----------------------------------------------------------------+
|                        ViewModel Layer                         |
|     (EditorViewModel, ScannerViewModel, ToolsViewModel)        |
+-------------------------------+--------------------------------+
                                |
                                v
+----------------------------------------------------------------+
|                    Document Controller                         |
|             (Dispatches Commands, Manages History)             |
+-------------------------------+--------------------------------+
                                |
                                v
+----------------------------------------------------------------+
|                      Document Engine                           |
|       (Maintains DocumentModel, Pages, Annotations, Cache)     |
+-------------------------------+--------------------------------+
                                |
                                v
+----------------------------------------------------------------+
|                       Layout Engine                            |
|        (Calculates Geometry, Coordinates, Lines, Bounds)       |
+-------------------------------+--------------------------------+
                                |
                                v
+----------------------------------------------------------------+
|                        Render Tree                             |
|          (Hierarchical Layer Nodes: Paper, Content, Marks)     |
+-------------------------------+--------------------------------+
                                |
                                v
+----------------------------------------------------------------+
|                       Render Engine                            |
|             (Paints to Compose Canvas, Viewport Cull)          |
+----------------------------------------------------------------+
```

## 2. Core Modules
- **`core/document/model`**: Document, Page, Block, TextRun, Table, Annotation, Signature, Watermark, FilterType, CropGeometry, PasswordSettings.
- **`core/command`**: Command interface, CommandManager, Undo/Redo stack with Command merging for fluid gestures.
- **`core/layout`**: LayoutEngine, LineBreak, TextMeasure, BoundingBox, ViewportTransform.
- **`core/render`**: RenderEngine, LayerSystem, PaintPool, OffscreenCache, DrawOrder (Background -> Paper -> PageContent -> Annotations -> Signature/Watermark -> Eraser -> Selection -> Overlays).
- **`core/pdf`**:
  - `PdfRendererCore`: Hardware-accelerated bitmap rasterization and page caching.
  - `PdfDocumentBuilder`: Vector & bitmap PDF synthesis using Android `PdfDocument`.
  - `PdfMerger`: Merges arbitrary PDF page arrays into consolidated files.
  - `PdfSplitter`: Splits pages by range, single page, or even/odd sets.
  - `PdfConverterEngine`: Bidirectional converter for Word (.docx/.txt), Excel (.csv/.table), Image (.png/.jpg), Long Image Stitcher.
  - `PdfCompressor`: Multi-tier compression (Extreme, High, Balanced, Low) with bitmap downsampling and compression.
  - `PdfSecurity`: Password encryption lock/unlock with permission flag enforcement.
  - `PdfRepairEngine`: Stream reconstruction, header/trailer sanitization, corrupted block recovery.
  - `PdfComparer`: Visual differential pixel comparison and structural diff engine.
- **`core/filter`**: CamScanner image processing filters (Asli, Tanpa Bayangan, H&P Black & White, Magic Color, Grayscale, Balik/Invert).
- **`core/ocr`**: On-device pattern & text recognition with searchable PDF text layer generation.
- **`core/ai`**: Gemini-powered translation, intelligent spellchecking, document summarization, and AI formal letter generator.
- **`data/local`**: Room SQLite Database for document library, scan history, tag metadata, and offline storage.
- **`ui`**: Material 3 light theme, high contrast typography, intuitive CamScanner-inspired navigation.
