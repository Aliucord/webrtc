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
        consumerProguardFiles("consumer-rules.pro")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    compileOnly(libs.annotation)
    compileOnly(libs.discord)
    compileOnly(libs.kotlin.stdlib)
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xno-call-assertions",
            "-Xno-param-assertions",
            "-Xno-receiver-assertions",
        )
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "com.aliucord"
                artifactId = project.name
                version = "1.0.1"

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
