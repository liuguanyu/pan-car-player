import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.parcelize)
    jacoco
}

android {
    namespace = "com.panmusic.carplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.panmusic.carplayer"
        minSdk = 28
        // 车机兼容性：部分魔改车机 PackageParser 拒绝解析 targetSdk > 29 的 APK
        targetSdk = 29
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "com.panmusic.carplayer.HiltTestRunner"

        // local.properties → BuildConfig 密钥注入（替代硬编码）
        val keystoreFile = rootProject.file("local.properties")
        val props = Properties()
        if (keystoreFile.exists()) {
            props.load(keystoreFile.inputStream())
        }
        buildConfigField("String", "BAIDU_APP_KEY", "\"${props.getProperty("BAIDU_APP_KEY", "")}\"")
        buildConfigField("String", "BAIDU_SECRET_KEY", "\"${props.getProperty("BAIDU_SECRET_KEY", "")}\"")
        buildConfigField("String", "BAIDU_APP_ID", "\"${props.getProperty("BAIDU_APP_ID", "")}\"")
    }

    signingConfigs {
        // 车机必须安装已签名 APK
        create("release") {
            val keystoreFile = rootProject.file("local.properties")
            val props = Properties()
            if (keystoreFile.exists()) {
                props.load(keystoreFile.inputStream())
            }
            storeFile = rootProject.file(props.getProperty("KEYSTORE_PATH", "debug.keystore"))
            storePassword = props.getProperty("KEYSTORE_PASSWORD", "android")
            keyAlias = props.getProperty("KEY_ALIAS", "androiddebugkey")
            keyPassword = props.getProperty("KEY_PASSWORD", "android")
            // 车机兼容性：V1+V2 双签名
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            // JaCoCo 覆盖率
            isTestCoverageEnabled = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    // ABI 拆分：arm64-v8a + armeabi-v7a，生成小包
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    // JNI 传统打包：.so 压缩存储，安装时解压，兼容旧车机安装器
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md"
            )
        }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.preference)

    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.gson)

    // Image loading
    implementation(libs.glide)
    ksp(libs.glide.compiler)

    // Media3 (纯音频播放)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.session)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation)

    // Security
    implementation(libs.androidx.security.crypto)

    // QR Code
    implementation(libs.zxing.core)

    // MediaSessionCompat (通知栏播放控制)
    implementation(libs.androidx.media.compat)

    // MultiDex (方法数超限)
    implementation("androidx.multidex:multidex:2.0.1")

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.androidx.room.testing)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.hilt.testing)
    kspAndroidTest(libs.hilt.testing.compiler)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.arch.core.testing)
}

// ==================== JaCoCo 覆盖率报告 ====================
// 用法：./gradlew testDebugUnitTest jacocoTestReport
// 报告输出：app/build/reports/jacoco/jacocoTestReport/html/index.html
// 通过 enableJacoco 属性按需开启：
//   ./gradlew testDebugUnitTest jacocoTestReport -PenableJacoco=true

tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "Generate JaCoCo coverage report"

    dependsOn("testDebugUnitTest")

    val fileFilter = setOf(
        "**/R.class",
        "**/R\$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
        "**/*_MembersInjector.*",
        "**/*_Factory.*",
        "**/Dagger*.*",
        "**/Hilt_*.*",
        "**/*_HiltModules.*",
        "**/di/**",
        "**/databinding/**"
    )

    val debugTree = fileTree("${project.buildDir}/tmp/kotlin-classes/debug") {
        exclude(fileFilter)
    }

    val mainSrc = "${project.projectDir}/src/main/java"

    sourceDirectories.setFrom(files(mainSrc))
    classDirectories.setFrom(files(debugTree))
    executionData.setFrom(
        fileTree("${project.buildDir}") {
            include("jacoco/testDebugUnitTest.exec",
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
        }
    )

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    // 只在 enableJacoco=true 时执行
    onlyIf { project.hasProperty("enableJacoco") }
}
