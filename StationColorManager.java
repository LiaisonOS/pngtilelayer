package org.ka2ddo.yaac.gui.tile;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages callsign→color assignments for the StationPushpinLayer.
 * Persists to ~/.yaac/station-colors.json.
 */
public class StationColorManager {

    private static final String CONFIG_FILE = "station-colors.json";

    /** Default pushpin color (red) for unassigned stations. */
    private static final Color DEFAULT_COLOR = new Color(220, 40, 40);

    /** Preset palette of 10 high-contrast SAR colors. */
    public static final Color[] PRESET_COLORS = {
        new Color(220, 40, 40),    // Red
        new Color(30, 100, 220),   // Blue
        new Color(40, 180, 50),    // Green
        new Color(240, 150, 0),    // Orange
        new Color(150, 50, 200),   // Purple
        new Color(0, 180, 180),    // Teal
        new Color(200, 50, 150),   // Magenta
        new Color(120, 80, 40),    // Brown
        new Color(60, 60, 60),     // Dark Gray
        new Color(0, 120, 60),     // Dark Green
    };

    /** Human-readable names for the preset colors. */
    public static final String[] PRESET_NAMES = {
        "Red", "Blue", "Green", "Orange", "Purple",
        "Teal", "Magenta", "Brown", "Dark Gray", "Dark Green"
    };

    /** Default minimum distance (meters) between consecutive trail points. */
    public static final int DEFAULT_MIN_DISTANCE = 20;

    /** Default phase-out time (minutes). 0 = disabled. */
    public static final int DEFAULT_PHASE_OUT = 30;

    /** Default station opacity (fully opaque). */
    public static final int DEFAULT_OPACITY = 255;

    /** Default trail segment count (number of historical lines to show). */
    public static final int DEFAULT_TRAIL_COUNT = 3;

    private final Map<String, Color> stationColors = new LinkedHashMap<>();
    private final Map<String, Integer> stationMinDistance = new LinkedHashMap<>();
    private final Map<String, Integer> stationPhaseOut = new LinkedHashMap<>();
    private final Map<String, Integer> stationOpacity = new LinkedHashMap<>();
    private final Map<String, Integer> stationTrailCount = new LinkedHashMap<>();
    private String configPath;

    /**
     * Get the color assigned to a callsign, or the default red if unassigned.
     */
    public Color getColor(String callsign) {
        if (callsign == null) return DEFAULT_COLOR;
        Color c = stationColors.get(callsign.toUpperCase().trim());
        return c != null ? c : DEFAULT_COLOR;
    }

    /**
     * Assign a color to a callsign.
     */
    public void setColor(String callsign, Color c) {
        if (callsign == null || callsign.trim().isEmpty()) return;
        stationColors.put(callsign.toUpperCase().trim(), c);
    }

    /**
     * Remove a callsign's color assignment (reverts to default).
     */
    public void removeColor(String callsign) {
        if (callsign != null) {
            stationColors.remove(callsign.toUpperCase().trim());
        }
    }

    /**
     * Get the minimum distance (meters) for a callsign, or the default if unset.
     */
    public int getMinDistance(String callsign) {
        if (callsign == null) return DEFAULT_MIN_DISTANCE;
        Integer d = stationMinDistance.get(callsign.toUpperCase().trim());
        return d != null ? d : DEFAULT_MIN_DISTANCE;
    }

    /**
     * Set the minimum distance (meters) for a callsign.
     */
    public void setMinDistance(String callsign, int meters) {
        if (callsign == null || callsign.trim().isEmpty()) return;
        stationMinDistance.put(callsign.toUpperCase().trim(), meters);
    }

    /**
     * Get the phase-out time (minutes) for a callsign, or the default if unset.
     * 0 means disabled.
     */
    public int getPhaseOut(String callsign) {
        if (callsign == null) return DEFAULT_PHASE_OUT;
        Integer p = stationPhaseOut.get(callsign.toUpperCase().trim());
        return p != null ? p : DEFAULT_PHASE_OUT;
    }

    /**
     * Set the phase-out time (minutes) for a callsign. 0 = disabled.
     */
    public void setPhaseOut(String callsign, int minutes) {
        if (callsign == null || callsign.trim().isEmpty()) return;
        stationPhaseOut.put(callsign.toUpperCase().trim(), minutes);
    }

    /**
     * Largest phase-out (in minutes) across every configured station, or
     * {@link #DEFAULT_PHASE_OUT} if no overrides exist. Used as the global
     * enumeration cutoff when restoring saved stations from the point
     * store — the wider this is, the further back we'll search.
     */
    public int getMaxPhaseOut() {
        int max = DEFAULT_PHASE_OUT;
        for (Integer p : stationPhaseOut.values()) {
            if (p != null && p > max) max = p;
        }
        return max;
    }

    /**
     * Get the opacity (0-255) for a callsign, or the default if unset.
     */
    public int getOpacity(String callsign) {
        if (callsign == null) return DEFAULT_OPACITY;
        Integer o = stationOpacity.get(callsign.toUpperCase().trim());
        return o != null ? o : DEFAULT_OPACITY;
    }

    /**
     * Set the opacity (0-255) for a callsign.
     */
    public void setOpacity(String callsign, int opacity) {
        if (callsign == null || callsign.trim().isEmpty()) return;
        stationOpacity.put(callsign.toUpperCase().trim(),
                Math.max(0, Math.min(255, opacity)));
    }

