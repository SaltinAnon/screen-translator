import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val defaultLlmKey: String = (project.findProperty("llmApiKey") as String?)
    ?: (project.findProperty("geminiApiKey") as String?)
    ?: ""
val keyAliasValue: String = (project.findProperty("st.keyAlias") as String?) ?: "screentranslator"
val storePassValue: String = (project.findProperty("st.keyStorePass") as String?) ?: "st2026key"
val keyPassValue: String = (project.findProperty("st.keyPass") as String?) ?: storePassValue
val releaseKeystore = rootProject.file("keystore/screentranslator.jks")

android {
    namespace = "com.saltin.screentranslator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.saltin.screentranslator"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "1.0.0"
        resourceConfigurations += listOf("zh", "en")
        // 编译期可注入一个默认 Key（gradle.properties 里的 llmApiKey），
        // 留空也不影响：用户可以在 App 设置页里自己填，或改用端侧离线模式。
        buildConfigField("String", "DEFAULT_LLM_API_KEY", "\"$defaultLlmKey\"")
    }

    signingConfigs {
        if (releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = storePassValue
                keyAlias = keyAliasValue
                keyPassword = keyPassValue
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    // ML Kit 的原生库占了绝大部分体积：只为真实手机架构出包，
    // 去掉模拟器用的 x86 / x86_64，APK 体积直接砍掉一半以上。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
            )
        }
    }

    androidResources {
        noCompress += listOf("tflite", "binarypb")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            // 自写分词器要在 JVM 上跑回归（对比官方 tokenizers 库的标准答案），
            // 而它引用了 android.util.Log。让 Log 的方法返回默认值而不是抛
            // "not mocked" 异常，这样纯逻辑类就不必为了测试去引 Robolectric。
            isReturnDefaultValues = true

            all {
                // ⚠ 这两个编码参数是必需的，不是「保险起见」：
                // 工程路径里有中文（C:\Users\闫涵\...），而测试 worker 是**独立 JVM**，
                // 不会继承 org.gradle.jvmargs 里的 -Dfile.encoding=UTF-8。
                // 少了它，worker 的 classpath 会被按系统默认编码（中文 Windows 上是 GBK）
                // 解析，结果是启动就报
                // "ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain"
                // —— 看起来像依赖坏了，其实是路径编码坏了。
                it.jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8", "-Dstdout.encoding=UTF-8")

                // NLLB 的 tokenizer.json 有 16.5MB：一个 25.6 万条的词表字典 + 51.4 万条
                // merge 规则，默认 512MB 的测试 worker 堆不够。同时这个数字本身也是
                // **真机内存需求的实测参照** —— Android 单应用堆上限通常 256~512MB，
                // 所以这里测出来的峰值必须远小于它。
                it.maxHeapSize = "2g"

                it.testLogging {
                    events("passed", "failed", "skipped", "standardOut")
                    showStandardStreams = true
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.okhttp)

    // 阶段二：ML Kit Text Recognition v2（按文字系统分包，只装需要的）
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.mlkit.text.recognition.japanese)
    implementation(libs.mlkit.text.recognition.korean)

    // 阶段三：端侧离线翻译
    implementation(libs.mlkit.translate)

    // 阶段三（v1.5）：增强离线翻译引擎（Opus-MT / NLLB-200）
    // ONNX Runtime 的 native 库按 ABI 分包，配合上面的 splits 只打 arm64 / armv7
    implementation(libs.onnxruntime.android)

    // JVM 单测：核对自写分词器与官方 tokenizers 库的输出是否逐 token 一致
    testImplementation(libs.junit)
}
