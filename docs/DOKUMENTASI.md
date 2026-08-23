# DokuPDF — Dokumentasi Audit Bug & Perbaikan

**Tanggal audit:** 22 Agustus 2026
**Cakupan:** Seluruh alur Scanner (kamera → auto-crop → potong ulang → simpan PDF) dan seluruh
modul `core/pdf/*` (compress, merge/split, repair, security, comparer, converter, renderer).
**Metode:** Audit statis menyeluruh terhadap source code. **Tidak ada Android SDK/emulator di
lingkungan ini**, jadi temuan di bawah didasarkan pada pembacaan kode yang cermat (alur
kepemilikan bitmap, exception handling, lifecycle), bukan reproduksi langsung di perangkat.
Bagian "Cara verifikasi" di tiap temuan menjelaskan cara memastikan di HP fisik + Logcat,
konsisten dengan alur kerja audit sebelumnya di proyek ini.

---

## Ringkasan Eksekutif

| # | Temuan | Severity | Status |
|---|--------|----------|--------|
| 1 | Bitmap bocor (tidak di-`recycle()`) di alur Potong Ulang + Putar → OOM crash tertunda | 🔴 **Kritis** — ini kemungkinan besar bug crash yang dilaporkan | ✅ **Diperbaiki** |
| 2 | `applyAutoEnhance` memanggil `getPixel()` per-piksel (ribuan native call per foto) | 🟡 Sedang (performa) | ✅ **Diperbaiki** |
| 3 | Fitur "Kunci PDF" menghasilkan kontainer `.dokupdf` proprietary, bukan PDF berpassword standar | 🟡 Sedang (ekspektasi pengguna) | 📝 Didokumentasikan, belum diubah — lihat catatan |
| 4 | Beberapa alat PDF (Compress, Merge, Repair) membaca dimensi halaman dan me-render halaman dalam **dua pass terpisah**, lalu mengindeks silang | 🟢 Rendah (sudah dibungkus try/catch, tidak crash) | 📝 Didokumentasikan |
| 5 | `docs/CHANGELOG.md` belum mencerminkan hardening besar yang sudah ada di kode | 🟢 Rendah (administratif) | 📝 Didokumentasikan |

File yang diubah: `ui/screens/InteractiveCropScreen.kt`, `ui/screens/ScannerScreen.kt`,
`core/filter/FilterProcessor.kt`.

---

## Temuan #1 (Kritis): Bitmap bocor di alur Potong Ulang + Putar

### Gejala yang cocok
"App crash saat selesai mengambil gambar dari kamera untuk dibuat PDF" — pada sesi pemindaian
multi-halaman, setelah pengguna memakai **Potong Ulang** pada satu atau lebih halaman (terutama
sambil menekan tombol **Putar Kiri/Kanan** untuk meluruskan dokumen — ini gerakan yang sangat
wajar saat membingkai dokumen), pengambilan foto **berikutnya** gagal dengan `OutOfMemoryError`.
Crash-nya memang "muncul" tepat setelah jepret foto, tapi akar masalahnya adalah memori yang
sudah bocor dari aksi-aksi sebelumnya di sesi yang sama.

### Root cause
Hampir di seluruh codebase ini, setiap kali sebuah bitmap resolusi penuh digantikan oleh bitmap
baru (rotate, crop, filter, scale), kode selalu memanggil `.recycle()` pada bitmap lama — ada
pola "ownership tracking" yang konsisten dipakai di `ScannedPageItem.getRenderedBitmap()`,
`PdfGenerator`, `PdfRendererEngine`, dll. **Dua tempat berikut adalah pengecualian dari pola
ini**, dan keduanya persis ada di jalur "Potong Ulang":

**1a. `InteractiveCropScreen.kt` — `rotateAndDetect()`**

```kotlin
// SEBELUM (bocor):
fun rotateAndDetect(degrees: Float) {
    scope.launch {
        isProcessing = true
        val source = workingBitmap
        try {
            val rotated = withContext(Dispatchers.Default) {
                val matrix = Matrix().apply { postRotate(degrees) }
                Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            }
            workingBitmap = rotated   // <- `source` lama tidak pernah di-recycle di sini
            ...
```

Setiap kali pengguna menekan **Putar Kiri/Kanan** di layar potong, satu bitmap resolusi penuh
baru dibuat dan bitmap lama (`source`) dilepas begitu saja tanpa `.recycle()`. Kalau pengguna
menekan putar 3–4 kali untuk meluruskan dokumen (wajar terjadi), itu 3–4 bitmap ARGB_8888
resolusi penuh (±15–50 MB masing-masing pada mode HD) yang bocor — hanya menunggu GC yang tidak
terjamin cukup cepat di bawah tekanan memori tinggi.

