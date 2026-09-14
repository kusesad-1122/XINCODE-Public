plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xincode.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
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
    implementation(project(":provider"))
    implementation(project(":data"))
    implementation(project(":security"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // 仅测试期依赖(不进 APK):Android 的 android.jar 里 org.json 是空壳,
    // 不引真实实现就无法在 JVM 单测里跑到 JSONObject 相关代码。
    // 与 :security 模块的既有做法保持一致(testImplementation 才引)。
    testImplementation("org.json:json:20240303")
}

android { testOptions { unitTests.isReturnDefaultValues = true } }
