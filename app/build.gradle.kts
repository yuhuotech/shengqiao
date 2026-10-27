plugins { id("com.android.application") }

android {
    namespace = "com.yuhuo.shengqiao"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.yuhuo.shengqiao"
        minSdk = 33
        targetSdk = 36
        versionCode = 15
        versionName = "0.5.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { aidl = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/release.keystore")
            // Passwords come from gradle.properties or env vars; never commit them.
            val storePassword = providers.gradleProperty("SHENGQIAO_STORE_PASSWORD")
                .orElse(providers.environmentVariable("SHENGQIAO_STORE_PASSWORD"))
            val alias = providers.gradleProperty("SHENGQIAO_KEY_ALIAS")
                .orElse(providers.environmentVariable("SHENGQIAO_KEY_ALIAS"))
            val keyPassword = providers.gradleProperty("SHENGQIAO_KEY_PASSWORD")
                .orElse(providers.environmentVariable("SHENGQIAO_KEY_PASSWORD"))
            this.storePassword = storePassword.get()
            this.keyAlias = alias.get()
            this.keyPassword = keyPassword.get()
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging {
        // Emit both aligned and unaligned APKs so consumers can sign with apksigner
        // or distribute the unaligned artifact for sideloading tools.
        jniLibs { useLegacyPackaging = false }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

val printVersionName by tasks.registering {
    val version = android.defaultConfig.versionName
    doLast { println("version=$version") }
}
