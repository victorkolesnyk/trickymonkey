import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Збірка APK без Android Gradle Plugin і без Android SDK.
// Причина: dl.google.com (SDK, Google Maven) недоступний у середовищі збірки.
// Ланцюжок: aapt2 (ресурси, R.java) -> kotlinc/javac -> D8 (dex) -> zipalign -> apksigner.
// Інструменти готує tools/setup-toolchain.sh.

plugins {
    kotlin("jvm")
}

val appId = "io.github.victorkolesnyk.hamir"
val versionCodeValue = 3
val versionNameValue = "0.2.1"
val minSdk = 26
val targetSdk = 34
val androidAllVersion = "14-robolectric-10818077"

val toolchainDir = file(System.getenv("HAMIR_TOOLCHAIN") ?: "/opt/hamir-toolchain")
val r8Jar = File(toolchainDir, "r8.jar")

// Повний фреймворк Android 14 з Maven Central: класи для компіляції і ресурси для aapt2.
val androidJar: Configuration by configurations.creating {
    isTransitive = false
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
    androidJar("org.robolectric:android-all:$androidAllVersion")
    compileOnly("org.robolectric:android-all:$androidAllVersion") { isTransitive = false }
    implementation(project(":core"))

    // Тести app: лише логіка без класів Android (Robolectric потребує Google Maven, якого тут немає).
    testImplementation(kotlin("test"))
}

val manifestFile = file("src/main/AndroidManifest.xml")
val resDir = file("src/main/res")
val assetsDir = file("src/main/assets")
val compiledRes = layout.buildDirectory.file("res/compiled.zip")
val resourcesApk = layout.buildDirectory.file("res/resources.ap_")
val generatedR = layout.buildDirectory.dir("generated/r")
val dexDir = layout.buildDirectory.dir("dex")
val apkDir = layout.buildDirectory.dir("apk")

val compileRes by tasks.registering(Exec::class) {
    inputs.dir(resDir)
    outputs.file(compiledRes)
    doFirst { compiledRes.get().asFile.parentFile.mkdirs() }
    commandLine("aapt2", "compile", "--dir", resDir.absolutePath, "-o", compiledRes.get().asFile.absolutePath)
}

val linkRes by tasks.registering(Exec::class) {
    dependsOn(compileRes)
    inputs.file(manifestFile)
    inputs.file(compiledRes)
    inputs.files(androidJar)
    if (assetsDir.exists()) inputs.dir(assetsDir)
    outputs.file(resourcesApk)
    outputs.dir(generatedR)
    doFirst {
        delete(generatedR)
        generatedR.get().asFile.mkdirs()
        val args = mutableListOf(
            "aapt2", "link",
            "-o", resourcesApk.get().asFile.absolutePath,
            "--manifest", manifestFile.absolutePath,
            "-I", androidJar.singleFile.absolutePath,
            "--java", generatedR.get().asFile.absolutePath,
            "--min-sdk-version", minSdk.toString(),
            "--target-sdk-version", targetSdk.toString(),
            "--version-code", versionCodeValue.toString(),
            "--version-name", versionNameValue,
            "--auto-add-overlay",
        )
        if (assetsDir.exists()) {
            args += listOf("-A", assetsDir.absolutePath)
        }
        args += compiledRes.get().asFile.absolutePath
        commandLine(args)
    }
}

sourceSets {
    main {
        java.srcDir(generatedR)
    }
}

tasks.named("compileKotlin") { dependsOn(linkRes) }
tasks.named("compileJava") { dependsOn(linkRes) }

val dex by tasks.registering(Exec::class) {
    dependsOn(tasks.named("jar"))
    val appJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val runtimeJars = configurations.runtimeClasspath
    inputs.file(appJar)
    inputs.files(runtimeJars)
    outputs.dir(dexDir)
    doFirst {
        require(r8Jar.exists()) { "Немає ${r8Jar}. Запустіть tools/setup-toolchain.sh" }
        delete(dexDir)
        dexDir.get().asFile.mkdirs()
        val javaHome = System.getProperty("java.home")
        val args = mutableListOf(
            "$javaHome/bin/java", "-cp", r8Jar.absolutePath, "com.android.tools.r8.D8",
            "--release",
            "--min-api", minSdk.toString(),
            "--lib", androidJar.singleFile.absolutePath,
            "--lib", javaHome,
            "--output", dexDir.get().asFile.absolutePath,
            appJar.get().asFile.absolutePath,
        )
        runtimeJars.get().files.filter { it.name.endsWith(".jar") }.forEach { args += it.absolutePath }
        commandLine(args)
    }
}

// Ключ підпису. За замовчуванням створюється локальний налагоджувальний ключ поза репозиторієм.
// Для оновлення вже встановленого застосунку потрібен той самий ключ: збережіть файл.
val keystoreFile = file(
    (findProperty("hamir.keystore") as String?)
        ?: "${System.getProperty("user.home")}/.hamir/debug.keystore"
)
val keystorePass = (findProperty("hamir.keystorePass") as String?) ?: "android"
val keyAlias = (findProperty("hamir.keyAlias") as String?) ?: "hamir"

val ensureKeystore by tasks.registering(Exec::class) {
    onlyIf { !keystoreFile.exists() }
    doFirst { keystoreFile.parentFile.mkdirs() }
    commandLine(
        "keytool", "-genkeypair", "-keystore", keystoreFile.absolutePath,
        "-storepass", keystorePass, "-keypass", keystorePass,
        "-alias", keyAlias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-dname", "CN=Hamir Debug, O=Hamir, C=UA",
    )
}

val apk by tasks.registering {
    group = "build"
    description = "Збирає підписаний APK: app/build/apk/hamir-<версія>.apk"
    dependsOn(linkRes, dex, ensureKeystore)
    val outApk = apkDir.map { it.file("hamir-$versionNameValue.apk") }
    inputs.file(resourcesApk)
    inputs.dir(dexDir)
    outputs.file(outApk)
    doLast {
        val dir = apkDir.get().asFile
        dir.mkdirs()
        val unsigned = File(dir, "unsigned.apk")
        val aligned = File(dir, "aligned.apk")
        val signed = outApk.get().asFile
        listOf(unsigned, aligned, signed).forEach { it.delete() }
        resourcesApk.get().asFile.copyTo(unsigned, overwrite = true)
        val dexFiles = dexDir.get().asFile.listFiles { f -> f.name.endsWith(".dex") }!!.sortedBy { it.name }
        require(dexFiles.isNotEmpty()) { "D8 не створив жодного .dex" }
        exec { commandLine(listOf("zip", "-q", "-j", unsigned.absolutePath) + dexFiles.map { it.absolutePath }) }
        exec { commandLine("zipalign", "-f", "-p", "4", unsigned.absolutePath, aligned.absolutePath) }
        exec {
            commandLine(
                "apksigner", "sign",
                "--ks", keystoreFile.absolutePath,
                "--ks-pass", "pass:$keystorePass",
                "--key-pass", "pass:$keystorePass",
                "--ks-key-alias", keyAlias,
                "--min-sdk-version", minSdk.toString(),
                "--out", signed.absolutePath,
                aligned.absolutePath,
            )
        }
        exec { commandLine("apksigner", "verify", "--min-sdk-version", minSdk.toString(), signed.absolutePath) }
        unsigned.delete()
        aligned.delete()
        File(dir, signed.name + ".idsig").delete()
        println("APK: ${signed.absolutePath} (${signed.length() / 1024} KB)")
    }
}

tasks.test {
    useJUnitPlatform()
}
