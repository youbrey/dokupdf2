# Audit dan Refactor DokuPDF2

Tanggal audit: 20 Agustus 2026
Ruang lingkup: scanner CameraX, auto-crop, filter gambar, crop interaktif, alat PDF, keamanan, memori, pengujian, dan CI.

## Ringkasan hasil

Audit menemukan bahwa jalur kamera tidak pernah menjalankan `AutoCropDetector`, sedangkan impor galeri menjalankan deteksi dan perspective crop di main thread. Detektor lama juga merusak rasio gambar menjadi 300×300 dan hanya mencari empat arah diagonal. Kombinasi tersebut menjelaskan auto-crop yang tidak konsisten serta UI yang dapat tersendat.

Implementasi baru menyatukan kamera dan impor ke satu pipeline: decode dengan batas resolusi, deteksi tepi dengan rasio asli, validasi quadrilateral, crop perspektif secara lazy, filter otomatis, dan preview pada dispatcher latar. Filter kini mempunyai preset otomatis dan foto serta lima kontrol profesional.

## Temuan dan perbaikan per komponen

| Area | Temuan | Perbaikan |
|---|---|---|
| Auto-crop kamera | Hasil kamera langsung ditambahkan tanpa deteksi sudut | Kamera memanggil pipeline `createAutoCroppedPage` yang sama dengan galeri |
| Detektor lama | Input dipaksa 300×300, skala X/Y diabaikan, empat ray mudah mengenai teks | Analisis mempertahankan rasio, Sobel dua arah, pencarian empat garis kontinu, intersection dan confidence |
| Validitas crop | Geometri cekung/bersilangan dapat masuk ke transformasi | Validasi finite, rentang, luas, panjang sisi, dan convexity |
| Perspective matrix | Hasil `setPolyToPoly` diabaikan | Status matrix diperiksa; input invalid kembali ke salinan penuh yang aman |
| Galeri/file | Decode full-resolution berisiko OOM | Decode dua tahap dengan `inSampleSize` dan batas sisi panjang |
| Main thread | Deteksi, crop, dan filter preview dijalankan saat composition/main thread | Seluruh proses berat dipindah ke `Dispatchers.Default`/`IO` |
| Memori multi-page | Original dan hasil crop full-resolution disimpan bersamaan | Hanya original + geometri disimpan; crop dirender secara lazy |
| Zoom kamera | Nilai zoom selalu dimulai dari nol pada setiap gesture | Zoom memakai `ZoomState.zoomRatio` aktual dan batas kamera |
| Tap focus | Factory 1×1 menerima koordinat piksel | Menggunakan `PreviewView.meteringPointFactory` |
| Tombol thumbnail | Handler kosong | Thumbnail memindahkan `HorizontalPager` ke halaman yang dipilih |
| Mode ID Card | Tidak berbeda dari mode biasa | Menggunakan preset `PHOTO_ENHANCE` agar warna/foto identitas tidak diputihkan |
| Crop overlay | `BlendMode.Clear` dapat menghapus layer gambar | Path even-odd menggambar dim hanya di luar quadrilateral |
| Handle crop | Radius sentuh memakai 126 piksel mentah | Target sentuh density-aware 48 dp |
| Crop manual | Drag dapat membentuk quadrilateral invalid | Kandidat drag ditolak bila concave, bersilangan, atau terlalu kecil |
| Rotasi crop | Bitmap sementara bocor saat rotasi berulang/batal | Bitmap milik layar dilacak dan di-recycle pada pergantian/dispose |
| Filter | Hanya preset statis | Preset `AUTO`, `PHOTO_ENHANCE`, statistik gambar, adaptive B&W, dan fine controls |
| Preview filter | Filter full-resolution dipanggil sinkron dari `remember` | `produceState` merender preview maksimal 1600 px di background |
| PDF tool fallback | Handler tak dikenal mengklaim operasi selesai | Sekarang melaporkan bahwa tidak ada handler dan tidak ada file diubah |
| Repair PDF | Byte rusak diberi header/EOF lalu tetap diklaim sukses | Sukses hanya jika halaman dapat dirender dan dibangun ulang menjadi PDF baru |
| Compress/merge/split | Input kosong dapat menghasilkan hasil palsu; bitmap bocor | Validasi input/halaman dan lifecycle bitmap/document diperketat |
| Render PDF | Error ditelan dan daftar halaman parsial dapat dipakai sebagai sukses | Error render dibersihkan lalu diteruskan; dimensi render dibatasi 4096 px |
| Compare PDF | Heatmap bitmap besar dibuat tetapi tidak pernah ditampilkan | Perbandingan menghasilkan metrik yang benar-benar dikonsumsi UI dan langsung membersihkan bitmap |
| Enkripsi | AES-CBC dengan hash password langsung, tanpa autentikasi; raw decrypt invalid | V3 `.dokupdf`: PBKDF2-HMAC-SHA256 + AES-256-GCM; V1/V2 tetap dapat dibuka |
| Dependency dummy | Room, KSP, Retrofit, Moshi, Firebase, Coil, dan networking libs tidak dipakai source | Dependency/plugin yang tidak digunakan dihapus dari module build |
| Test dummy | Test 2+2, string resource, dan screenshot `Text("DokuPDF")` | Test range filter, auto-crop sintetis, crop invalid, filter, enkripsi, serta HomeScreen nyata |
| Instrumentation | Mengharapkan package `com.example` | Disesuaikan ke application ID `com.aistudio.dokupdf.scanedit` |
| Gradle wrapper | `gradle-wrapper.jar` hilang dan `gradlew` tidak executable | JAR resmi 9.3.1 ditambahkan dan mode executable dipulihkan |
| CI | Tidak memasang SDK, tidak memulihkan wrapper yang tidak lengkap, tidak menjalankan test | Setup API 36, bootstrap wrapper, unit/Robolectric test, lalu build APK |

