import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Self-contained command-line jar: core classes plus kotlin-stdlib. Run with `java -jar`.
tasks.register<Jar>("cliJar") {
    group = "build"
    description = "Builds build/libs/hamir-cli.jar with the babble generator CLI and its runtime dependencies."
    archiveFileName.set("hamir-cli.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    manifest {
        attributes["Main-Class"] = "io.github.victorkolesnyk.hamir.core.CliKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**", "module-info.class")
    }
}
