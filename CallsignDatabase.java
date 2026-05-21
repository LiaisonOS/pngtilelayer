package org.ka2ddo.yaac.gui.tile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Read-only lookup against the LiaisonOS amateur-radio license databases
 * (same files used by et-logger and et-predict). Returns the registered
 * operator name for a callsign, or {@code null} if unknown.
 * <p>
 * File format (pipe-delimited):
 * <ul>
 *   <li>{@code license.csv}   — US: {@code CALL|NAME|CITY|ZIP|STATE}</li>
 *   <li>{@code license-ca.csv} — CA: {@code CALL|NAME|CITY|POSTAL|PROV|LAT|LON}</li>
 * </ul>
 * <p>
 * Single shared HashMap, loaded lazily on first lookup. The 700K-row FCC dump
 * costs &lt;200&nbsp;ms to parse and ~30&nbsp;MB resident, which is cheap for the
 * benefit of operator names in click popups.
 */
public final class CallsignDatabase {

    /** Same search order as et-logger.py. */
    private static final String[] SEARCH_DIRS = {
            "/opt/emcomm-tools-api/data",
            "/opt/emcomm-tools/data",
            "/opt/emcomm-tools/conf/data",
    };

    private static final String[] DB_FILES = {
            "license.csv",      // US (also contains some CA entries)
            "license-ca.csv",   // CA
    };

    private static volatile Map<String, String> cache;     // CALL → NAME
    private static volatile boolean loadAttempted = false;

    private CallsignDatabase() { }

    /**
     * Returns the operator's name for {@code callsign}, or {@code null} if
     * the callsign is unknown or the DB files aren't installed. The SSID
     * suffix (e.g. {@code -9}, {@code -15}) is stripped before lookup.
     */
    public static String lookupName(String callsign) {
        if (callsign == null || callsign.isEmpty()) return null;
        ensureLoaded();
        Map<String, String> c = cache;
        if (c == null) return null;
        String key = stripSsid(callsign).toUpperCase().trim();
        return c.get(key);
    }

    private static String stripSsid(String call) {
        int dash = call.indexOf('-');
        return (dash >= 0) ? call.substring(0, dash) : call;
    }

    private static void ensureLoaded() {
        if (loadAttempted) return;
        synchronized (CallsignDatabase.class) {
            if (loadAttempted) return;
            loadAttempted = true;
            cache = loadAll();
        }
    }

    private static Map<String, String> loadAll() {
        HashMap<String, String> out = new HashMap<>(800_000);
        for (String dir : SEARCH_DIRS) {
            for (String fileName : DB_FILES) {
                File f = new File(dir, fileName);
                if (!f.exists()) continue;
                loadFileInto(f, out);
            }
            // First directory with at least one matching file wins (matches
            // et-logger's _find_data_file behaviour — stops at first hit).
            if (!out.isEmpty()) break;
        }
        return out.isEmpty() ? null : out;
    }

    private static void loadFileInto(File f, Map<String, String> out) {
        long t0 = System.currentTimeMillis();
        int loaded = 0;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f),
                        StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = br.readLine()) != null) {
                int p1 = line.indexOf('|');
                if (p1 <= 0) continue;
                int p2 = line.indexOf('|', p1 + 1);
                if (p2 <= p1) continue;
                String call = line.substring(0, p1).trim().toUpperCase();
                String name = line.substring(p1 + 1, p2).trim();
                if (!call.isEmpty() && !name.isEmpty()) {
                    out.put(call, name);
                    loaded++;
                }
            }
        } catch (IOException e) {
            System.err.println("CallsignDatabase: failed to read "
                    + f + ": " + e.getMessage());
        }
        System.err.println("CallsignDatabase: loaded " + loaded
                + " entries from " + f.getName()
                + " in " + (System.currentTimeMillis() - t0) + " ms");
    }
}
