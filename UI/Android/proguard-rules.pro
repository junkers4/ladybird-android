# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep class org.libsdl.app.** {
	*;
}

-keep class org.serenityos.ladybird.LadybirdActivity {
	*;
}

-keep class org.serenityos.ladybird.SettingsActivity {
	*;
}

-keep class org.serenityos.ladybird.WebViewImplementation {
	void bindWebContentService(int);
	void bindRequestServerForWorker(int);
	void bindImageDecoderForWorker(int);
	void bindWebWorkerService(int);
	void invalidateLayout();
	void onLoadStart(java.lang.String, boolean);
	void onLoadFinish(java.lang.String);
	void onTitleChange(java.lang.String);
	void onUrlChange(java.lang.String);
	void onFindInPage(int, int);
	void onLinkHover(java.lang.String);
}

-keep class org.serenityos.ladybird.WebContentService {
	void bindRequestServer(int);
	void bindImageDecoder(int);
	void bindWebWorker(int);
}

-keep class org.serenityos.ladybird.WebWorkerService { *; }
-keep class org.serenityos.ladybird.RequestServerService { *; }
-keep class org.serenityos.ladybird.ImageDecoderService { *; }

-keep class org.serenityos.ladybird.TimerExecutorService {
	long registerTimer(org.serenityos.ladybird.TimerExecutorService$Timer, boolean, long);
	void unregisterTimer(long);
}

-keep class org.serenityos.ladybird.TimerExecutorService$Timer {
	<init>(long);
}

# --- Tor (Guardian Project) ---
# libtor.so reaches back into TorService via JNI by name (e.g. the native
# methods and the `torConfiguration` field used by mainConfigurationFree).
# R8 must not rename or strip any of it, or the native lookups throw
# NoSuchFieldError / UnsatisfiedLinkError at runtime in release builds.
-keep class org.torproject.jni.** { *; }
-keepclasseswithmembernames class org.torproject.jni.** {
	native <methods>;
}
# jtorctl control connection used by TorService.
-keep class net.freehaven.tor.control.** { *; }

# --- I2P (embedded i2pd) ---
# libi2pd.so resolves its JNI entry points against this exact class + native
# method names (Java_org_purplei2p_i2pd_I2PD_1JNI_*); R8 must not rename or
# strip them, or System.loadLibrary/startDaemon will crash.
-keep class org.purplei2p.i2pd.** { *; }
-keepclasseswithmembernames class org.purplei2p.i2pd.** {
    native <methods>;
}
