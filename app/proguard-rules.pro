-keepclassmembers class * extends android.webkit.WebChromeClient {
    public *;
}

-keepclassmembers class * extends android.webkit.WebViewClient {
    public *;
}

-keep class com.nautrix.browser.AdBlockEngine {
    native <methods>;
}

-keep class com.frostwire.jlibtorrent.** { *; }
-keep class com.frostwire.jlibtorrent.swig.libtorrent_jni { *; }
