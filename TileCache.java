package org.ka2ddo.yaac.gui.tile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SQLite tile cache reader/writer compatible with existing .db files.
 * <p>
 * Schema:
 * <pre>
 * CREATE TABLE tiles (
 *     z INTEGER NOT NULL,
 *     x INTEGER NOT NULL,
 *     y INTEGER NOT NULL,
 *     data BLOB NOT NULL,
 *     PRIMARY KEY (z, x, y)
 * );
 *
 * Legacy databases built with the older schema (which had extension,
 * created_at, updated_at, expires_at columns) keep working — we only
 * SELECT data, and putTile falls back to a legacy INSERT shape if
 * the minimal one fails on NOT NULL constraints.
 * </pre>
 * <p>
 * Each tile source has its own .db file. Connections are pooled per file.
 */
public class TileCache {

    /**
     * MINIMAL SCHEMA. No expires_at, no extension, no timestamps —
     * just (z, x, y) → data. If a tile is in the table, it is served.
     * No "expired" tile logic anywhere in this class. Forever.
     *
     * Why: this cache must work 100% of the time, today and in 100 years.
     * The product is offline-first SAR mapping. Any column in the SELECT
     * that doesn't exist in legacy caches throws SQLException → cache
     * miss → silent fall-through to the internet. Has bitten the operator
     * repeatedly. See feedback memory [[tilecache-no-expiration]].
     *
     * The wider 4-column SELECT is GONE — never re-add columns to the read
     * path. Storage path stays compatible with both old (extended) and new
     * (minimal) schemas via "INSERT OR IGNORE missing-column safe" wrapper
     * in {@link #putTile}.
     */
    private static final String SQL_GET_TILE =
            "SELECT data FROM tiles WHERE z = ? AND x = ? AND y = ?";

    private static final String SQL_HAS_TILE =
            "SELECT 1 FROM tiles WHERE z = ? AND x = ? AND y = ?";

    /** Minimal-schema insert for NEW caches. */
    private static final String SQL_PUT_TILE_MIN =
            "INSERT OR REPLACE INTO tiles (z, x, y, data) VALUES (?, ?, ?, ?)";

    /** Legacy-schema insert: existing caches built with the older NOT NULL
     *  columns. Tried as a fallback if the minimal form fails. */
    private static final String SQL_PUT_TILE_LEGACY =
            "INSERT OR REPLACE INTO tiles " +
            "(z, x, y, data, extension, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, '.png', ?, ?)";

