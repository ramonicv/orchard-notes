plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Published releases (docs/releasing.md): the release workflow passes the version from the tag
// and the project's release key as Gradle properties. Local builds keep the defaults below.
val releaseVersion: String? = providers.gradleProperty("orchardVersion").orNull
val releaseKeystore: String? = providers.gradleProperty("orchardKeystoreFile").orNull

/** 1.2.3 -> 1002003: each release gets a higher code than the last, which Android requires to update. */
fun versionCodeOf(version: String): Int {
    val parts = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})""").matchEntire(version)?.groupValues?.drop(1)?.map(String::toInt)
        ?: throw GradleException("orchardVersion must look like 1.2.3, not '$version'")
    val (major, minor, patch) = parts
    return major * 1_000_000 + minor * 1_000 + patch
}

fun signingProperty(name: String): String = providers.gradleProperty(name).orNull
    ?: throw GradleException("$name is required when orchardKeystoreFile is set")

android {
    namespace = "dev.rortega.orchardnotes"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.rortega.orchardnotes"
        minSdk = 26
        targetSdk = 37
        versionCode = releaseVersion?.let(::versionCodeOf) ?: 1
        versionName = releaseVersion ?: "0.1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = signingProperty("orchardKeystorePassword")
                keyAlias = signingProperty("orchardKeyAlias")
                keyPassword = signingProperty("orchardKeyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the release key (any build but a published release), sign with the debug key so
            // the APK still installs for personal sideloading.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}

tasks.withType<Test>().configureEach {
    // Robolectric reaches into JDK internals for file descriptors and shared memory.
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    maxHeapSize = "2g"
    // Screenshot rendering is opt-in: ./gradlew testDebugUnitTest -Pscreenshots --tests '*AppScreenshots*'
    systemProperty("orchard.screenshots", providers.gradleProperty("screenshots").isPresent.toString())
}
