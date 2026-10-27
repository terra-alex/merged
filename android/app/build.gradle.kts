plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "co.tryterra.merged"
    compileSdk = 36

    defaultConfig {
        applicationId = "co.tryterra.merged"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // OAuth App client id for GitHub Device Flow. Not a secret (device flow
        // has no client secret). Set githubClientId in ~/.gradle/gradle.properties
        // or local.properties; empty → setup screen falls back to token paste.
        val clientId = (project.findProperty("githubClientId") as String?)
            ?: rootProject.file("local.properties").takeIf { it.exists() }
                ?.readLines()?.firstOrNull { it.startsWith("githubClientId=") }
                ?.substringAfter('=')
            ?: ""
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"$clientId\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            // ponytail: unminified — personal sideload, size doesn't matter and it
            // saves keep-rules for Shizuku's reflected newProcess.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Shizuku — run `service call activity_task 50` as uid=shell to move our
    // task onto the rear display (see docs/rear-screen-routing.md).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // JVM self-checks for the pure logic (Progress, badge rules); no Android classes in them.
    testImplementation("junit:junit:4.13.2")
}
