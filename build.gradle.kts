import proguard.ConfigurationParser
import proguard.ProGuard
import proguard.Configuration as ProguardConfiguration

plugins {
    kotlin("jvm") version "2.1.10"
    id("com.gradleup.shadow") version("9.0.0-beta12")
}

buildscript {
    repositories.mavenCentral()
    dependencies.classpath("com.guardsquare:proguard-base:7.7.0")
}

group = "dev.rdh"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm-util:9.7.1")
}

tasks.withType<AbstractArchiveTask> {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.jar {
    manifest.attributes["Main-Class"] = "bf.MainKt"
}

tasks.shadowJar {
    manifest.attributes["Main-Class"] = "bf.MainKt"

    exclude("**/*.kotlin_builtins")

    relocate("org.objectweb.asm", "bf.asm")
    relocate("kotlin", "bf.kotlin")
}

tasks.register("proguard") {
    group = "build"
    description = "Run ProGuard on the shadow jar"

    dependsOn("shadowJar")

    val input = tasks.shadowJar.get().archiveFile.get().asFile
    val output = layout.buildDirectory.dir("libs").get().asFile.resolve("bf.jar")

    inputs.file(input)
    outputs.file(output)

    doLast {
        val options = listOf(
            "-injars", input.absolutePath,
            "-outjars", output.absolutePath,
            "-libraryjars", "${System.getProperty("java.home")}/jmods/java.base.jmod",
            "-keep public class bf.* { public *; }",
            "-dontobfuscate",
            "-optimizationpasses", "5",
            "-dontwarn", "java.lang.invoke.*",
            "-dontnote",
            "-assumenosideeffects", "public class bf.DslKt\$bfProgram\$Impl { kotlin.Unit getUnit(java.lang.Object); }",
        )

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

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

for (file in file("src/test/resources").listFiles() ?: emptyArray()) {
    if (file.isFile && file.extension == "b") {
        val name = file.nameWithoutExtension

        tasks.register<JavaExec>(name) {
            group = "examples"
            description = "Run the file ${name}.b"
            args = listOf(
                "-OSce",
                file.absolutePath
            )

            val input = file.resolveSibling("${name}.in")
            if (input.exists()) {
                doFirst {
                    standardInput = input.inputStream()
                }
            }

            classpath = sourceSets["main"].runtimeClasspath
            mainClass.set("bf.MainKt")
            javaLauncher = javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
    }
}