# Requirement BLD-008: only the JNI surface is kept by name.
# Native code registers these methods by name (RegisterNatives) and calls these callbacks by name.
-keep class io.github.junkers4.ladybird.engine.NativeEngine { native <methods>; }
-keep class io.github.junkers4.ladybird.engine.NativeCallbacks {
    public static void onViewEvent(long, int, java.lang.String, java.lang.String, long, long);
    public static void onEngineEvent(int, java.lang.String);
    public static java.lang.String getClipboardText();
    public static void setClipboardText(java.lang.String);
}
