package org.ka2ddo.yaac.gui.tile;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Global tile-rendering flags — separate from {@link TrackSettings} because
 * these affect the basemap and overlays, not the station styling.
 * <p>
 * Persisted to {@code ~/.config/liaisonos/YAAC/map-display.json}.
 */
public class MapDisplaySettings {

    /**
     * Standard cartographic scale ladder mapped to a sensible grid step
     * (in metres). The convention: grid cell ≈ 2 cm at the named scale,
     * which is what 1:25k topo sheets use.
     */
    public static final Map<Integer, Integer> SCALE_GRID_LADDER;
    static {
        LinkedHashMap<Integer, Integer> m = new LinkedHashMap<>();
        m.put(2_500,    50);
        m.put(5_000,   100);
        m.put(10_000,  200);
        m.put(25_000,  500);
        m.put(50_000,  1_000);
        m.put(100_000, 2_000);
        m.put(250_000, 5_000);
        SCALE_GRID_LADDER = java.util.Collections.unmodifiableMap(m);
    }

    private static final String DIR_RELATIVE = ".config/liaisonos/YAAC";
    private static final String FILE_NAME    = "map-display.json";

    private static volatile MapDisplaySettings INSTANCE;

    private boolean grayscale = false;
    private boolean showGrid = false;
    private int gridSpacingMeters = 100;
    private boolean cacheOnly = false;
    private final File file;

    private MapDisplaySettings() {
        File dir = new File(System.getProperty("user.home"), DIR_RELATIVE);
        if (!dir.exists()) dir.mkdirs();
        this.file = new File(dir, FILE_NAME);
        load();
    }

    public static MapDisplaySettings getInstance() {
        MapDisplaySettings local = INSTANCE;
        if (local == null) {
            synchronized (MapDisplaySettings.class) {
                local = INSTANCE;
                if (local == null) INSTANCE = local = new MapDisplaySettings();
            }
        }
        return local;
    }

    public synchronized boolean isGrayscale() { return grayscale; }
    public synchronized void setGrayscale(boolean v) { this.grayscale = v; save(); }

    public synchronized boolean isShowGrid() { return showGrid; }
    public synchronized void setShowGrid(boolean v) { this.showGrid = v; save(); }

    public synchronized int getGridSpacingMeters() { return gridSpacingMeters; }
    public synchronized void setGridSpacingMeters(int v) {
        if (v > 0) { this.gridSpacingMeters = v; save(); }
    }

    public synchronized boolean isCacheOnly() { return cacheOnly; }
    public synchronized void setCacheOnly(boolean v) { this.cacheOnly = v; save(); }

    /**
     * Lookup the grid step from the standard ladder. Returns 0 if the scale
     * isn't in the ladder (caller can fall back to the current setting).
     */
    public static int gridStepForScale(int scale) {
        Integer step = SCALE_GRID_LADDER.get(scale);
        return step != null ? step : 0;
    }

    private void load() {
        if (!file.exists()) return;
        try {
            String txt = new String(Files.readAllBytes(file.toPath()));
            Matcher m = Pattern.compile("\"grayscale\"\\s*:\\s*(true|false)").matcher(txt);
            if (m.find()) grayscale = Boolean.parseBoolean(m.group(1));
            m = Pattern.compile("\"showGrid\"\\s*:\\s*(true|false)").matcher(txt);
            if (m.find()) showGrid = Boolean.parseBoolean(m.group(1));
            m = Pattern.compile("\"gridSpacingMeters\"\\s*:\\s*(\\d+)").matcher(txt);
            if (m.find()) {
                try { gridSpacingMeters = Integer.parseInt(m.group(1)); }
                catch (NumberFormatException ignored) { }
            }
            m = Pattern.compile("\"cacheOnly\"\\s*:\\s*(true|false)").matcher(txt);
            if (m.find()) cacheOnly = Boolean.parseBoolean(m.group(1));
        } catch (IOException e) {
            System.err.println("MapDisplaySettings.load: " + e.getMessage());
        }
    }

    private void save() {
        try (FileWriter w = new FileWriter(file)) {
            w.write("{\n");
            w.write("  \"grayscale\": " + grayscale + ",\n");
            w.write("  \"showGrid\": " + showGrid + ",\n");
            w.write("  \"gridSpacingMeters\": " + gridSpacingMeters + ",\n");
            w.write("  \"cacheOnly\": " + cacheOnly + "\n");
            w.write("}\n");
        } catch (IOException e) {
            System.err.println("MapDisplaySettings.save: " + e.getMessage());
        }
    }
}
