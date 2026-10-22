plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.speedcompiler.tv"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.speedcompiler.tv"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Release key lives outside the repo (~/.gradle/gradle.properties); without it, release builds stay unsigned.
    val releaseStore = providers.gradleProperty("releaseStoreFile").orNull
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = file(releaseStore)
            storePassword = providers.gradleProperty("releaseStorePassword").get()
            keyAlias = providers.gradleProperty("releaseKeyAlias").get()
            keyPassword = storePassword
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.findByName("release") }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Roborazzi plugin is not in the catalog; this property alone makes captureRoboImage write files.
        // Opt-in: ./gradlew test -Proborazzi.record. Without it captureRoboImage does nothing.
        unitTests.all {
            if (providers.gradleProperty("roborazzi.record").isPresent) it.systemProperty("roborazzi.test.record", "true")
            // Absolute, since tests run in app/ and a relative ../ path broke before.
            it.systemProperty("pixelDir", rootProject.file("tools/pixel").absolutePath)
            it.inputs.dir(rootProject.file("tools/pixel"))
        }
    }
    // NanoHTTPD and Conscrypt ship duplicate license files
    packaging { resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/AL2.0", "META-INF/LGPL2.1") }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)
    implementation(libs.material.kolor)
    implementation(libs.libadb)
    implementation(libs.conscrypt)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.work.testing)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    // ui-test-junit4 brings androidx.test runner and ext-junit, so no new catalog entries are needed
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.coroutines.test)
}
