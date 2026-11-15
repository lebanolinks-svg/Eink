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
        // Google-hosted mirror of Maven Central; serves large test artifacts directly.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "InkPhone"
include(":app")
