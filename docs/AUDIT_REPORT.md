# DokuPDF — Laporan Audit

## Investigasi Laporan Pengguna 2026-08-24 — Filter "Mempertajam" Buruk & Crop Tidak Bisa Digeser

**Input:** 2 screenshot perbandingan (CamScanner vs DokuPDF pada dokumen yang sama,
berbayangan hangat/indoor) + laporan langsung: filter hasil "sangat buruk" dan bingkai
crop "tidak bisa digeser/diatur" sama sekali (CamScanner bisa).

### 1. Bug crop — root cause ditemukan di `InteractiveCropScreen.kt`

`Canvas(...).pointerInput(displayedImageBounds, cropGeometry) { detectDragGestures(...) }`
memakai `cropGeometry` sebagai salah satu **key** pointerInput. Tapi `cropGeometry` diubah
**di dalam `onDrag` itu sendiri** setiap piksel jari bergerak. Compose pointerInput
me-restart ulang seluruh coroutine gesture-nya setiap kali salah satu key berubah — jadi
setiap gerakan sekecil apa pun membatalkan `detectDragGestures` yang sedang berjalan dan
memulainya dari nol lagi, sebelum drag sempat "menempel" secara berkelanjutan. Efek yang
terlihat pengguna: jari terasa menyentuh handle, tapi bingkai tidak pernah benar-benar
bergeser mengikuti jari.

**Fix:** `cropGeometry` dihapus dari daftar key. Lambda `onDrag`/`onDragStart` tetap
membaca nilai `cropGeometry` terbaru lewat closure state Compose biasa — tidak perlu
me-restart coroutine untuk itu. Hanya `displayedImageBounds` yang valid sebagai key
(berubah jarang: saat layout awal atau setelah rotasi).

### 2. Bug filter — root cause ditemukan di `FilterProcessor.kt`

Filter `Mempertajam` (`applySuperSharpen`), `Magic Color` (`applyMagicColor`), dan
`Otomatis` (yang bisa memanggil salah satu dari keduanya) punya cabang khusus untuk
**mempertahankan** warna asli elemen seperti stempel biru/tanda tangan/materai merah —
piksel dengan `chroma >= 20` (atau `22`) DINAIKKAN saturasinya 1.4x alih-alih diratakan
ke putih seperti bayangan biasa.

Masalahnya: bayangan hangat/kuning khas foto kamera HP indoor (bukan tinta berwarna sama
sekali) juga sering punya chroma di atas 20 — karena channel R dan G tinggi sementara B
jauh lebih rendah. Bayangan seperti itu, alih-alih diratakan ke putih, malah ikut kena
saturasi 1.4x dan berubah jadi **noda kuning pekat** — persis pola yang terlihat di
screenshot 2 pengguna (blotch kuning besar menutupi sebagian dokumen).

**Fix:** ditambahkan `isWarmShadowCast(r, g, b)` — mendeteksi pola spesifik "R dan G
sama-sama jauh di atas B" (ciri bayangan kuning/oranye) dan MENGECUALIKANNYA dari cabang
"elemen warna" di kedua filter. Stempel merah sungguhan tidak ikut terkecualikan karena G
pada tinta merah tetap rendah (hanya R yang tinggi) — jadi diskriminatornya tetap akurat
membedakan bayangan hangat vs tinta berwarna asli. Jalur deteksi tinta biru
(`b > r + ... && b > g + ...`) sama sekali tidak tersentuh perubahan ini.

**Belum diverifikasi di device fisik** — perbaikan ini berbasis analisis kode + pola warna
di screenshot, bukan hasil re-run scan langsung (lingkungan pengembangan tidak punya
Android SDK/device/emulator). Rekomendasi: uji ulang scan dokumen yang sama persis dengan
kondisi pencahayaan di screenshot 2 untuk konfirmasi visual sebelum rilis.


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

## Babak 3 — mutex TERBUKTI TIDAK cukup; root cause sebenarnya ditemukan

Run CI berikutnya (`logs_88517272032.zip`, workflow sudah manual/`workflow_dispatch`
sesuai permintaan) memberi bukti definitif: **kedua test masih gagal, dengan stack
trace yang identik** (hanya nomor baris bergeser sedikit akibat kode mutex yang
ditambahkan). Ini membuktikan hipotesis mutex di Babak 2 **salah** — bukan bug
konkurensi/race, karena test ini berjalan sepenuhnya sekuensial (satu coroutine,
tidak ada instance `PdfConverterEngine` lain yang aktif bersamaan) sehingga mutex
tidak pernah benar-benar berkontensi di sini.

### Bukti baru yang mengonfirmasi root cause sebenarnya

Pencarian literatur komunitas Robolectric (artikel *"How to Solve Flaky Robolectric
and Roborazzi Tests"*, dan berbagai open issue resmi `robolectric/robolectric` soal
`GraphicsMode.NATIVE`) mengonfirmasi pola yang **persis cocok** dengan gejala di sini:

