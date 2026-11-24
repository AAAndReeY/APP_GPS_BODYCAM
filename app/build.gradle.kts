plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Lee el archivo .env de la raíz del proyecto (ignorado por git). Ver .env.example
val envVars: Map<String, String> = rootProject.file(".env").takeIf { it.exists() }
    ?.readLines()
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
    ?.associate { line ->
        line.substringBefore("=").trim() to line.substringAfter("=").trim().trim('"', '\'')
    }
    ?: emptyMap()

fun requireEnv(name: String): String =
    envVars[name]?.takeIf { it.isNotEmpty() }
        ?: throw GradleException("Falta '$name' en el archivo .env de la raíz del proyecto (copia .env.example a .env y complétalo)")

android {
    namespace = "com.bodycam.tracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bodycam.tracker"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "SERVER_URL", "\"${requireEnv("SERVER_URL").trimEnd('/')}\"")
        buildConfigField("String", "API_TOKEN", "\"${requireEnv("API_TOKEN")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.gms:play-services-location:21.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("com.google.android.material:material:1.10.0")
}