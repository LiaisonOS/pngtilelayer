package org.ka2ddo.yaac.gui.tile;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Global, plugin-wide track rendering settings. Applies to every station's
 * trail and breadcrumb markers — there is no per-station override.
 * <p>
 * Persisted to {@code ~/.config/liaisonos/YAAC/track-settings.json}. Singleton
 * with lazy load on first access. Mutations are written through immediately.
 */
public class TrackSettings {

    public enum Shape       { TRIANGLE, CIRCLE }
    public enum MarkerSize  { LARGE, SMALL }
    public enum LineStyle   { DASHED, SOLID }
    public enum Coverage    { ALL, LAST_3 }

    private static final String DIR_RELATIVE  = ".config/liaisonos/YAAC";
    private static final String FILE_NAME     = "track-settings.json";

    private static volatile TrackSettings INSTANCE;

    private Shape       shape       = Shape.TRIANGLE;
    private MarkerSize  markerSize  = MarkerSize.LARGE;
    private LineStyle   lineStyle   = LineStyle.DASHED;
    private Coverage    coverage    = Coverage.ALL;

    private final File file;

    private TrackSettings() {
        File dir = new File(System.getProperty("user.home"), DIR_RELATIVE);
        if (!dir.exists()) dir.mkdirs();
        this.file = new File(dir, FILE_NAME);
        load();
    }

    public static TrackSettings getInstance() {
        TrackSettings local = INSTANCE;
        if (local == null) {
            synchronized (TrackSettings.class) {
                local = INSTANCE;
                if (local == null) INSTANCE = local = new TrackSettings();
            }
        }
        return local;
    }

    public synchronized Shape       getShape()      { return shape; }
    public synchronized MarkerSize  getMarkerSize() { return markerSize; }
    public synchronized LineStyle   getLineStyle()  { return lineStyle; }
    public synchronized Coverage    getCoverage()   { return coverage; }

    public synchronized void setAll(Shape shape, MarkerSize size,
                                    LineStyle line, Coverage coverage) {
        if (shape != null)    this.shape = shape;
        if (size != null)     this.markerSize = size;
        if (line != null)     this.lineStyle = line;
        if (coverage != null) this.coverage = coverage;
        save();
    }

    private void load() {
        if (!file.exists()) return;
        try {
            String txt = new String(Files.readAllBytes(file.toPath()));
            shape      = parseEnum(txt, "shape",      Shape.class,      shape);
            markerSize = parseEnum(txt, "markerSize", MarkerSize.class, markerSize);
            lineStyle  = parseEnum(txt, "lineStyle",  LineStyle.class,  lineStyle);
            coverage   = parseEnum(txt, "coverage",   Coverage.class,   coverage);
        } catch (IOException e) {
            System.err.println("TrackSettings.load: " + e.getMessage());
        }
    }

    private void save() {
        try (FileWriter w = new FileWriter(file)) {
            w.write("{\n");
            w.write("  \"shape\": \""      + shape.name()      + "\",\n");
            w.write("  \"markerSize\": \"" + markerSize.name() + "\",\n");
            w.write("  \"lineStyle\": \""  + lineStyle.name()  + "\",\n");
            w.write("  \"coverage\": \""   + coverage.name()   + "\"\n");
            w.write("}\n");
        } catch (IOException e) {
            System.err.println("TrackSettings.save: " + e.getMessage());
        }
    }

    private static <E extends Enum<E>> E parseEnum(String json, String key,
                                                   Class<E> type, E fallback) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        if (m.find()) {
            try {
                return Enum.valueOf(type, m.group(1).trim().toUpperCase());
            } catch (IllegalArgumentException ignored) { }
        }
        return fallback;
    }
}
