plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "app.momobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.momobile"
        minSdk = 26
        // "Everything on this phone" mode bundles Python; 64-bit phones only.
        ndk { abiFilters += listOf("arm64-v8a") }
        targetSdk = 34
        versionCode = 5
        versionName = "1.0"

        // Where Mo is reachable from the phone. Empty by default: the app asks
        // on first launch (long-press the icon -> Change server to edit it).
        // Bake one in with: ./gradlew assembleDebug -PmoServerUrl=http://host:8420
        // The app refuses to load any other host, since the JS bridge hands
        // real Bluetooth access to whatever page is loaded.
        buildConfigField(
            "String",
            "DEFAULT_SERVER_URL",
            "\"${providers.gradleProperty("moServerUrl").getOrElse("")}\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    // Release signing comes from the environment so no key or password ever
    // lives in the repo:
    //   MO_KEYSTORE=/path/to/release.jks MO_KEYSTORE_PASSWORD=... ./gradlew assembleRelease
    val keystorePath = System.getenv("MO_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("MO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MO_KEY_ALIAS") ?: "momobile"
                keyPassword = System.getenv("MO_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
}

// "Everything on this phone" mode: the Mo server (app/ and scripts/ from the
// repo root) runs inside the app on Chaquopy's Python.
val stageMo by tasks.registering(Sync::class) {
    from("../../app") {
        into("app")
        exclude("data/**", "**/__pycache__/**")
    }
    from("../../scripts") {
        into("scripts")
        exclude("**/__pycache__/**", "deploy/**")
    }
    // __init__.py files so Chaquopy treats scripts/ as packages it can extract.
    from("src/mo-stub")
    from("../../cards_json") {
        into("mo_seed/cards_json")
        include("MSH.json")
    }
    into(layout.buildDirectory.dir("mo-python"))
}
tasks.named("preBuild") { dependsOn(stageMo) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("PythonSources") }
    .configureEach { dependsOn(stageMo) }

chaquopy {
    defaultConfig {
        version = "3.12"
        // Needs Python 3.12 on the build machine. Point at it with
        // -PmoBuildPython=/path/to/python3.12 if it isn't on PATH.
        buildPython(providers.gradleProperty("moBuildPython").getOrElse("python3.12"))
        // build_data.py runs its steps with runpy, which needs real files on disk.
        extractPackages("scripts", "scripts.build")
        pip {
            // Same as requirements.txt, at the versions Chaquopy builds for Android.
            install("flask==3.1.3")
            install("werkzeug==3.1.6")
            install("pillow==11.0.0")
            install("requests==2.32.5")
            install("ijson==3.1.1")
            install("segno==1.6.6")
            install("pyserial==3.5")
            install("beautifulsoup4==4.13.4")
        }
    }
    sourceSets {
        getByName("main") {
            srcDir("src/main/python")
            srcDir(layout.buildDirectory.dir("mo-python"))
        }
    }
}
