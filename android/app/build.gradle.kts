import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.acme.scantotally"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.acme.scantotally"
        // API 26: covers every rugged Android device in current warehouse use
        // (Zebra TC-series, Honeywell CT-series, Urovo) without dragging in
        // compatibility shims for devices nobody has.
        minSdk = 26
        targetSdk = 36
        // The release, read from the VERSION file at the repository root so
        // the app, the APK's filename and the download all say the same thing.
        val release = File(rootDir, "../VERSION").readText().trim()

        // Bumped every build from the clock, independently of the release.
        //
        // Android only installs over an APK whose code is higher, and two
        // rounds were lost to "is the new build actually on the phone?" --
        // unanswerable when every APK claims the same version. The release
        // answers "which version", this answers "which build of it".
        versionCode = ((System.currentTimeMillis() / 1000) % 100000000).toInt()
        versionName = release
        val stamp = SimpleDateFormat("d MMM HH:mm").apply {
            timeZone = TimeZone.getTimeZone("Asia/Riyadh")
        }.format(Date())
        buildConfigField("String", "BUILD_STAMP", "\"" + stamp + "\"")
        buildConfigField("String", "RELEASE", "\"" + release + "\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug { applicationIdSuffix = ".debug" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }

    testOptions { unitTests.isIncludeAndroidResources = true }
    // The shared barcode vectors live outside the module; the unit test reads
    // them from here so all three implementations stay in step.
    sourceSets["test"].resources.srcDir("../../contracts")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Offline-first: the outbox lives in Room, drained by WorkManager.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-okhttp:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Camera scanning: the fallback path, and what makes the app testable on an
    // ordinary phone with no rugged hardware to hand.
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
