# DokuPDF - Project Changelog

## [1.0.0] - 2026-08-16
### Initial Release & Full Core Engine Implementation
- **Custom Canvas Document Engine**:
  - Implemented DocumentModel, PageModel, BlockModel, and Annotation hierarchy.
  - Implemented DocumentEngine, DocumentController, and Command Pattern with Multi-level Undo & Redo.
  - Implemented LayoutEngine and RenderEngine for multi-page interactive Canvas rendering with gesture zooming and panning.
- **CamScanner Document Scanner Suite**:
  - CameraX scanner with live document alignment guide, flash toggle, and single/batch/ID-card modes.
  - 4-point interactive perspective crop with corner magnifying lens.
  - 6 Document Filters: Original (Asli), Shadow Removal (Tanpa Bayangan), H&P High-Contrast B&W (H&P), Magic Color (Hemat), Grayscale, Invert (Balik).
- **Comprehensive PDF Toolset**:
  - **Organize & Edit**: Reorder pages, rotate 90/180/270°, split, merge, extract pages, delete pages.
  - **Annotation & Signing**: Canvas signature draw pad with customizable stroke, watermark creator (text/tile/opacity), and smart whiteout eraser brush.
  - **Converters**:
    - PDF to Word (DOCX/Formatted Text) & Word to PDF.
    - PDF to Excel (Table extraction/CSV) & Excel to PDF.
    - Image to PDF & PDF to Image / PDF to Long Image (Stitched continuous PNG).
    - PDF to Presentation / Text outlines.
  - **Optimization & Utility**:
    - Multi-tier PDF Compression (Calculates saved percentage).
    - PDF Password Lock (Encryption) & Password Unlock (Decryption).
    - PDF Comparison (Side-by-side visual difference engine).
    - PDF Repair Engine (Header reconstruction & orphan stream repair).
    - OCR Engine (Text recognition with copy, translation, and export).
    - Spell Checker & Proofreader with one-tap corrections.
    - AI Letter Generator & Document Translator.
- **Architecture & UI**:
  - Clean Material 3 Light design with Emerald Teal & Slate palette.
  - Room Database integration for scanned documents, tags, and persistent storage.
  - Fully functional navigation across Beranda, File, Alat, Saya, Scanner, and Canvas Editor.
