package io.github.libxposed.api;

import android.app.AppComponentFactory;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import io.github.libxposed.annotation.SinceApi;

import java.util.List;

@SuppressWarnings("unused")
public interface XposedModuleInterface {
    interface ModuleLoadedParam {
        boolean isSystemServer();

        @NonNull
        String getProcessName();
    }

    interface PackageLoadedParam {
        @NonNull
        String getPackageName();

        @NonNull
        ApplicationInfo getApplicationInfo();

        boolean isFirstPackage();

        @RequiresApi(Build.VERSION_CODES.Q)
        @NonNull
        ClassLoader getDefaultClassLoader();
    }

    interface PackageReadyParam extends PackageLoadedParam {
        @NonNull
        ClassLoader getClassLoader();

        @RequiresApi(Build.VERSION_CODES.P)
        @NonNull
        AppComponentFactory getAppComponentFactory();
    }

    interface SystemServerStartingParam {
        @NonNull
        ClassLoader getClassLoader();
    }

    @SinceApi(XposedInterface.API_102)
    interface HotReloadingParam {
        @Nullable
        Bundle getExtras();

        void setSavedInstanceState(@Nullable Object outState);
    }

    @SinceApi(XposedInterface.API_102)
    interface HotReloadedParam extends ModuleLoadedParam {
        @Nullable
        Bundle getExtras();

        @Nullable
        Object getSavedInstanceState();

        @NonNull
        List<XposedInterface.HookHandle> getOldHookHandles();
    }

    default void onModuleLoaded(@NonNull ModuleLoadedParam param) {
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    default void onPackageLoaded(@NonNull PackageLoadedParam param) {
    }

    default void onPackageReady(@NonNull PackageReadyParam param) {
    }

    default void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
    }

    @SinceApi(XposedInterface.API_102)
    default boolean onHotReloading(@NonNull HotReloadingParam param) {
        return false;
    }

    @SinceApi(XposedInterface.API_102)
    default void onHotReloaded(@NonNull HotReloadedParam param) {
        param.getOldHookHandles().forEach(XposedInterface.HookHandle::unhook);
    }
}
