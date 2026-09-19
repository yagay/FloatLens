package com.yagay.floatlens;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One bounded, timeout-safe implementation for every FloatLens su command. */
final class RootCommandExecutor {
    static final class Result {
        final int exitCode;
        final byte[] stdout;
        final String stderr;
        final Throwable error;
        final boolean timedOut;

        Result(int exitCode, byte[] stdout, String stderr, Throwable error, boolean timedOut) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? new byte[0] : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.error = error;
            this.timedOut = timedOut;
        }

        boolean success() { return !timedOut && error == null && exitCode == 0; }

        String text() {
            return new String(stdout, StandardCharsets.UTF_8).trim();
        }

        String failureMessage(String timeoutMessage) {
            if (timedOut) return timeoutMessage == null ? "Root 命令超时" : timeoutMessage;
            if (error != null) {
                String message = error.getMessage();
                return message == null || message.isBlank()
                        ? error.getClass().getSimpleName() : message;
            }
            if (!stderr.isBlank()) return stderr.trim();
            return "su exit=" + exitCode;
        }
    }

    static Result runText(String command, long timeoutSeconds, int maxOutputBytes) {
        return run(command, timeoutSeconds, Math.max(1024, maxOutputBytes), 16 * 1024, true);
    }

    static Result runBinary(String command, long timeoutSeconds, int maxOutputBytes) {
        return run(command, timeoutSeconds, Math.max(1024, maxOutputBytes), 16 * 1024, false);
    }

    private static Result run(String command, long timeoutSeconds,
                              int maxStdoutBytes, int maxStderrBytes,
                              boolean mergeError) {
        Process process = null;
        Thread stdoutReader = null;
        Thread stderrReader = null;
        try {
            ProcessBuilder builder = new ProcessBuilder("su", "-c", command == null ? "" : command);
            builder.redirectErrorStream(mergeError);
            process = builder.start();
            Process active = process;

            ByteArrayOutputStream stdout = new ByteArrayOutputStream(
                    Math.min(maxStdoutBytes, 2 * 1024 * 1024));
            ByteArrayOutputStream stderr = new ByteArrayOutputStream(
                    Math.min(maxStderrBytes, 16 * 1024));
            AtomicReference<Throwable> readFailure = new AtomicReference<>();

            stdoutReader = readerThread("FloatLens-root-stdout", active.getInputStream(),
                    stdout, maxStdoutBytes, readFailure);
            stdoutReader.start();
            if (!mergeError) {
                stderrReader = readerThread("FloatLens-root-stderr", active.getErrorStream(),
                        stderr, maxStderrBytes, readFailure);
                stderrReader.start();
            }

            boolean finished = active.waitFor(Math.max(1L, timeoutSeconds), TimeUnit.SECONDS);
            if (!finished) {
                active.destroy();
                if (active.isAlive()) active.destroyForcibly();
                joinQuietly(stdoutReader, 400L);
                joinQuietly(stderrReader, 400L);
                return new Result(-1, stdout.toByteArray(),
                        new String(stderr.toByteArray(), StandardCharsets.UTF_8),
                        null, true);
            }

            joinQuietly(stdoutReader, 1500L);
            joinQuietly(stderrReader, 1500L);
            Throwable streamError = readFailure.get();
            if (streamError != null) {
                return new Result(active.exitValue(), stdout.toByteArray(),
                        new String(stderr.toByteArray(), StandardCharsets.UTF_8),
                        streamError, false);
            }
            return new Result(active.exitValue(), stdout.toByteArray(),
                    new String(stderr.toByteArray(), StandardCharsets.UTF_8),
                    null, false);
        } catch (Throwable t) {
            return new Result(-1, new byte[0], "", t, false);
        } finally {
            if (process != null) {
                if (process.isAlive()) {
                    try { process.destroyForcibly(); } catch (Throwable ignored) { }
                } else {
                    try { process.destroy(); } catch (Throwable ignored) { }
                }
            }
            interruptQuietly(stdoutReader);
            interruptQuietly(stderrReader);
        }
    }

    private static Thread readerThread(String name, InputStream input,
                                       ByteArrayOutputStream output, int maxBytes,
                                       AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try (InputStream in = input) {
                byte[] buffer = new byte[64 * 1024];
                int stored = 0;
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (n == 0) continue;
                    int remaining = Math.max(0, maxBytes - stored);
                    if (remaining > 0) {
                        int keep = Math.min(remaining, n);
                        output.write(buffer, 0, keep);
                        stored += keep;
                    }
                    if (stored >= maxBytes) {
                        // Continue draining the pipe so the child cannot block, but keep memory bounded.
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void joinQuietly(Thread thread, long ms) {
        if (thread == null) return;
        try {
            thread.join(ms);
            if (thread.isAlive()) {
                thread.interrupt();
                throw new IllegalStateException("Root command stream read timeout");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void interruptQuietly(Thread thread) {
        if (thread != null && thread.isAlive()) {
            try { thread.interrupt(); } catch (Throwable ignored) { }
        }
    }

    private RootCommandExecutor() {}
}
