package dev.skytrade.database;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Exports analytics tables to CSV files. The API key and other config are never exported. */
public final class CsvExporter {
    private static final String[] TABLES = {"transactions", "portfolio_snapshots", "strategy_performance", "auction_valuations", "trades"};

    private CsvExporter() {}

    /** @return the files written */
    public static List<Path> export(Database db, Path dir) throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path out = dir.resolve("export-" + stamp);
        Files.createDirectories(out);
        List<Path> written = new ArrayList<>();
        for (String table : TABLES) {
            Path f = out.resolve(table + ".csv");
            Boolean ok = db.call(c -> {
                try (Statement s = c.createStatement();
                     ResultSet rs = s.executeQuery("SELECT * FROM " + table);
                     BufferedWriter w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    for (int i = 1; i <= n; i++) {
                        if (i > 1) w.write(',');
                        w.write(escape(md.getColumnName(i)));
                    }
                    w.newLine();
                    while (rs.next()) {
                        for (int i = 1; i <= n; i++) {
                            if (i > 1) w.write(',');
                            String v = rs.getString(i);
                            w.write(v == null ? "" : escape(v));
                        }
                        w.newLine();
                    }
                } catch (IOException e) {
                    throw new java.sql.SQLException(e);
                }
                return Boolean.TRUE;
            });
            if (Boolean.TRUE.equals(ok)) written.add(f);
        }
        return written;
    }

    static String escape(String v) {
        boolean quote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        if (!quote) return v;
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}
