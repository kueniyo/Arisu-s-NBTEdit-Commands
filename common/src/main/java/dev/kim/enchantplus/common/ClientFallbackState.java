package dev.kim.enchantplus.common;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Tracks whether the connected server has this mod, before enabling local command fallback. */
public final class ClientFallbackState {
    private static final AtomicLong SESSION = new AtomicLong();
    private static volatile boolean serverHasMod;
    private static volatile boolean fallbackEnabled;
    private static volatile Runnable stateChangeListener = () -> { };

    private ClientFallbackState() {
    }

    /** Installs a loader-specific callback used to refresh client command suggestions. */
    public static void setStateChangeListener(Runnable listener) {
        stateChangeListener = listener == null ? () -> { } : listener;
    }

    private static void notifyStateChanged() {
        try {
            stateChangeListener.run();
        } catch (RuntimeException ignored) {
            // A command dispatcher may not exist yet (for example, while joining a server).
        }
    }

    public static void beginConnection() {
        long session = SESSION.incrementAndGet();
        serverHasMod = false;
        fallbackEnabled = false;
        notifyStateChanged();

        // Give the server time to announce itself through the optional marker payload.
        CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS).execute(() -> {
            if (SESSION.get() == session && !serverHasMod) {
                fallbackEnabled = true;
                notifyStateChanged();
            }
        });
    }

    public static void markServerPresent() {
        serverHasMod = true;
        fallbackEnabled = false;
        notifyStateChanged();
    }

    public static void disconnect() {
        SESSION.incrementAndGet();
        serverHasMod = false;
        fallbackEnabled = false;
        notifyStateChanged();
    }

    public static boolean isFallbackEnabled() {
        return fallbackEnabled && !serverHasMod;
    }
}
