package io.github.libxposed.api.error;

public class HookFailedError extends XposedFrameworkError {
    public HookFailedError() {
        super();
    }

    public HookFailedError(String message) {
        super(message);
    }

    public HookFailedError(String message, Throwable cause) {
        super(message, cause);
    }

    public HookFailedError(Throwable cause) {
        super(cause);
    }
}
