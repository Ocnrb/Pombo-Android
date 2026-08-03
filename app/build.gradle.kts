plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.pombo.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pombo.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            // The JS bridge depends on exact global names; no obfuscation.
            isMinifyEnabled = false
            // Sign release with the debug key so the variant can be Run
            // straight from Android Studio. Without a signingConfig the release
            // APK comes out unsigned and the device refuses to install it.
            // This is for local testing only — a store build needs a real key.
            signingConfig = signingConfigs.getByName("debug")
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
    }
}

dependencies {
    // FCM: receives the relay wake signals as data-only messages.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    // Full Bouncy Castle for the dead-app push path ONLY: opening a sealed-
    // sender DM envelope needs secp256k1 ECDH + keccak256 + ecrecover, none of
    // which Android's stripped BC has, and the WebView bridge is not running
    // when FCM wakes a dead process. Used via direct class references (no JCA
    // provider registration), byte-parity locked by SealedSenderCryptoTest
    // vectors generated with the web's own ethers.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // Real org.json for JVM unit tests — the Android stub throws "not mocked".
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Encrypted private-key storage (equivalent of the web's secureStorage)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Device-credential gate before revealing or destroying the key. The web
    // asks for the keystore password; this app has no such password, so the
    // device lock is the equivalent proof of ownership.
    implementation("androidx.biometric:biometric:1.1.0")

    // Deterministic SVG avatars (same output as the web's AvatarGenerator)
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    // Animated GIF/WebP playback in chat (the web just uses <img>, which animates).
    implementation("io.coil-kt:coil-gif:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
