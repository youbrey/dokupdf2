# DokuPDF 📄✨

**DokuPDF** adalah aplikasi pemindai, pengolah, dan pengedit dokumen Android berbasis Jetpack Compose. Aplikasi memakai CameraX, OCR Google ML Kit di perangkat, ekspor DOCX OpenXML/CSV, dan fitur daring Gemini yang aktif ketika API key tersedia.

---

## 🌟 Fitur Utama

- 🎨 **Custom Canvas Rendering Engine**:
  - Rendering multi-halaman berbasis Jetpack Compose Canvas tanpa ketergantungan pada `EditText` atau `TextView`.
  - Manipulasi dokumen berbasis *Command Pattern* dengan multi-level **Undo & Redo**.
  - Dukungan navigasi dokumen: Pan, Pinch Zoom (Matrix Transformation), dan Virtualized Multi-page layout.
- 📷 **CameraX Document Scanner**:
  - Deteksi tepi dokumen otomatis dengan fallback aman dan koreksi perspektif.
  - Preset *Otomatis, Warna Ajaib, Tanpa Bayangan, H&P, Foto HD, Grayscale,* dan kontrol manual kecerahan, kontras, saturasi, temperatur, serta ketajaman.
  - Crop perspektif interaktif dengan 4 sudut dan 4 pegangan sisi.
  - Mode pemindaian tunggal (*Single*), banyak halaman (*Batch*), dan kartu identitas (*ID Card*).
- 🧰 **Pusat Alat PDF Terlengkap**:
  - **Organisasi**: Gabungkan PDF, Pisahkan PDF, Rotasi Halaman (90°, 180°, 270°), Urutkan & Hapus Halaman.
  - **Keamanan**: Kontainer `.dokupdf` dengan PBKDF2-HMAC-SHA256 dan enkripsi terautentikasi **AES-256-GCM**; format lama V1/V2 tetap dapat didekripsi.
  - **Optimasi**: Kompresi ukuran berkas PDF dengan indikator penghematan ruang penyimpanan.
  - **Perbaikan**: Validasi dan pembangunan ulang PDF melalui rasterisasi halaman; proses gagal aman bila tidak ada halaman yang dapat dipulihkan.
  - **Bandingkan Dokumen**: Analisis perbedaan visual antar dua berkas PDF (Pixel heatmap difference).
- 🔄 **Konversi Format Lengkap**:
  - PDF ke Word (`.docx` OpenXML native) & Word ke PDF.
  - PDF ke Excel (`.csv`/Tabular) & Data Tabular ke PDF.
  - Foto/Gambar ke PDF & PDF ke Gambar Satuan atau Gambar Panjang Jahit (*Continuous Long Image*).
- 🤖 **Kecerdasan Buatan (Google ML Kit + Gemini AI)**:
  - Ekstraksi teks cepat luring (*Offline On-Device Text Recognition*).
  - Pemeriksa tata bahasa & ejaan otomatis (*Spell Checker & Proofreader*).
  - Generator draf surat formal (*AI Letter Generator*) dan penerjemah dokumen multibahasa.

---

## 🏗️ Arsitektur Sistem

```text
DocumentModel
     ↓
DocumentEngine
     ↓
LayoutEngine
     ↓
RenderTree
     ↓
RenderEngine
     ↓
Jetpack Compose Canvas
```

---

## 🛠️ Prasyarat & Menjalankan Proyek

- **Android Studio**: Android Studio Ladybug | 2024.2.1 atau lebih baru
- **JDK**: Java 17 atau 21
- **Min SDK**: API 24 (Android 7.0)
- **Target SDK**: API 36 (Android 15+)

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
