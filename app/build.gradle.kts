import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Release signing.
 *
 * The keystore lives outside version control (see .gitignore). If it is missing we fall back to
 * the debug signing config so `assembleRelease` still produces an installable APK for sideloading.
 * For a real distribution, create the keystore with:
 *   keytool -genkeypair -v -keystore keystore/release.jks -alias campus \
 *           -keyalg RSA -keysize 2048 -validity 10950 \
 *           -storepass <storepass> -keypass <keypass> \
 *           -dname "CN=SLAI Campus, OU=Personal, O=Personal, L=Shenzhen, ST=Guangdong, C=CN"
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile")?.let { rootProject.file(it).exists() } == true

android {
    namespace = "com.slai.campus"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.slai.campus"
        minSdk = 26
        targetSdk = 36
        /*
         * 发布新版本时**两处都要改**：
         *   versionCode 必须递增，否则 Android 会拒绝覆盖安装（"应用未安装"）；
         *   versionName 只影响显示，但要和 git tag 保持一致，方便对上号。
         */
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Default endpoints. They can also be overridden at runtime in Settings.
        //
        // SIS 的登录入口没有单独的常量：它是从 base URL 推出来的（`{base}/htxylogin`），
        // 这样用户在设置里改成站点根地址时入口会跟着走 —— 见 core/web/SisEndpoints.kt。
        buildConfigField("String", "DEFAULT_SIS_BASE_URL", "\"https://sis.slai.edu.cn/yjsxt\"")
        buildConfigField("String", "DEFAULT_STU_BASE_URL", "\"https://stu.slai.edu.cn\"")
        buildConfigField("String", "DEFAULT_STU_ENTRY_URL", "\"https://stu.slai.edu.cn/sso/login\"")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            isDebuggable = true
            buildConfigField("boolean", "WEBVIEW_DEBUG_ENABLED", "true")
            buildConfigField("boolean", "VERBOSE_LOGGING", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            buildConfigField("boolean", "WEBVIEW_DEBUG_ENABLED", "false")
            buildConfigField("boolean", "VERBOSE_LOGGING", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jsoup)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
