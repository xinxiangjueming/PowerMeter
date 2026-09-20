plugins {
    // AGP 9.0 起内置 Kotlin 支持，不再需要 org.jetbrains.kotlin.android 插件
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.chen.powermeter"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.chen.powermeter"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Shizuku UserService 走 AIDL（见 src/main/aidl/.../IShellService.aidl），
        // AGP 需显式开启 aidl 编译开关（口径对齐 fold app/build.gradle.kts:45-48）
        aidl = true
    }

    buildTypes {
        release {
            // R8：代码压缩 + 混淆 + 优化
            isMinifyEnabled = true
            // 资源缩减（依赖 isMinifyEnabled，单独开启无效）
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.graphics.shapes)

    // 顶栏三档模糊：Haze（API 31–32 真实模糊）+ com.kyant.backdrop（API≥33 渐进式 AGSL 模糊）
    implementation(libs.dev.chrisbanes.haze)
    implementation(libs.kyant.backdrop) {
        // backdrop 2.x 是 Compose Multiplatform 库，默认传递 org.jetbrains.compose.*:1.12.0；
        // 本项目是纯 androidx Compose，必须排除掉这些 CMP 产物，改用项目自带的
        // androidx.compose.*（两者包名一致 androidx.compose.*，排除后可正常编译，
        // 也避免出包时的 duplicate class 冲突）
        exclude(group = "org.jetbrains.compose.foundation")
        exclude(group = "org.jetbrains.compose.ui")
    }

    // Miuix UI：曲线颜色色盘（top.yukonga.miuix.kmp.basic.ColorPalette）。
    // 版本与 SportLink 完全一致，视觉口径同源。其 AAR minSdk=23 < 本项目 30，
    // 无需 tools:overrideLibrary（区别于 miuix-blur，那个才是 33）。
    // 若日后解析出 org.jetbrains.compose.* 传递依赖导致 duplicate class，
    // 按上方 backdrop 的写法加同样的 exclude 即可（当前与 SportLink 实测配置一致，未加）。
    implementation(libs.yukonga.miuix.ui)

    // Shizuku：非 root 机器通过 adb（无线调试）授权，以 shell 身份读取 /sys 电量节点。
    // 版本与 fold 完全一致（13.1.5）。api = 宿主侧 SDK；provider = 声明 ShizukuProvider
    // 自动初始化 binder 连接。UserService（ShellService）由 Shizuku 反射加载，无需在 Manifest 注册。
    implementation(libs.dev.rikka.shizuku.api)
    implementation(libs.dev.rikka.shizuku.provider)
}
