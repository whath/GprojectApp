import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.application")
}
kotlin {
    androidTarget()
    jvm("desktop")
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            implementation("io.ktor:ktor-client-core:3.1.3")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.10.1")
            implementation("io.ktor:ktor-client-okhttp:3.1.3")
        }
        val desktopMain by getting
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            implementation("io.ktor:ktor-client-cio:3.1.3")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(compose.desktop.uiTestJUnit4)
            implementation("io.ktor:ktor-client-mock:3.1.3")
        }
    }
}
android {
    namespace = "cn.gproject"
    compileSdk = 35
    defaultConfig { applicationId = "cn.gproject"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
compose.desktop {
    application {
        mainClass = "cn.gproject.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Gproject"
            packageVersion = "1.0.0"
            modules("java.net.http", "jdk.unsupported")
            windows { perUserInstall = true; menuGroup = "Gproject"; shortcut = true; upgradeUuid = "34d609b0-a05f-4ba4-87af-ebd07b606399" }
        }
    }
}
