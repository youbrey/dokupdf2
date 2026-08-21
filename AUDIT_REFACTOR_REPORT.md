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
| Rotasi crop | Bitmap yang masih direferensikan display list Compose di-recycle saat rotasi/dispose | Bitmap yang pernah dipublikasikan ke Compose tidak lagi di-recycle manual; referensi dilepas dan reclamation diserahkan ke runtime |
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

## Verifikasi iterasi awal (riwayat sebelum audit lanjutan)

- `git diff --check`: lulus, tidak ada whitespace error.
- Integritas `gradle-wrapper.jar`: lulus (`unzip -t`, tidak ada error).
- Pada iterasi/workflow sebelumnya, Gradle 9.3.1 berhasil di-bootstrap dan mencapai konfigurasi/kompilasi test sebelum berhenti pada assertion grafis yang dijelaskan di bawah.
- Pencarian ulang handler/placeholder kritis: handler semua tool terdaftar; fallback tidak lagi memberi sukses palsu.
- Sandbox iterasi awal tidak menyediakan Android SDK/API 36. Status sandbox audit lanjutan yang menjadi status definitif saat ini dicatat pada bagian **Status verifikasi audit lanjutan**.

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

## Audit lanjutan menyeluruh — 21 Agustus 2026

Audit lanjutan dipicu oleh crash produksi berikut setelah pengambilan gambar:

```text
java.lang.RuntimeException: Canvas: trying to use a recycled bitmap
at androidx.compose.ui.graphics.painter.BitmapPainter.onDraw(BitmapPainter.kt:89)
```

Stack trace menunjukkan use-after-recycle pada display list Compose, bukan kegagalan kamera. `Bitmap.asImageBitmap()` tidak menyalin pixel; Compose dapat masih menggambar storage yang sama setelah state layar berubah. Karena itu, pemanggilan `Bitmap.recycle()` pada bitmap yang pernah menjadi state UI tidak aman walaupun composable sedang meninggalkan layar.

### Perbaikan crash dan ownership bitmap

1. `ScannerScreen.kt`
   - Menghapus recycle terhadap `originalBitmap`, `croppedBitmap`, dan preview yang sudah dipublikasikan ke Compose.
   - Crop konfirmasi hanya me-recycle hasil crop sementara yang tidak pernah masuk ke state UI.
   - Penyimpanan scan tidak lagi membuat `List<Bitmap>` semua halaman. `generatedBitmapsToPdf` meminta, merekam, dan melepas satu halaman pada satu waktu.
   - Nama hasil scan dibuat unik agar dua penyimpanan pada detik yang sama tidak menimpa dokumen.
   - CameraX, converter, dan recognizer OCR ditutup independen saat layar dilepas.
   - Menu impor PDF tidak lagi mengirim byte PDF ke `BitmapFactory` (yang selalu gagal). PDF divalidasi, dirender per halaman dengan budget pixel yang memperhitungkan halaman sesi yang sudah ada, lalu masuk ke layar review.
   - Bitmap hasil kamera/galeri yang gagal sebelum dipublikasikan ke state sekarang dilepas; setelah dipublikasikan kepemilikannya berpindah dan tidak di-`recycle()` saat masih dapat digambar Compose.
   - Pipeline render halaman membebaskan bitmap intermediate miliknya bila scaling, crop, filter, rotasi, watermark, atau alokasi hasil gagal; bitmap sumber Compose tetap tidak pernah ikut di-`recycle()`.
   - Seluruh persiapan penyimpanan scan (direktori, nama unik, snapshot halaman, dan konversi) berada di dalam pemulihan `try/catch/finally`, sehingga kegagalan storage tidak mengunci indikator simpan.
   - Tombol review **Tandai** yang sebelumnya hanya mengubah state tanpa pernah menampilkan UI kini membuka dialog watermark nyata, menerapkan teks per halaman, membatasi 200 karakter, dan menghapus watermark bila input dikosongkan.
   - Impor galeri/PDF tetap dapat masuk ke review/crop saat izin kamera ditolak; sebelumnya guard permission selalu `return` dan menjebak pengguna di layar izin walaupun gambar sudah berhasil dimuat. Layar tanpa izin kini juga menyediakan tombol impor PDF langsung.
   - Penghapusan halaman pada review meminta konfirmasi agar satu ketukan ikon tidak langsung menghilangkan hasil foto yang belum disimpan.
   - Indeks pager dijepit ulang setelah halaman terakhir yang aktif dihapus agar tidak sempat mengakses indeks di luar rentang; mode perbandingan asli juga direset saat pengguna berpindah halaman.
   - State indeks crop/watermark divalidasi tanpa pemaksaan non-null, sehingga callback tertunda tidak dapat memicu `NullPointerException` atau mengakses halaman yang sudah berubah.
   - Foto dari galeri kini menghormati seluruh orientasi EXIF (rotasi, mirror, transpose); sebelumnya auto-crop dapat menganalisis gambar yang tampak menyamping walaupun aplikasi galeri menampilkannya tegak.
   - Sesi kamera, galeri, dan impor PDF memakai satu budget pixel dinamis berdasarkan heap serta batas 100 halaman. Penambahan yang melampaui budget ditolak dengan pesan untuk menyimpan sesi, bukan dibiarkan berakhir sebagai OOM proses.
   - Resolusi tangkapan dibatasi 2600 px untuk HD dan 1600 px untuk mode cepat; ketajaman dokumen tetap memadai tanpa mempertahankan raster sensor 12–108 MP di memori.
   - Perangkat tanpa kamera belakang atau kegagalan bind CameraX kini menampilkan error terkontrol beserta tombol impor Galeri/PDF; shutter juga dinonaktifkan sampai `ImageCapture` benar-benar siap.
   - Hasil OCR scanner yang kosong tidak lagi membuka dialog kosong; pengguna menerima pesan bahwa teks tidak terdeteksi.
