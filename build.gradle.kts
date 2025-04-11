plugins {
    kotlin("jvm") version "2.1.10"
}

group = "dev.rdh"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
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
            args = listOf(file.absolutePath)

            classpath = sourceSets["main"].runtimeClasspath
            mainClass.set("bf.MainKt")
            javaLauncher = javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
    }
}