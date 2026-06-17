// Source stamp 2024-12-12T04:05:15 @ 53c76ef
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin)
    id("maven-publish")
}

android {
    namespace = "org.webrtc"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    compileOnly(libs.annotation)
}

kotlin {
    jvmToolchain(21)
}


afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "com.aliucord"
                artifactId = "webrtc"
                version = "0.0.1"

                from(components["release"])
            }
        }

        repositories {
            maven {
                url = uri("https://maven.aliucord.com/releases")
                credentials {
                    username = System.getenv("MAVEN_RELEASE_USERNAME")
                    password = System.getenv("MAVEN_RELEASE_PASSWORD")
                }
            }
        }
    }
}