2. `InteractiveCropScreen.kt`
   - Menghapus recycle langsung pada bitmap rotasi lama dan pada `onDispose` karena hardware renderer masih dapat memutar ulang display list lama.
   - Bitmap hasil rotasi yang dikonfirmasi diserahkan ke parent tanpa pemindahan ownership yang ambigu.
3. `OcrEngine.kt`
   - Menolak bitmap invalid/recycled dengan pesan eksplisit.
   - Callback ML Kit hanya me-resume continuation yang masih aktif.
   - Menambahkan `close()` untuk membebaskan recognizer ML Kit.
4. `PdfConverterEngine.kt`
   - OCR dibuat lazy sehingga operasi non-OCR tidak menginisialisasi recognizer.
   - Menambahkan `generatedBitmapsToPdf` dengan kontrak ownership yang jelas dan pemrosesan satu halaman per iterasi.

### Fondasi keamanan berkas dan kegagalan atomik

File baru `core/pdf/PdfFileUtils.kt` menjadi satu sumber aturan I/O:

- validasi file ada, dapat dibaca, tidak kosong, dan berada di bawah batas 250 MB untuk PDF atau 50 MB untuk Office;
- validasi signature `%PDF-` sebelum file diteruskan ke `PdfRenderer`;
- larangan input dan output menunjuk canonical file yang sama;
- sanitasi nama file dan pencegahan path traversal;
- nama file/direktori unik tanpa silent overwrite;
- penulisan melalui temporary sibling, validasi ukuran, backup output lama, commit rename/copy, serta rollback jika commit gagal;
- jika rollback ikut gagal karena storage bermasalah, cadangan lengkap dipertahankan untuk recovery dan lokasinya dilampirkan pada exception;
- format ukuran berkas yang konsisten untuk UI.

Semua engine keluaran memakai mekanisme ini. Hasil gagal tidak lagi meninggalkan PDF/DOCX/CSV setengah jadi atau menghapus output lama yang masih valid.

### Perbaikan per alat PDF

