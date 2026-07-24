pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "XINCODE"

include(":app")
include(":core")
include(":provider")
include(":tools")
include(":security")
include(":data")
include(":ui")
include(":service")