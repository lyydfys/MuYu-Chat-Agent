pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MuYuChatAgent"

include(":app")
include(":core:native")
include(":core:sd-native")
include(":core:engine")
include(":core:modelstore")
include(":core:download")
include(":core:telemetry")
include(":core:deviceprofile")
include(":core:tuning")
include(":core:advisor")
include(":core:benchmark")
include(":api:local")
include(":feature:chat")
include(":feature:agent")
include(":feature:modelhub")
include(":feature:settings")