Selain itu, kalau pengguna menekan **Kembali** (batal) setelah memutar tanpa menekan
"Berikutnya", bitmap hasil putar terakhir juga tidak pernah dilepas maupun dipakai — bocor total.

**1b. `ScannerScreen.kt` — callback `onCropConfirmed`**

```kotlin
// SEBELUM (bocor):
onCropConfirmed = { croppedBmp, geometry, rotatedBmp ->
    val idx = activeCropPageIndex
    if (idx in scannedPages.indices) {
        val previous = scannedPages[idx]
        scannedPages[idx] = previous.copy(
            originalBitmap = rotatedBmp,
            cropGeometry = geometry,
            croppedBitmap = null
        )
        // `previous.originalBitmap` dan `previous.croppedBitmap` (kalau ada) tidak
        // pernah di-recycle di sini -- keduanya digantikan begitu saja.
        if (croppedBmp !== rotatedBmp && !croppedBmp.isRecycled) croppedBmp.recycle()
    }
    cropTargetPageIndex = null
}
```

Setiap kali pengguna menekan "Berikutnya" di layar Potong Ulang, `originalBitmap` **lama**
halaman tersebut (bitmap mentah sebelum potong-ulang ini) diganti dengan bitmap hasil putar yang
baru — tapi bitmap lama itu tidak pernah dilepas. Satu "Potong Ulang" = satu bitmap resolusi
penuh bocor secara permanen selama sesi scanner masih terbuka.

### Kenapa ini memicu crash "setelah jepret foto", bukan saat potong-ulang itu sendiri
`ScannerScreen` sudah punya budget memori sesi (`maximumSessionPixels`, di sekitar 10–40 MB
setara *piksel* tersimpan) yang dicek **hanya saat menambah halaman baru dari kamera**, bukan
saat potong-ulang. Bitmap yang bocor lewat potong-ulang/putar tidak pernah masuk hitungan budget
ini sama sekali — jadi budget tetap terlihat "aman" sementara heap sesungguhnya makin penuh oleh
sampah yang tidak tercatat. Begitu heap benar-benar habis, kegagalan alokasi berikutnya (paling
sering: `imageProxyToBitmap()` saat memproses foto baru) yang melempar `OutOfMemoryError` — dan
itulah momen yang terasa sebagai "crash setelah jepret foto".

### Perbaikan yang diterapkan
1. **`InteractiveCropScreen.kt`**: `rotateAndDetect()` sekarang me-recycle bitmap lama
   (`source`) segera setelah rotasi baru berhasil dibuat (dengan pengaman agar tidak pernah
   me-recycle `initialBitmap` milik caller). Ditambahkan juga `DisposableEffect` yang
   me-recycle `workingBitmap` saat layar ini ditutup **tanpa** konfirmasi (menekan "Kembali"
   setelah memutar) — dengan flag `workingBitmapOwnershipTransferred` supaya bitmap yang
   sudah diserahkan ke caller lewat `onCropConfirmed` tidak ikut ke-recycle.
2. **`ScannerScreen.kt`**: `onCropConfirmed` sekarang me-recycle `previous.originalBitmap`
   dan `previous.croppedBitmap` (kalau ada) setelah halaman diperbarui — dengan pengecekan
   `scannedPages.none { it.originalBitmap === orphanedOriginal }` sebagai pengaman ekstra agar
   tidak pernah me-recycle bitmap yang (secara tak terduga) masih dipakai halaman lain.

### Cara verifikasi di perangkat fisik
1. Pasang build hasil perbaikan ini, buka **Profiler memori Android Studio** (atau
   `adb shell dumpsys meminfo <package>`) sambil memindai.
2. **Sebelum fix**: pindai 1 halaman → Potong Ulang → tekan Putar 4× → Berikutnya → ulangi untuk
   3–4 halaman. Perhatikan grafik heap naik terus tanpa pernah turun meski sudah kembali ke
   kamera. Ambil lebih banyak foto sampai `OutOfMemoryError` muncul di Logcat (filter tag
   `DokuPdfCamera`).
3. **Sesudah fix**: ulangi urutan yang sama — heap harus turun kembali setiap kali kembali ke
   kamera (GC bisa langsung membebaskan bitmap yang sudah eksplisit di-recycle), dan sesi bisa
   berlanjut jauh lebih lama tanpa OOM.

---

## Temuan #2 (Performa, sudah diperbaiki): `applyAutoEnhance` memanggil `getPixel()` per-piksel

