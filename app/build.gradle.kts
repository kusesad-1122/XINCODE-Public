import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 发布签名:从本机的 keystore.properties 读取(该文件已 .gitignore,绝不入库),
// 从而正式版 APK 在【你自己的机器上】签名——签名密钥永远留在本地,不进仓库、不外传。
// 用法见 keystore.properties.example。缺该文件时 release 走【未签名】(CI/他人可正常编译)。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.xincode.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xincode.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 101          // 1.0 → 100;之后每次 +0.01 版本对应 +1(1.01→101…)
        versionName = "1.01"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":provider"))
    implementation(project(":tools"))
    implementation(project(":security"))
    implementation(project(":data"))
    implementation(project(":ui"))
    implementation(project(":service"))

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.topjohnwu.libsu:core:5.2.2")
    // Hermes-④ execute_code:Rhino JS 解释器(Android 用 optimizationLevel=-1 解释模式)。
    implementation("org.mozilla:rhino:1.7.14")
    // Hermes-⑦ cron:WorkManager 周期 tick。
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}