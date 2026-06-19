// Source stamp 2024-12-12T04:05:15 @ 53c76ef
import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    alias(libs.plugins.aliucord.injector)
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

private fun exec(vararg cmd: String) {
    val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    proc.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }
    if (proc.waitFor() != 0) error("Command failed: ${cmd.joinToString(" ")}")
}
val injectWebrtcDex by tasks.registering {
    dependsOn("bundleReleaseAar")

    doLast {
        val aar = layout.buildDirectory.file("outputs/aar/${project.name}-release.aar").get().asFile
        val work = layout.buildDirectory.dir("aliuvoice").get().asFile
        work.deleteRecursively()
        work.mkdirs()

        copy {
            from(zipTree(aar)) { include("classes.jar") }
            into(work)
        }
        val classesJar = File(work, "classes.jar")
        require(classesJar.exists()) { "classes.jar missing in AAR" }

        val sdk = android.sdkDirectory
        val buildToolsVersion = "36.0.0"
        val buildToolsRoot = File(sdk, "build-tools")
        val buildTools = File(buildToolsRoot, buildToolsVersion).takeIf { it.isDirectory }
            ?: buildToolsRoot.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
            ?: error("No build-tools installed in $sdk")
        val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val d8 = File(buildTools, if (isWindows) "d8.bat" else "d8")
        val androidJar = File(sdk, "platforms/android-${android.compileSdk}/android.jar")

        exec(
            d8.absolutePath,
            "--release",
            "--min-api", "24",
            "--lib", androidJar.absolutePath,
            "--output", work.absolutePath,
            classesJar.absolutePath,
        )

        val dexes = work.listFiles { f -> f.isFile && f.name.endsWith(".dex") }
            ?.sortedBy { it.name }
            ?: error("d8 produced no dex output")

        FileSystems.newFileSystem(URI("jar:${aar.toURI()}"), mapOf("create" to "false")).use { fs ->
            for (dex in dexes) {
                val entryName = if (dex.name == "classes.dex") "webrtc.dex" else dex.name
                Files.copy(dex.toPath(), fs.getPath("/$entryName"), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        logger.lifecycle("Injected ${dexes.size} dex file(s) into ${aar.name}")
    }
}

tasks.matching {
    it.name.startsWith("publish") || it.name.startsWith("generateMetadataFile")
}.configureEach {
    dependsOn(injectWebrtcDex)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "com.aliucord"
                artifactId = project.name
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
