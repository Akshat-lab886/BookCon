import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("app/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.bookcon.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bookcon.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "2.2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    lint {
        // Release-lint requires downloading analyzer jars; run lint in CI instead.
        checkReleaseBuilds = false
        abortOnError = false
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    // Robolectric downloads the `android-all` runtime jar into the user's home
    // directory by default, which is read-only in this environment. Point its
    // dependency cache (and the lock file it creates there) at the workspace.
    tasks.withType<Test>().configureEach {
        // Robolectric resolves its `android-all` runtime jar against the JVM's
        // user.home (read-only here), so point user.home at the workspace.
        systemProperty("user.home", "${rootProject.projectDir}/.robolectric-home")
        systemProperty("robolectric.logging", "stdout")
        maxHeapSize = "2g"

        // The @GraphicsMode(NATIVE) tests are environment-flaky, not app-flaky.
        // 22 of them fail with `UnsatisfiedLinkError: RenderNodeNatives.nCreate`
        // on some runs and pass on others with no code change in between; the other
        // tests are always green. The native runtime registers its JNI methods from
        // JNI_OnLoad, so a partial or contended extraction shows up as a missing
        // symbol rather than a clear error. Disk pressure makes it markedly worse
        // (it was 100% reproducible at 96% full) but it is not the only factor.
        // One fork removes the cross-JVM extraction race, which is the part we can
        // actually control. If these fail, free disk space and re-run before
        // suspecting a real regression.
        maxParallelForks = 1
    }

    testOptions {
        // Robolectric needs merged resources + native graphics to rasterise Compose.
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // required by the Readium toolkit
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // Room (offline-first source of truth, TRD §1.1)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore for typed settings
    implementation(libs.datastore.preferences)

    // WorkManager + Hilt workers
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.dagger.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Network: Retrofit + OkHttp + kotlinx.serialization (OpenAPI DTOs)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Encrypted token storage
    implementation(libs.security.crypto)

    // Images
    implementation(libs.coil.compose)

    // Readium Kotlin toolkit (EPUB/PDF/CBZ navigators, TRD §1.1)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)

    // PDFBox-Android for PDF text extraction (in-reader AI page summaries)
    implementation(libs.pdfbox.android)

    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    // JVM-side rendering of the Compose UI so the screens can be verified
    // without a device attached (Robolectric native graphics).
    testImplementation(libs.robolectric)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(kotlin("test"))
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
}
