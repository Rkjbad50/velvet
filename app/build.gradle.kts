import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.io.ByteArrayOutputStream
import java.time.Instant

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val catalogs = extensions.getByType<VersionCatalogsExtension>()
val libs = catalogs.named("libs")

val newpipeVersion = libs.findVersion("newpipe").get().requiredVersion
val media3Version = libs.findVersion("media3").get().requiredVersion
val okhttpVersion = libs.findVersion("okhttp").get().requiredVersion
val composeVersion = libs.findVersion("compose-bom").get().requiredVersion
val kotlinVersion = libs.findVersion("kotlin").get().requiredVersion
val compileSdkVer = libs.findVersion("compileSdk").get().requiredVersion.toInt()
val minSdkVer = libs.findVersion("minSdk").get().requiredVersion.toInt()
val targetSdkVer = libs.findVersion("targetSdk").get().requiredVersion.toInt()

val gitCommit = try {
    val stdout = ByteArrayOutputStream()
    exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        standardOutput = stdout
    }
    stdout.toString().trim().ifBlank { "f897992" }
} catch (_: Exception) {
    "f897992"
}
val buildTime = Instant.now().toString()

// CI passes the build number so every build installs as an update over the previous one
val buildNumber = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 2
val upstreamBuildNumber = System.getenv("VELVET_UPSTREAM_BUILD")?.toIntOrNull() ?: 51
val keystorePath: String? = System.getenv("VELVET_KEYSTORE")
val releaseUrl = System.getenv("VELVET_RELEASES_URL")
    ?: System.getenv("GITHUB_REPOSITORY")?.let { "https://github.com/$it/releases/latest" }
    ?: "https://github.com/Rkjbad50/velvet/releases/latest"

android {
    namespace = "com.velvet.app"
    compileSdk = compileSdkVer

    defaultConfig {
        applicationId = System.getenv("VELVET_APPLICATION_ID")?.takeIf { it.isNotBlank() } ?: "com.velvet.app"
        minSdk = minSdkVer
        targetSdk = targetSdkVer
        versionCode = buildNumber
        versionName = "2.7.4"
        buildConfigField("int", "UPSTREAM_BUILD_NUMBER", upstreamBuildNumber.toString())
        buildConfigField("String", "RELEASES_URL", "\"${releaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("String", "NEWPIPE_VERSION", "\"$newpipeVersion\"")
        buildConfigField("String", "MEDIA3_VERSION", "\"$media3Version\"")
        buildConfigField("String", "OKHTTP_VERSION", "\"$okhttpVersion\"")
        buildConfigField("String", "COMPOSE_VERSION", "\"$composeVersion\"")
        buildConfigField("String", "KOTLIN_VERSION", "\"$kotlinVersion\"")
        buildConfigField("int", "COMPILE_SDK_VERSION", compileSdkVer.toString())
        buildConfigField("int", "MIN_SDK_VERSION", minSdkVer.toString())
        buildConfigField("int", "TARGET_SDK_VERSION", targetSdkVer.toString())
    }

    val localKeystore = rootProject.file("keystore/velvet.jks")
    signingConfigs {
        create("release") {
            if (localKeystore.exists()) {
                storeFile = localKeystore
                storePassword = System.getenv("VELVET_KEYSTORE_PASSWORD") ?: "velvetapp"
                keyAlias = System.getenv("VELVET_KEY_ALIAS") ?: "velvet"
                keyPassword = System.getenv("VELVET_KEY_PASSWORD") ?: "velvetapp"
            } else if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("VELVET_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VELVET_KEY_ALIAS")
                keyPassword = System.getenv("VELVET_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 makes Compose noticeably smoother; proguard-rules.pro keeps the extractor's reflection working
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (localKeystore.exists() || keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor uses java.time / java.nio APIs missing on older Android
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/README.md",
                "META-INF/CHANGES",
                "META-INF/COPYRIGHT",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        exceptionFormat = TestExceptionFormat.FULL
        events("passed", "failed", "skipped")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.3")

    implementation(libs.findLibrary("newpipe-extractor").get())
    // the extractor's JSON library, same commit it uses; needed to read player responses directly
    implementation(libs.findLibrary("nanojson").get())
    implementation(libs.findLibrary("okhttp").get())

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation(platform(libs.findLibrary("compose-bom").get()))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation(libs.findLibrary("media3-exoplayer").get())
    implementation(libs.findLibrary("media3-session").get())
    implementation(libs.findLibrary("media3-datasource").get())
    implementation(libs.findLibrary("media3-database").get())
    implementation(libs.findLibrary("media3-datasource-okhttp").get())
    // installs the Compose libraries' bundled startup profiles on sideloaded installs
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.palette:palette-ktx:1.0.0")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
