import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The key that signs the app. See README.md, "Building the app".
val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

android {
    namespace = "com.glucosescan.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.glucosescan.app"
        minSdk = 23
        targetSdk = 34
        versionCode = 9
        versionName = "4.2.0"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file("keystore/" + keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // The code is small. It is not shrunk, so that it stays easy to check.
            isMinifyEnabled = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // no list of build libraries inside the app file
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        textReport = true
        htmlReport = true
        // The app targets Android 14 on purpose (see README.md).
        disable += "OldTargetApi"
        disable += "GradleDependency"
        disable += "AndroidGradlePluginVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "1g"
                // ScreensTest saves pictures of the screens here
                it.systemProperty("screenshots.dir", rootProject.file("docs/screenshots").absolutePath)
                // The screen tests download a copy of Android (about 150 MB each). Normally it goes
                // to ~/.m2. Set ROBOLECTRIC_REPO to keep it in another folder.
                System.getenv("ROBOLECTRIC_REPO")?.let { repo -> it.systemProperty("maven.repo.local", repo) }
                it.testLogging {
                    events("failed", "skipped")
                    showStandardStreams = false
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
