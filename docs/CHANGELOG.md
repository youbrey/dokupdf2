# DokuPDF - Project Changelog

## [Unreleased] - 2026-08-25 (lanjutan)
### Fitur Baru: "Simpan ke Perangkat" (setara "Simpan ke Galeri" CamScanner)
**Root cause keluhan "tidak bisa menyimpan/ekspor":** seluruh proyek sebelumnya tidak
pernah memakai `MediaStore` atau `DownloadManager` (dikonfirmasi lewat grep menyeluruh).
PDF hasil scan/PDF Tools hanya tersimpan di sandbox privat aplikasi
(`context.filesDir`/`context.cacheDir`) yang tidak bisa diakses aplikasi File Manager/Galeri
lain — satu-satunya jalan keluar adalah `Intent.ACTION_SEND` (share sheet), bukan aksi
"simpan ke perangkat" mandiri.

**Ditambahkan:**
- `core/pdf/ExportUtils.kt` (baru) — menyalin berkas dari sandbox privat ke `Download/DokuPDF/`
  publik. Dua jalur sesuai versi Android: `MediaStore.Downloads` untuk API 29+ (tanpa izin,
  scoped storage), dan `Environment.DIRECTORY_DOWNLOADS` + izin runtime
  `WRITE_EXTERNAL_STORAGE` untuk API 24-28 (sesuai minSdk=24 proyek ini). Penamaan berkas
  otomatis dibuat unik (query MediaStore/filesystem sungguhan, bukan asumsi) supaya ekspor
  berulang tidak saling menimpa.
- `AndroidManifest.xml` — izin `WRITE_EXTERNAL_STORAGE` dengan `maxSdkVersion="28"`.
- `ScannerScreen.kt` — dialog "PDF Berhasil Dibuat" sekarang punya tombol "Simpan ke
  Perangkat" di samping "Bagikan", lengkap alur permintaan izin runtime untuk API 24-28.
- `PdfToolsScreen.kt` — tombol "Simpan ke Perangkat" ditambahkan di samping "Bagikan Hasil"
  untuk seluruh hasil PDF Tools (kompres, gabung, konversi, dll), mendukung multi-file
  sekaligus. Pesan hasil melaporkan jujur jika sebagian berkas gagal disimpan (bukan pesan
  sukses generik ketika sebagian sebenarnya gagal).

**[Refactor]** Fungsi `mimeTypeFor()` yang sebelumnya `private` di `PdfToolsScreen.kt`
dipindahkan ke `ExportUtils.kt` sebagai satu sumber kebenaran, dipakai bersama oleh
`ExportUtils`, `PdfToolsScreen`, dan (secara implisit lewat `import com.example.core.pdf.*`)
kode lain yang butuh deteksi MIME type dari ekstensi berkas.

## [Unreleased] - 2026-08-25
### Fix: Deteksi 4-titik crop otomatis salah pilih garis tabel internal sebagai tepi kertas
Root cause (ditemukan dari analisis frame-by-frame video pengujian): `AutoCropDetector.kt`
mencari 4 sisi kertas independen, hanya berdasar kekuatan gradien — garis tabel/formulir
internal yang kontras tinggi bisa mengalahkan tepi kertas asli dalam skor, terutama saat
latar belakang foto gelap. Ditambahkan `brightnessBiasFactor()`: memprioritaskan kandidat
garis yang sisi dalamnya (kertas) jelas lebih terang dari sisi luarnya (latar) — pola yang
tidak dimiliki garis tabel internal (kedua sisinya sama-sama kertas terang). Ini yang
menyebabkan hasil rectify/crop terlihat "miring" — bukan bug di perspective warp itu sendiri,
tapi geometri sumber yang dikirim ke warp sudah salah bentuk. Detail di `docs/AUDIT_REPORT.md`.
Belum diverifikasi di device fisik.

### Fix: Filter "Mempertajam" masih terasa blur dibanding CamScanner
Root cause: `applyUnsharpSharpen()` selalu pakai radius tetangga 1px, terlalu sempit untuk
resolusi scan dokumen sehingga efek penajaman nyaris tak terlihat. Ditambahkan parameter
`radius` (default 1, pemanggil lain tidak berubah), `applySuperSharpen()` sekarang memanggil
dua pass (radius 1px + 3px) meniru unsharp mask multi-skala. Detail di `docs/AUDIT_REPORT.md`.
Belum diverifikasi di device fisik.

