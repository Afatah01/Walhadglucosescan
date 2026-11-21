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
// Never embed an AI shared secret in an APK: packaged values are public.
// AI is disabled until the backend uses authenticated per-user access.
val DEFAULT_AI_URL = ""
val DEFAULT_AI_SECRET = ""

// The address and the PUBLIC ("anon") key of the online copy of the values, committed on purpose,
// exactly like the AI door above. The anon key is not a secret: it lets the app reach the project,
// but every table in it is protected by Row Level Security, so it can only ever read or write the
// rows of the signed-in user — never another person's. The service-role key does NOT appear here,
// nowhere in this app, and never will. secrets.properties may override both for somebody running
// their own copy of the project.
val DEFAULT_SUPABASE_URL = "https://bhogtezbvoijtixkzrwr.supabase.co"
val DEFAULT_SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJob2d0ZXpidm9panRpeGt6cndyIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTQ3ODQ5NzQsImV4cCI6MjExMDM2MDk3NH0.fZBckKl35oUSN-xIuNV0MvlEV0crSWwiQqdoPSTBg8k"

/** The short commit id, or "unknown" when the build does not run inside a git checkout. */
val GIT_SHA: String = try {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD").directory(rootDir).redirectErrorStream(true).start()
    val out = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0 && out.matches(Regex("[0-9a-f]{7,40}"))) out else "unknown"
} catch (e: Exception) {
    "unknown"
}

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
        versionCode = 27
        versionName = "5.11.0"

        // The AI door. Already filled in: the app asks nothing of the person using it.
        buildConfigField("String", "WALHAD_AI_URL", "\"${secretValue("walhad.ai.url", DEFAULT_AI_URL)}\"")
        buildConfigField("String", "WALHAD_AI_SECRET", "\"\"")
        // the public address and public key of the online copy (see above for why they are public)
        buildConfigField("String", "WALHAD_SUPABASE_URL", "\"${secretValue("walhad.supabase.url", DEFAULT_SUPABASE_URL)}\"")
        buildConfigField("String", "WALHAD_SUPABASE_ANON_KEY", "\"${secretValue("walhad.supabase.anon_key", DEFAULT_SUPABASE_ANON_KEY)}\"")
        // the source the build came from — shown on the Monitoring screen
        buildConfigField("String", "GIT_SHA", "\"$GIT_SHA\"")
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
    // AndroidX WorkManager: persistent background sync with network constraint, unique work,
    // retry and exponential backoff. This is the only non-platform library on purpose.
    implementation("androidx.work:work-runtime:2.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
