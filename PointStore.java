package org.ka2ddo.yaac.gui.tile;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * SQLite-backed point store for PNGTileLayer.
 * <p>
 * Persists:
 * <ul>
 *   <li><b>Station positions</b> — every position observed by the plugin's
 *       station layer is inserted with an idempotent {@code INSERT OR IGNORE}
 *       keyed on {@code (callsign, timestamp)}. Survives YAAC restarts,
 *       crashes, mode reboots — critical for SAR operations where the
 *       in-memory APRS history alone is not enough.</li>
 *   <li><b>SAR objects</b> — evidence markers placed by the operator.</li>
 * </ul>
 * <p>
 * Database file: {@code ~/.config/liaisonos/YAAC/ptl-data.db}
 * <p>
 * Singleton; uses one persistent connection with WAL mode. Same JDBC
 * bootstrap pattern as {@link TileCache} (bundled sqlite-jdbc driver).
 */
public class PointStore {

    private static final String DB_DIR_RELATIVE = ".config/liaisonos/YAAC";
    private static final String DB_FILENAME = "ptl-data.db";

    private static final String SQL_CREATE_STATIONS =
            "CREATE TABLE IF NOT EXISTS station_positions (" +
            "  id           INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "  callsign     TEXT    NOT NULL, " +
            "  lat          REAL    NOT NULL, " +
            "  lon          REAL    NOT NULL, " +
            "  timestamp    INTEGER NOT NULL, " +
            "  ssid         INTEGER, " +
            "  comment      TEXT, " +
            "  speed        REAL, " +
            "  course       REAL, " +
            "  altitude     REAL, " +
            "  UNIQUE (callsign, timestamp))";

    private static final String SQL_CREATE_STATION_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_station_callsign_time " +
            "ON station_positions(callsign, timestamp)";

    private static final String SQL_CREATE_SAR =
            "CREATE TABLE IF NOT EXISTS sar_objects (" +
            "  id        TEXT PRIMARY KEY, " +
            "  category  TEXT NOT NULL, " +
            "  type      TEXT NOT NULL, " +
            "  lat       REAL NOT NULL, " +
            "  lon       REAL NOT NULL, " +
            "  found_by  TEXT, " +
            "  notes     TEXT, " +
            "  timestamp INTEGER NOT NULL)";

    private static final String SQL_INSERT_STATION =
            "INSERT OR IGNORE INTO station_positions " +
            "(callsign, lat, lon, timestamp, ssid, comment, speed, course, altitude) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_SELECT_STATION_POSITIONS =
            "SELECT lat, lon, timestamp FROM station_positions " +
            "WHERE callsign = ? AND timestamp >= ? " +
            "ORDER BY timestamp ASC";

    private static final String SQL_LATEST_TIMESTAMP =
            "SELECT MAX(timestamp) FROM station_positions WHERE callsign = ?";

    private static final String SQL_INSERT_SAR =
            "INSERT OR REPLACE INTO sar_objects " +
            "(id, category, type, lat, lon, found_by, notes, timestamp) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_SELECT_ALL_SAR =
            "SELECT id, category, type, lat, lon, found_by, notes, timestamp FROM sar_objects";

    private static final String SQL_DELETE_SAR =
            "DELETE FROM sar_objects WHERE id = ?";

    private static final String SQL_DELETE_ALL_STATIONS =
            "DELETE FROM station_positions";

    private static final String SQL_DELETE_ALL_SAR =
            "DELETE FROM sar_objects";

    private static volatile PointStore INSTANCE;

    private final Connection conn;
    private final String dbPath;

    private PointStore() throws SQLException {
        String home = System.getProperty("user.home");
        File dir = new File(home, DB_DIR_RELATIVE);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.dbPath = new File(dir, DB_FILENAME).getAbsolutePath();

        try {
            Class.forName("org.sqlite.JDBC", true, this.getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not on classpath", e);
        }

        org.sqlite.JDBC driver = new org.sqlite.JDBC();
        this.conn = driver.connect("jdbc:sqlite:" + dbPath, new Properties());
        this.conn.setAutoCommit(true);

        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute(SQL_CREATE_STATIONS);
            st.execute(SQL_CREATE_STATION_INDEX);
            st.execute(SQL_CREATE_SAR);
        }
    }

    /**
     * Returns the singleton instance, or {@code null} if initialization
     * failed (caller can degrade gracefully — the plugin still works
     * without persistence).
     */
    public static PointStore getInstance() {
        PointStore local = INSTANCE;
        if (local == null) {
            synchronized (PointStore.class) {
                local = INSTANCE;
                if (local == null) {
                    try {
                        INSTANCE = local = new PointStore();
                    } catch (SQLException e) {
                        System.err.println("PointStore: init failed: " + e.getMessage());
                    }
                }
            }
        }
        return local;
    }

    public String getDatabasePath() {
        return dbPath;
    }

    // --- Station positions ------------------------------------------------