## [Unreleased] - 2026-08-24
### Fix: Filter "Mempertajam"/"Magic Color" merusak bayangan hangat jadi noda kuning
Root cause: cabang "preserve warna stempel/tanda tangan" (`chroma >= 20/22` → saturasi
dinaikkan 1.4x) salah mengklasifikasikan bayangan kuning/hangat khas foto indoor sebagai
"tinta berwarna", karena bayangan seperti itu juga punya chroma tinggi. Ditambahkan
`isWarmShadowCast()` di `FilterProcessor.kt` untuk mengecualikan pola warna itu secara
spesifik dari cabang tersebut. Detail lengkap di `docs/AUDIT_REPORT.md`. Belum diverifikasi
di device fisik.

### Fix: Bingkai crop tidak bisa digeser sama sekali
Root cause: `pointerInput` di `InteractiveCropScreen.kt` memakai `cropGeometry` sebagai key,
padahal `cropGeometry` berubah di dalam gesture drag itu sendiri — menyebabkan
`detectDragGestures` di-restart pada setiap gerakan jari sebelum sempat terdaftar sebagai
drag berkelanjutan. `cropGeometry` dihapus dari key pointerInput. Detail di
`docs/AUDIT_REPORT.md`.

## [Unreleased] - 2026-08-23 (Babak 4 — final)
### Root Cause Dipastikan & Ditutup: "document is closed!"
Setelah 3 babak upaya perbaikan berbasis hipotesis berbeda (semua terbukti
tidak relevan lewat bukti CI nyata: konkurensi/Mutex, lalu thread-hop
Dispatchers.IO/dispatcher injection), root cause akhirnya DIPASTIKAN lewat
penelusuran source resmi AOSP Robolectric: **`android.graphics.pdf.PdfDocument`
tidak memiliki native shadow di Robolectric sama sekali** — limitasi tooling
test, bukan bug aplikasi. Detail lengkap & bukti di `docs/AUDIT_REPORT.md` Babak 4.

**Keputusan:** 2 test yang terdampak (`lazy bitmap PDF conversion releases
each generated page`, `word and wide spreadsheet conversion paginate instead
of truncating`) ditandai `@Ignore` dengan justifikasi lengkap di kode — bukan
dihapus, bukan "diperbaiki" secara palsu. Rencana pemulihan cakupan test lewat
Android Instrumented Test dicatat di `docs/ROADMAP.md`.

Mutex (`PdfFileUtils.pdfDocumentMutex`) dan dispatcher injection
(`ioDispatcher`) dari babak-babak sebelumnya **tetap dipertahankan** — keduanya
perbaikan yang sah secara independen meski tidak relevan untuk kegagalan
spesifik ini.

## [Unreleased] - 2026-08-23
### Audit: Investigasi Kegagalan CI (`:app:testDebugUnitTest`)
Build APK debug gagal di CI karena 2 dari 19 unit test gagal, keduanya di
`OfficeFileParserTest` — satu-satunya file di project yang memakai
`android.graphics.pdf.PdfDocument` untuk membuat dokumen **multi-halaman**.
Rincian investigasi lengkap ada di `docs/AUDIT_REPORT.md` § Investigasi CI 2026-08-23.

**Diperbaiki (pasti, terverifikasi lewat pembacaan kode):**
- `PdfConverterEngine.kt` — 13 fungsi pembuat PDF sebelumnya hanya menangkap
  `catch (e: Exception)`, sehingga `Error`/`AssertionError` (mis. dari `assertTrue`
  di dalam callback pemanggil) lolos begitu saja dari kontrak `Result<File>` yang
  dijanjikan API ini. Sekarang semua `Throwable` ditangkap secara eksplisit dan
  dicatat lewat `Log.e` dengan stack trace lengkap.
- `OfficeFileParserTest.kt` — pesan kegagalan assertion untuk hasil konversi PDF
  sebelumnya hanya menampilkan `.message` (mis. `"document is closed!"`) tanpa
  stack trace, sehingga baris kode persis yang melempar exception tidak pernah
  terlihat di laporan test. Sekarang memakai `Log.getStackTraceString()` supaya
  CI run berikutnya memberi info diagnosis yang jauh lebih lengkap.

**BELUM diperbaiki — akar masalah "document is closed!" masih perlu diverifikasi:**
Setelah audit mendalam (baca kode `generatedBitmapsToPdf`/`wordLinesToPdf` baris
demi baris terhadap dokumentasi resmi `PdfDocument`), tidak ditemukan jalur logika
aplikasi yang secara tekstual salah — urutan `startPage → finishPage → writeTo →
close` sudah benar. Dugaan kuat: keterbatasan lingkungan Robolectric
(`@GraphicsMode(NATIVE)`, API 34, versi 4.16.1) saat mensimulasikan `PdfDocument`
multi-halaman — BUKAN dipastikan sebagai bug produksi. Perbaikan stack trace di
atas dibuat justru supaya CI run berikutnya bisa memastikan ini secara pasti,
bukan menebak.

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