| Alat | Bug/potensi bug sebelumnya | Implementasi sekarang |
|---|---|---|
| Render PDF | `renderPdfPages` dapat menyisakan bitmap parsial dan semua consumer harus menahan semua halaman | API `forEachRenderedPage` merender/recycle satu halaman; ukuran raster maksimum 4096 px dan page size keluaran dibatasi aman |
| Gabungkan PDF | Satu input/duplikat dapat diklaim sebagai merge; seluruh halaman ditahan | Minimal dua canonical file berbeda, validasi tiap input, proses sekuensial, page size dipertahankan, output atomik |
| Pisahkan PDF | UI selalu memaksa satu halaman; collision menimpa hasil; kegagalan menyisakan bagian parsial | Opsi 1/2/5/10 halaman, nama bagian unik, dokumen chunk ditutup deterministik, semua hasil dibersihkan jika operasi gagal |
| Putar PDF | Raster semua halaman sekaligus dan dimensi halaman tidak konsisten | Rotasi 90/180/270 tervalidasi, halaman diproses satu per satu, orientasi page size ditukar dengan benar |
| Kompres PDF | Hasil dapat lebih besar tetapi tetap disebut hemat | Empat tier termasuk ekstrem; bila hasil raster tidak lebih kecil, byte sumber disalin agar output tidak membesar dan UI melaporkan 0% secara jujur |
| Bandingkan PDF | Ukuran berbeda dibandingkan tidak konsisten; kegagalan render dianggap halaman hilang | Kedua halaman dinormalisasi fit-center 512×512; halaman ekstra bernilai 100% berbeda; kegagalan render menghentikan operasi; UI merinci hingga 12 halaman dengan persentase perbedaan tertinggi |
| Repair PDF | Seluruh file dibaca sebagai `String`; header/EOF palsu dapat diklaim sukses | Header dicari streaming, trailer diperiksa dari tail, candidate harus dapat dirender, lalu semua halaman dibangun ulang ke PDF bersih |
| Enkripsi/dekripsi | Seluruh ciphertext/plaintext dimuat ke RAM; OOM dapat salah dilaporkan sebagai password salah | AES-GCM/CBC/ECB legacy ditransformasi streaming 32 KB; hasil dekripsi harus lolos validasi PDF; cancellation diteruskan dan kegagalan memori dibedakan dari autentikasi |
| Foto ke PDF | Decode full-size, EXIF rotasi saja, collision nama | Decode sampling, seluruh 8 orientasi EXIF termasuk mirror/transpose, optimasi raster, output pustaka unik |
| PDF ke gambar | Semua bitmap halaman ditahan dan file collision | JPEG ditulis atomik per halaman ke direktori hasil unik; output dapat dibagikan multi-file |
| Gambar panjang | Tinggi bitmap dapat melampaui batas perangkat/OOM | Skala dihitung dari lebar, tinggi 30.000 px, dan budget 16 juta pixel; PDF terlalu panjang ditolak dengan arahan split |
| PDF ke DOCX | Fallback regex mengekstrak byte PDF sebagai teks dan tetap sukses | OCR semua halaman, hasil kosong ditolak, paket OpenXML valid, XML escaped/control-char dibersihkan, page break dan style benar |
| DOCX/TXT ke PDF | DOCX dibaca sebagai binary/regex; baris panjang terpotong | Parser `word/document.xml` nyata, TXT UTF-8, wrap teks dan pagination sampai seluruh konten selesai |
| PDF ke CSV | Hanya data heuristik terbatas; CSV formula injection mungkin | OCR seluruh halaman, kolom dinamis, quoting RFC-style, UTF-8 BOM, dan neutralisasi nilai yang diawali `=`, `+`, `-`, atau `@` |
| CSV/XLSX ke PDF | XLSX binary dapat diperlakukan sebagai teks; hanya 40 baris/8 kolom pertama | Parser CSV quoted + delimiter comma/semicolon/tab; parser shared strings/inline/sparse maupun sequential cell seluruh worksheet XLSX; pagination semua row dan kelompok kolom |
| OCR teks | Semua bitmap ditahan dan tidak di-recycle | OCR sekuensial hingga batas karakter aman, marker truncation eksplisit, dan hasil penuh yang diproses dapat disalin |
| Terjemahan/ejaan AI | Hanya halaman pertama dipakai | OCR seluruh halaman, dokumen dibagi per 12.000 karakter, penanda halaman dipertahankan, respons kosong dianggap gagal |

### Integrasi UI dan hasil nyata

