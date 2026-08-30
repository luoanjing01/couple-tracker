plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.coupletracker.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.coupletracker.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // ====== 服务器地址配置 ======
        // 真机默认：开发电脑的局域网 IP（手机和电脑需在同一 Wi-Fi）
        // 模拟器覆盖：在 gradle.properties 里设 CUSTOM_API_BASE=http://10.0.2.2:3001
        val apiBase = project.properties["CUSTOM_API_BASE"] as? String
            ?: "http://192.168.101.7:3001"
        val webBase = project.properties["CUSTOM_WEB_BASE"] as? String
            ?: "http://192.168.101.7:5173"
        buildConfigField("String", "API_BASE", "\"$apiBase\"")
        buildConfigField("String", "WEB_BASE", "\"$webBase\"")
        // APP 内置的默认服务器地址，用户可在设置页修改（存 DataStore）
        buildConfigField("String", "DEFAULT_API_BASE", "\"$apiBase\"")
        buildConfigField("String", "DEFAULT_WEB_BASE", "\"$webBase\"")

        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // debug版允许明文HTTP（访问10.0.2.2:3001）
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    // ===== AndroidX + Compose + Material3 =====
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // ===== 定位 - Google Fused Location Provider =====
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // ===== 网络：OkHttp + Retrofit + Socket.IO =====
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("io.socket:socket.io-client:2.1.0")

    // ===== 协程 =====
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // ===== 持久化（token存储） =====
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // ===== 图片加载 =====
    implementation("io.coil-kt:coil-compose:2.5.0")

    // ===== 工具 =====
    implementation("com.google.code.gson:gson:2.10.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
