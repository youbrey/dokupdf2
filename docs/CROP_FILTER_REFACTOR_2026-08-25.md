# Refactor Auto-Crop, Dewarp, dan Filter Dokumen

Tanggal audit: 25 Agustus 2026

## Bukti Perbandingan

Analisis menggunakan pasangan video dan PDF dari dokumen sumber yang sama: `PROSES CAMSCANNER.mp4`, `PROSES DOKUPDF.mp4`, `HASIL CAM SCANNER.pdf`, dan `HASIL DOKUPDF.pdf`.

| Indikator | CamScanner | DokuPDF sebelum refactor | Dampak |
|---|---:|---:|---|
| Raster tertanam di PDF | 2236 x 3008 px | 1125 x 1452 px | DokuPDF membuang lebih dari separuh resolusi linear |
| Resolusi efektif PDF | 271 ppi | 124 ppi | Teks kecil DokuPDF cepat terlihat lembek saat diperbesar |
| Ukuran halaman | A4, 595 x 842 pt | 652 x 841 pt | Rasio halaman DokuPDF mengikuti crop foto yang belum rapi |
| Variansi Laplacian pada skala uji sama | 4292,17 | 1953,35 | Energi detail/tepi DokuPDF sekitar 45% hasil CamScanner |
| Tenengrad pada skala uji sama | 70307,23 | 38973,45 | Tepi karakter dan garis DokuPDF jauh lebih lemah |
| Fraksi piksel tinta gelap | 4,21% | 2,41% | Teks dan garis DokuPDF terlihat abu-abu/tipis |
| Median simpangan garis dari garis lurus | 1,12 px | 4,27 px | Garis DokuPDF masih tampak bergelombang |
| Median rentang lengkung garis | 3,69 px | 18,76 px | Homografi empat sudut tidak mengatasi kelengkungan kertas |

Metrik ketajaman dan kelurusan dihitung setelah kedua halaman dinormalisasi ke sisi panjang 1800 px. Angka tersebut merupakan diagnosis baseline lampiran, bukan klaim kualitas hasil build baru sebelum pengujian perangkat dilakukan.

## Akar Masalah

1. Empat sisi crop dipilih secara independen. Garis tabel internal yang panjang dan kontras dapat mengalahkan tepi kertas, sehingga empat pemenang tidak selalu membentuk batas halaman yang sama.
2. `Matrix.setPolyToPoly` hanya melakukan homografi. Homografi dapat memperbaiki perspektif bidang datar, tetapi tidak dapat meratakan kertas yang secara fisik melengkung atau bergelombang.
3. Filter `AUTO` menggunakan statistik global. Formulir berbayang sering diarahkan ke `NO_SHADOW`, yang sebelumnya hanya menerangkan gambar tanpa normalisasi putih dan penguatan tinta yang memadai.
4. Jalur ekspor lama menurunkan sisi panjang dan melakukan JPEG encode-decode sebelum `PdfDocument`, sehingga detail yang sudah melemah akibat crop kembali dilunakkan.

## Perubahan Implementasi

### 1. Deteksi batas sebagai satu quadrilateral

`AutoCropDetector` sekarang:

- menyimpan beberapa kandidat garis yang berbeda secara spasial untuk setiap sisi;
- mengevaluasi kombinasi kiri-kanan-atas-bawah sebagai satu quadrilateral;
- menilai luas halaman, dukungan gradien, cakupan garis, dan keseimbangan sisi berlawanan;
- memberi bobot lebih tinggi pada transisi kertas terang ke latar gelap;
- menurunkan skor garis tabel yang memiliki kertas dengan kecerahan serupa di kedua sisinya;
- tetap memakai fallback dan crop manual jika dukungan tepi tidak cukup.

### 2. Mesh dewarp setelah koreksi perspektif

`DocumentDewarpProcessor` menambahkan tahap non-planar setelah homografi:

1. Analisis diperkecil maksimal 720 px agar ringan.
2. Gradien vertikal digunakan untuk menemukan garis tabel, garis buku, dan baseline teks yang panjang.
3. Posisi setiap struktur dilacak pada 29 node horizontal.
4. Track yang lemah, kasar, saling memotong, terlalu pendek, atau meminta pergeseran ekstrem ditolak.
5. Track valid menjadi anchor mesh piecewise-linear.
6. Bitmap resolusi penuh di-resample secara vertikal agar setiap anchor menjadi horizontal.

Tahap ini confidence-gated. Foto, kartu identitas, atau halaman tanpa cukup struktur horizontal dikembalikan tanpa resampling agar tidak mengalami distorsi buatan.

### 3. Filter dokumen adaptif

Filter diperbarui dengan:

- profil scan yang mengukur luminans, chroma, proporsi kertas netral, dan tinta;
- routing `AUTO` ke pipeline dokumen penuh ketika bukti kertas+tinta terdeteksi;
- estimasi pencahayaan latar memakai persentil luminans lokal, bukan empat piksel paling terang;
- normalisasi bayangan dan warna kertas secara lokal;
- sharpening dua skala untuk tepi huruf halus dan stroke yang lebih lebar;
- sharpening ringan pada `NO_SHADOW` agar tidak lagi sekadar terlihat lebih terang;
- perlindungan piksel putih untuk menghindari noise dan halo pada latar kertas.

### 4. Resolusi dan ekspor

- Mode cepat: sisi panjang maksimum 2200 px.
- Mode HD dan impor galeri: sisi panjang maksimum 3008 px.
- Batas raster ekspor: 3008 px atau sekitar 257 DPI pada A4.
- JPEG encode-decode perantara dihapus; `PdfDocument` menerima bitmap hasil scan secara langsung.
- Crop/dewarp full-resolution tidak lagi dihitung lalu langsung dibuang ketika pengguna menekan `Berikutnya`; geometri disimpan dan render dilakukan secara lazy pada resolusi preview atau ekspor yang benar.

## Pengujian Regresi

Pengujian mencakup:

- halaman perspektif terang pada latar gelap;
- halaman dengan garis tabel internal yang lebih kuat daripada sebagian tepi kertas;
- fallback deterministik pada gambar datar;
- penolakan geometri self-intersecting;
- perataan garis sintetis yang melengkung;
- tidak melakukan dewarp pada gambar foto tanpa struktur dokumen;
- normalisasi bayangan kertas dan penguatan tinta oleh filter otomatis;
- kepemilikan bitmap agar Compose tidak menerima bitmap yang sudah di-recycle.

## Batas yang Disengaja

- Algoritme tidak mengklaim menyalin implementasi proprietary CamScanner. Targetnya adalah menyamai kelas hasil secara terukur dengan pipeline lokal milik DokuPDF.
- Lipatan ekstrem, halaman tertekuk tajam, bagian tertutup tangan, atau tepi di luar frame masih dapat memerlukan crop manual.
- Dewarp saat ini memprioritaskan kelengkungan baris horizontal, sesuai kegagalan dominan pada bukti uji. Distorsi buku dua halaman yang sangat kompleks memerlukan model 3D/ML khusus pada tahap lanjutan.