`PdfToolsScreen.kt` kini mempunyai tepat 18 handler yang dapat dieksekusi. Perubahan integrasinya:

- chip kategori alat dapat digulir horizontal sehingga lima kategori tidak terpotong pada layar ponsel sempit;
- PDF baru disimpan ke `filesDir/documents` sehingga langsung muncul di Beranda;
- DOCX, CSV, JPEG, dan `.dokupdf` mempunyai tombol **Bagikan Hasil** melalui URI `FileProvider`;
- multi-output PDF split dan PDF-to-image dapat dibagikan sekaligus;
- warna hasil sukses/error dibedakan dan error dapat dicoba ulang tanpa menutup dialog;
- unlock tidak lagi menawarkan PDF pustaka sebagai kontainer terenkripsi;
- semua delapan bahasa terjemahan ditampilkan, bukan hanya empat pertama;
- klaim DOCX/CSV diubah menjadi OCR-based agar UI tidak menjanjikan preservasi layout yang tidak dilakukan engine;
- input SAF disalin di dispatcher I/O, nama disanitasi, ukuran dibatasi, temporary file dibersihkan saat diganti/ditutup;
- nama SAF tanpa ekstensi atau dengan ekstensi tidak aman memakai ekstensi fallback berdasarkan jenis picker; sebelumnya nama seperti `scan` dapat gagal sebelum isi berkas diperiksa;
- `file_paths.xml` mengizinkan hasil alat yang memang dibagikan;
- akses `FileProvider` ke seluruh direktori cache dihapus; hanya `documents/` dan `tools_output/` yang dapat diberi URI berbagi;
- permission storage lama dihapus karena seluruh input memakai Storage Access Framework.
- aksi cepat **OCR Teks** sekarang membuka alat OCR secara langsung; sebelumnya parameter aksi diabaikan dan hanya membuka grid umum;
- tombol **AI Dokumen/AI Assistant** membuka kategori `AI Pro` secara langsung, sementara navigasi Alat PDF biasa tetap membuka seluruh kategori;
- indeks dokumen pilihan dijepit ulang saat pustaka berubah agar tidak menunjuk item yang sudah dihapus;
- filter Beranda yang semula identik (`PDF`/`Tersimpan`) diganti menjadi klasifikasi nyata `Pindai`/`Dokumen`; penghapusan kini meminta konfirmasi dan kegagalan storage ditampilkan kepada pengguna.
- klaim menu scanner yang belum memiliki implementasi (split buku otomatis, penggabungan KTP, penghapusan jari/lipatan) diganti deskripsi yang sesuai dengan perilaku batch, preset kartu, dan pengurangan bayangan yang benar-benar tersedia.
- klaim editor “setara Word & Docs” di Beranda dihapus; deskripsi sekarang hanya menyebut kemampuan canvas yang benar-benar tersedia.
- komponen `CompressDialog` dan `SleekActionCard` yang tidak pernah dipanggil di aplikasi dihapus; pemilihan tingkat kompresi tetap memakai kontrol aktif di `PdfToolsScreen`.
- direktori hasil alat tidak lagi dibuat dengan `require` saat composition. Kegagalan storage sekarang masuk ke jalur error operasi dan tidak meruntuhkan layar hanya karena pengguna membuka menu Alat PDF.
- Pesan hasil pada `LazyColumn` menangkap nilai non-null lokal; recomposition setelah dialog direset tidak lagi dapat memicu `NullPointerException` dari `resultMessage!!`.

### Perbaikan editor dan repository

