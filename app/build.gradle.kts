import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Optional: only present on a machine that's set up to produce a signed release build (see
// keystore.properties.example). Its absence just means `assembleRelease` yields an unsigned,
// still-minified APK instead of failing the whole build.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "com.example.notepad"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.notepad"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Lets RecurrenceUtils use java.time (LocalDate/ChronoUnit) for a genuinely DST-safe
        // calendar-day diff, back to minSdk 24 -- java.time itself is only built into the
        // platform from API 26.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    sourceSets {
        // Room's exported schema JSON (one per version, written by KSP below) needs to be a
        // readable asset at test time for MigrationTestHelper to validate a migration's result
        // against the real target-version schema.
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }

    packaging {
        // google-auth-library-oauth2-http and google-auth-library-credentials (pulled in
        // transitively by the Drive API client) both ship the same META-INF/INDEX.LIST and
        // META-INF/DEPENDENCIES metadata files; neither is needed at runtime on Android.
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

ksp {
    // Room writes one <version>.json schema snapshot here per build -- see sourceSets above for
    // why androidTest needs to read it back, and TESTING.md for why this was off before now
    // (no historical snapshots exist for versions predating this session's Phase 3 work).
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2") // 1.4.0 requires compileSdk 35 (current AGP 8.5.2 caps at 34)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4") // 2.9+/2.11 need Kotlin 2.1 (project pins 1.9)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.biometric:biometric:1.1.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.room:room-paging:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Encrypts the local notes database at rest (see AppDatabase/DatabaseKeyProvider): the raw
    // sqlite file is unreadable without the per-install passphrase, which only this app can read
    // back out of Keystore-backed EncryptedSharedPreferences.
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite:2.4.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    // Tiny, dependency-free artifact (no Android framework or test-runner code) meant exactly
    // for being referenced from production code: lets NoteEditorActivity's own
    // unlockIdlingResource tell Espresso when the off-main-thread password check (PBKDF2) is in
    // flight, so instrumented tests can wait for it deterministically instead of polling/sleeping.
    implementation("androidx.test.espresso:espresso-idling-resource:3.6.1")

    // Pages the main notes list from Room instead of loading every row at once (see
    // NoteRepository.observeAllPaged / PagedNoteAdapter).
    implementation("androidx.paging:paging-runtime-ktx:3.3.2")

    // Google sign-in + Drive backup. Needs an OAuth client (Android type: package name + debug/
    // release SHA-1) created by hand in Google Cloud Console -- see SettingsActivity's Drive
    // backup row / the project's setup notes for what to configure there before this works.
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.google.api-client:google-api-client-android:2.7.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.apis:google-api-services-drive:v3-rev20240914-2.0.0")
    implementation("com.google.http-client:google-http-client-gson:1.45.0")

    testImplementation("junit:junit:4.13.2")
    // Pure JVM, no Android/SQLCipher conflict -- see TESTING.md's "Phase 2 tooling evaluation"
    // for why this is the one new test dependency this session added (Robolectric/MockK weren't).
    testImplementation("io.kotest:kotest-property-jvm:5.9.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.work:work-testing:2.9.1")
    androidTestImplementation("androidx.test.espresso:espresso-contrib:3.6.1") {
        exclude(group = "org.checkerframework", module = "checker")
    }
    // For NotificationTapOpensCalendarDayTest: drives the real notification shade, outside this
    // app's own window, which Espresso (confined to this app's own Activities) can't reach.
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
