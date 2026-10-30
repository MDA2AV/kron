plugins {
    kotlin("multiplatform")
}

kotlin {
    for (target in listOf(linuxX64(), linuxArm64())) {
        target.binaries {
            executable {
                entryPoint = "playground.main"
            }
        }
    }

    // The same samples on the JVM: playground-jvm-all.jar, run as
    //   java --enable-native-access=ALL-UNNAMED -Dkron.boringssl=native/linux-x64/libkron_boringssl.so \
    //       -jar playground/build/libs/playground-jvm-all.jar tcp-raw
    jvm()
    jvmToolchain(25)

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        matching { it.name.startsWith("native") || it.name.startsWith("linux") }.all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain.dependencies {
            implementation(project(":kron"))
        }
    }
}

tasks.register<Jar>("jvmFatJar") {
    group = "build"
    description = "The JVM samples and everything they run on, in one runnable jar."
    archiveBaseName.set("playground-jvm")
    archiveClassifier.set("all")
    archiveVersion.set("")
    manifest {
        attributes("Main-Class" to "playground.MainKt", "Enable-Native-Access" to "ALL-UNNAMED")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val jvmJar = tasks.named<Jar>("jvmJar")
    dependsOn(jvmJar)
    from(jvmJar.map { zipTree(it.archiveFile) })
    from(configurations.named("jvmRuntimeClasspath").map { classpath -> classpath.map { if (it.isDirectory) it else zipTree(it) } })
}
