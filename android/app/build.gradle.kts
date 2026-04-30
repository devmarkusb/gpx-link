plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "io.github.gpxlink"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.gpxlink"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        // Chaquopy Python 3.12 ships native libs only for arm64-v8a and x86_64.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
