import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// keystore/keystore.properties (git 제외)가 있으면 release를 서명한다. 없으면 unsigned로 빌드된다.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "ai.kilopace.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.kilopace.app"
        // Pleos 공식 요구: minSdk 28 이상 (차량 사이버보안 요건)
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // AAOS CarUxRestrictionsManager (주행 규제 연동)
    useLibrary("android.car")

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)
    implementation(libs.pleos.fusedlocation)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