- `DocumentEditorScreen.kt` menyimpan ekspor ke pustaka `documents`, bukan direktori `exports` yang tidak pernah dipindai Beranda. Tombol export dikunci selama proses, nama file disanitasi/diunikkan, serta kegagalan direktori/storage/OOM ditampilkan tanpa meruntuhkan coroutine UI.
- Dialog tabel tidak lagi mengisi cell dummy `Data R…C…`; pengguna dapat memasukkan CSV/semicolon/tab nyata atau membuat cell kosong, dengan batas 1–12 baris dan 1–12 kolom per tabel agar seluruh grid tetap muat pada satu halaman editor.
- Teks manual maupun surat AI panjang dipecah menjadi blok maksimum 500 karakter dan didistribusikan memakai estimasi tinggi berdasarkan lebar halaman/font/isi yang sudah ada, bukan sekadar menghitung jumlah blok. Overflow dari kartu/nota kecil masuk ke halaman A4 baru agar teks tidak terpotong; satu blok 100.000 karakter tidak lagi dirender ulang pada setiap frame canvas. Batas field surat UI juga disamakan dengan validasi service.
- `RenderEngine.kt` tidak lagi meregangkan gambar. Render memakai fit-center + rotasi tanpa membuat bitmap baru, vector blocks ditampilkan sebagai overlay pada PDF/scan impor, bitmap block recycled diabaikan, dan watermark tile tidak digambar di luar halaman.
- Pipeline filter melepas bitmap preset sementara jika penyesuaian profesional berikutnya gagal/OOM; preset Photo Enhance juga tidak lagi menyisakan output setengah jadi ketika alokasi sharpening gagal.
- `PdfGenerator.kt` mempertahankan rasio gambar, merender rich text bold/italic/underline/alignment dengan wrapping, memotong teks tabel secara aman, menskalakan image block, membatasi geometri/stroke/signature, dan tidak me-recycle bitmap milik editor.
- `MainActivity.kt` menghitung scale saat membuka PDF dari budget total 12 juta pixel dan menolak dokumen yang tetap melampaui budget pada skala minimum. Error/OOM ditangkap; aplikasi tidak lagi membuka halaman kosong palsu atau membiarkan exception coroutine meruntuhkan UI.
- `DocumentRepository.kt` tidak lagi memaksa PDF rusak menjadi satu halaman, merender hanya thumbnail halaman pertama, menghindari overwrite saat `savePdf`, dan membersihkan cache thumbnail stale.
- Cache thumbnail yang tidak dapat didekode dihapus dan dibuat ulang; kegagalan alokasi thumbnail tidak lagi menggagalkan refresh seluruh pustaka.
- Hapus/rename repository menolak path di luar direktori pustaka, dan penghapusan kini memakai nama cache thumbnail yang benar (termasuk timestamp/ukuran) sebelum refresh.
- Tanda tangan kini menskalakan koordinat area sentuh ke bitmap ekspor 400×200. Sebelumnya koordinat layar berdensitas tinggi dapat berada di luar bitmap dan memotong goresan.
- Dokumen baru tidak lagi berisi surat contoh/dummy; editor dibuka sebagai halaman kosong yang benar-benar siap diisi.
- Referensi bitmap hasil render PDF dilepas dari state navigasi setelah editor ditutup/disimpan, tanpa memanggil `recycle()` saat Compose masih mungkin memakai display list terakhir.
- Riwayat command undo/redo dibatasi 100 perubahan terbaru agar dokumen/bitmap lama tidak tertahan tanpa batas.
- `CommandManager` tidak lagi menyimpan properti dokumen awal yang tidak pernah dibaca; setelah reset/perubahan, raster lama dapat dilepas ketika tidak ada di state atau history.
- `DocumentEngine` tidak lagi menahan `Activity Context` yang sama sekali tidak dipakai oleh state/command layer.
- Engine merge, compress, dan repair tidak lagi menyimpan `Context` setelah dependency renderer selesai dibuat.
- Converter dan repository menormalkan context yang memang dibutuhkan menjadi `applicationContext`, sehingga pekerjaan OCR/cache tidak dapat menahan Activity lama.
- `LayoutEngine` menghitung lebar final sebelum menempatkan halaman, sehingga dokumen dengan ukuran halaman campuran tetap terpusat; viewport dan dimensi nol/NaN kini ditolak.
- Canvas editor mengikuti rasio halaman aktif (A4, landscape, Legal, kartu ID, atau hasil impor), bukan memaksa seluruh halaman ke bingkai 340×480 yang sama.
- Input watermark dibatasi 200 karakter dan label tiled diperjelas sebagai pengulangan di area halaman, bukan pilihan halaman dokumen.
- Dokumen editor yang datang dengan daftar halaman kosong dinormalisasi menjadi satu halaman nyata dan seluruh `pageIndex` diurutkan ulang. Indeks aktif juga dijepit setelah undo/redo sehingga canvas, tab, dan subtitle tidak menunjuk halaman yang sudah tidak ada.
- Thumbnail PDF yang sudah dirender/cache oleh repository sekarang benar-benar ditampilkan pada kartu Beranda; sebelumnya aplikasi membayar biaya render dan memori tetapi selalu menampilkan ikon PDF statis.
- Tombol sukses scanner yang sebelumnya berlabel **Selesai & Buka** padahal hanya kembali ke Beranda diubah menjadi **Selesai**, sehingga UI tidak lagi menjanjikan viewer yang tidak dijalankan.

