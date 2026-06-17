package io.github.libxposed.api.error;

public class XposedFrameworkError extends Error {
    public XposedFrameworkError() {
        super();
    }

    public XposedFrameworkError(String message) {
        super(message);
    }

    public XposedFrameworkError(String message, Throwable cause) {
        super(message, cause);
    }

    public XposedFrameworkError(Throwable cause) {
        super(cause);
    }
}