    /**
     * Insert a station position. Idempotent — duplicates on
     * {@code (callsign, timestamp)} are silently dropped.
     */
    public void addStationPosition(String callsign, double lat, double lon,
                                   long timestamp, Integer ssid, String comment,
                                   Double speed, Double course, Double altitude) {
        if (callsign == null || callsign.isEmpty()) return;
        if (timestamp <= 0) return;

        try (PreparedStatement st = conn.prepareStatement(SQL_INSERT_STATION)) {
            st.setString(1, callsign);
            st.setDouble(2, lat);
            st.setDouble(3, lon);
            st.setLong(4, timestamp);
            if (ssid != null)     st.setInt(5, ssid);     else st.setNull(5, java.sql.Types.INTEGER);
            if (comment != null)  st.setString(6, comment); else st.setNull(6, java.sql.Types.VARCHAR);
            if (speed != null)    st.setDouble(7, speed);  else st.setNull(7, java.sql.Types.REAL);
            if (course != null)   st.setDouble(8, course); else st.setNull(8, java.sql.Types.REAL);
            if (altitude != null) st.setDouble(9, altitude); else st.setNull(9, java.sql.Types.REAL);
            st.executeUpdate();
        } catch (SQLException e) {
            System.err.println("PointStore.addStationPosition: " + e.getMessage());
        }
    }

    /**
     * Get all stored positions for {@code callsign} since {@code sinceTimestamp}
     * (inclusive), ordered oldest-first. Each result is {@code [lat, lon, timestamp]}.
     * Returns an empty list (never null) if the call fails.
     */
    public List<double[]> getStationPositions(String callsign, long sinceTimestamp) {
        List<double[]> out = new ArrayList<>();
        if (callsign == null || callsign.isEmpty()) return out;

        try (PreparedStatement st = conn.prepareStatement(SQL_SELECT_STATION_POSITIONS)) {
            st.setString(1, callsign);
            st.setLong(2, sinceTimestamp);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(new double[]{rs.getDouble(1), rs.getDouble(2), rs.getLong(3)});
                }
            }
        } catch (SQLException e) {
            System.err.println("PointStore.getStationPositions: " + e.getMessage());
        }
        return out;
    }

    /**
     * Returns the most recent stored timestamp for {@code callsign}, or 0 if
     * none. Useful for diff-style incremental syncing during paint.
     */
    public long getLatestTimestamp(String callsign) {
        if (callsign == null || callsign.isEmpty()) return 0L;
        try (PreparedStatement st = conn.prepareStatement(SQL_LATEST_TIMESTAMP)) {
            st.setString(1, callsign);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    return rs.wasNull() ? 0L : v;
                }
            }
        } catch (SQLException e) {
            System.err.println("PointStore.getLatestTimestamp: " + e.getMessage());
        }
        return 0L;
    }

    /**
     * Returns the set of distinct callsigns that have at least one stored
     * position newer than {@code sinceTimestamp}. Used by the layer to
     * surface stations YAAC hasn't seen yet on the current run.
     */
    public java.util.Set<String> getActiveCallsigns(long sinceTimestamp) {
        java.util.Set<String> out = new java.util.HashSet<>();
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT DISTINCT callsign FROM station_positions WHERE timestamp >= ?")) {
            st.setLong(1, sinceTimestamp);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            System.err.println("PointStore.getActiveCallsigns: " + e.getMessage());
        }
        return out;
    }

    public int removeAllStationPositions() {
        try (Statement st = conn.createStatement()) {
            return st.executeUpdate(SQL_DELETE_ALL_STATIONS);
        } catch (SQLException e) {
            System.err.println("PointStore.removeAllStationPositions: " + e.getMessage());
            return 0;
        }
    }

    // --- SAR objects ------------------------------------------------------

    public void addSarObject(SARObject obj) {
        if (obj == null) return;
        try (PreparedStatement st = conn.prepareStatement(SQL_INSERT_SAR)) {
            st.setString(1, obj.getId());
            st.setString(2, obj.getCategory());
            st.setString(3, obj.getType());
            st.setDouble(4, obj.getLat());
            st.setDouble(5, obj.getLon());
            st.setString(6, obj.getFoundBy() != null ? obj.getFoundBy() : "");
            st.setString(7, obj.getNotes() != null ? obj.getNotes() : "");
            st.setLong(8, obj.getTimestamp());
            st.executeUpdate();
        } catch (SQLException e) {
            System.err.println("PointStore.addSarObject: " + e.getMessage());
        }
    }

    public List<SARObject> getAllSarObjects() {
        List<SARObject> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(SQL_SELECT_ALL_SAR)) {
            while (rs.next()) {
                SARObject obj = new SARObject();
                obj.setId(rs.getString(1));
                obj.setCategory(rs.getString(2));
                obj.setType(rs.getString(3));
                obj.setLat(rs.getDouble(4));
                obj.setLon(rs.getDouble(5));
                obj.setFoundBy(rs.getString(6));
                obj.setNotes(rs.getString(7));
                obj.setTimestamp(rs.getLong(8));
                out.add(obj);
            }
        } catch (SQLException e) {
            System.err.println("PointStore.getAllSarObjects: " + e.getMessage());
        }
        return out;
    }

    public void removeSarObject(String id) {
        if (id == null) return;
        try (PreparedStatement st = conn.prepareStatement(SQL_DELETE_SAR)) {
            st.setString(1, id);
            st.executeUpdate();
        } catch (SQLException e) {
            System.err.println("PointStore.removeSarObject: " + e.getMessage());
        }
    }

    public int removeAllSarObjects() {
        try (Statement st = conn.createStatement()) {
            return st.executeUpdate(SQL_DELETE_ALL_SAR);
        } catch (SQLException e) {
            System.err.println("PointStore.removeAllSarObjects: " + e.getMessage());
            return 0;
        }
    }
}
