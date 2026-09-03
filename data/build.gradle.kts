plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.xincode.data"
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
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    api("androidx.room:room-runtime:$roomVersion")
    testImplementation("junit:junit:4.13.2")
}

// Room exportSchema=true 的落盘位置:每次编译更新 data/schemas 下的版本快照,
// 审计/升级前先 diff 它,防止“实体改了但迁移没跟上”导致老用户启动即崩。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android { testOptions { unitTests.isReturnDefaultValues = true } }