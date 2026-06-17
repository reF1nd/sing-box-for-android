package io.github.libxposed.api;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.libxposed.annotation.SinceApi;

import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

@SuppressWarnings("unused")
public interface XposedInterface {
    int API_101 = 101;
    int API_102 = 102;
    int LIB_API = API_102;

    long PROP_CAP_SYSTEM = 1L;
    long PROP_CAP_REMOTE = 1L << 1;
    long PROP_RT_API_PROTECTION = 1L << 2;

    int PRIORITY_DEFAULT = 50;
    int PRIORITY_LOWEST = Integer.MIN_VALUE;
    int PRIORITY_HIGHEST = Integer.MAX_VALUE;

    interface Invoker<T extends Invoker<T, U>, U extends Executable> {
        sealed interface Type permits Type.Origin, Type.Chain {
            Origin ORIGIN = new Origin();

            record Origin() implements Type {
            }

            record Chain(int maxPriority) implements Type {
                public static final Chain FULL = new Chain(PRIORITY_HIGHEST);
            }
        }

        T setType(@NonNull Type type);

        Object invoke(Object thisObject, Object... args)
                throws InvocationTargetException, IllegalArgumentException, IllegalAccessException;

        Object invokeSpecial(@NonNull Object thisObject, Object... args)
                throws InvocationTargetException, IllegalArgumentException, IllegalAccessException;
    }

    interface CtorInvoker<T> extends Invoker<CtorInvoker<T>, Constructor<T>> {
        @NonNull
        T newInstance(Object... args)
                throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException;

        @NonNull
        <U> U newInstanceSpecial(@NonNull Class<U> subClass, Object... args)
                throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException;
    }

    interface Chain {
        @NonNull
        Executable getExecutable();

        Object getThisObject();

        @NonNull
        List<Object> getArgs();

        Object getArg(int index) throws IndexOutOfBoundsException, ClassCastException;

        Object proceed() throws Throwable;

        Object proceed(@NonNull Object[] args) throws Throwable;

        Object proceedWith(@NonNull Object thisObject) throws Throwable;

        Object proceedWith(@NonNull Object thisObject, @NonNull Object[] args) throws Throwable;
    }

    interface Hooker {
        Object intercept(@NonNull Chain chain) throws Throwable;
    }

    interface HookHandle {
        @NonNull
        Executable getExecutable();

        void unhook();

        @SinceApi(API_102)
        @Nullable
        String getId();

        @SinceApi(API_102)
        @NonNull
        HookHandle replaceHook(@NonNull Hooker hooker);
    }

    enum ExceptionMode {
        DEFAULT,
        PROTECTIVE,
        PASSTHROUGH,
    }

    interface HookBuilder {
        HookBuilder setPriority(int priority);

        HookBuilder setExceptionMode(@NonNull ExceptionMode mode);

        @NonNull
        HookHandle intercept(@NonNull Hooker hooker);

        @SinceApi(API_102)
        HookBuilder setId(@Nullable String id);
    }

    default int getApiVersion() {
        return LIB_API;
    }

    @NonNull
    String getFrameworkName();

    @NonNull
    String getFrameworkVersion();

    long getFrameworkVersionCode();

    long getFrameworkProperties();

    @NonNull
    HookBuilder hook(@NonNull Executable origin);

    @NonNull
    HookBuilder hookClassInitializer(@NonNull Class<?> origin);

    boolean deoptimize(@NonNull Executable executable);

    @NonNull
    Invoker<?, Method> getInvoker(@NonNull Method method);

    @NonNull
    <T> CtorInvoker<T> getInvoker(@NonNull Constructor<T> constructor);

    void log(int priority, @Nullable String tag, @NonNull String msg);

    void log(int priority, @Nullable String tag, @NonNull String msg, @Nullable Throwable tr);

    @NonNull
    ApplicationInfo getModuleApplicationInfo();

    @NonNull
    SharedPreferences getRemotePreferences(@NonNull String group);

    @NonNull
    String[] listRemoteFiles();

    @NonNull
    ParcelFileDescriptor openRemoteFile(@NonNull String name) throws FileNotFoundException;
}
