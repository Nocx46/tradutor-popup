plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tradutorpopup.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tradutorpopup.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Celulares atuais (ex.: Galaxy A36) usam arm64. Deixa o app bem menor.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Tradução no próprio celular (funciona offline depois do download do idioma)
    implementation("com.google.mlkit:translate:17.0.3")
    // Detecção automática do idioma do texto
    implementation("com.google.mlkit:language-id:17.0.6")

    // Leitura de texto em imagens (OCR) — usado quando a tela não expõe texto.
    // Versão via Google Play Services: o modelo é baixado pelo Play, app fica pequeno.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1")

    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
