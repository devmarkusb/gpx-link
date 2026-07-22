import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

/** Reads `project.version` from repo-root pyproject.toml and maps it to a monotonic versionCode. */
fun readVersionFromPyproject(pyproject: java.io.File): Pair<Int, String> {
    require(pyproject.exists()) { "Missing ${pyproject.absolutePath}" }
    val text = pyproject.readText()
    val m =
        Regex("""(?m)^version\s*=\s*"([^"]+)"""")
            .find(text)
            ?: error("No version = \"…\" line in pyproject.toml")
    val raw = m.groupValues[1]
    val semverCore = raw.split("+", limit = 2).first().split("-", limit = 2).first().trim()
    val parts = semverCore.split(".").map { it.toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    val code = major * 1_000_000 + minor * 1_000 + patch
    require(code in 1..2_147_483_647) { "versionCode $code out of range for Google Play" }
    return Pair(code, raw)
}

val repoRoot = rootProject.projectDir.parentFile
val pyprojectToml = repoRoot.resolve("pyproject.toml")
val (playVersionCode, playVersionName) = readVersionFromPyproject(pyprojectToml)
val keystorePropertiesFile = rootProject.file("keystore.properties")

/** Google test app id (AdMob) — safe for debug and CI; set real id for production release (see gradle.properties). */
val admobTestAppId = "ca-app-pub-3940256099942544~3347511713"
fun envOrProperty(envName: String, propName: String): String? {
    val fromEnv = System.getenv(envName)?.trim()?.takeIf { it.isNotEmpty() }
    if (fromEnv != null) return fromEnv
    return (project.findProperty(propName) as String?)?.trim()?.takeIf { it.isNotEmpty() }
}

val admobAppIdRelease = envOrProperty("ADMOB_APPLICATION_ID", "gpxlink.admobAppId") ?: admobTestAppId
val admobBannerUnitRelease =
    envOrProperty("ADMOB_BANNER_UNIT_ID", "gpxlink.admobBannerUnitId")
        ?: "ca-app-pub-3940256099942544/6300978111"
val admobInterstitialUnitRelease =
    envOrProperty("ADMOB_INTERSTITIAL_UNIT_ID", "gpxlink.admobInterstitialUnitId")
        ?: "ca-app-pub-3940256099942544/1033173712"
val removeAdsProductId =
    envOrProperty("PLAY_REMOVE_ADS_PRODUCT_ID", "gpxlink.removeAdsProductId") ?: "remove_ads"

android {
    namespace = "org.cismypa.gpxlink"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "org.cismypa.gpxlink"
        minSdk = 24
        targetSdk = 36
        versionCode = playVersionCode
        versionName = playVersionName
        manifestPlaceholders["admobAppId"] = admobTestAppId
        buildConfigField("String", "REMOVE_ADS_PRODUCT_ID", "\"$removeAdsProductId\"")
        buildConfigField("String", "DEBUG_ADMOB_TEST_DEVICE_IDS", "\"\"")
        resValue("string", "admob_banner_unit_id", "ca-app-pub-3940256099942544/6300978111")
        resValue("string", "admob_interstitial_unit_id", "ca-app-pub-3940256099942544/1033173712")
        // Chaquopy Python 3.12 ships native libs only for arm64-v8a and x86_64.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            val props = Properties()
            keystorePropertiesFile.inputStream().use { props.load(it) }
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile")!!)
                storePassword = props.getProperty("storePassword")!!
                keyAlias = props.getProperty("keyAlias")!!
                keyPassword = props.getProperty("keyPassword")!!
            }
        }
    }

    buildTypes {
        debug {
            val raw =
                System.getenv("DEBUG_ADMOB_TEST_DEVICE_IDS")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: ((project.findProperty("gpxlink.debugAdmobTestDeviceIds") as String?)?.trim()
                        ?: "")
            val escaped = raw.replace("\\", "\\\\").replace("\"", "\\\"")
            buildConfigField("String", "DEBUG_ADMOB_TEST_DEVICE_IDS", "\"$escaped\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            manifestPlaceholders["admobAppId"] = admobAppIdRelease
            resValue("string", "admob_banner_unit_id", admobBannerUnitRelease)
            resValue("string", "admob_interstitial_unit_id", admobInterstitialUnitRelease)
            // debugSymbolLevel only affects .so built by this module (CMake/ndk-build).
            // Chaquopy ships prebuilt, stripped native libs — AGP emits no native-debug
            // metadata (mergeReleaseNativeDebugMetadata NO-SOURCE), so Play may still
            // warn until Chaquopy (or you) provides separate symbol archives.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            install("gpxpy>=1.6.2")
        }
    }
}

tasks.register<Copy>("syncGpxLinkPython") {
    val repoRoot = rootProject.projectDir.resolve("..").normalize()
    from(repoRoot.resolve("src/gpx_link"))
    into(layout.projectDirectory.dir("src/main/python/gpx_link"))
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.named("preBuild").configure { dependsOn("syncGpxLinkPython") }

afterEvaluate {
    listOf("mergeDebugPythonSources", "mergeReleasePythonSources").forEach { taskName ->
        tasks.named(taskName).configure { dependsOn("syncGpxLinkPython") }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation("com.android.billingclient:billing-ktx:9.1.0")
    implementation("com.google.android.ump:user-messaging-platform:3.1.0")
}
