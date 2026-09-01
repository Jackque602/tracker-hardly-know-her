plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Release signing, supplied through the environment so the private key never enters this
 * repository.
 *
 * Android identifies an installed app by its signature. If two builds are signed with different
 * keys, the second one cannot be installed over the first - the only way out is to uninstall,
 * which deletes the database and every cell you have uncovered. So the key has to be one stable
 * key that outlives any individual build machine.
 *
 * Read through the provider API rather than System.getenv so Gradle's configuration cache knows
 * to invalidate itself when these change.
 */
// An unset variable and one set to the empty string both mean "no key": CI passes empty strings
// when the secrets are absent, and Gradle counts those as present.
fun signingInput(name: String) =
    providers.environmentVariable(name).map(String::trim).filter(String::isNotEmpty)

val keystorePath = signingInput("ROAMED_KEYSTORE_FILE")
val keystorePassword = signingInput("ROAMED_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingInput("ROAMED_KEY_ALIAS")
val releaseKeyPassword = signingInput("ROAMED_KEY_PASSWORD")

val signingInputs = listOf(keystorePath, keystorePassword, releaseKeyAlias, releaseKeyPassword)
val hasReleaseSigning = signingInputs.all { it.isPresent }

// Half-configured signing means a silent fall back to an unstable key, which is the exact failure
// this is here to prevent. Say so instead.
require(hasReleaseSigning || signingInputs.none { it.isPresent }) {
    "Release signing is partly configured. Set all of ROAMED_KEYSTORE_FILE, " +
        "ROAMED_KEYSTORE_PASSWORD, ROAMED_KEY_ALIAS and ROAMED_KEY_PASSWORD, or none of them."
}

android {
    namespace = "dev.jackque.roamed"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.jackque.roamed"
        minSdk = 26
        targetSdk = 35
        // Every CI build gets a higher versionCode than the last, so updates are never refused
        // as a downgrade. Local builds stay at 1; they are debug builds under a separate
        // application id, so they cannot collide with an installed release.
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER")
            .map(String::toInt)
            .getOrElse(1)
        versionName = "1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystorePath.get())
                storePassword = keystorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // A separate application id, so a debug build can sit alongside an installed release
            // instead of fighting it for the same package name.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                // The debug keystore is generated per machine, so this key differs on every CI
                // runner and such a build can never be updated in place. Usable for a first
                // install or a quick try; see README for setting up the real key.
                versionNameSuffix = "-unstablesigning"
                signingConfigs.getByName("debug")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
        }
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.play.services.location)
    implementation(libs.osmdroid.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
