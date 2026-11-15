import java.io.File

plugins {
    kotlin("jvm") version "2.4.21"
    application
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}

sourceSets.main {
    kotlin.srcDir("../core/domain/src/main/java")
    kotlin.exclude("**/source/**", "**/usecase/**", "**/repository/**")
    kotlin.exclude("**/utility/DataParser.kt")
}

sourceSets.main {
    kotlin.srcDir("../core/data/src/main/java/com/rtbishop/look4sat/core/data/framework")
    kotlin.exclude("**/Ft817Controller.kt", "**/Ic705Controller.kt", "**/RadioTrackingService.kt",
        "**/NetworkReporter.kt", "**/BluetoothReporter.kt")
}

application { mainClass.set("com.rtbishop.look4sat.desktop.MainKt") }

tasks.register<Exec>("packageMac") {
    group = "distribution"
    description = "Build a self-contained macOS .app for the architecture of the current JDK 21."
    dependsOn(tasks.named("installDist"))
    doFirst {
        require(System.getProperty("os.name").contains("Mac", ignoreCase = true)) { "Run packageMac on macOS" }
        val app = layout.buildDirectory.dir("macos/Look4Sat-Mac.app").get().asFile
        if (app.exists()) app.deleteRecursively()
    }
    commandLine(File(System.getProperty("java.home"), "bin/jpackage").absolutePath, "--type", "app-image", "--name", "Look4Sat-Mac", "--app-version", "4.4.9",
        "--input", layout.buildDirectory.dir("install/Look4Sat-Mac/lib").get().asFile.absolutePath,
        "--main-jar", "Look4Sat-Mac.jar", "--main-class", "com.rtbishop.look4sat.desktop.MainKt",
        "--dest", layout.buildDirectory.dir("macos").get().asFile.absolutePath,
        "--mac-package-identifier", "com.rtbishop.look4sat.desktop")
    doLast {
        val app = layout.buildDirectory.dir("macos/Look4Sat-Mac.app").get().asFile
        val plist = File(app, "Contents/Info.plist")
        check(ProcessBuilder("/usr/libexec/PlistBuddy", "-c",
            "Set :NSMicrophoneUsageDescription Look4Sat decodes SSTV signals from your selected audio input.",
            plist.absolutePath).inheritIO().start().waitFor() == 0)
        check(ProcessBuilder("/usr/bin/codesign", "--force", "--deep", "--sign", "-", app.absolutePath)
            .inheritIO().start().waitFor() == 0)
    }
}

tasks.register<JavaExec>("verifyDesktop") {
    group = "verification"
    description = "Run deterministic offline desktop parser and orbital prediction checks."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.rtbishop.look4sat.desktop.DesktopVerificationKt")
}
