plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.dualaudioplayer"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.dualaudioplayer"
        minSdk = 31
        targetSdk = 34
        versionCode = 14
        versionName = "14.0 Attenuation"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    buildFeatures { viewBinding = true }
    sourceSets { getByName("main") { kotlin.srcDirs("src/main/kotlin") } }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media:media:1.7.0")
    implementation("io.coil-kt:coil:2.6.0")
}
