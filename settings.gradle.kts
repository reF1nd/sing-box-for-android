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
        exclusiveContent {
            forRepository {
                maven {
                    name = "Xposed"
                    url = uri("https://api.xposed.info/")
                }
            }
            filter {
                includeGroup("de.robv.android.xposed")
            }
        }
        maven { url = uri("https://jitpack.io") }
        maven {
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            mavenContent { snapshotsOnly() }
            content { includeGroup("io.github.sagernet") }
        }
    }
}
rootProject.name = "sing-box"
include(":app")
include(":libxposed-api")
project(":libxposed-api").projectDir = file("third_party/libxposed-api")
