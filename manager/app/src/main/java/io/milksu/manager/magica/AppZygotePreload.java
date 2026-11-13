package io.milksu.manager.magica;

import android.app.ZygotePreload;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import androidx.annotation.NonNull;

import io.milksu.manager.NativeLibraryLoader;
import io.milksu.manager.data.system.ManagerNativeLibraries;
import java.io.File;

public class AppZygotePreload implements ZygotePreload {
    public static final String TAG = "KernelSUMagica";

    private static native void forkDontCareAndExecKsud(String ksudPath, String packageName);

    @Override
    public void doPreload(@NonNull ApplicationInfo appInfo) {
        try {
            File f = NativeLibraryLoader.file(appInfo, ManagerNativeLibraries.DAEMON);
            // A missing ABI must not leave a broken child in the app zygote.
            if (!f.isFile()) {
                Log.w(TAG, "no ksud daemon for this ABI: " + f.getAbsolutePath());
                return;
            }
            NativeLibraryLoader.loadBridge(appInfo);
            Log.d(TAG, "executing magica ...");
            forkDontCareAndExecKsud(f.getAbsolutePath(), appInfo.packageName);
        } catch (Throwable t) {
            Log.e(TAG, "failed to late load", t);
        }
    }
}
