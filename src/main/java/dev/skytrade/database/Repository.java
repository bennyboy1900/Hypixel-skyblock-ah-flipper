package dev.skytrade.database;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.execution.LedgerEntry;
import dev.skytrade.execution.Trade;
import dev.skytrade.execution.TradeStore;
import dev.skytrade.market.BazaarProduct;
import dev.skytrade.market.BazaarSnapshot;
import dev.skytrade.market.PricePoint;
import dev.skytrade.portfolio.CompletedTrade;
import dev.skytrade.portfolio.PortfolioView;
import dev.skytrade.simulation.SimOrder;
import dev.skytrade.valuation.SaleRecord;
import dev.skytrade.valuation.Valuation;

import java.sql.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

/** All SQL used by the mod, grouped by table. Implements {@link TradeStore} for the trade engine. */
public final class Repository implements TradeStore {
    private static final Gson GSON = new GsonBuilder().serializeSpecialFloatingPointValues().create();
    private final Database db;

    public Repository(Database db) {
        this.db = db;
    }

    public Database db() {
        return db;
    }

    // ------------------------------------------------------------ market snapshots & history

    public void saveBazaarSnapshot(BazaarSnapshot snap, Collection<Map.Entry<String, PricePoint>> points) {
        db.submit(c -> Database.tx(c, cc -> {
            double bidD = 0, askD = 0;
            for (BazaarProduct p : snap.products().values()) {
                bidD += p.bidDepth();
                askD += p.askDepth();
            }
            try (PreparedStatement ps = cc.prepareStatement("INSERT OR IGNORE INTO market_snapshots VALUES (?,?,?,?,?,?,?)")) {
                ps.setLong(1, snap.fetchedAt());
                ps.setString(2, "BAZAAR");
                ps.setLong(3, snap.lastUpdated());
                ps.setInt(4, snap.products().size());
                ps.setInt(5, 1);
                ps.setDouble(6, bidD);
                ps.setDouble(7, askD);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = cc.prepareStatement("INSERT OR IGNORE INTO bazaar_history VALUES (?,?,?,?,?,?,?,?)")) {
                for (Map.Entry<String, PricePoint> e : points) {
                    PricePoint p = e.getValue();
                    ps.setString(1, e.getKey());
                    ps.setLong(2, p.timestamp());
                    ps.setDouble(3, p.bid());
                    ps.setDouble(4, p.ask());
                    ps.setDouble(5, p.bidDepth());
                    ps.setDouble(6, p.askDepth());
                    ps.setDouble(7, p.sellMovingWeek());
                    ps.setDouble(8, p.buyMovingWeek());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        }));
    }

    public void saveAuctionSnapshot(long fetchedAt, long lastUpdated, int count, boolean complete) {
        db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO market_snapshots VALUES (?,?,?,?,?,0,0)")) {
                ps.setLong(1, fetchedAt);
                ps.setString(2, "AUCTIONS");
                ps.setLong(3, lastUpdated);
                ps.setInt(4, count);
                ps.setInt(5, complete ? 1 : 0);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public interface PointSink {
        void accept(String productId, PricePoint p);
    }

    /** Streams history points with ts in [from, to] ordered by time. */
    public void loadHistory(long from, long to, PointSink sink) {
        db.call(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM bazaar_history WHERE ts >= ? AND ts <= ? ORDER BY ts")) {
                ps.setLong(1, from);
                ps.setLong(2, to);
                ps.setFetchSize(5000);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) sink.accept(rs.getString(1), point(rs));
                }
            }
            return null;
        });
    }

    public List<PricePoint> productHistory(String id, long from, long to) {
        List<PricePoint> r = db.call(c -> {
            List<PricePoint> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM bazaar_history WHERE product_id = ? AND ts >= ? AND ts <= ? ORDER BY ts")) {
                ps.setString(1, id);
                ps.setLong(2, from);
                ps.setLong(3, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(point(rs));
                }
            }
            return out;
        });
        return r == null ? List.of() : r;
    }

    private static PricePoint point(ResultSet rs) throws SQLException {
        return new PricePoint(rs.getLong(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), rs.getDouble(7), rs.getDouble(8));
    }

    // ------------------------------------------------------------ auctions

    public void saveSales(List<SaleRecord> sales) {
        if (sales.isEmpty()) return;
        db.submit(c -> Database.tx(c, cc -> {
            try (PreparedStatement ps = cc.prepareStatement("INSERT OR IGNORE INTO auction_sales VALUES (?,?,?,?,?,?,?,?,?)")) {
                for (SaleRecord s : sales) {
                    ps.setString(1, s.uuid());
                    ps.setLong(2, s.timestamp());
                    ps.setLong(3, s.price());
                    ps.setInt(4, s.bin() ? 1 : 0);
                    ps.setString(5, s.itemId());
                    ps.setString(6, s.base());
                    ps.setString(7, s.relaxed());
                    ps.setString(8, s.strict());
                    ps.setString(9, String.join(",", s.upgrades()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        }));
    }

    public List<SaleRecord> loadSales(long since) {
        List<SaleRecord> r = db.call(c -> {
            List<SaleRecord> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM auction_sales WHERE ts >= ? ORDER BY ts")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String up = rs.getString(9);
                        List<String> ups = up == null || up.isEmpty() ? List.of() : List.of(up.split(","));
                        out.add(new SaleRecord(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getInt(4) == 1, rs.getString(5),
                                rs.getString(6), rs.getString(7), rs.getString(8), ups));
                    }
                }
            }
            return out;
        });
        return r == null ? List.of() : r;
    }

    public void saveValuation(long ts, String uuid, String itemId, double listing, Valuation v) {
        db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO auction_valuations(ts,uuid,item_id,listing_price,fair_value,sale_price,confidence,comparables,match_level) VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, ts);
                ps.setString(2, uuid);
                ps.setString(3, itemId);
                ps.setDouble(4, listing);
                ps.setDouble(5, v.fairValue());
                ps.setDouble(6, v.salePrice());
                ps.setDouble(7, v.confidence());
                ps.setInt(8, v.comparables());
                ps.setString(9, v.matchLevel());
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------ trades (TradeStore)

    @Override
    public void commit(Trade t, List<LedgerEntry> entries) {
        String json = GSON.toJson(t);
        Object ok = db.call(c -> Database.tx(c, cc -> {
            try (PreparedStatement ps = cc.prepareStatement("INSERT OR REPLACE INTO trades VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, t.id);
                ps.setString(2, t.state.name());
                ps.setString(3, t.itemId);
                ps.setString(4, t.strategy);
                ps.setLong(5, t.createdAt);
                ps.setLong(6, t.updatedAt);
                ps.setString(7, json);
                ps.executeUpdate();
            }
            if (!entries.isEmpty()) {
                try (PreparedStatement ps = cc.prepareStatement("INSERT OR IGNORE INTO transactions VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                    for (LedgerEntry e : entries) {
                        ps.setString(1, e.tradeId());
                        ps.setInt(2, e.seq());
                        ps.setLong(3, e.timestamp());
                        ps.setString(4, e.type().name());
                        ps.setString(5, e.itemId());
                        ps.setLong(6, e.quantity());
                        ps.setDouble(7, e.price());
                        ps.setDouble(8, e.amount());
                        ps.setDouble(9, e.fee());
                        ps.setString(10, e.strategy());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            return Boolean.TRUE;
        }));
        if (ok == null && db.available()) throw new IllegalStateException("trade persistence failed");
    }

    @Override
    public List<Trade> loadAll() {
        List<Trade> r = db.call(c -> {
            List<Trade> out = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT json FROM trades ORDER BY created_at")) {
                while (rs.next()) {
                    try {
                        out.add(GSON.fromJson(rs.getString(1), Trade.class));
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            return out;
        });
        return r == null ? List.of() : r;
    }

    @Override
    public List<LedgerEntry> loadLedger() {
        List<LedgerEntry> r = db.call(c -> {
            List<LedgerEntry> out = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM transactions ORDER BY ts, trade_id, seq")) {
                while (rs.next()) {
                    out.add(new LedgerEntry(rs.getString(1), rs.getInt(2), rs.getLong(3), LedgerEntry.Type.valueOf(rs.getString(4)),
                            rs.getString(5), rs.getLong(6), rs.getDouble(7), rs.getDouble(8), rs.getDouble(9), rs.getString(10)));
                }
            }
            return out;
        });
        return r == null ? List.of() : r;
    }

    @Override
    public void clear() {
        db.call(c -> Database.tx(c, cc -> {
            try (Statement s = cc.createStatement()) {
                s.execute("DELETE FROM trades");
                s.execute("DELETE FROM transactions");
                s.execute("DELETE FROM sim_orders");
                s.execute("DELETE FROM portfolio_snapshots");
                s.execute("DELETE FROM strategy_performance");
            }
            return null;
        }));
    }

    // ------------------------------------------------------------ simulator orders

    public void saveSimOrder(SimOrder o) {
        String json = GSON.toJson(o);
        db.call(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO sim_orders VALUES (?,?,?,?,?)")) {
                ps.setString(1, o.id);
                ps.setString(2, o.clientId);
                ps.setString(3, o.state);
                ps.setLong(4, System.currentTimeMillis());
                ps.setString(5, json);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public List<SimOrder> loadSimOrders() {
        List<SimOrder> r = db.call(c -> {
            List<SimOrder> out = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT json FROM sim_orders")) {
                while (rs.next()) out.add(GSON.fromJson(rs.getString(1), SimOrder.class));
            }
            return out;
        });
        return r == null ? List.of() : r;
    }

    // ------------------------------------------------------------ portfolio & performance

    public void savePortfolioSnapshot(PortfolioView v) {
        db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO portfolio_snapshots VALUES (?,?,?,?,?,?)")) {
                ps.setLong(1, v.timestamp());
                ps.setDouble(2, v.totalValue());
                ps.setDouble(3, v.cash());
                ps.setDouble(4, v.invested());
                ps.setDouble(5, v.realizedTotal());
                ps.setDouble(6, v.unrealized());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public record ValuePoint(long ts, double value) {}

    public List<ValuePoint> portfolioHistory(long since) {
        List<ValuePoint> r = db.call(c -> {
            List<ValuePoint> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT ts, total_value FROM portfolio_snapshots WHERE ts >= ? ORDER BY ts")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new ValuePoint(rs.getLong(1), rs.getDouble(2)));
                }
            }
            return out;
        });
        return r == null ? List.of() : r;
    }

    public void recordStrategyResult(CompletedTrade t) {
        String day = Instant.ofEpochMilli(t.closedAt()).atZone(ZoneOffset.UTC).toLocalDate().toString();
        db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO strategy_performance VALUES (?,?,1,?,?,?,?)
                    ON CONFLICT(day, strategy) DO UPDATE SET trades = trades + 1, wins = wins + excluded.wins,
                      profit = profit + excluded.profit, predicted = predicted + excluded.predicted, capital = capital + excluded.capital""")) {
                ps.setString(1, day);
                ps.setString(2, t.strategy());
                ps.setInt(3, t.realizedProfit() > 0 ? 1 : 0);
                ps.setDouble(4, t.realizedProfit());
                ps.setDouble(5, t.predictedProfit());
                ps.setDouble(6, t.capital());
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------ errors & retention

    public void logError(String level, String event, String message) {
        db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO error_log(ts, level, event, message) VALUES (?,?,?,?)")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, level);
                ps.setString(3, event);
                ps.setString(4, message.length() > 2000 ? message.substring(0, 2000) : message);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Deletes data older than the configured retention windows. Terminal trades follow transaction retention. */
    public void cleanup(SkyTradeConfig.Database cfg, long now) {
        db.submit(c -> {
            long day = 86_400_000L;
            long snapCut = now - cfg.snapshotRetentionDays * day;
            long txCut = now - cfg.transactionRetentionDays * day;
            long errCut = now - cfg.errorRetentionDays * day;
            int removed = 0;
            try (Statement s = c.createStatement()) {
                removed += s.executeUpdate("DELETE FROM bazaar_history WHERE ts < " + snapCut);
                removed += s.executeUpdate("DELETE FROM market_snapshots WHERE ts < " + snapCut);
                removed += s.executeUpdate("DELETE FROM auction_sales WHERE ts < " + snapCut);
                removed += s.executeUpdate("DELETE FROM auction_valuations WHERE ts < " + snapCut);
                removed += s.executeUpdate("DELETE FROM error_log WHERE ts < " + errCut);
                removed += s.executeUpdate("DELETE FROM portfolio_snapshots WHERE ts < " + txCut);
                removed += s.executeUpdate("DELETE FROM transactions WHERE ts < " + txCut
                        + " AND trade_id IN (SELECT id FROM trades WHERE state IN ('SALE_COMPLETED','CANCELLED'))");
                removed += s.executeUpdate("DELETE FROM trades WHERE updated_at < " + txCut + " AND state IN ('SALE_COMPLETED','CANCELLED')");
                removed += s.executeUpdate("DELETE FROM sim_orders WHERE updated_at < " + (now - 7 * day) + " AND state <> 'OPEN'");
            }
            dev.skytrade.util.Log.info("db.cleanup", "rowsRemoved", removed);
            return null;
        });
    }
}
