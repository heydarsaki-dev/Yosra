plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ir.yosra.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ir.yosra.app"
        minSdk = 24
        targetSdk = 29
        versionCode = 43
        versionName = "1.0.0"
    }

    signingConfigs {
        create("yosra") {
            storeFile = file("debug.keystore")
            storePassword = "yosra12345"
            keyAlias = "yosra"
            keyPassword = "yosra12345"
        }
    }

    // lintVital در کم‌حافظه‌تر ماشین‌ها دایمن Gradle را می‌کشد؛
    // پروژه از AndroidX/Library استفاده نمی‌کند پس بررسی اضافه است.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("yosra")
        }
        debug {
            signingConfig = signingConfigs.getByName("yosra")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
}

// خروجی با نام خوانا: dist/Yosra-v<versionName>.apk
val distDir = layout.buildDirectory.dir("../../dist")

val exportApk by tasks.registering(Copy::class) {
    group = "distribution"
    description = "کپی APK نهایی به پوشه dist/"
    val vName = android.defaultConfig.versionName
    val vCode = android.defaultConfig.versionCode
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename { "Yosra-v$vName-$vCode.apk" }
    }
    into(distDir)
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(exportApk)
}
