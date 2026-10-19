# ONNX Runtime（v1.5 增强离线引擎）
#
# ⚠ 这几条是**必须**的，不是保险起见：ORT 的 native 库（libonnxruntime4j_jni.so）
# 是按 `Java_ai_onnxruntime_OnnxTensor_...` 这种约定名去查 Java 方法的。
# R8 一旦把 `ai.onnxruntime.**` 里的类名/方法名混淆掉，JNI 就找不到符号，
# 运行时报 UnsatisfiedLinkError —— 而且只在真机上、只在第一次加载模型时炸，
# 编译期完全看不出来。所以整体保留。
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
-dontwarn org.slf4j.**

# ML Kit / Play Services：模型加载器与注册表大量使用反射，必须整体保留
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
-dontwarn com.google.mlkit.**

# 保留服务/Activity/Tile 的入口（虽然 manifest 已声明，双保险）
-keep class com.saltin.screentranslator.service.** { *; }
-keep class com.saltin.screentranslator.ui.** { *; }
-keep class com.saltin.screentranslator.ScreenTranslatorApp { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# 工程内数据类（反射/序列化场景保险起见保留）
-keep class com.saltin.screentranslator.ocr.** { *; }
-keep class com.saltin.screentranslator.translate.** { *; }
-keep class com.saltin.screentranslator.overlay.** { *; }
-keep class com.saltin.screentranslator.core.** { *; }
