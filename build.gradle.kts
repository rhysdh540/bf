import proguard.ConfigurationParser
import proguard.ProGuard
import proguard.Configuration as ProguardConfiguration

plugins {
    kotlin("jvm") version "2.2.21"
    id("com.gradleup.shadow") version("9.2.2")
}

buildscript {
    repositories.mavenCentral()
    dependencies.classpath("com.guardsquare:proguard-base:7.8.1")
}

group = "dev.rdh"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.ow2.asm:asm-util:9.9")
    implementation("org.ow2.asm:asm-commons:9.9")

    testImplementation(kotlin("test"))
}

tasks.withType<AbstractArchiveTask> {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    archiveClassifier = ""
}

tasks.jar {
    manifest.attributes["Main-Class"] = "bf.Main"
}

tasks.shadowJar {
    manifest.attributes["Main-Class"] = "bf.Main"

    exclude("**/*.kotlin_builtins")

    relocate("org.objectweb.asm", "bf.asm")
    relocate("kotlin", "bf.kotlin")
    relocate("org.jetbrains.annotations", "bf.jbannotations")
    relocate("org.intellij.lang.annotations", "bf.ijannotations")

    mergeServiceFiles()
    archiveClassifier = "fat"
}

tasks.register<ProGuardTask>("proguard") {
    inputJar = tasks.shadowJar.flatMap { it.archiveFile }

    options = listOf(
        "-libraryjars", "${System.getProperty("java.home")}/jmods/java.base.jmod",
        "-keep public class bf.* { public *; }",
        "-dontobfuscate",
        "-optimizations", "!method/specialization/parametertype",
        "-optimizationpasses", "5",
        "-dontwarn", "java.lang.invoke.*",
        "-dontnote",
        "-assumenosideeffects", $$"public class bf.DslKt$bfProgram$Impl { kotlin.Unit getUnit(java.lang.Object); }"
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

tasks.test {
    useJUnitPlatform()
}

for (file in file("src/test/resources").listFiles() ?: emptyArray()) {
    if (file.isFile && file.extension == "b") {
        val name = file.nameWithoutExtension

        tasks.register<JavaExec>(name) {
            group = "examples"
            description = "Run the file ${name}.b"
            args = listOf(
                "--optimise", "--strip",
                "--compile", "--export",
                "--time",
                "--overflow-protection",
                file.absolutePath
            )

            jvmArgs("-server", "-Xmx3g", "-XX:+UseZGC", "-XX:-DontCompileHugeMethods")

            val input = file.resolveSibling("${name}.in")
            if (input.exists()) {
                doFirst {
                    standardInput = input.inputStream()
                }
            }

            classpath = sourceSets["main"].runtimeClasspath
            mainClass.set("bf.Main")
            javaLauncher = javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
    }
}