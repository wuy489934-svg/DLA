# 性能包：保留 JS Bridge、Shizuku 反射与 Agent 运行时，避免 R8 改掉反射目标
-keep class com.example.chatdemo.** { *; }
-keep class rikka.shizuku.** { *; }
-keep class dev.rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*
-dontwarn rikka.shizuku.**
-dontwarn dev.rikka.shizuku.**
