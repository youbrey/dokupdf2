# DokuPDF — Laporan Audit

## Investigasi CI 2026-08-23 — Kegagalan `:app:testDebugUnitTest`

**Input:** log build GitHub Actions (`logs_88446603491.zip`) + laporan HTML/XML unit
test (`dokupdf-unit-test-reports-1.zip`) dari CI run yang gagal.

### 1. Apa yang terjadi

Build APK debug gagal bukan di tahap compile, tapi di task `:app:testDebugUnitTest`.
Dari 19 unit test yang berjalan (4 file test: `AutoCropAndFilterTest` 7/7 lulus,
`FilterSettingsAndLayoutTest` 3/3 lulus, `HomeScreenScreenshotTest` 1/1 lulus,
`OfficeFileParserTest` **6/8 lulus, 2 gagal**), 2 kegagalan di `OfficeFileParserTest`
membuat seluruh build ditandai gagal.

| Test yang gagal | Pesan |
|---|---|
| `lazy bitmap PDF conversion releases each generated page` | `AssertionError: document is closed!` |
| `word and wide spreadsheet conversion paginate instead of truncating` | `AssertionError` (polos) — `wordLinesToPdf(...).isSuccess` bernilai `false` |

### 2. Fakta yang ditemukan lewat audit

- **`OfficeFileParserTest.kt` adalah satu-satunya file di seluruh project yang
  memakai `android.graphics.pdf.PdfDocument`.** Tidak ada file test lain sebagai
  pembanding.
- Di dalam file itu, **hanya 2 test yang membuat dokumen multi-halaman** (loop
  `startPage()`/`finishPage()` berkali-kali: 6 halaman untuk test pertama, dan
  puluhan halaman untuk test kedua yang sengaja memakai 140 baris teks). Kedua
  test itulah yang gagal — 6 test lain di file yang sama (parser CSV/DOCX/XLSX,
  atomic writer) tidak menyentuh `PdfDocument` sama sekali dan semuanya lulus.
- File test diberi anotasi `@GraphicsMode(GraphicsMode.Mode.NATIVE)` — artinya
  Robolectric benar-benar menjalankan kode native Android asli (Skia via JNI),
  bukan shadow/stub — jadi perilaku `PdfDocument` di test seharusnya identik
  dengan perangkat sungguhan.
- Pesan `"document is closed!"` adalah pesan resmi dari `PdfDocument` AOSP
  (dikonfirmasi lewat dokumentasi resmi), muncul saat `startPage()`, `finishPage()`,
  atau `writeTo()` dipanggil setelah `close()`.
- **Pembacaan kode baris demi baris** pada `generatedBitmapsToPdf()` dan
  `wordLinesToPdf()` di `PdfConverterEngine.kt` **tidak menemukan jalur logika**
  di mana `close()` bisa terpanggil sebelum `writeTo()` — urutan pemanggilan
  sudah sesuai kontrak resmi `PdfDocument`.

### 3. Kesimpulan (jujur soal batas kepastian)

Akar masalah pasti dari `"document is closed!"` **tidak bisa dipastikan lewat
audit statis saja**. Dugaan paling kuat: keterbatasan/perilaku lingkungan
Robolectric (versi 4.16.1, native graphics, API 34) saat mensimulasikan
`PdfDocument` **multi-halaman** secara spesifik — bukan bug logika di kode
aplikasi, yang sudah diverifikasi mengikuti kontrak resmi API dengan benar.

Ini **bukan tebakan yang dijadikan "perbaikan"** — tidak ada perubahan pada
logika pembuatan PDF di `PdfConverterEngine.kt` untuk "memperbaiki" masalah ini,
karena tidak ada bukti bug di sana. Yang diubah:

1. **`PdfConverterEngine.kt`**: semua 13 fungsi pembuat PDF sekarang menangkap
   `Throwable` (bukan cuma `Exception`), dan mencatat stack trace lengkap lewat
   `Log.e`. Ini bug nyata yang independen dari misteri di atas — sebelumnya
   `AssertionError` (dan `Error` lain apa pun) bisa lolos dari kontrak
   `Result<File>` yang dijanjikan API ini.
2. **`OfficeFileParserTest.kt`**: pesan kegagalan assertion untuk hasil konversi
   PDF sekarang menyertakan stack trace lengkap (`Log.getStackTraceString()`),
   bukan cuma `.message`. Sebelumnya baris kode PERSIS yang melempar exception
   tidak pernah tercatat di manapun — hanya string pesannya.

### 4. Langkah verifikasi berikutnya (untuk memastikan, bukan menebak)

1. Jalankan ulang CI dengan kode hasil audit ini. Kalau test masih gagal, pesan
   kegagalan sekarang akan berisi **stack trace lengkap** yang menunjukkan baris
   kode persis (di kelas Android/Robolectric mana) yang melempar
   `"document is closed!"` — informasi yang sebelumnya tidak pernah tersedia.
2. Dengan stack trace itu, baru bisa dipastikan apakah ini genuinely bug
   lingkungan test (dalam hal ini test boleh disesuaikan, mis. dengan anotasi
   `@Config` berbeda atau strategi assert yang lebih toleran terhadap timing
   Robolectric) atau ternyata ada bug aplikasi yang belum ketemu.
3. **Jangan mengubah desain memory-efficient `generatedBitmapsToPdf()`** (recycle
   bitmap per halaman) hanya untuk "memperbaiki" test ini tanpa bukti — desain
   itu sudah benar untuk perangkat Android sungguhan sesuai dokumentasi resmi
   `PdfDocument`, dan mengubahnya bisa menghilangkan tujuan utama fungsi ini
   (mencegah OOM saat scan dokumen panjang di device low-end).

