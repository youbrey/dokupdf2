# DokuPDF — Roadmap

## 🟡 Penting — pulihkan cakupan test untuk PdfDocument multi-halaman

**Latar belakang:** Dua skenario test di `OfficeFileParserTest.kt` ditandai
`@Ignore` per 2026-08-23:
- `lazy bitmap PDF conversion releases each generated page`
- `word and wide spreadsheet conversion paginate instead of truncating`

**Alasan:** `android.graphics.pdf.PdfDocument` tidak memiliki native shadow di
Robolectric (dipastikan lewat penelusuran source AOSP — lihat
`docs/AUDIT_REPORT.md` Babak 4 untuk detail & bukti lengkap). Ini limitasi
tooling test, BUKAN bug di `PdfConverterEngine.kt` — perilaku produksi di
Android sungguhan tidak terpengaruh.

**Yang perlu dikerjakan:**

1. Buat `app/src/androidTest/java/com/example/OfficeFileParserInstrumentedTest.kt`
   yang memindahkan 2 skenario di atas ke Android Instrumented Test — jalan di
   emulator/device asli lewat `androidx.test.ext.junit.runners.AndroidJUnit4`,
   bukan `RobolectricTestRunner`. Di lingkungan itu `PdfDocument` memakai
   implementasi native asli Android, bukan simulasi JVM.
2. Tambahkan job CI baru untuk menjalankan instrumented test (butuh emulator —
   mis. lewat `reactivecircus/android-emulator-runner` GitHub Action, atau
   Firebase Test Lab). Ini pekerjaan infrastruktur CI yang terpisah dari kerja
   `:app:testDebugUnitTest` yang sudah ada — pertimbangkan sebagai job paralel
   supaya tidak memperlambat build APK debug yang sudah berjalan cepat.
3. Setelah instrumented test berjalan hijau, hapus anotasi `@Ignore` di
   `OfficeFileParserTest.kt` HANYA jika ingin tetap mempertahankan versi
   Robolectric-nya sebagai dokumentasi assertion (opsional) — atau hapus
   sepenuhnya kedua fungsi test itu dari `OfficeFileParserTest.kt` kalau
   instrumented test dianggap sudah menggantikan perannya sepenuhnya.

**Prioritas:** 🟡 (bukan 🔴) — karena ini gap *coverage otomatis*, bukan bug
fungsional yang diketahui. Fitur "scan multi-halaman" dan "konversi Word/Excel
dengan paginasi" tetap perlu diverifikasi manual (mis. checklist sebelum
rilis) sampai instrumented test ini ada.
