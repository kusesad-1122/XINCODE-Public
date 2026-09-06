import java.util.Properties
import java.io.FileInputStream
import org.gradle.api.GradleException

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
        versionCode = 134
        versionName = "1.13.8"
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
                val storeFileProp = keystoreProperties.getProperty("storeFile")
                    ?: throw GradleException("keystore.properties 缺少 storeFile:请检查 CI Decode keystore 步骤是否写出完整四项(storeFile/storePassword/keyAlias/keyPassword)")
                val storePasswordProp = keystoreProperties.getProperty("storePassword")
                    ?: throw GradleException("keystore.properties 缺少 storePassword:请检查 CI Decode keystore 步骤是否写出完整四项")
                val keyAliasProp = keystoreProperties.getProperty("keyAlias")
                    ?: throw GradleException("keystore.properties 缺少 keyAlias:请检查 CI Decode keystore 步骤是否写出完整四项")
                val keyPasswordProp = keystoreProperties.getProperty("keyPassword")
                    ?: throw GradleException("keystore.properties 缺少 keyPassword:请检查 CI Decode keystore 步骤是否写出完整四项")
                storeFile = rootProject.file(storeFileProp)
                storePassword = storePasswordProp
                keyAlias = keyAliasProp
                keyPassword = keyPasswordProp
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

    testOptions {
        // android.util.Log 等 framework 类在单测里是【抛异常】的 stub,
        // 于是被测代码里随手一句 Log.w 就能让纯逻辑测试挂掉。
        // 让 stub 返回默认值而不是抛。(org.json 不靠这个 —— 它挂了真实实现)
        unitTests.isReturnDefaultValues = true
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
    // 玻璃拟态背景高斯模糊(0.9.0-rc03 与 Compose 1.7/Kotlin 1.9 匹配;Android <12 自动降级为半透明)
    implementation("dev.chrisbanes.haze:haze:0.9.0-rc03")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.topjohnwu.libsu:core:5.2.2")
    // Shizuku 通过反射调用，无需编译期依赖，避免 Maven 拉取；普通用户仍可通过普通 shell 使用
    // execute_code 的 JS 解释器(CodeExecTool 依赖 org.mozilla.javascript.*)。缺失会导致
    // compileDebugKotlin 报 35 处 "Unresolved reference: mozilla" —— 别删。
    implementation("org.mozilla:rhino:1.7.14")
    // 定时任务(CronScheduler/CronWorker 依赖 androidx.work.*)。同样别删。
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 群聊连锁的防失控闸门要能脱离网络测(见 GroupChainTest)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Android 打包的 org.json 在单测里是抛异常的 stub,挂上真实实现。
    // 注意这条要配合 isReturnDefaultValues:那个开关会让 stub 静默返回 null,
    // 只开开关不换实现的话,碰 JSON 的测试会得到一堆诡异空值而不是明确报错。
    testImplementation("org.json:json:20231013")
}