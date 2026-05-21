package org.ka2ddo.yaac.gui.tile;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages SAR evidence objects placed on the map.
 * <p>
 * Persists to {@link PointStore} (SQLite at
 * {@code ~/.config/liaisonos/YAAC/ptl-data.db}). On first launch, any legacy
 * {@code ~/.yaac/sar-objects.json} is imported once and renamed
 * {@code sar-objects.json.imported}.
 */
public class SARObjectManager {

    private static final String CONFIG_FILE = "sar-objects.json";

    /** Category → color mapping (8 high-contrast colors). */
    private static final Map<String, Color> CATEGORY_COLORS;
    static {
        Map<String, Color> m = new LinkedHashMap<>();
        m.put("Clothing",          new Color(220, 40, 40));
        m.put("Fire / Smoking",    new Color(240, 150, 0));
        m.put("Food / Drink",      new Color(40, 180, 50));
        m.put("Shelter / Camping", new Color(30, 100, 220));
        m.put("Traces / Signs",    new Color(150, 50, 200));
        m.put("Tools / Equipment", new Color(120, 80, 40));
        m.put("Bags / Containers", new Color(0, 180, 180));
        m.put("Custom",            new Color(100, 100, 100));
        CATEGORY_COLORS = Collections.unmodifiableMap(m);
    }

    private static final Color DEFAULT_COLOR = new Color(100, 100, 100);

    private final List<SARObject> objects = new ArrayList<>();
    private final SARCatalog catalog;
    private String configPath;

    public SARObjectManager() {
        this.catalog = new SARCatalog();
    }

    public SARCatalog getCatalog() {
        return catalog;
    }

    public void addObject(SARObject obj) {
        objects.add(obj);
        PointStore store = PointStore.getInstance();
        if (store != null) store.addSarObject(obj);
    }

    public void removeObject(String id) {
        for (int i = 0; i < objects.size(); i++) {
            if (objects.get(i).getId().equals(id)) {
                objects.remove(i);
                PointStore store = PointStore.getInstance();
                if (store != null) store.removeSarObject(id);
                return;
            }
        }
    }

    /** Remove every SAR object, both in memory and in the persistent store. */
    public void clearAll() {
        objects.clear();
        PointStore store = PointStore.getInstance();
        if (store != null) store.removeAllSarObjects();
    }

    public List<SARObject> getObjects() {
        return Collections.unmodifiableList(objects);
    }

    /**
     * Get the display color for a category.
     */
    public static Color getCategoryColor(String category) {
        Color c = CATEGORY_COLORS.get(category);
        return c != null ? c : DEFAULT_COLOR;
    }

    /**
     * Load SAR objects. Primary source is the SQLite {@link PointStore}.
     * On first run, also imports a legacy {@code ~/.yaac/sar-objects.json}
     * if present (one-shot migration) and renames it
     * {@code sar-objects.json.imported} so it doesn't import again.
     */
    public void loadObjects(String yaacConfigDir) {
        this.configPath = yaacConfigDir + File.separator + CONFIG_FILE;
        objects.clear();

        // Primary load: SQLite
        PointStore store = PointStore.getInstance();
        if (store != null) {
            objects.addAll(store.getAllSarObjects());
        }

        // One-shot legacy JSON import — only if the file still exists.
        File legacyFile = new File(configPath);
        if (legacyFile.exists()) {
            try {
                String json = readFile(legacyFile);
                List<SARObject> before = new ArrayList<>(objects);
                parseJson(json);
                // parseJson() replaces in-memory list with JSON contents.
                // Merge anything that came from SQL but wasn't in JSON.
                for (SARObject sqlObj : before) {
                    boolean found = false;
                    for (SARObject jsonObj : objects) {
                        if (sqlObj.getId().equals(jsonObj.getId())) { found = true; break; }
                    }
                    if (!found) objects.add(sqlObj);
                }
                // Now mirror everything to SQLite (idempotent inserts)
                if (store != null) {
                    for (SARObject obj : objects) store.addSarObject(obj);
                }
                // Rename the legacy file so we don't import it again
                File renamed = new File(configPath + ".imported");
                if (!legacyFile.renameTo(renamed)) {
                    System.err.println("SARObjectManager: could not rename legacy "
                            + "JSON; will re-import next launch");
                }
            } catch (Exception e) {
                System.err.println("SARObjectManager: legacy import error: "
                        + e.getMessage());
            }
        }
    }

