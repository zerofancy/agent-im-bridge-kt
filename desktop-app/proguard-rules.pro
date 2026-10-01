# OkHttp shares its platform detection code with Android. These APIs are never
# selected by this desktop app, which uses the bundled JDK's TLS provider.
-dontwarn android.os.Build**
-dontwarn android.security.NetworkSecurityPolicy
-dontwarn android.net.ssl.SSLSockets
-dontwarn android.net.http.X509TrustManagerExtensions
-dontwarn android.util.Log

# Optional TLS providers probed by OkHttp; none is bundled with this app.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**

# ProGuard 7.7 specializes Okio's buffer(Source) return type without updating
# its BufferedSource cast, producing VerifyError on the first HTTP connection.
# Keep shrinking enabled, but do not optimize Okio classes or their members.
-keep,allowshrinking,allowobfuscation class okio.** { *; }
