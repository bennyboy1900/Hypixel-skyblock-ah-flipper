package dev.skytrade.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Structured logging: {@code Log.info("bazaar.fetch", "products", 2207, "ageMs", 1200)} produces
 * {@code bazaar.fetch products=2207 ageMs=1200}. Values containing secrets must never be passed.
 */
public final class Log {
    private static final Logger LOGGER = LoggerFactory.getLogger("SkyTrade");
    private static volatile Consumer<String[]> errorSink;

    private Log() {}

    /** Optional sink for persisting warnings/errors (wired to the database error log). */
    public static void setErrorSink(Consumer<String[]> sink) {
        errorSink = sink;
    }

    public static void info(String event, Object... kv) {
        LOGGER.info(format(event, kv));
    }

    public static void debug(String event, Object... kv) {
        if (LOGGER.isDebugEnabled()) LOGGER.debug(format(event, kv));
    }

    public static void warn(String event, Object... kv) {
        String msg = format(event, kv);
        LOGGER.warn(msg);
        sink("WARN", event, msg);
    }

    public static void error(String event, Throwable t, Object... kv) {
        String msg = format(event, kv) + (t != null ? " error=" + t : "");
        LOGGER.error(msg, t);
        sink("ERROR", event, msg);
    }

    private static void sink(String level, String event, String msg) {
        Consumer<String[]> s = errorSink;
        if (s != null) {
            try {
                s.accept(new String[]{level, event, msg});
            } catch (RuntimeException ignored) {
                // never let logging break callers
            }
        }
    }

    static String format(String event, Object... kv) {
        StringBuilder sb = new StringBuilder(event);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append(' ').append(kv[i]).append('=');
            String v = String.valueOf(kv[i + 1]);
            if (v.indexOf(' ') >= 0) sb.append('"').append(v).append('"');
            else sb.append(v);
        }
        return sb.toString();
    }
}
