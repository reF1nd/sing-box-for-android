package io.github.libxposed.api;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.libxposed.annotation.InternalApi;
import io.github.libxposed.annotation.SinceApi;

import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

@SuppressWarnings("unused")
public class XposedInterfaceWrapper implements XposedInterface {
    private XposedInterface mBase;
    private Runnable mDetachImpl;

    @InternalApi
    public final void attachFramework(@NonNull XposedInterface base, @NonNull Runnable detachImpl) {
        if (mBase != null) {
            throw new IllegalStateException("Framework already attached");
        }
        mBase = base;
        mDetachImpl = detachImpl;
    }

    private void ensureAttached() {
        if (mBase == null) {
            throw new IllegalStateException("Framework not attached");
        }
    }

    @SinceApi(API_102)
    public final void detach() {
        ensureAttached();
        mDetachImpl.run();
    }

    @Override
    public final int getApiVersion() {
        ensureAttached();
        return XposedInterface.super.getApiVersion();
    }

    @Override
    @NonNull
    public final String getFrameworkName() {
        ensureAttached();
        return mBase.getFrameworkName();
    }

    @Override
    @NonNull
    public final String getFrameworkVersion() {
        ensureAttached();
        return mBase.getFrameworkVersion();
    }

    @Override
    public final long getFrameworkVersionCode() {
        ensureAttached();
        return mBase.getFrameworkVersionCode();
    }

    @Override
    public final long getFrameworkProperties() {
        ensureAttached();
        return mBase.getFrameworkProperties();
    }

    @Override
    @NonNull
    public final HookBuilder hook(@NonNull Executable origin) {
        ensureAttached();
        return mBase.hook(origin);
    }

    @Override
    @NonNull
    public final HookBuilder hookClassInitializer(@NonNull Class<?> origin) {
        ensureAttached();
        return mBase.hookClassInitializer(origin);
    }

    @Override
    public final boolean deoptimize(@NonNull Executable executable) {
        ensureAttached();
        return mBase.deoptimize(executable);
    }

    @Override
    @NonNull
    public final Invoker<?, Method> getInvoker(@NonNull Method method) {
        ensureAttached();
        return mBase.getInvoker(method);
    }

    @Override
    @NonNull
    public final <T> CtorInvoker<T> getInvoker(@NonNull Constructor<T> constructor) {
        ensureAttached();
        return mBase.getInvoker(constructor);
    }

    @Override
    public final void log(int priority, @Nullable String tag, @NonNull String msg) {
        ensureAttached();
        mBase.log(priority, tag, msg);
    }

    @Override
    public final void log(int priority, @Nullable String tag, @NonNull String msg, @Nullable Throwable tr) {
        ensureAttached();
        mBase.log(priority, tag, msg, tr);
    }

    @Override
    @NonNull
    public final ApplicationInfo getModuleApplicationInfo() {
        ensureAttached();
        return mBase.getModuleApplicationInfo();
    }

    @Override
    @NonNull
    public final SharedPreferences getRemotePreferences(@NonNull String group) {
        ensureAttached();
        return mBase.getRemotePreferences(group);
    }

    @Override
    @NonNull
    public final String[] listRemoteFiles() {
        ensureAttached();
        return mBase.listRemoteFiles();
    }

    @Override
    @NonNull
    public final ParcelFileDescriptor openRemoteFile(@NonNull String name) throws FileNotFoundException {
        ensureAttached();
        return mBase.openRemoteFile(name);
    }
}
