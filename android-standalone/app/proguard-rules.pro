# libtorrent4j usa JNI: no ofusques sus clases nativas
-keep class org.libtorrent4j.** { *; }
-keep class com.frostwire.** { *; }
-dontwarn org.libtorrent4j.**
-dontwarn fi.iki.elonen.**
