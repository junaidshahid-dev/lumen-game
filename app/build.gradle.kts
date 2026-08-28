plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is driven entirely by environment variables so that no secret
// ever lives in the repo. When they are absent (local/debug builds, or a CI run
// on a fork) the release build simply stays unsigned instead of failing.
val keystoreFile: String? = System.getenv("LUMEN_KEYSTORE")
val keystorePassword: String? = System.getenv("LUMEN_KEYSTORE_PASSWORD")
val keystoreAlias: String? = System.getenv("LUMEN_KEY_ALIAS")
val keystoreAliasPassword: String? = System.getenv("LUMEN_KEY_PASSWORD")
val hasSigningConfig = !keystoreFile.isNullOrBlank() && !keystorePassword.isNullOrBlank() &&
    !keystoreAlias.isNullOrBlank() && !keystoreAliasPassword.isNullOrBlank()

android {
    namespace = "com.junaidshahid.lumen"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.junaidshahid.lumen"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(keystoreFile!!)
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystoreAliasPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningConfig) {
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
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    testImplementation("junit:junit:4.13.2")
}
