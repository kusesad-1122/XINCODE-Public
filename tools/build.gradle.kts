plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xincode.tools"
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
    implementation(project(":core"))
    implementation(project(":provider"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.github.topjohnwu.libsu:core:5.2.2")

    // delete_file 是破坏性操作,它的护栏必须有测试钉住(见 FileManageToolTest)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Android 打包的 org.json 在单测里是抛异常的 stub,而工具一构造就要 put 出 schema。
    // 挂上真实实现,否则任何碰 JSONObject 的工具都没法做单测。
    testImplementation("org.json:json:20231013")
}