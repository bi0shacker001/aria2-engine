plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

// build/build.sh's output for each ABI, <prefix-root>/<abi>/prefix (CI passes -Paria2Prefix=out).
val prefixRoot = (findProperty("aria2Prefix") as String?)?.let { rootProject.file(it).absolutePath }
    ?: rootProject.file("out").absolutePath

android {
    namespace = "io.github.bi0shacker001.aria2engine"
    compileSdk = 35
    // The NDK on the machine: GitHub's runners set ANDROID_NDK_LATEST_HOME, a local build ANDROID_NDK_HOME.
    (System.getenv("ANDROID_NDK_HOME") ?: System.getenv("ANDROID_NDK_LATEST_HOME"))?.let { ndkPath = it }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DARIA2_PREFIX_ROOT=$prefixRoot", "-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is stubs that throw; the real one for JVM tests.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "io.github.bi0shacker001"
            artifactId = "aria2-engine"
            version = project.findProperty("version") as String
            afterEvaluate { from(components["release"]) }
        }
    }
}