`FilterType.AUTO` adalah filter **default** untuk mode Satu Halaman/Banyak Halaman (mode paling
umum dipakai). Implementasinya sebelumnya memanggil `Bitmap.getPixel(x, y)` satu-per-satu di
grid sampel ~320px:

```kotlin
for (y in 0 until source.height step sampleStep) {
    for (x in 0 until source.width step sampleStep) {
        val color = source.getPixel(x, y)   // 1 native call per titik sampel
```

Pada foto ~3000px, ini bisa jadi ribuan native call terpisah **di setiap foto** yang diambil.
Diperbaiki dengan membaca satu baris penuh sekaligus lewat `getPixels()` (1 native call per
baris yang disampel), lalu mengambil sampel kolom dari array di sisi CPU — hasil sampel yang
diukur sama persis, hanya jauh lebih sedikit panggilan native.

---

## Temuan #3 (Didokumentasikan, belum diubah): "Kunci PDF" ≠ PDF berpassword standar

`PdfSecurity.lockPdf()` sengaja menghasilkan kontainer terenkripsi `.dokupdf` (AES-256-GCM +
PBKDF2, praktik kriptografi yang sudah benar), **bukan** PDF dengan proteksi password bawaan PDF
spec. Artinya file terkunci **tidak bisa dibuka** di Adobe Reader/pembaca PDF lain dengan
memasukkan password — harus dibuka kembali lewat DokuPDF untuk didekripsi dulu jadi PDF biasa.

Ini murni catatan produk (bukan bug kode) karena tampaknya memang disengaja (ada komentar
eksplisit di kode). Tapi kalau pengguna mengharapkan "Kunci PDF" berarti PDF standar yang minta
password saat dibuka di aplikasi lain, ini akan terasa seperti bug. Sarannya: perjelas di UI
("File akan disimpan sebagai kontainer terenkripsi .dokupdf, hanya bisa dibuka lewat aplikasi
ini") atau — kalau proteksi password PDF standar memang yang diinginkan — itu perlu implementasi
terpisah (mis. lewat pustaka PDF yang mendukung `/Encrypt` dictionary standar PDF, di luar
`android.graphics.pdf.PdfDocument` yang dipakai proyek ini karena API itu tidak mendukung
enkripsi PDF native).

---

## Temuan #4 (Rendah, tidak crash): Dua-pass dimensi halaman di Compress/Merge/Repair

`PdfCompressor`, `PdfMergerSplitter`, dan `PdfRepairEngine` masing-masing memanggil
`getPageDimensions(file)` (buka+parse PDF sekali) lalu `forEachRenderedPage(file) { index, bmp -> dimensions[index] ... }`
(buka+parse PDF kedua kalinya). Kalau jumlah halaman dari dua pass ini pernah tidak sama, akan
terjadi `IndexOutOfBoundsException` — **tapi ini sudah dibungkus `catch (error: Exception)`** di
setiap fungsi tadi, jadi terdegradasi jadi `Result.failure` yang rapi, bukan crash. Ditulis di
sini sebagai catatan kerapuhan desain (dua kali buka file yang sama, bukan satu), bukan sebagai
bug yang perlu buru-buru diperbaiki.

---

## Temuan #5 (Administratif): `docs/CHANGELOG.md` sudah usang

Changelog hanya mencatat rilis `[1.0.0]`, padahal kode saat ini sudah jauh lebih matang:
auto-crop tanpa OpenCV (`AutoCropDetector`), budget memori sesi scanner, penulisan file atomik
dengan rollback (`PdfFileUtils.writeAtomically`), enkripsi AES-GCM, dsb. Menyarankan entri baru
ditambahkan saat rilis berikutnya supaya riwayat perbaikan (termasuk audit ini) mudah dilacak di
audit berikutnya.

---

## Ringkasan Perubahan Kode

| File | Perubahan |
|------|-----------|
| `ui/screens/InteractiveCropScreen.kt` | Recycle bitmap lama setelah tiap rotasi; `DisposableEffect` untuk membersihkan bitmap saat layar ditutup tanpa konfirmasi |
| `ui/screens/ScannerScreen.kt` | Recycle `originalBitmap`/`croppedBitmap` lama sebuah halaman saat digantikan hasil Potong Ulang |
| `core/filter/FilterProcessor.kt` | `applyAutoEnhance` memakai `getPixels()` bulk-read alih-alih `getPixel()` per titik sampel |

Semua perbaikan mengikuti pola ownership-tracking (`!== `, `.isRecycled`) yang sudah dipakai
konsisten di seluruh codebase ini, jadi gaya kodenya tetap seragam dengan yang lain.
