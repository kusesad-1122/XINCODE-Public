import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
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

    // 只有在 keystore.properties 真实存在时才建 release 签名配置。
    // 无条件 create 会在 CI(无该文件)上让 getProperty 返回 null,file(null) 直接抛异常,
    // 导致 Gradle【配置阶段】就失败——连 assembleDebug 都跑不起来。
    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                // 必须用 rootProject.file():在 app 模块里 file("x.jks") 会相对【app/ 目录】解析,
                // 而 keystore.properties 与 .jks 都放在【仓库根】(CI 的 Decode keystore 步骤也写在根),
                // 用 file() 会去找 app/xincode-release.jks 从而报 "Keystore file not found"。
                // rootProject.file() 以仓库根为基准;若给的是绝对路径也能正确处理。
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            // 同理:没有密钥文件时不绑定签名配置(CI release job 会先写出 keystore.properties 再构建)。
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
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
    // execute_code 的 JS 解释器(CodeExecTool 依赖 org.mozilla.javascript.*)。缺失会导致
    // compileDebugKotlin 报 35 处 "Unresolved reference: mozilla" —— 别删。
    implementation("org.mozilla:rhino:1.7.14")
    // 定时任务(CronScheduler/CronWorker 依赖 androidx.work.*)。同样别删。
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}