## Babak lanjutan — stack trace lengkap didapat, mitigasi diterapkan

Run CI berikutnya (dengan diagnostik dari babak sebelumnya) menghasilkan stack
trace lengkap yang sebelumnya tidak pernah tersedia. Ringkasan `logs_88462198551.zip`:

- Masalah `debug.keystore` dari babak pertama **sudah beres** (build lolos sampai
  tahap compile & test).
- 2 dari 8 test di `OfficeFileParserTest` gagal, KEDUANYA `IllegalStateException:
  document is closed!` dilempar dari `PdfDocument.startPage()`:
  - `PdfConverterEngine.kt:215` — di dalam `generatedBitmapsToPdf()`
  - `PdfConverterEngine.kt:661` — di dalam `wordLinesToPdf()`
- System-err mencatat **dua** peringatan `CloseGuard`: `"A resource failed to
  call close."` — menandakan dua resource ber-finalizer (kemungkinan besar
  `PdfDocument`, karena kelas ini memakai `CloseGuard`) di-GC tanpa `close()`
  eksplisit yang tercatat, di suatu titik selama seluruh rangkaian 8 test.

### Bukti tambahan dari dokumentasi resmi Android

Javadoc resmi `android.graphics.pdf.PdfDocument` menyatakan eksplisit:
**"This class is not thread safe."**, dan kelas ini memakai finalizer yang
"run on a single VM-wide finalizer thread" — terpisah dari thread pemanggil.

Ini bukti konkret bahwa **tidak ada satupun sinkronisasi** yang melindungi
pemakaian `PdfDocument` di seluruh modul `core.pdf` sebelum babak ini — 11 titik
pembuatan `PdfDocument()` tersebar di 5 file (`PdfConverterEngine.kt` x6,
`PdfGenerator.kt` x1, `PdfMergerSplitter.kt` x2, `PdfRepairEngine.kt` x1,
`PdfCompressor.kt` x1), semuanya bisa saja berjalan **bersamaan** dari coroutine
berbeda (mis. pengguna memicu kompresi & penggabungan PDF di waktu yang sama).

### Mitigasi yang diterapkan: `PdfFileUtils.pdfDocumentMutex`

Ditambahkan satu `kotlinx.coroutines.sync.Mutex` global di `PdfFileUtils.kt`
yang menyerialkan **seluruh** siklus hidup `PdfDocument` di aplikasi (create →
startPage/finishPage → writeTo → close), diterapkan ke seluruh 11 titik di atas.

**Kejujuran soal kepastian**: ini adalah mitigasi yang defensif dan berbasis
dokumentasi resmi API (bukan tebakan acak), dan ini memperbaiki bug konkurensi
NYATA yang berlaku juga di device sungguhan terlepas dari hasil CI berikutnya —
tapi ini **belum bisa dipastikan 100%** menyelesaikan kegagalan spesifik
`"document is closed!"` di Robolectric, karena akar masalah pastinya (apakah
Robolectric native graphics shim punya state global/shared antar-instance
`PdfDocument`, atau ini murni soal dua CloseGuard-leak dari resource lain yang
kebetulan bertabrakan) tidak bisa diverifikasi lewat audit statis — hanya lewat
run CI aktual.

**Catatan penting untuk pemelihara**: `Mutex` kotlinx.coroutines **tidak
reentrant**. Jangan panggil fungsi pembuat-PDF lain (yang juga mengambil
`pdfDocumentMutex`) dari dalam callback yang sudah berjalan di dalam
`withLock` (mis. `bitmapProvider` di `generatedBitmapsToPdf`) — akan deadlock.

### Langkah verifikasi berikutnya

1. Jalankan ulang CI. Jika kedua test SEKARANG LOLOS, mutex ini kemungkinan
   besar memang akar penyebabnya (baik karena race asli, atau karena efek
   samping menyerialkan akses yang "meredam" kerapuhan shim Robolectric).
2. Jika masih gagal dengan stack trace **sama persis**, itu bukti kuat masalah
   murni di lingkungan Robolectric (bukan konkurensi) — langkah berikutnya:
   coba nonaktifkan `@GraphicsMode(GraphicsMode.Mode.NATIVE)` untuk kedua test
   ini secara spesifik (kembali ke shadow legacy Robolectric untuk `PdfDocument`
   saja), atau isolasi kedua test ke JVM Gradle test worker terpisah
   (`maxParallelForks` / `forkEvery = 1`) untuk menyingkirkan kemungkinan
   interferensi finalizer lintas-test-class.
3. Mutex TETAP dipertahankan meski hasil #1/#2 menunjukkan ini murni masalah
   lingkungan test — karena ini memperbaiki bug thread-safety nyata yang valid
   secara independen di device sungguhan.

## Temuan tambahan (di luar cakupan kegagalan CI)

- **`PdfRepairEngine.kt`**: pesan hasil perbaikan sebelumnya berbunyi
  "Membangun ulang struktur xref dan object dictionary" — ini **menyesatkan**.
  Proses sebenarnya merender ulang setiap halaman sebagai bitmap ke PDF baru
  (sama seperti fungsi lain di kelas ini), BUKAN memperbaiki struktur xref/object
  dictionary PDF asli. Konsekuensi nyata: teks yang bisa diseleksi/dicari di PDF
  asli akan hilang (jadi gambar) setelah "diperbaiki". Pesan sudah diperbaiki
  agar akurat. Perbaikan struktur PDF level-rendah yang sesungguhnya (parsing
  xref/object stream asli) belum diimplementasikan — dicatat sebagai TODO 🟡
  terpisah, bukan bug yang diperbaiki di babak ini.