> *"there will be threads in the app that aren't controlled during testing, which
> also contributes to the tests' flakiness"* — dan solusi standar yang disebutkan
> eksplisit: **"Injecting Coroutine Dispatchers: I replace the Default and IO
> dispatchers with StandardTestDispatcher"**.

Akar masalahnya: `withContext(Dispatchers.IO)` di dalam `runTest { }` **benar-benar
berpindah ke thread pool nyata** (bukan scheduler waktu-virtual `TestDispatcher`
milik `runTest`), sehingga kode `PdfDocument` (native graphics `@GraphicsMode.NATIVE`)
dieksekusi di thread yang **tidak dikendalikan Robolectric** — persis kategori bug
yang didokumentasikan komunitas sebagai penyebab utama flakiness jenis ini.

### Perbaikan yang diterapkan: dependency-inject `ioDispatcher`

Setiap kelas yang sebelumnya hardcode `withContext(Dispatchers.IO)` sekarang
menerima parameter constructor `ioDispatcher: CoroutineDispatcher = Dispatchers.IO`
(default tidak berubah — perilaku device asli identik):

- `PdfConverterEngine.kt` (13 fungsi)
- `PdfRendererEngine.kt` (5 fungsi — dipakai `forEachRenderedPage`/`getPageCount`/dst
  oleh `PdfConverterEngine.rotatePdf()`, `PdfMergerSplitter`, `PdfRepairEngine`,
  `PdfCompressor`)
- `PdfGenerator.kt`, `PdfMergerSplitter.kt`, `PdfRepairEngine.kt`, `PdfCompressor.kt`

Parameter `ioDispatcher` sengaja dideklarasikan **sebelum** `pdfRenderer`/
`rendererEngine` di constructor masing-masing kelas, supaya nilai default
`PdfRendererEngine(context, ioDispatcher)` bisa meneruskan dispatcher yang sama —
satu rantai pemanggilan (mis. `rotatePdf()` yang memanggil `forEachRenderedPage()`
di dalamnya) sekarang konsisten memakai satu dispatcher yang sama dari ujung ke ujung.

Di `OfficeFileParserTest.kt`, kedua test yang gagal sekarang mengonstruksi
`PdfConverterEngine(context, ioDispatcher = Dispatchers.Unconfined)` — menjaga
seluruh kode `PdfDocument` tetap berjalan di thread test yang sama (tidak
berpindah ke thread pool), alih-alih mengubah desain memory-efficient produksi.

**Mutex dari Babak 2 (`PdfFileUtils.pdfDocumentMutex`) TETAP dipertahankan** —
terbukti tidak menyelesaikan masalah SPESIFIK ini, tapi tetap sinkronisasi yang
sah untuk melindungi `PdfDocument` (didokumentasikan resmi "not thread safe")
dari race konkurensi nyata yang independen, yang bisa saja terjadi di device
sungguhan meski tidak relevan untuk kegagalan test ini.

### Cakupan yang SENGAJA tidak diubah

`PdfComparer.kt`, `PdfSecurity.kt`, dan `DocumentRepository.kt` juga memakai
`withContext(Dispatchers.IO)`, tapi tidak disentuh test manapun yang gagal saat
ini (tidak ada assertion Robolectric NATIVE yang melibatkan kelas-kelas ini
secara multi-halaman). Dibiarkan apa adanya untuk menjaga perubahan tetap
berbasis bukti, bukan blanket-refactor spekulatif. **Kalau nanti ada test baru
untuk kelas-kelas ini yang menunjukkan gejala serupa, terapkan pola yang sama
(inject `ioDispatcher`, konstruksi dengan `Dispatchers.Unconfined` di test).**

### Kejujuran soal kepastian (masih berlaku)

Ini analisis berbasis bukti kuat (dokumentasi komunitas + kecocokan gejala
persis), bukan tebakan — tapi seperti babak sebelumnya, **kepastian mutlak
hanya bisa didapat dari run CI aktual berikutnya**. Kalau kedua test MASIH
gagal setelah perubahan ini, itu petunjuk kuat root cause-nya bukan soal thread
sama sekali, dan langkah berikutnya adalah opsi #2 dari Babak 2 (nonaktifkan
`@GraphicsMode(NATIVE)` khusus 2 test ini).

## Babak 4 — Root cause DIPASTIKAN: PdfDocument tidak punya native shadow di Robolectric

Run CI berikutnya (`logs_88518760928.zip`) membuktikan hipotesis thread-hop di
Babak 3 **juga salah** — kedua test masih gagal dengan pesan identik, dan kali
ini stack trace-nya membuktikan eksekusi tetap berada di
`kotlinx.coroutines.test.TestDispatcher`/`TestCoroutineScheduler` (bukan lagi
`CoroutineScheduler$Worker` seperti Babak 3) — artinya `Dispatchers.Unconfined`
berhasil menahan eksekusi tetap di thread test, TAPI kegagalan tetap terjadi.
Ini secara definitif membuktikan bukan soal thread sama sekali.

