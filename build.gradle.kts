// Top-level build file where you can add configuration options common to all sub-projects/modules.

// AGP 9's built-in Kotlin secara default memakai compiler Kotlin versi internal
// yang dibundel di dalam AGP sendiri. Di kombinasi AGP 9.1.1 + Kotlin 2.2.10 proyek ini,
// compiler internal itu ternyata TIDAK cocok secara biner dengan compose-compiler-plugin
// yang di-fetch terpisah oleh plugin kotlin.plugin.compose (versi sama "2.2.10" tapi build
// artifact beda), menyebabkan error saat compileDebugKotlin:
//   "Plugin androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar is incompatible
//    with the current version of the compiler" (AbstractMethodError: getPluginId()).
//
// Percobaan fix sebelumnya (memaksa classpath kotlin-gradle-plugin:2.2.10 saja) TERNYATA
// TIDAK CUKUP dan build tetap gagal dengan error yang sama. Penyebabnya: dokumentasi AGP 9
// (https://developer.android.com/build/releases/agp-9-0-0-release-notes, bagian "Runtime
// dependency on Kotlin Gradle plugin") menyebut 2.2.10 itu adalah versi MINIMUM yang
// dipakai built-in Kotlin ("KGP 2.2.10 atau lebih tinggi"), bukan versi yang dikunci mati.
// Karena resolusi dependency Gradle default memakai versi TERTINGGI yang diminta di seluruh
// graph, permintaan classpath "2.2.10" gampang kalah/diabaikan begitu ada plugin lain
// (mis. KSP) yang secara transitif meminta versi Kotlin compiler yang berbeda — sehingga
// compiler yang benar-benar dipakai AGP untuk compileDebugKotlin bisa berakhir TIDAK SAMA
// dengan versi yang dipakai plugin kotlin.plugin.compose untuk mengambil compose-compiler-
// plugin-embeddable-nya (yang mengikuti `kotlin` di libs.versions.toml).
//
// Fix: paksa classpath KGP *dan* KSP secara eksplisit ke satu pasangan versi yang sama-sama
// terbaru dan memang divalidasi bareng AGP 9's built-in Kotlin (per rekomendasi resmi
// developer.android.com per Agustus 2026), supaya tidak ada satu pun bagian dari graph yang
// diam-diam beda versi compiler dari yang lain.
// PENTING: versi KGP di bawah ini HARUS selalu sama persis dengan `kotlin` di
// libs.versions.toml, dan versi KSP HARUS selalu sama persis dengan `googleDevtoolsKsp`.
buildscript {
  repositories {
    google()
    mavenCentral()
  }
  dependencies {
    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.9")
  }
}

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.roborazzi) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
}