    /**
     * Get the trail count for a callsign, or the default if unset.
     * 0 = no trail lines. Default = 3.
     */
    public int getTrailCount(String callsign) {
        if (callsign == null) return DEFAULT_TRAIL_COUNT;
        Integer t = stationTrailCount.get(callsign.toUpperCase().trim());
        return t != null ? t : DEFAULT_TRAIL_COUNT;
    }

    /**
     * Set the trail count for a callsign. 0 = no trail lines.
     */
    public void setTrailCount(String callsign, int count) {
        if (callsign == null || callsign.trim().isEmpty()) return;
        stationTrailCount.put(callsign.toUpperCase().trim(), Math.max(0, count));
    }

    /**
     * Get all current callsign→color assignments.
     */
    public Map<String, Color> getAssignments() {
        return Collections.unmodifiableMap(stationColors);
    }

    /**
     * Get the default color used for unassigned stations.
     */
    public Color getDefaultColor() {
        return DEFAULT_COLOR;
    }

    /**
     * Load color assignments from ~/.yaac/station-colors.json.
     */
    public void loadColors(String yaacConfigDir) {
        this.configPath = yaacConfigDir + File.separator + CONFIG_FILE;
        File configFile = new File(configPath);

        if (!configFile.exists()) return;

        try {
            String json = readFile(configFile);
            parseJson(json);
        } catch (Exception e) {
            System.err.println("StationColorManager: error loading " +
                    configPath + ": " + e.getMessage());
        }
    }

    /**
     * Save current assignments to JSON config file.
     */
    public void saveColors() {
        if (configPath == null) return;

        try {
            File configFile = new File(configPath);
            File parentDir = configFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            String json = toJson();
            BufferedWriter writer = new BufferedWriter(new FileWriter(configFile));
            writer.write(json);
            writer.close();
        } catch (IOException e) {
            System.err.println("StationColorManager: error saving config: " +
                    e.getMessage());
        }
    }

    // --- JSON serialization (hand-rolled, same pattern as TileSourceManager) ---

    private String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"stations\": [\n");

        int i = 0;
        for (Map.Entry<String, Color> entry : stationColors.entrySet()) {
            Color c = entry.getValue();
            sb.append("    {");
            sb.append("\"callsign\": \"").append(escapeJson(entry.getKey())).append("\", ");
            sb.append("\"r\": ").append(c.getRed()).append(", ");
            sb.append("\"g\": ").append(c.getGreen()).append(", ");
            sb.append("\"b\": ").append(c.getBlue());
            Integer minDist = stationMinDistance.get(entry.getKey());
            if (minDist != null) {
                sb.append(", \"minDistance\": ").append(minDist);
            }
            Integer phaseOut = stationPhaseOut.get(entry.getKey());
            if (phaseOut != null) {
                sb.append(", \"phaseOut\": ").append(phaseOut);
            }
            Integer opacity = stationOpacity.get(entry.getKey());
            if (opacity != null) {
                sb.append(", \"opacity\": ").append(opacity);
            }
            Integer trailCount = stationTrailCount.get(entry.getKey());
            if (trailCount != null) {
                sb.append(", \"trailCount\": ").append(trailCount);
            }
            sb.append("}");
            if (i < stationColors.size() - 1) sb.append(",");
            sb.append("\n");
            i++;
        }

        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private void parseJson(String json) {
        stationColors.clear();
        stationMinDistance.clear();
        stationPhaseOut.clear();
        stationOpacity.clear();
        stationTrailCount.clear();

        // Find "stations" array
        int stationsIdx = json.indexOf("\"stations\"");
        if (stationsIdx == -1) return;

        int arrayStart = json.indexOf('[', stationsIdx);
        int arrayEnd = findMatchingBracket(json, arrayStart);
        if (arrayStart == -1 || arrayEnd == -1) return;

        String arrayContent = json.substring(arrayStart + 1, arrayEnd);

        // Parse each station object
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
                    String objJson = arrayContent.substring(objStart, i + 1);
                    parseStationObject(objJson);
                    objStart = -1;
                }
            }
        }
    }

    private void parseStationObject(String json) {
        String callsign = extractJsonString(json, "callsign");
        if (callsign == null || callsign.trim().isEmpty()) return;

        Integer r = extractJsonInt(json, "r");
        Integer g = extractJsonInt(json, "g");
        Integer b = extractJsonInt(json, "b");

        if (r == null || g == null || b == null) return;

        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));

        String key = callsign.toUpperCase().trim();
        stationColors.put(key, new Color(r, g, b));

        Integer minDist = extractJsonInt(json, "minDistance");
        if (minDist != null) {
            stationMinDistance.put(key, Math.max(0, minDist));
        }

        Integer phaseOut = extractJsonInt(json, "phaseOut");
        if (phaseOut != null) {
            stationPhaseOut.put(key, Math.max(0, phaseOut));
        }

        Integer opacity = extractJsonInt(json, "opacity");
        if (opacity != null) {
            stationOpacity.put(key, Math.max(0, Math.min(255, opacity)));
        }

        Integer trailCount = extractJsonInt(json, "trailCount");
        if (trailCount != null) {
            stationTrailCount.put(key, Math.max(0, trailCount));
        }
    }

    // --- Minimal JSON helpers ---

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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
                .replace("\\\\", "\\");
    }

    private static Integer extractJsonInt(String json, String key) {
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
            return Integer.parseInt(json.substring(start, end));
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
