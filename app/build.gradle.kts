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

// Where the AI door is. See supabase/README.md.
//
// These two values are baked into the app, so that nobody has to type a key or
// choose a provider. They live in secrets.properties, which is NOT in the
// repository — see secrets.properties.example for the shape of the file.
//
// Without the file the app still builds and works: it simply has no address to
// call, so every answer comes from the rules engine on the phone instead.
val secretsPropertiesFile = rootProject.file("secrets.properties")
val secretsProperties = Properties()
if (secretsPropertiesFile.exists()) {
    secretsPropertiesFile.inputStream().use { secretsProperties.load(it) }
}
// Where the AI door is, baked in so that a fresh clone works with nothing to fill in.
//
// Neither of these is a secret. Both end up inside the APK, and anyone who decompiles it can
// read them. The one value that would cost money if it leaked — the Groq key — lives
// only on the server and is never compiled into the app. That is the whole reason for the door.
//
// secrets.properties overrides these two, for somebody building their own copy.
val DEFAULT_AI_URL = "https://bhogtezbvoijtixkzrwr.supabase.co/functions/v1/walhad-ai-core"
val DEFAULT_AI_SECRET = "a274936ddec05a084677d6f1d6fe501328044569820fc4e88716887741a65d65"

/** The value for [name] if secrets.properties sets one, otherwise [fallback]. */
fun secretValue(name: String, fallback: String): String {
    // A clean value: no quotes or backslashes that could break the generated code.
    val own = (secretsProperties.getProperty(name) ?: "").trim().replace("\"", "").replace("\\", "")
    return own.ifEmpty { fallback }
}

android {
    namespace = "com.glucosescan.app"
    compileSdk = 35

    // BuildConfig.WALHAD_AI_URL and BuildConfig.WALHAD_AI_SECRET above.
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.glucosescan.app"
        minSdk = 23
        targetSdk = 34
        versionCode = 13
        versionName = "5.1.0"

        // The AI door. Already filled in: the app asks nothing of the person using it.
        buildConfigField("String", "WALHAD_AI_URL", "\"${secretValue("walhad.ai.url", DEFAULT_AI_URL)}\"")
        buildConfigField("String", "WALHAD_AI_SECRET", "\"${secretValue("walhad.ai.secret", DEFAULT_AI_SECRET)}\"")
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
