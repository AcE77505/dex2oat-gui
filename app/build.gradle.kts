import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ace77505.dex2oat"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.ace77505.dex2oat"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val propFile = rootProject.file("signing.properties")
            if (propFile.exists()) {
                val properties = Properties().apply { load(propFile.inputStream()) }
                storeFile = rootProject.file(properties.getProperty("STORE_FILE"))
                storePassword = properties.getProperty("STORE_PASSWORD")
                keyAlias = properties.getProperty("KEY_ALIAS")
                keyPassword = properties.getProperty("KEY_PASSWORD")

                enableV3Signing = true
            }
        }
        getByName("debug") {
            val propFile = rootProject.file("signing.properties")
            if (propFile.exists()) {
                val properties = Properties().apply { load(propFile.inputStream()) }
                storeFile = rootProject.file(properties.getProperty("STORE_FILE"))
                storePassword = properties.getProperty("STORE_PASSWORD")
                keyAlias = properties.getProperty("KEY_ALIAS")
                keyPassword = properties.getProperty("KEY_PASSWORD")

                enableV3Signing = true
            }
        }

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

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.libsu.core)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.compose.ui.tooling)
}