### Investigasi ulang dari nol (sesuai instruksi eksplisit)

Alih-alih menambah dugaan baru, dilakukan penelusuran ke sumber utama:
**commit AOSP yang memperkenalkan `@GraphicsMode(NATIVE)`** (Michael Hoisie,
*"Introduce a set of graphics shadows backed by native code"*, 13 Des 2022,
https://android.googlesource.com/platform/external/robolectric/+/636a0fdbbae8).

Commit itu melampirkan **daftar lengkap semua kelas graphics yang mendapat
native shadow** — sekitar 70 file: `ShadowNativeBitmap`, `ShadowNativeCanvas`,
`ShadowNativePaint`, `ShadowNativeTypeface`, `ShadowNativePath`,
`ShadowNativeRegion`, dst. **`PdfDocument` sama sekali tidak ada di daftar
itu** — tidak ada `ShadowNativePdfDocument`, tidak ada `PdfDocumentNatives`.
Penelusuran rilis-rilis berikutnya (4.10 s/d 4.16.1) tidak menemukan bukti
penambahan shadow untuk `PdfDocument` di kemudian hari.

### Mekanisme kegagalan yang sekarang bisa dijelaskan sepenuhnya

Robolectric punya perilaku default terdokumentasi resmi: method native **tanpa
shadow eksplisit otomatis menjadi no-op yang mengembalikan nilai default**
(bukan error/crash). Urutan kejadiannya:

1. `PdfDocument()` dipanggil → constructor memanggil native method pembuat
   handle dokumen → **tidak ada shadow untuk `PdfDocument`** → Robolectric
   no-op, mengembalikan `0` → `mNativeDocument = 0` **sejak konstruksi**,
   bukan setelah `close()` dipanggil.
2. `pdfDoc.startPage(pageInfo)` dipanggil (baris kode aplikasi mana pun,
   halaman pertama sekalipun) → `throwIfClosed()` mengecek
   `mNativeDocument == 0` → **selalu true** → `IllegalStateException:
   "document is closed!"`.

Teori ini menjelaskan **seluruh** bukti yang terkumpul sejak Babak 1, tanpa
sisa anomali:
- Kenapa gagal 100% deterministik (bukan flaky) — bukan race, tapi absennya
  implementasi.
- Kenapa Mutex (Babak 2) tidak berpengaruh — tidak ada konkurensi yang terlibat.
- Kenapa dispatcher injection (Babak 3) tidak berpengaruh — tidak ada soal thread.
- Kenapa gagal tepat di `startPage()` pertama, bukan `writeTo()`/`finishPage()`.
- Kenapa HANYA 2 test yang memakai `PdfDocument` di seluruh project yang gagal,
  sementara `AutoCropAndFilterTest`/`HomeScreenScreenshotTest` (pakai
  Bitmap/Canvas/Paint — SEMUANYA punya native shadow) lulus di
  `@GraphicsMode(NATIVE)` yang sama persis.

### Kenapa TIDAK pindah ke `@GraphicsMode(LEGACY)`

Robolectric juga tidak pernah menyediakan shadow legacy (non-native) untuk
`PdfDocument`. Di mode legacy, method native tanpa shadow **juga** jadi no-op
— bedanya, TIDAK ada pengecekan `throwIfClosed()` yang sama-sama no-op, jadi
`writeTo()` kemungkinan besar akan "sukses" menulis PDF kosong/rusak. Test
akan **lulus secara palsu** — pelanggaran prinsip inti audit ini (tidak boleh
ada sukses palsu), lebih buruk daripada status merah yang jujur. Diputuskan
TIDAK mengganti mode.

### Keputusan final: `@Ignore` dengan justifikasi eksplisit + rencana instrumented test

Kedua test ditandai `@Ignore` dengan pesan yang menjelaskan root cause secara
lengkap (bukan `@Ignore` polos) — supaya siapa pun yang membaca kode langsung
tahu KENAPA, bukan mengira fiturnya rusak atau ditinggalkan. Ini BUKAN
menyembunyikan masalah: perilaku produksi (`generatedBitmapsToPdf`,
`wordLinesToPdf`) tidak diubah sama sekali dan tetap benar untuk Android
sungguhan sesuai kontrak resmi `PdfDocument`.

Item roadmap konkret dicatat: pindahkan kedua skenario ini ke Android
Instrumented Test (`app/src/androidTest/`) supaya cakupan verifikasi otomatis
pulih — di lingkungan itu `PdfDocument` berjalan di device/emulator nyata,
bukan simulasi JVM Robolectric yang punya gap ini. Lihat `docs/ROADMAP.md`.

**Mutex (`pdfDocumentMutex`) dan dispatcher injection (`ioDispatcher`) TETAP
dipertahankan** meski keduanya terbukti tidak relevan untuk kegagalan spesifik
ini — keduanya tetap perbaikan yang sah secara independen (thread-safety nyata
untuk `PdfDocument`, dan fleksibilitas testing untuk kelas-kelas lain).