### Hardening konfigurasi aplikasi

- Template `TODO` backup Android diganti aturan eksplisit yang mengecualikan `documents/` dan `tools_output/` dari cloud backup maupun device transfer karena dapat memuat dokumen sensitif.
- Build release tidak lagi diam-diam memakai debug keystore ketika kredensial produksi tidak tersedia; AGP menghasilkan release unsigned secara jujur.
- Build debug kembali memakai signing default AGP, sehingga clone lokal baru tidak gagal hanya karena `debug.keystore` khusus repo tidak tersedia.
- Workflow tidak lagi membuat `debug.keystore` repositori yang tidak pernah dipakai; secret Gemini diteruskan melalui environment step dan ditulis dengan `printf` terkutip.
- Workflow berjalan otomatis untuk `pull_request` dan `push` ke `main`, sedangkan pemicu manual tetap mendukung debug/release/both. Nilai build type mempunyai fallback debug eksplisit untuk event tanpa input.
- Cache Gradle ganda pada `setup-java` dihapus; cache/build instrumentation hanya dikelola `gradle/actions/setup-gradle`.
- Alias catalog dan dependency untuk Room, Firebase, Retrofit, Coil, KSP, Moshi, Credentials, Navigation/ViewModel Compose, dan library lain yang tidak dipakai ikut dihapus.
- Nilai `GEMINI_API_KEY` di-escape sebelum masuk `BuildConfig`, sehingga backslash/quote tidak dapat merusak source hasil generate.
- Wrapper `OcrResult/OcrBlock.processOcr` yang tidak pernah dipakai dan sebelumnya mengarang confidence/bahasa dihapus; jalur aktif mengembalikan teks ML Kit/Gemini apa adanya.
- Ringkasan AI kini memvalidasi input, memecah dokumen panjang, dan memperlakukan teks dokumen sebagai data, bukan instruksi.

### AI, jaringan, dan OCR

- Endpoint Gemini dipindahkan dari API key di query string ke header `x-goog-api-key`.
- Model diubah ke `gemini-3.6-flash`, model GA untuk penggunaan production pada tanggal audit.
- `HttpURLConnection.disconnect()` selalu dipanggil; error stream nullable ditangani; respons dibatasi 4 juta karakter.
- Request ditulis eksplisit sebagai UTF-8; input AI dibatasi 200.000 karakter dan kegagalan alokasi saat menyiapkan OCR cloud dikembalikan sebagai error terkontrol.
- Bitmap resize untuk Vision OCR selalu di-recycle di blok `finally`.
- Prompt dokumen dibatasi tag data agar teks dokumen tidak diperlakukan sebagai instruksi aplikasi.