    /**
     * @deprecated SAR objects now persist via {@link PointStore}; this method
     *             is a no-op kept for source compatibility. Will be removed
     *             once all callers are migrated.
     */
    @Deprecated
    public void saveObjects() {
        // intentionally empty — persistence happens per-call in
        // addObject() / removeObject() / clearAll() via PointStore.
    }

    // --- JSON serialization ---

    private String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"objects\": [\n");

        for (int i = 0; i < objects.size(); i++) {
            SARObject obj = objects.get(i);
            sb.append("    {");
            sb.append("\"id\": \"").append(escapeJson(obj.getId())).append("\", ");
            sb.append("\"category\": \"").append(escapeJson(obj.getCategory())).append("\", ");
            sb.append("\"type\": \"").append(escapeJson(obj.getType())).append("\", ");
            sb.append("\"lat\": ").append(obj.getLat()).append(", ");
            sb.append("\"lon\": ").append(obj.getLon()).append(", ");
            sb.append("\"foundBy\": \"").append(escapeJson(
                    obj.getFoundBy() != null ? obj.getFoundBy() : "")).append("\", ");
            sb.append("\"notes\": \"").append(escapeJson(
                    obj.getNotes() != null ? obj.getNotes() : "")).append("\", ");
            sb.append("\"timestamp\": ").append(obj.getTimestamp());
            sb.append("}");
            if (i < objects.size() - 1) sb.append(",");
            sb.append("\n");
        }

        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private void parseJson(String json) {
        objects.clear();

        int objsIdx = json.indexOf("\"objects\"");
        if (objsIdx == -1) return;

        int arrayStart = json.indexOf('[', objsIdx);
        int arrayEnd = findMatchingBracket(json, arrayStart);
        if (arrayStart == -1 || arrayEnd == -1) return;

        String arrayContent = json.substring(arrayStart + 1, arrayEnd);

        int depth = 0;
        int objStart = -1;
        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    parseSARObject(arrayContent.substring(objStart, i + 1));
                    objStart = -1;
                }
            }
        }
    }

    private void parseSARObject(String json) {
        String id = extractJsonString(json, "id");
        String category = extractJsonString(json, "category");
        String type = extractJsonString(json, "type");
        Double lat = extractJsonDouble(json, "lat");
        Double lon = extractJsonDouble(json, "lon");
        String foundBy = extractJsonString(json, "foundBy");
        String notes = extractJsonString(json, "notes");
        Long timestamp = extractJsonLong(json, "timestamp");

        if (id == null || category == null || type == null
                || lat == null || lon == null) return;

        SARObject obj = new SARObject();
        obj.setId(id);
        obj.setCategory(category);
        obj.setType(type);
        obj.setLat(lat);
        obj.setLon(lon);
        obj.setFoundBy(foundBy != null ? foundBy : "");
        obj.setNotes(notes != null ? notes : "");
        obj.setTimestamp(timestamp != null ? timestamp : 0L);
        objects.add(obj);
    }

    // --- JSON helpers ---

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;

        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx == -1) return null;

        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart == -1) return null;

        int quoteEnd = quoteStart + 1;
        while (quoteEnd < json.length()) {
            char c = json.charAt(quoteEnd);
            if (c == '\\') {
                quoteEnd += 2;
            } else if (c == '"') {
                break;
            } else {
                quoteEnd++;
            }
        }

        return json.substring(quoteStart + 1, quoteEnd)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    private static Double extractJsonDouble(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;

        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx == -1) return null;

        int start = colonIdx + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;

        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (Character.isDigit(c) || c == '-' || c == '.' || c == 'E' || c == 'e' || c == '+') {
                end++;
            } else {
                break;
            }
        }

        if (end == start) return null;
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long extractJsonLong(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;

        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx == -1) return null;

        int start = colonIdx + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;

        int end = start;
        while (end < json.length() &&
                (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }

        if (end == start) return null;
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int findMatchingBracket(String json, int openPos) {
        if (openPos < 0 || openPos >= json.length()) return -1;
        char open = json.charAt(openPos);
        char close = (open == '[') ? ']' : '}';
        int depth = 1;
        boolean inString = false;

        for (int i = openPos + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && inString) {
                i++;
                continue;
            }
            if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == open) depth++;
                else if (c == close) {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    private static String readFile(File file) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(file));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        reader.close();
        return sb.toString();
    }
}
