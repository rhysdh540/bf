@file:OptIn(ExperimentalWasmDsl::class)

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import proguard.ConfigurationParser
import proguard.ProGuard
import kotlin.io.path.ExperimentalPathApi
import proguard.Configuration as ProguardConfiguration
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively

plugins {
    kotlin("multiplatform") version "2.3.10"
    id("com.gradleup.shadow") version("9.3.2")
}

buildscript {
    repositories.mavenCentral()
    dependencies.classpath("com.guardsquare:proguard-base:7.8.2")
}

group = "dev.rdh"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
    jvm()
    wasmJs {
        browser()
        binaries.executable()
    }

    macosX64 {
        binaries.executable {
            baseName = "bf"
            entryPoint = "dev.rdh.bf.main"
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val nativeMain by getting
        named("wasmJsMain") {
            dependencies {
                implementation(npm("binaryen", "125.0.0"))
            }
        }

        named("jvmMain") {
            dependencies {
                implementation("org.ow2.asm:asm-util:9.9")
                implementation("org.ow2.asm:asm-commons:9.9")
            }
        }

        named("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val x64Main by creating {
            dependsOn(nativeMain)
        }

        configureEach {
            if ("X64" in this.name) {
                dependsOn(x64Main)
            }
        }
    }
}

tasks.withType<AbstractArchiveTask> {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    archiveClassifier = ""
}


tasks.named<Jar>("jvmJar") {
    manifest.attributes["Main-Class"] = "dev.rdh.bf.Main"
}

tasks.named<ShadowJar>("shadowJar") {
    manifest.attributes["Main-Class"] = "dev.rdh.bf.Main"

    exclude("**/*.kotlin_builtins")

    relocate("org.objectweb.asm", "dev.rdh.bf.asm")
    relocate("kotlin", "dev.rdh.bf.kotlin")
    relocate("org.jetbrains.annotations", "dev.rdh.bf.jbannotations")
    relocate("org.intellij.lang.annotations", "dev.rdh.bf.ijannotations")

    mergeServiceFiles()
    archiveClassifier = "fat"
}

tasks.register<ProGuardTask>("proguard") {
    inputJar = tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }

    options = listOf(
        "-libraryjars", "${System.getProperty("java.home")}/jmods/java.base.jmod",
        "-keep public class dev.rdh.bf.* { public *; }",
        "-dontobfuscate",
        "-optimizations", "!method/specialization/parametertype",
        "-optimizationpasses", "5",
        "-dontwarn", "java.lang.invoke.*",
        "-dontnote",
        "-assumenosideeffects", $$"public class dev.rdh.bf.DslKt$bfProgram$Impl { kotlin.Unit getUnit(java.lang.Object); }"
    )

    archiveClassifier = ""
}

abstract class ProGuardTask : Jar() {
    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:Input
    abstract val options: ListProperty<String>

    override fun copy() {
        val options = listOf(
            "-injars", inputJar.get().asFile.absolutePath,
            "-outjars", archiveFile.get().asFile.absolutePath
        ) + this.options.get()

        val proguardConfig = ProguardConfiguration()
        ConfigurationParser(options.toTypedArray(), null).apply {
            parse(proguardConfig)
        }

        ProGuard(proguardConfig).execute()
    }
}

tasks.assemble {
    dependsOn(tasks["proguard"])
}

tasks.withType<Test> {
    useJUnitPlatform()
}

for (file in file("src/jvmTest/resources").listFiles() ?: emptyArray()) {
    if (file.isFile && file.extension == "b") {
        val name = file.nameWithoutExtension

        tasks.register<JavaExec>(name) {
            group = "examples"
            description = "Run the file ${name}.b"
            args = listOf(
                "--optimise", "--compile",
                "--export", "--time",
                file.absolutePath
            )

            jvmArgs(
                "-server", "-Xmx3g", "-XX:+UseZGC",
                "-XX:-DontCompileHugeMethods", "-XX:+UseCompactObjectHeaders",
                "-Xlog:verification=error",
            )

            val input = file.resolveSibling("${name}.in")
            if (input.exists()) {
                doFirst {
                    standardInput = input.inputStream()
                }
            }

            doFirst {
                @OptIn(ExperimentalPathApi::class)
                Path(".bf.out").deleteRecursively()
            }

            classpath = sourceSets["jvmMain"].runtimeClasspath
            mainClass = "dev.rdh.bf.Main"
            javaLauncher = javaToolchains.launcherFor {
                languageVersion = JavaLanguageVersion.of(25)
            }
        }
    }
}
