import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.personal.tv"
    compileSdk = 35

    defaultConfig {
        // 安装用的包名（沿用早期的 piandan，改了就不能覆盖升级）；代码里的 namespace 是 dev.personal.tv
        applicationId = "com.sstudio.piandan"
        minSdk = 23
        targetSdk = 35
        versionCode = 13
        versionName = "0.7.6"
        resValue("string", "app_name", "SStudioTV")
    }

    // 自己的签名：signing/keystore.properties + keystore 文件（务必备份，丢了只能卸载重装；不进 git）
    val signProps = rootProject.file("signing/keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }
    signingConfigs {
        if (signProps != null) create("release") {
            storeFile = rootProject.file("signing/" + signProps.getProperty("storeFile"))
            storePassword = signProps.getProperty("storePassword")
            keyAlias = signProps.getProperty("keyAlias")
            keyPassword = signProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        // debug 也用同一把签名：电视上 debug / release 可以互相覆盖安装
        debug {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        release {
            // 插件直接调用宿主的 okhttp3 / gson，不能混淆
            isMinifyEnabled = false
            // 没配自己的签名（比如刚 clone 下来）就用 debug 签名，出的包至少能装
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
    }
    buildFeatures {
        compose = true
        aidl = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.tv.material)
    debugImplementation(libs.compose.ui.tooling)

    // 片源：插件（spider jar）直接调用宿主的 okhttp3 / gson，类名不能混淆
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core) // 片源页的二维码
    implementation(libs.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.okhttp)
    implementation(libs.coil.compose)
    testImplementation("junit:junit:4.13.2")
}
