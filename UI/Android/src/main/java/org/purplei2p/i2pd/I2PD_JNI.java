/*
 * Copyright (c) 2013-2024, The PurpleI2P Project
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Vendored verbatim from PurpleI2P/i2pd-android so the bundled libi2pd.so
 * (src/main/jniLibs/arm64-v8a/libi2pd.so) resolves its JNI symbols, which are
 * mangled against this exact package + class name. Do not rename or move.
 */
package org.purplei2p.i2pd;

public class I2PD_JNI {
    public static native String getABICompiledWith();

    public static void loadLibraries() {
        System.loadLibrary("i2pd");
    }

    /**
     * returns error info if failed
     * returns "ok" if daemon initialized and started okay
     */
    public static native String startDaemon();
    public static native void stopDaemon();

    public static native void startAcceptingTunnels();
    public static native void stopAcceptingTunnels();
    public static native void reloadTunnelsConfigs();

    public static native void setDataDir(String jdataDir);
    public static native void setLanguage(String jlanguage);

    public static native int getTransitTunnelsCount();
    public static native String getWebConsAddr();
    public static native String getDataDir();

    public static native boolean getHTTPProxyState();
    public static native boolean getSOCKSProxyState();
    public static native boolean getBOBState();
    public static native boolean getSAMState();
    public static native boolean getI2CPState();

    public static native void onNetworkStateChanged(boolean isConnected);
}
