import java.util.Properties
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

abstract class FontPreviewAssetTask : DefaultTask() {
    @get:InputFile
    abstract val corpus: RegularFileProperty

    @get:OutputDirectory
    abstract val assetDirectory: DirectoryProperty

    @TaskAction
    fun copyCorpus() {
        val bytes = corpus.get().asFile.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(digest == "e45b90d6244268251d69d9b95d0ae5f36df4b0174b8b6dce280236be2db30bd2") {
            "Font preview corpus differs from the canonical r1 snapshot"
        }
        val target = assetDirectory.file("font-preview/coolapk-53620096-r1.txt").get().asFile
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
    }
}

val moduleProperties = Properties().apply {
    val moduleProp = rootProject.projectDir.parentFile.resolve("module.prop")
    moduleProp.inputStream().use { load(it) }
}
val moduleVersion = moduleProperties.getProperty("version")
    ?: error("module.prop is missing version")
val moduleVersionCode = moduleProperties.getProperty("versionCode")?.toIntOrNull()
    ?: error("module.prop has an invalid versionCode")
val appVersionName = moduleVersion
    .removePrefix("v")
    .lowercase()
    .replace(Regex("[^0-9a-z.]+"), "-")
    .trim('-')
val releaseStoreFile = (providers.environmentVariable("ZIYU_KEYSTORE_FILE").orNull
    ?: providers.environmentVariable("LUOSHU_KEYSTORE_FILE").orNull)
val releaseStorePassword = (providers.environmentVariable("ZIYU_KEYSTORE_PASSWORD").orNull
    ?: providers.environmentVariable("LUOSHU_KEYSTORE_PASSWORD").orNull)
val releaseKeyAlias = (providers.environmentVariable("ZIYU_KEY_ALIAS").orNull
    ?: providers.environmentVariable("LUOSHU_KEY_ALIAS").orNull)
val releaseKeyPassword = (providers.environmentVariable("ZIYU_KEY_PASSWORD").orNull
    ?: providers.environmentVariable("LUOSHU_KEY_PASSWORD").orNull)
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }
val xposedApi = rootProject.file("xposed-api/api-82.jar")
val xposedApiHash = MessageDigest.getInstance("SHA-256").digest(xposedApi.readBytes()).joinToString("") { "%02x".format(it) }
check(xposedApiHash == "f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25") {
    "Unexpected Xposed API jar digest"
}

// Package the user's canonical corpus verbatim instead of maintaining a second text copy.
val fontPreviewCorpus = rootProject.projectDir.parentFile
    .resolve("docs/font-test-baseline/coolapk-53620096-r1.txt")
val syncFontPreviewCorpus = tasks.register<FontPreviewAssetTask>("syncFontPreviewCorpus") {
    corpus.set(fontPreviewCorpus)
    assetDirectory.set(layout.buildDirectory.dir("generated/fontPreviewAssets"))
}

android {
    namespace = "io.github.xgl34222220.ziyu"
    // Miuix 0.9.3 publishes against API 37. This is compile-time only: targetSdk and the
    // install/runtime floor stay unchanged, while shader calls remain capability-gated.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.shishui611_art.ziyu"
        minSdk = 28
        targetSdk = 36
        // module.prop is the only version source shared by the module, native App and CI artifacts.
        versionCode = moduleVersionCode
        versionName = appVersionName
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }


    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // All distributions keep one identity. Covering an installed App
            // still requires its existing certificate; local Debug signing
            // cannot replace the GitHub release certificate.
            versionNameSuffix = "-debug"
            // Keep release-like optimization and a visible Debug version label.
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*"
        )
    }
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(syncFontPreviewCorpus) { it.assetDirectory }
}

dependencies {
    // The framework supplies this API to selected processes; never bundle it in the APK.
    compileOnly(files(xposedApi))
    val composeBom = platform("androidx.compose:compose-bom:2026.03.00")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.compose.foundation:foundation")
    // Match SukiSU's public Expressive API; alpha17 keeps this interface internal.
    implementation("androidx.compose.material3:material3:1.5.0-alpha28")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // COUI 1.1.0 uses MaterialKolor 5 APIs; keep one aligned implementation at runtime.
    implementation("com.materialkolor:material-kolor:5.0.0")
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    implementation("dev.chrisbanes.haze:haze-materials:1.6.10")
    // COUI provides the ColorOS theme and navigation components for the COUI appearance.
    implementation("io.github.suqi8.coui.kmp:coui-ui-android:1.1.0")
    // Use Miuix's own controls for the MIUIx appearance, aligned with blur/squircle.
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    // Keep Miuix's RuntimeShader backdrop with Haze for API 28-32; it is capability-gated.
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-squircle-android:0.9.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20251224")
}
