plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

tasks.withType<JavaCompile> {
    options.encoding = "utf-8"
}

android {
    namespace = "top.stevezmt.calsync"
    compileSdk = 35

    defaultConfig {
        applicationId = "top.stevezmt.calsync"
        minSdk = 23
        targetSdk = 35
        versionCode = 100
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                val requestedAbis = project.findProperty("abiFilter")?.toString()?.split(",")
                if (requestedAbis != null) {
                    abiFilters.addAll(requestedAbis)
                } else {
                    abiFilters.addAll(listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86", "riscv64"))
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions.add("version")
    productFlavors {
        create("foss") {
            dimension = "version"
            versionNameSuffix = "-foss"
        }
        create("full") {
            dimension = "version"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64", "armeabi-v7a", "x86", "riscv64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    val llamaCmake = file("../third_party/llama.cpp/CMakeLists.txt")
    if (llamaCmake.exists()) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // 针对 AGP 8.x 的 KTS 脚本，使用修复后的 applicationVariants 逻辑
    // 这种方式在处理 APK 重命名和版本号偏移时最稳定，避免了新 API 接口解析失败的问题
    @Suppress("DEPRECATION")
    applicationVariants.all {
        val variant = this
        val baseVersionCode = variant.versionCode
        
        // 设置 BuildConfig 中的 VERSION_CODE
        variant.buildConfigField("int", "VERSION_CODE", "${baseVersionCode}")
        
        variant.outputs.all {
            val output = this
            if (output is com.android.build.gradle.api.ApkVariantOutput) {
                // 修复：使用 VariantOutput.FilterType.ABI 枚举代替 String，解决类型不匹配错误
                val abi = output.getFilter(com.android.build.VariantOutput.FilterType.ABI)
                
                val abiSuffix = when (abi) {
                    "armeabi-v7a" -> 1
                    "arm64-v8a" -> 2
                    "x86" -> 3
                    "x86_64" -> 4
                    "riscv64" -> 5
                    else -> 0
                }
                
                val apkVersionCode = baseVersionCode * 10 + abiSuffix
                output.versionCodeOverride = apkVersionCode
                val abiName = abi ?: "universal"
                output.outputFileName = "calsync-${variant.versionName}-vc${apkVersionCode}-${abiName}.apk"
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.jieba)
    implementation(libs.xk.time)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)
    "fullImplementation"(libs.mlkit.entity.extraction)
    
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
