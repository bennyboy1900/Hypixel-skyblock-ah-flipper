package dev.skytrade.database;

import dev.skytrade.util.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.concurrent.*;

/**
 * SQLite access. A single connection is owned by one dedicated thread; every query and write is
 * submitted to it, which serialises access without locks and keeps I/O off the render thread.
 */
public final class Database implements AutoCloseable {
    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private static final int SCHEMA_VERSION = 2;
    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SkyTrade-DB");
        t.setDaemon(true);
        return t;
    });
    private final Path file;
    private Connection conn;
    private volatile boolean available;

    public Database(Path file) {
        this.file = file;
    }

    public boolean available() {
        return available;
    }

    public void open() {
        try {
            thread.submit(() -> {
                Files.createDirectories(file.getParent());
                Class.forName("org.sqlite.JDBC");
                conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
                try (Statement s = conn.createStatement()) {
                    s.execute("PRAGMA journal_mode=WAL");
                    s.execute("PRAGMA synchronous=NORMAL");
                    s.execute("PRAGMA foreign_keys=ON");
                    s.execute("PRAGMA busy_timeout=5000");
                }
                migrate();
                available = true;
                return null;
            }).get(30, TimeUnit.SECONDS);
            Log.info("db.opened", "path", file);
        } catch (Exception e) {
            available = false;
            Log.error("db.open_failed", e, "path", file);
        }
    }

    private void migrate() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)");
            int version = 0;
            try (ResultSet rs = s.executeQuery("SELECT value FROM meta WHERE key='schema'")) {
                if (rs.next()) version = Integer.parseInt(rs.getString(1));
            }
            if (version < 1) {
                s.execute("""
                        CREATE TABLE IF NOT EXISTS market_snapshots (
                          ts INTEGER NOT NULL, kind TEXT NOT NULL, last_updated INTEGER NOT NULL, items INTEGER,
                          complete INTEGER, total_bid_depth REAL, total_ask_depth REAL, PRIMARY KEY (kind, last_updated))""");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS bazaar_history (
                          product_id TEXT NOT NULL, ts INTEGER NOT NULL, bid REAL, ask REAL, bid_depth REAL, ask_depth REAL,
                          sell_week REAL, buy_week REAL, PRIMARY KEY (product_id, ts))""");
                s.execute("CREATE INDEX IF NOT EXISTS idx_bh_ts ON bazaar_history(ts)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS auction_sales (
                          uuid TEXT PRIMARY KEY, ts INTEGER NOT NULL, price INTEGER NOT NULL, bin INTEGER, item_id TEXT,
                          base_fp TEXT, relaxed_fp TEXT, strict_fp TEXT, upgrades TEXT)""");
                s.execute("CREATE INDEX IF NOT EXISTS idx_sales_ts ON auction_sales(ts)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS auction_valuations (
                          id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, uuid TEXT, item_id TEXT, listing_price REAL,
                          fair_value REAL, sale_price REAL, confidence REAL, comparables INTEGER, match_level TEXT)""");
                s.execute("CREATE INDEX IF NOT EXISTS idx_val_ts ON auction_valuations(ts)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS trades (
                          id TEXT PRIMARY KEY, state TEXT NOT NULL, item_id TEXT, strategy TEXT, created_at INTEGER,
                          updated_at INTEGER, json TEXT NOT NULL)""");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS sim_orders (
                          id TEXT PRIMARY KEY, client_id TEXT UNIQUE, state TEXT, updated_at INTEGER, json TEXT NOT NULL)""");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS transactions (
                          trade_id TEXT NOT NULL, seq INTEGER NOT NULL, ts INTEGER NOT NULL, type TEXT NOT NULL, item_id TEXT,
                          quantity INTEGER, price REAL, amount REAL, fee REAL, strategy TEXT, PRIMARY KEY (trade_id, seq))""");
                s.execute("CREATE INDEX IF NOT EXISTS idx_tx_ts ON transactions(ts)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS portfolio_snapshots (
                          ts INTEGER PRIMARY KEY, total_value REAL, cash REAL, invested REAL, realized REAL, unrealized REAL)""");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS strategy_performance (
                          day TEXT NOT NULL, strategy TEXT NOT NULL, trades INTEGER, wins INTEGER, profit REAL, predicted REAL,
                          capital REAL, PRIMARY KEY (day, strategy))""");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS error_log (
                          id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, level TEXT, event TEXT, message TEXT)""");
            }
            if (version < 2) {
                // v2: seller/buyer on sales (wash-trade filtering).
                if (!hasColumn(s, "auction_sales", "seller")) s.execute("ALTER TABLE auction_sales ADD COLUMN seller TEXT");
                if (!hasColumn(s, "auction_sales", "buyer")) s.execute("ALTER TABLE auction_sales ADD COLUMN buyer TEXT");
            }
            if (version < SCHEMA_VERSION) {
                s.execute("INSERT OR REPLACE INTO meta(key, value) VALUES ('schema', '" + SCHEMA_VERSION + "')");
            }
        }
    }

    private static boolean hasColumn(Statement s, String table, String column) throws SQLException {
        try (ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) if (column.equalsIgnoreCase(rs.getString("name"))) return true;
        }
        return false;
    }

    /** Runs work on the database thread and waits for it (use only from background threads). */
    public <T> T call(Work<T> work) {
        if (!available) return null;
        try {
            return thread.submit(() -> work.run(conn)).get(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            Log.error("db.call_failed", e.getCause() != null ? e.getCause() : e);
            return null;
        }
    }

    /** Fire-and-forget write. */
    public void submit(Work<?> work) {
        if (!available) return;
        try {
            thread.submit(() -> {
                try {
                    work.run(conn);
                } catch (SQLException e) {
                    Log.error("db.write_failed", e);
                }
                return null;
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    /** Async query whose future completes on the database thread. */
    public <T> CompletableFuture<T> query(Work<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        if (!available) {
            f.complete(null);
            return f;
        }
        try {
            thread.submit(() -> {
                try {
                    f.complete(work.run(conn));
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            f.complete(null);
        }
        return f;
    }

    /** Executes {@code work} inside a transaction; rolls back on failure. */
    public static <T> T tx(Connection c, Work<T> work) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            T r = work.run(c);
            c.commit();
            return r;
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    @Override
    public void close() {
        try {
            thread.submit(() -> {
                if (conn != null) {
                    try (Statement s = conn.createStatement()) {
                        s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                    } catch (SQLException ignored) {
                    }
                    conn.close();
                }
                return null;
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.error("db.close_failed", e);
        }
        available = false;
        thread.shutdown();
    }
}
