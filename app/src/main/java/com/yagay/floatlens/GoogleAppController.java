package com.yagay.floatlens;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Root-only lifecycle control for the Google App package used by Circle to Search. */
public final class GoogleAppController {
    public static final String PACKAGE = "com.google.android.googlequicksearchbox";

    public enum State {
        RUNNING,
        STOPPED,
        FROZEN,
        UNKNOWN
    }

    public static final class Result {
        public final boolean success;
        public final State state;
        public final String detail;

        Result(boolean success, State state, String detail) {
            this.success = success;
            this.state = state == null ? State.UNKNOWN : state;
            this.detail = detail == null ? "" : detail;
        }
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-google-root");
        t.setDaemon(true);
        return t;
    });

    private GoogleAppController() {}

    public static void queryAsync(Context context, Consumer<Result> callback) {
        runAsync(context, Operation.QUERY, callback);
    }

    public static void stopAsync(Context context, Consumer<Result> callback) {
        runAsync(context, Operation.STOP, callback);
    }

    public static void freezeAsync(Context context, Consumer<Result> callback) {
        runAsync(context, Operation.FREEZE, callback);
    }

    public static void restoreAsync(Context context, Consumer<Result> callback) {
        runAsync(context, Operation.RESTORE, callback);
    }

    private enum Operation { QUERY, STOP, FREEZE, RESTORE }

    private static void runAsync(Context context, Operation operation, Consumer<Result> callback) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            FloatSettings settings = new FloatSettings(app);
            Result result;
            if (!settings.canUseRoot()) {
                result = new Result(false, State.UNKNOWN,
                        "需要同时开启“增强模式”和“使用 Root 功能”");
            } else {
                result = runRoot(operation);
            }
            DiagnosticLog.i(app, "GOOGLE_APP_ROOT",
                    "op=" + operation.name().toLowerCase()
                            + " success=" + result.success
                            + " state=" + result.state
                            + " detail=" + safe(result.detail, 900));
            if (callback != null) {
                Result delivered = result;
                app.getMainExecutor().execute(() -> callback.accept(delivered));
            }
        });
    }

    private static Result runRoot(Operation operation) {
        String body = switch (operation) {
            case QUERY -> "";
            case STOP -> stopBody();
            case FREEZE -> freezeBody();
            case RESTORE -> restoreBody();
        };

        String script = shellPrelude() + body + statusBody();
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(operation == Operation.QUERY ? 5 : 10,
                    TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Result(false, State.UNKNOWN, "Root 命令超时");
            }

            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && out.length() < 4096) {
                    if (out.length() > 0) out.append('\n');
                    out.append(line);
                }
            }

            String detail = out.toString().trim();
            State state = parseState(detail);
            boolean success = process.exitValue() == 0 && state != State.UNKNOWN;
            if (detail.isBlank()) detail = "su exit=" + process.exitValue();
            return new Result(success, state, detail);
        } catch (Throwable t) {
            String message = t.getMessage();
            return new Result(false, State.UNKNOWN,
                    message == null || message.isBlank()
                            ? t.getClass().getSimpleName() : message);
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String shellPrelude() {
        return "PKG='" + PACKAGE + "'; "
                + "USER_ID=$(am get-current-user 2>/dev/null | tr -dc '0-9'); "
                + "[ -n \"$USER_ID\" ] || USER_ID=0; ";
    }

    private static String stopBody() {
        return "am force-stop --user \"$USER_ID\" \"$PKG\" >/dev/null 2>&1; "
                + "for PID in $(ps -A -o PID,NAME 2>/dev/null "
                + "| awk '$2 ~ /^com\\.google\\.android\\.googlequicksearchbox(:|$)/ {print $1}'); "
                + "do kill -9 \"$PID\" >/dev/null 2>&1 || true; done; "
                + "am force-stop --user \"$USER_ID\" \"$PKG\" >/dev/null 2>&1; ";
    }

    private static String freezeBody() {
        return stopBody()
                + "pm disable-user --user \"$USER_ID\" \"$PKG\" >/dev/null 2>&1; "
                + "am force-stop --user \"$USER_ID\" \"$PKG\" >/dev/null 2>&1; ";
    }

    private static String restoreBody() {
        return "pm enable --user \"$USER_ID\" \"$PKG\" >/dev/null 2>&1 || "
                + "pm enable \"$PKG\" >/dev/null 2>&1; "
                + "am start --user \"$USER_ID\" -a android.intent.action.MAIN "
                + "-c android.intent.category.LAUNCHER -p \"$PKG\" >/dev/null 2>&1 || true; ";
    }

    private static String statusBody() {
        return "DISABLED=0; "
                + "pm list packages -d --user \"$USER_ID\" 2>/dev/null "
                + "| grep -qx \"package:$PKG\" && DISABLED=1; "
                + "RUNNING=0; "
                + "ps -A -o NAME 2>/dev/null "
                + "| grep -Eq '^com\\.google\\.android\\.googlequicksearchbox(:|$)' "
                + "&& RUNNING=1; "
                + "STOPPED=0; "
                + "dumpsys package \"$PKG\" 2>/dev/null "
                + "| grep -m1 -q 'stopped=true' && STOPPED=1; "
                + "echo \"FLOATLENS_GOOGLE_STATE user=$USER_ID disabled=$DISABLED "
                + "running=$RUNNING stopped=$STOPPED\"; ";
    }

    static State parseState(String detail) {
        if (detail == null || detail.isBlank()) return State.UNKNOWN;
        String line = "";
        for (String candidate : detail.split("\\R")) {
            if (candidate.contains("FLOATLENS_GOOGLE_STATE")) line = candidate;
        }
        if (line.isBlank()) return State.UNKNOWN;
        if (line.contains("disabled=1")) return State.FROZEN;
        if (line.contains("running=1")) return State.RUNNING;
        if (line.contains("stopped=1")) return State.STOPPED;
        // A package can be enabled and temporarily not running without the package-manager
        // stopped bit being visible on every Android build. Treat that as stopped/idle.
        if (line.contains("disabled=0") && line.contains("running=0")) return State.STOPPED;
        return State.UNKNOWN;
    }

    public static String stateLabel(State state) {
        if (state == null) return "未知";
        return switch (state) {
            case RUNNING -> "正在运行";
            case STOPPED -> "已停止";
            case FROZEN -> "已冻结";
            case UNKNOWN -> "未知";
        };
    }

    private static String safe(String value, int max) {
        if (value == null) return "";
        String out = value.replace("\u0000", "?");
        return out.length() <= max ? out : out.substring(0, max);
    }
}
