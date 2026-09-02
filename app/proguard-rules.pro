# =============================================================================
# DNS Override —— R8 / ProGuard 规则
# =============================================================================

# ---------- 通用属性：保留注解、签名与行号，保证反序列化与崩溃栈可用 ----------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------- Kotlin ----------
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { public <methods>; }
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**

# ---------- Gson ----------
# 规则组与规则通过 Gson 反射序列化到 SharedPreferences，字段与类必须保留
-keep class com.dnsoverride.app.model.** { <fields>; }
-keepclassmembers class com.dnsoverride.app.model.** { <fields>; }
-keep class com.google.gson.** { *; }
-keepclassmembers class * implements java.io.Serializable { *; }
-dontwarn sun.misc.**
-dontwarn com.google.gson.**

# ---------- OkHttp / Okio（DoH 与订阅拉取）----------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
# HTTP/3 相关依赖本工程未引入，避免警告堆积
-dontwarn okhttp3.internal.platform.**
-dontwarn okhttp3.internal.http2.**

# ---------- Android 组件：由清单反射创建，不可裁剪 ----------
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Application
-keep public class com.dnsoverride.app.MainActivity

# ---------- 自定义 View：XML 中按全限定名反射创建 ----------
-keep class com.dnsoverride.app.ui.stats.DonutChartView { *; }
-keep class com.dnsoverride.app.ui.stats.TrendChartView { *; }

# ---------- WorkManager ----------
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keep class com.dnsoverride.app.service.SubscriptionWorker

# ---------- 序列化数据兜底：避免统计 map 的泛型类型被裁剪后 Gson 行为异常 ----------
-keepclassmembers class com.dnsoverride.app.store.** { *; }