    /** New caches get this minimal schema. Existing caches keep their schema
     *  (CREATE TABLE IF NOT EXISTS no-ops). */
    private static final String SQL_CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS tiles (" +
            "z INTEGER NOT NULL, " +
            "x INTEGER NOT NULL, " +
            "y INTEGER NOT NULL, " +
            "data BLOB NOT NULL, " +
            "PRIMARY KEY (z, x, y))";

    /** Connection pool: cache file path -> connection */
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    private final String cacheDirectory;

    /**
     * @param cacheDirectory base directory for cache .db files
     */
    public TileCache(String cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
        try {
            Class.forName("org.sqlite.JDBC", true, this.getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            System.err.println("TileCache: SQLite JDBC driver not found");
        }
    }

    /**
     * Get a tile image from the cache.
     *
     * @param cacheFile the .db filename (e.g., "OpenStreet Map.db")
     * @param z         zoom level
     * @param x         tile X
     * @param y         tile Y
     * @return the tile image, or null if not found
     */
    public BufferedImage getTile(String cacheFile, int z, int x, int y) {
        if (cacheFile == null || cacheFile.isEmpty()) return null;

        try {
            Connection conn = getConnection(cacheFile);
            if (conn == null) return null;

            PreparedStatement stmt = conn.prepareStatement(SQL_GET_TILE);
            stmt.setInt(1, z);
            stmt.setInt(2, x);
            stmt.setInt(3, y);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                byte[] data = rs.getBytes(1);
                rs.close();
                stmt.close();
                if (data != null && data.length > 0) {
                    return ImageIO.read(new ByteArrayInputStream(data));
                }
            }
            rs.close();
            stmt.close();
        } catch (Exception e) {
            System.err.println("TileCache: error reading " + z + "/" + x + "/" + y +
                    " from " + cacheFile + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Get raw tile bytes from the cache (for forwarding without decode).
     *
     * @return raw PNG bytes, or null if not found
     */
    public byte[] getTileBytes(String cacheFile, int z, int x, int y) {
        if (cacheFile == null || cacheFile.isEmpty()) return null;

        try {
            Connection conn = getConnection(cacheFile);
            if (conn == null) return null;

            PreparedStatement stmt = conn.prepareStatement(SQL_GET_TILE);
            stmt.setInt(1, z);
            stmt.setInt(2, x);
            stmt.setInt(3, y);

            ResultSet rs = stmt.executeQuery();
            byte[] data = null;
            if (rs.next()) {
                data = rs.getBytes(1);
            }
            rs.close();
            stmt.close();
            return data;
        } catch (Exception e) {
            System.err.println("TileCache: error reading bytes " + z + "/" + x + "/" + y +
                    " from " + cacheFile + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Store a tile in the cache. Tries the minimal-schema insert first
     * (new caches). If it fails because the existing table requires the
     * legacy NOT NULL columns (extension, created_at, updated_at), falls
     * back to the legacy insert. Either way the tile lands.
     *
     * No expiration metadata is written. Tiles live forever in the cache.
     *
     * @param cacheFile the .db filename
     * @param z         zoom level
     * @param x         tile X
     * @param y         tile Y
     * @param pngData   raw PNG bytes
     */
    public void putTile(String cacheFile, int z, int x, int y, byte[] pngData) {
        if (cacheFile == null || cacheFile.isEmpty()) return;
        if (pngData == null || pngData.length == 0) return;

        Connection conn = getConnection(cacheFile);
        if (conn == null) return;

        // Try minimal insert first
        try (PreparedStatement stmt = conn.prepareStatement(SQL_PUT_TILE_MIN)) {
            stmt.setInt(1, z);
            stmt.setInt(2, x);
            stmt.setInt(3, y);
            stmt.setBytes(4, pngData);
            stmt.executeUpdate();
            return;
        } catch (Exception minErr) {
            // Legacy schema has NOT NULL columns the minimal insert skipped.
            // Fall through to the legacy form below.
        }

        try (PreparedStatement stmt = conn.prepareStatement(SQL_PUT_TILE_LEGACY)) {
            long now = System.currentTimeMillis() / 1000;
            stmt.setInt(1, z);
            stmt.setInt(2, x);
            stmt.setInt(3, y);
            stmt.setBytes(4, pngData);
            stmt.setLong(5, now);  // created_at
            stmt.setLong(6, now);  // updated_at
            stmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("TileCache: error writing " + z + "/" + x + "/" + y +
                    " to " + cacheFile + ": " + e.getMessage());
        }
    }

    /**
     * Check if a tile exists in the cache (without loading the image).
     */
    public boolean hasTile(String cacheFile, int z, int x, int y) {
        if (cacheFile == null || cacheFile.isEmpty()) return false;

        try {
            Connection conn = getConnection(cacheFile);
            if (conn == null) return false;

            PreparedStatement stmt = conn.prepareStatement(SQL_HAS_TILE);
            stmt.setInt(1, z);
            stmt.setInt(2, x);
            stmt.setInt(3, y);

            ResultSet rs = stmt.executeQuery();
            boolean found = rs.next();
            rs.close();
            stmt.close();
            return found;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get or create a JDBC connection to the specified cache database.
     * Creates the database and table if they don't exist.
     */
    private Connection getConnection(String cacheFile) {
        return connections.computeIfAbsent(cacheFile, f -> {
            try {
                String path = cacheDirectory + File.separator + f;
                File dbFile = new File(path);

                // Ensure parent directory exists
                File parentDir = dbFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }

                // Use SQLite JDBC directly to bypass DriverManager classloader issues
                org.sqlite.JDBC driver = new org.sqlite.JDBC();
                Connection conn = driver.connect("jdbc:sqlite:" + path, new java.util.Properties());
                conn.setAutoCommit(true);

                // Enable WAL mode for better concurrent read performance.
                // Best-effort: if the filesystem is read-only the PRAGMA
                // call may fail or downgrade silently — keep going.
                try (Statement pragmaStmt = conn.createStatement()) {
                    pragmaStmt.execute("PRAGMA journal_mode=WAL");
                    pragmaStmt.execute("PRAGMA synchronous=NORMAL");
                } catch (SQLException ignore) {
                    // read-only DB / no write permission — reads still work
                }

                // Create table if missing. Best-effort and isolated: a
                // failure here (read-only FS, locked file, anything) MUST
                // NOT prevent us from returning the open connection. Reads
                // come first; CREATE TABLE only matters for writes anyway.
                try (Statement createStmt = conn.createStatement()) {
                    createStmt.execute(SQL_CREATE_TABLE);
                } catch (SQLException ignore) {
                    // Existing DB with a different schema, or read-only.
                    // Either way, reads via SELECT data still work.
                }

                return conn;
            } catch (SQLException e) {
                System.err.println("TileCache: cannot open " + f + ": " + e.getMessage());
                return null;
            }
        });
    }

    /**
     * Close a specific database connection (e.g., before deleting the file).
     */
    public void closeConnection(String cacheFile) {
        Connection conn = connections.remove(cacheFile);
        if (conn != null) {
            try {
                if (!conn.isClosed()) conn.close();
            } catch (SQLException e) {
                // ignore
            }
        }
    }

    /**
     * Close all open database connections.
     */
    public void close() {
        for (Map.Entry<String, Connection> entry : connections.entrySet()) {
            try {
                Connection conn = entry.getValue();
                if (conn != null && !conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException e) {
                // ignore on shutdown
            }
        }
        connections.clear();
    }
}
