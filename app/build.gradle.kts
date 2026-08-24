plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.mango.adbtool"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.mango.adbtool"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.3.1"
    }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.02.01")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
val buildServerDex by tasks.registering {
    dependsOn(":server:assembleRelease")
    doLast {
        val aar = rootProject.file("server/build/outputs/aar/server-release.aar")
        val unpack = File(project.layout.buildDirectory.get().asFile, "server-unpack").apply { deleteRecursively(); mkdirs() }
        copy { from(zipTree(aar)); into(unpack) }
        val bt = File(android.sdkDirectory, "build-tools").listFiles()
            ?.filter { it.isDirectory && it.name.substringBefore('.').toIntOrNull() != null }
            ?.maxByOrNull { it.name.substringBefore('.').toIntOrNull() ?: 0 }
            ?: error("未找到 build-tools")
        val isWin = org.gradle.internal.os.OperatingSystem.current().isWindows
        val d8 = File(bt, if (isWin) "d8.bat" else "d8")
        val outDir = File(project.layout.buildDirectory.get().asFile, "server-dex").apply { deleteRecursively(); mkdirs() }
        val proc = ProcessBuilder(
            d8.absolutePath, "--release", "--min-api", "26",
            "--lib", File(android.sdkDirectory, "platforms/android-34/android.jar").absolutePath,
            "--output", outDir.absolutePath,
            File(unpack, "classes.jar").absolutePath
        ).redirectErrorStream(true).start()
        val d8Out = proc.inputStream.bufferedReader().readText()
        val d8Code = proc.waitFor()
        if (d8Code != 0) error("d8 失败 (exit=$d8Code):\n$d8Out")
        val assets = File(projectDir, "src/main/assets").apply { mkdirs() }
        File(outDir, "classes.dex").copyTo(File(assets, "mango-server.dex"), overwrite = true)
        println("🥭 mango-server.dex 已生成: ${File(assets, "mango-server.dex").length()} bytes")
    }
}
tasks.named("preBuild") { dependsOn(buildServerDex) }
