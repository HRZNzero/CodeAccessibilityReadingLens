plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "me.netizendev"
version = "1.0.6"

repositories {
    mavenCentral()
}

// No Gradle toolchain block on purpose.
// This avoids "Undefined Toolchain Download Repositories" on machines where
// Gradle tries to auto-download JDK 17. Instead, IntelliJ/Gradle uses the JDK
// you select in Settings > Build Tools > Gradle > Gradle JVM.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

intellij {
    version.set("2024.2.5")
    type.set("IC")
    plugins.set(listOf("java"))
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    patchPluginXml {
        sinceBuild.set("242")
        untilBuild.set("")
    }
}
