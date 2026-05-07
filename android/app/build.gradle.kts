import java.util.Properties

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

android {
    namespace = "org.cismypa.gpxlink"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.cismypa.gpxlink"
        minSdk = 24
        targetSdk = 35
        versionCode = playVersionCode
        versionName = playVersionName
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
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Native .so (Chaquopy): lets Play Console symbolicate JNI/native crashes.
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

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
