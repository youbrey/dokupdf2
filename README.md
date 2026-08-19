# DokuPDF 📄✨

**DokuPDF** adalah aplikasi pengolah, pemindai, dan pengedit dokumen PDF modern untuk Android dengan arsitektur **Custom Canvas Rendering Engine**, integrasi Google ML Kit On-Device OCR, konverter dokumen Microsoft Office OpenXML (DOCX & XLSX), serta kecerdasan buatan Gemini AI.

---

## 🌟 Fitur Utama

- 🎨 **Custom Canvas Rendering Engine**:
  - Rendering multi-halaman berbasis Jetpack Compose Canvas tanpa ketergantungan pada `EditText` atau `TextView`.
  - Manipulasi dokumen berbasis *Command Pattern* dengan multi-level **Undo & Redo**.
  - Dukungan navigasi dokumen: Pan, Pinch Zoom (Matrix Transformation), dan Virtualized Multi-page layout.
- 📷 **CamScanner CameraX Document Scanner**:
  - Deteksi dokumen otomatis dengan filter cerdas (*Asli, Tanpa Bayangan, H&P High-Contrast B&W, Hemat Tinta, Grayscale, Invert*).
  - Crop perspektif 4 titik interaktif dengan kaca pembesar sudut (*Magnifying Lens*).
  - Mode pemindaian tunggal (*Single*), banyak halaman (*Batch*), dan kartu identitas (*ID Card*).
- 🧰 **Pusat Alat PDF Terlengkap**:
  - **Organisasi**: Gabungkan PDF, Pisahkan PDF, Rotasi Halaman (90°, 180°, 270°), Urutkan & Hapus Halaman.
  - **Keamanan**: Kunci Sandi & Buka Proteksi PDF dengan enkripsi standar industri **AES-256 CBC** + random IV.
  - **Optimasi**: Kompresi ukuran berkas PDF dengan indikator penghematan ruang penyimpanan.
  - **Perbaikan**: Engine pemulihan PDF korup via rekonstruksi tabel XRef dan stream analyzer.
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
- **Min SDK**: API 26 (Android 8.0)
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
