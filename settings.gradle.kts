pluginManagement {
    repositories {
        maven("https://dl.bintray.com/kotlin/kotlin-eap")
        mavenCentral()
        maven("https://plugins.gradle.org/m2/")
        jcenter()

        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/kotlin/p/dokka/dev")
    }
}
rootProject.name = "kotlin-js-threads"