Rujukan implementasi API: [Gemini text generation REST](https://ai.google.dev/gemini-api/docs/generate-content/text-generation) dan [panduan model terbaru](https://ai.google.dev/gemini-api/docs/latest-model).

### Test regresi baru

- `OfficeFileParserTest.kt`: CSV BOM/delimiter/quoted value, DOCX OpenXML nyata, XLSX shared/inline/sparse cell dan multi-sheet, rollback output lama, kegagalan output baru tanpa artefak parsial, commit atomik sukses tanpa file sementara, lazy bitmap-to-PDF, serta pagination Word/spreadsheet.
- `AutoCropAndFilterTest.kt`: deteksi auto-crop perspektif dan fallback datar, penolakan crop bersilangan, filter profesional dan `ORIGINAL` netral, ownership bitmap scanner agar source Compose tidak di-recycle, serta round-trip kontainer terenkripsi dan penolakan password salah.
- `FilterSettingsAndLayoutTest.kt`: normalisasi rentang filter, batas 100 command undo/redo, dan centering layout untuk halaman dengan lebar campuran.
- `DokuPdfInstrumentedSmokeTest.kt`: smoke test perangkat membuka Beranda nyata, masuk ke editor dokumen kosong, lalu memastikan canvas dan aksi ekspor tampil.
- `HomeScreenScreenshotTest.kt`: capture permukaan Beranda nyata ke output build; nama/file `Greeting` dan gambar golden placeholder lama dihapus.
- File lama `ExampleInstrumentedTest.kt` dihapus setelah audit menemukan file lama dan file hasil rename sama-sama mendeklarasikan `DokuPdfInstrumentedSmokeTest`, yang akan menyebabkan duplicate declaration saat `compileDebugAndroidTestKotlin`.

Test di atas merupakan source regresi yang ditambahkan. Status eksekusinya tetap mengikuti bagian verifikasi berikut; laporan ini tidak menganggap test lulus hanya karena file test tersedia.

### Status verifikasi audit lanjutan

- `git diff --check`: lulus.
- Semua XML resource/manifest: lulus parsing XML.
- `gradle/libs.versions.toml`: lulus parsing TOML; seluruh 30 alias library dan 3 alias plugin dipakai serta semua referensi Gradle mempunyai alias yang tersedia.
- `.github/workflows/build-apk.yml`: lulus parsing YAML; major action yang dipakai diverifikasi masih tersedia pada dokumentasi upstream [actions/checkout](https://github.com/actions/checkout), [actions/setup-java](https://github.com/actions/setup-java), [actions/upload-artifact](https://github.com/actions/upload-artifact), dan [gradle/actions](https://github.com/gradle/actions).
- Pemeriksaan deklarasi tipe Kotlin: tidak menemukan class/object/interface dengan fully-qualified name ganda setelah file test lama dihapus.
- Pemeriksaan delimiter Kotlin/KTS: seluruh kurung, kurawal, string, char, dan komentar seimbang pada source yang diaudit.
- Pencarian ulang `TODO`/`FIXME`/placeholder handler: tidak menemukan handler alat PDF yang masih dummy.
- Pemetaan statis alat: 18 ID unik memiliki 15 handler eksekusi dialog dan 3 handler launcher (`image_to_pdf`, `word_to_pdf`, `excel_to_pdf`); tidak ada ID yang hilang atau handler yatim.
- Percobaan `./gradlew :app:compileDebugKotlin --offline`: tidak dapat dimulai karena distribusi Gradle 9.3.1 tidak tersedia pada Gradle user home sandbox.
- Percobaan dengan Gradle user home writable: wrapper mencoba mengunduh distribusi, tetapi sandbox mengembalikan `UnknownHostException: services.gradle.org`.
- Karena kompilasi dan test tidak benar-benar berjalan di lingkungan ini, laporan ini **tidak** menyatakan build/test lulus. Workflow GitHub harus menjalankan `testDebugUnitTest` dan `assembleDebug` setelah perubahan dipublikasikan atas izin pengguna.

### Batasan yang sengaja dinyatakan, bukan disamarkan sebagai sukses

- Merge, split, rotate, compress, dan repair memakai raster rebuild Android karena proyek tidak membawa library manipulasi object-level PDF. Visual dipertahankan, tetapi selectable text, link, form, metadata, dan digital signature PDF sumber tidak dipertahankan.
- PDF ke DOCX/CSV berbasis OCR; paragraph/table kompleks tidak dapat dijamin identik dengan layout sumber.
- Spreadsheet `.xls` biner lama ditolak dengan pesan untuk menyimpan ulang sebagai `.xlsx`/`.csv`; engine tidak pernah lagi membaca byte biner `.xls` sebagai teks palsu.
- API key yang dibundel dalam APK dapat diekstrak oleh pihak yang memiliki APK. Untuk produksi publik, panggilan Gemini sebaiknya diproksikan melalui backend dengan autentikasi pengguna, quota, dan key restriction.