## Perubahan file

- `core/crop/AutoCropDetector.kt`: algoritme dan validasi auto-crop baru.
- `core/filter/FilterProcessor.kt`: preset otomatis/foto, adaptive threshold, fine controls, dan crop aman.
- `core/model/DocumentModel.kt`: `FilterType` serta `FilterSettings` baru.
- `ui/screens/ScannerScreen.kt`: pipeline scanner terpadu, kamera, preview async, panel adjustment, decoding dan lifecycle bitmap.
- `ui/screens/InteractiveCropScreen.kt`: deteksi async, overlay, validasi drag, touch target, dan lifecycle rotasi.
- `core/pdf/PdfSecurity.kt`: kontainer enkripsi V3 dan kompatibilitas legacy.
- `core/pdf/PdfRendererEngine.kt`, `PdfCompressor.kt`, `PdfConverterEngine.kt`, `PdfMergerSplitter.kt`, `PdfRepairEngine.kt`, `PdfComparer.kt`: validasi, fail-safe, batas ukuran, dan perbaikan kebocoran memori.
- `ui/screens/PdfToolsScreen.kt`: pesan yang jujur, ekstensi `.dokupdf`, dan pemilih file dekripsi.
- `app/build.gradle.kts`, `build.gradle.kts`: menghapus dependency/plugin yang tidak dipakai.
- `.github/workflows/build-apk.yml`, `gradlew`, `gradle/wrapper/gradle-wrapper.jar`: jalur build dan test yang dapat direproduksi.
- `app/src/test/**`, `app/src/androidTest/**`: mengganti test placeholder dengan regresi fungsional.
- `README.md`: menyelaraskan klaim fitur, keamanan, dan minimum SDK dengan source aktual.

## Verifikasi

- `git diff --check`: lulus, tidak ada whitespace error.
- Integritas `gradle-wrapper.jar`: lulus (`unzip -t`, tidak ada error).
- Gradle 9.3.1: berhasil di-bootstrap dan menjalankan konfigurasi task.
- Pencarian ulang handler/placeholder kritis: handler semua tool terdaftar; fallback tidak lagi memberi sukses palsu.
- Test Android lokal di sandbox: belum dapat dikompilasi karena sandbox tidak menyediakan Android SDK/API 36. Workflow CI kini memasang SDK tersebut dan menjalankan `testDebugUnitTest` sebelum build.

## Tindak lanjut kegagalan workflow 20 Agustus 2026

Workflow manual pada merge commit `6a9043a` berhasil melewati setup Android API 36, Gradle 9.3.1, kompilasi aplikasi, dan kompilasi test. Build berhenti karena dua assertion grafis di `AutoCropAndFilterTest`. Kedua test memakai `Canvas`, `Path`, dan `ColorMatrixColorFilter`, tetapi class test belum memakai native graphics Robolectric seperti test screenshot yang sudah berhasil.

Perbaikan tindak lanjut:

- `AutoCropAndFilterTest` memakai `@GraphicsMode(GraphicsMode.Mode.NATIVE)` agar rasterisasi test sama dengan pipeline grafis Android.
- Assertion auto-crop/filter menyertakan confidence, geometri, alasan fallback, dan nilai piksel untuk diagnosis yang dapat ditindaklanjuti.
- Ditambahkan regresi untuk fallback gambar datar dan filter `ORIGINAL` netral.
- `AutoCropResult` membawa `failureReason`; scanner dan crop interaktif menulis alasan fallback ke Logcat.
- Detektor tidak lagi menangkap seluruh `Throwable`; hanya kegagalan operasional dan kehabisan memori yang dikonversi menjadi fallback aman.
- Workflow mengunggah laporan HTML/XML unit test meski test gagal, hanya mengunggah APK setelah build sukses, dan tidak lagi menyamarkan kegagalan release dengan `continue-on-error`.
- Action CI dimutakhirkan ke generasi Node.js 24 untuk menghapus peringatan deprecation runner.
- Peringatan kompilasi utama ditangani: Locale memakai `forLanguageTag`, lifecycle owner memakai package Compose terbaru, clipboard memakai API platform, test Compose memakai rule v2, dan ikon navigasi memakai varian `AutoMirrored`.
- Gesture zoom/pan editor kini mempertahankan titik centroid gesture dan benar-benar menerapkan scale/translation ke canvas; sebelumnya state berubah tetapi tidak pernah memengaruhi tampilan.
- Pemanggilan paksa `System.gc()` dari callback memori yang deprecated dihapus karena tidak membebaskan bitmap milik aplikasi secara deterministik dan dapat menimbulkan jeda UI.

## Catatan kompatibilitas

File hasil enkripsi baru memakai ekstensi `.dokupdf` karena ciphertext bukan PDF yang dapat dibaca viewer umum. Ini mencegah aplikasi atau pengguna mengira kontainer terenkripsi sebagai PDF standar. Fungsi dekripsi tetap mendukung kontainer lama `DOKUPDF_ENCRYPTED_V1` dan `V2`.
