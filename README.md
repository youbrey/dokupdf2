# DokuPDF 📄✨

**DokuPDF** adalah aplikasi pemindai, pengolah, dan pengedit dokumen Android berbasis Jetpack Compose. Aplikasi memakai CameraX, OCR Google ML Kit di perangkat, ekspor DOCX OpenXML/CSV, dan fitur daring Gemini yang aktif ketika API key tersedia.

---

## 🌟 Fitur Utama

- 🎨 **Custom Canvas Rendering Engine**:
  - Rendering multi-halaman berbasis Jetpack Compose Canvas tanpa ketergantungan pada `EditText` atau `TextView`.
  - Manipulasi dokumen berbasis *Command Pattern* dengan multi-level **Undo & Redo**.
  - Dukungan navigasi halaman, pan, pinch zoom, dan reset viewport dengan ketuk ganda.
- 📷 **CameraX Document Scanner**:
  - Deteksi tepi dokumen otomatis dengan fallback aman dan koreksi perspektif.
  - Preset *Otomatis, Warna Ajaib, Tanpa Bayangan, H&P, Foto HD, Grayscale,* dan kontrol manual kecerahan, kontras, saturasi, temperatur, serta ketajaman.
  - Crop perspektif interaktif dengan 4 sudut dan 4 pegangan sisi.
  - Mode pemindaian tunggal (*Single*), banyak halaman (*Batch*), dan kartu identitas (*ID Card*).
- 🧰 **Pusat 18 Alat PDF**:
  - **Organisasi**: Gabungkan PDF, pisahkan per 1/2/5/10 halaman, dan rotasi semua halaman (90°, 180°, 270°).
  - **Keamanan**: Kontainer `.dokupdf` dengan PBKDF2-HMAC-SHA256 dan enkripsi terautentikasi **AES-256-GCM**; format lama V1/V2 tetap dapat didekripsi.
  - **Optimasi**: Kompresi ukuran berkas PDF dengan indikator penghematan ruang penyimpanan.
  - **Perbaikan**: Validasi dan pembangunan ulang PDF melalui rasterisasi halaman; proses gagal aman bila tidak ada halaman yang dapat dipulihkan.
  - **Bandingkan Dokumen**: Metrik kemiripan visual per halaman dan deteksi halaman tambahan/hilang.
- 🔄 **Konversi Format Lengkap**:
  - PDF ke Word (`.docx` OpenXML berbasis OCR semua halaman) serta DOCX/TXT ke PDF.
  - PDF ke Excel (`.csv` berbasis OCR) serta CSV/seluruh worksheet XLSX ke PDF dengan pagination.
  - Foto/Gambar ke PDF & PDF ke Gambar Satuan atau Gambar Panjang Jahit (*Continuous Long Image*).
- 🤖 **Kecerdasan Buatan (Google ML Kit + Gemini AI)**:
  - Ekstraksi teks cepat luring (*Offline On-Device Text Recognition*).
  - Pemeriksa tata bahasa, penerjemahan seluruh dokumen, dan OCR cloud opsional dengan Gemini 3.6 Flash.
  - Generator draf surat formal dengan template luring bila API key belum tersedia.

---

## 🏗️ Arsitektur Sistem

```text
Compose UI ──> DocumentController ──> DocumentEngine / CommandManager
                                         │
                                         v
                                   DocumentModel
                                     │       │
                                     v       v
                              RenderEngine  PdfGenerator

CameraX / Galeri / PDF ──> AutoCropDetector ──> Perspective Warp
                                                    │
                                                    v
                                      DocumentDewarpProcessor
                                                    │
                                                    v
                                           FilterProcessor
                                                    │
                                                    v
                                           PdfConverterEngine

Pusat Alat PDF ──> engine merge/split/rotate/compress/repair/security/OCR
```

Scanner membatasi resolusi per tangkapan dan total pixel sesi secara dinamis berdasarkan heap perangkat. Jika batas aman tercapai, simpan sesi menjadi PDF sebelum menambah halaman. Operasi merge/split/rotate/compress/repair saat ini membangun ulang tampilan halaman sebagai raster; elemen object-level seperti form, link, selectable text, metadata, dan tanda tangan digital sumber tidak dipertahankan.

---

## 🛠️ Prasyarat & Menjalankan Proyek

- **Android Studio**: versi yang mendukung Android Gradle Plugin 9.1.1
- **JDK**: Java 17 atau 21
- **Min SDK**: API 24 (Android 7.0)
- **Target SDK / Compile SDK**: API 36

### Cara Build via Terminal / GitHub Codespaces

```bash
# Build Debug APK
./gradlew assembleDebug

# Menjalankan Unit & Robolectric Tests
./gradlew testDebugUnitTest
```

---

## 📄 Lisensi
Hak Cipta © 2026 DokuPDF. Dilindungi undang-undang.
