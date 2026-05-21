package org.ka2ddo.yaac.gui.tile;

import com.bbn.openmap.Layer;
import com.bbn.openmap.MapBean;
import com.bbn.openmap.event.MapMouseListener;
import com.bbn.openmap.event.ProjectionEvent;
import com.bbn.openmap.proj.Projection;
import com.bbn.openmap.proj.coords.LatLonPoint;
import com.bbn.openmap.proj.coords.UTMPoint;

import org.ka2ddo.aprs.MessageMessage;
import org.ka2ddo.aprs.ObjectReport;
import org.ka2ddo.aprs.PositionMessage;
import org.ka2ddo.yaac.io.SendableMessageWrapper;
import org.ka2ddo.ax25.AX25Message;
import org.ka2ddo.yaac.ax25.Age;
import org.ka2ddo.yaac.ax25.StationState;
import org.ka2ddo.yaac.ax25.StationTracker;
import org.ka2ddo.yaac.ax25.TrackerListener;
import org.ka2ddo.yaac.gui.GeographicalMap;
import org.ka2ddo.yaac.gui.GeoMapGuiIfc;
import org.ka2ddo.yaac.gui.GeoMapGuiIfc2;
import org.ka2ddo.yaac.gui.StationRenderer;
import org.ka2ddo.yaac.core.provider.CoreProvider;
import org.ka2ddo.yaac.io.BeaconData;
import org.ka2ddo.yaac.YAAC;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Map layer that draws colored pushpin markers with callsign labels
 * at each tracked station position, plus BluMap-style breadcrumb trails
 * showing the last 3 historical positions with graduated styling.
 * <p>
 * Z-order 55: above StationRenderer (50), below NWSZone (60).
 * Toggled via "Show Pushpin Markers" in the Map Sources dialog.
 * <p>
 * Trail rendering (BluMap style):
 * <ul>
 *   <li>Only last 3 historical positions are shown (plus current pushpin)</li>
 *   <li>Newest segment: solid line, filled breadcrumb</li>
 *   <li>Middle segment: dashed line, outline-only breadcrumb (alpha 0.6)</li>
 *   <li>Oldest segment: dashed line, outline-only breadcrumb (alpha 0.35)</li>
 * </ul>
 * <p>
 * Click on any pushpin or breadcrumb to see station info popup (callsign,
 * time received, UTM position).
 * <p>
 * When enabled, hides StationRenderer via setVisible(false) to suppress
 * APRS symbols, and delegates mouse events through StationRenderer's
 * listener for map panning and station click handling.
 */
public class StationPushpinLayer extends Layer
        implements TrackerListener, MapMouseListener {

    // --- Pushpin colors ---
    private static final Color PUSHPIN_COLOR = new Color(220, 40, 40);
    private static final Color PHASED_OUT_COLOR = new Color(128, 128, 128, 179);
    private static final Color PUSHPIN_BORDER = Color.DARK_GRAY;
    private static final Color PUSHPIN_HIGHLIGHT = Color.WHITE;
    private static final Color TEXT_COLOR = Color.BLACK;
    private static final Color TEXT_BG = new Color(255, 255, 255, 200);

    private static final Font LABEL_FONT = new Font("SansSerif", Font.BOLD, 11);
    private static final Stroke STEM_STROKE = new BasicStroke(2.0f);

    // --- Trail strokes ---
    private static final Stroke TRAIL_STROKE = new BasicStroke(4.5f,
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke TRAIL_STROKE_DASHED = new BasicStroke(4.5f,
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0f,
            new float[]{12.0f, 8.0f}, 0.0f);
    private static final Stroke CRUMB_STROKE_DASHED = new BasicStroke(1.5f,
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0f,
            new float[]{6.0f, 4.0f}, 0.0f);

    // --- Pushpin dimensions ---
    private static final int PIN_HEIGHT = 24;
    private static final int PIN_RADIUS = 6;
    private static final int HIGHLIGHT_RADIUS = 2;
    private static final int LABEL_PAD_X = 3;
    private static final int LABEL_PAD_Y = 2;
    private static final int LABEL_OFFSET_X = 8;
    private static final int LABEL_OFFSET_Y = -4;
    private static final int LABEL_ARC = 6;

    // --- Breadcrumb triangle dimensions ---
    private static final int CRUMB_SIZE = 14;
    private static final int CRUMB_SIZE_SMALL = 11;
    // Tip at origin (0,0) — anchored at the trail point, body extends behind
    private static final int[] TRI_X = {0, -CRUMB_SIZE, CRUMB_SIZE};
    private static final int[] TRI_Y = {0, CRUMB_SIZE * 3 / 2, CRUMB_SIZE * 3 / 2};
    private static final int[] TRI_X_SMALL = {0, -CRUMB_SIZE_SMALL, CRUMB_SIZE_SMALL};
    private static final int[] TRI_Y_SMALL = {0, CRUMB_SIZE_SMALL * 3 / 2, CRUMB_SIZE_SMALL * 3 / 2};

    /** Max historical trail points to display (BluMap style) */
    private static final int MAX_TRAIL_POINTS = 3;

    /** Fallback max age for breadcrumb trail points (24 hours) */
    private static final long DEFAULT_TRAIL_AGE_MS = 86400000L;

    /** Hit radius for click detection on pushpins/breadcrumbs */
    private static final int HIT_RADIUS = 26;

    // --- SAR diamond marker dimensions ---
    private static final int DIAMOND_HALF = 8;
    private static final int[] DIAMOND_X = {0, DIAMOND_HALF, 0, -DIAMOND_HALF};
    private static final int[] DIAMOND_Y = {-DIAMOND_HALF, 0, DIAMOND_HALF, 0};

    private static final SimpleDateFormat UTC_FORMAT;
    static {
        UTC_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
        UTC_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private final MapBean mapBean;
    private final GeoMapGuiIfc2 guiIfc;
    private boolean enabled = true;
    private StationColorManager colorManager;
    private SARObjectManager sarManager;

    /** Stations hidden via the visibility dialog */
    private final Set<String> hiddenStations = new HashSet<>();

    /**
     * High-water mark per callsign for incremental PointStore sync — only
     * positions strictly newer than the stored timestamp are INSERTed during
     * paint. Without this guard, every paint cycle replayed YAAC's full
     * message history into SQLite on the EDT, starving sibling layers
     * (notably the PNGTileLayer below us) of repaint time.
     */
    private final Map<String, Long> lastSyncedTime = new HashMap<>();

    /**
     * Operator's own callsign read from {@code ~/.config/liaisonos/user.json}.
     * Re-read at the start of each paint so a Dashboard change is picked up
     * without restarting YAAC. We deliberately exclude this from PointStore
     * persistence — the home station is always live in YAAC's tracker so
     * persisting it wastes DB space, and if the operator renames their
     * callsign mid-session, stale rows under the old name would surface as
     * phantom pushpins on the next replay.
     */
    private String homeCallsign;

    private static String readHomeCallsign() {
        java.io.File f = new java.io.File(System.getProperty("user.home"),
                ".config/liaisonos/user.json");
        if (!f.exists()) return null;
        try {
            String content = new String(
                    java.nio.file.Files.readAllBytes(f.toPath()));
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"callsign\"\\s*:\\s*\"([^\"]*)\"")
                    .matcher(content);
            if (m.find()) {
                String cs = m.group(1).trim().toUpperCase();
                if (!cs.isEmpty() && !cs.equals("N0CALL")) return cs;
            }
        } catch (Exception e) {
            // silent — fall back to no filter
        }
        return null;
    }

    /** Screen-space points from last paint, for click hit-testing */
    private final List<RenderedPoint> renderedPoints = new ArrayList<>();

    /** Highlight line state — temp dashed line from clicked breadcrumb to next point */
    private double highlightFromLat = Double.NaN, highlightFromLon = Double.NaN;
    private double highlightToLat = Double.NaN, highlightToLon = Double.NaN;
    private Color highlightColor = null;

    /** Panning state — own implementation to avoid double-processing via delegation */
    private boolean panMouseDown;
    private boolean panDragged;
    private int panStartX, panStartY;
    private Point2D panLastCenter;

    public StationPushpinLayer(MapBean mapBean, GeoMapGuiIfc2 guiIfc) {
        this.mapBean = mapBean;
        this.guiIfc = guiIfc;
        setName("Station Pushpins");
        StationTracker.getInstance().addTrackerListener(this);
        syncStationRendererVisibility();
    }

    public void setColorManager(StationColorManager mgr) {
        this.colorManager = mgr;
    }

    public StationColorManager getColorManager() {
        return colorManager;
    }

    public void setSARObjectManager(SARObjectManager mgr) {
        this.sarManager = mgr;
    }

    public SARObjectManager getSARObjectManager() {
        return sarManager;
    }

    // --- Station visibility ---

    public boolean isStationVisible(String callsign) {
        return !hiddenStations.contains(callsign.toUpperCase().trim());
    }

    public void setStationVisible(String callsign, boolean visible) {
        String key = callsign.toUpperCase().trim();
        if (visible) {
            hiddenStations.remove(key);
        } else {
            hiddenStations.add(key);
        }
        repaint();
    }

    // --- MapMouseListener ---

    @Override
    public MapMouseListener getMapMouseListener() {
        return this;
    }

    @Override
    public String[] getMouseModeServiceList() {
        return new String[]{"Gestures"};
    }

    @Override
    public boolean mouseClicked(MouseEvent e) {
        if (!enabled) {
            MapMouseListener delegate = getStationRendererListener();
            return delegate != null && delegate.mouseClicked(e);
        }

        // Right-click (or Ctrl/Meta+click) on empty area → SAR + map context menu
        if (e.getButton() == MouseEvent.BUTTON3
                || (e.getButton() == MouseEvent.BUTTON1
                    && (e.getModifiersEx() & (MouseEvent.CTRL_DOWN_MASK
                        | MouseEvent.META_DOWN_MASK)) != 0)) {
            RenderedPoint hit = findHitPoint(e.getX(), e.getY());
            if (hit == null && sarManager != null) {
                showSARContextMenu(e.getComponent(), e.getX(), e.getY());
                return true;
            }
            // If hit something, fall through to YAAC's right-click
        }

        if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 1) {
            RenderedPoint hit = findHitPoint(e.getX(), e.getY());
            if (hit != null) {
                // SAR marker click
                if (hit.callsign.startsWith("SAR:")) {
                    showSARObjectPopup(e.getComponent(), e.getX(), e.getY(), hit);
                    return true;
                }
                // Show temp dashed line to next point on click
                if (!hit.isCurrent && !Double.isNaN(hit.nextLat)) {
                    highlightFromLat = hit.lat;
                    highlightFromLon = hit.lon;
                    highlightToLat = hit.nextLat;
                    highlightToLon = hit.nextLon;
                    highlightColor = colorManager != null
                            ? colorManager.getColor(hit.callsign)
                            : PUSHPIN_COLOR;
                    repaint();
                } else {
                    highlightFromLat = Double.NaN;
                    highlightColor = null;
                }
                showPointPopup(e.getComponent(), e.getX(), e.getY(), hit);
                return true;
            }
        }
        MapMouseListener delegate = getStationRendererListener();
        if (delegate != null) {
            return delegate.mouseClicked(e);
        }
        return false;
    }

    @Override
    public boolean mousePressed(MouseEvent e) {
        if (!enabled) {
            MapMouseListener delegate = getStationRendererListener();
            return delegate != null && delegate.mousePressed(e);
        }
        panMouseDown = true;
        panDragged = false;
        panStartX = e.getX();
        panStartY = e.getY();
        Projection proj = getProjection();
        if (proj != null) {
            panLastCenter = proj.getCenter();
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseEvent e) {
        if (!enabled) {
            MapMouseListener delegate = getStationRendererListener();
            return delegate != null && delegate.mouseReleased(e);
        }
        panMouseDown = false;
        panDragged = false;
        return true;
    }

    @Override
    public void mouseEntered(MouseEvent e) {
        MapMouseListener delegate = getStationRendererListener();
        if (delegate != null) delegate.mouseEntered(e);
    }

    @Override
    public void mouseExited(MouseEvent e) {
        MapMouseListener delegate = getStationRendererListener();
        if (delegate != null) delegate.mouseExited(e);
    }

    @Override
    public boolean mouseDragged(MouseEvent e) {
        if (!enabled) {
            MapMouseListener delegate = getStationRendererListener();
            return delegate != null && delegate.mouseDragged(e);
        }
        if (panMouseDown) {
            panDragged = true;
            Projection proj = getProjection();
            if (proj != null && panLastCenter != null) {
                Point2D oldCenter = proj.forward(panLastCenter);
                int newCx = (int) oldCenter.getX() + (panStartX - e.getX());
                int newCy = (int) oldCenter.getY() + (panStartY - e.getY());
                Point2D newCenter = proj.inverse(newCx, newCy);
                mapBean.setCenter(newCenter);
            }
        }
        return true;
    }

    @Override
    public boolean mouseMoved(MouseEvent e) {
        if (!enabled) {
            MapMouseListener delegate = getStationRendererListener();
            return delegate != null && delegate.mouseMoved(e);
        }
        return true;
    }

    @Override
    public void mouseMoved() {
    }

    private MapMouseListener getStationRendererListener() {
        if (!(guiIfc instanceof GeographicalMap)) return null;
        StationRenderer sr = ((GeographicalMap) guiIfc).getStationRenderer();
        if (sr != null) {
            return sr.getMapMouseListener();
        }
        return null;
    }

    // --- Painting ---

    @Override
    public void paint(Graphics g) {
        if (!enabled) return;

        Projection proj = getProjection();
        if (proj == null) return;

        // Refresh home callsign (cheap file read) so a Dashboard change picks up
        // immediately. Used to skip persisting our own beacons to PointStore.
        homeCallsign = readHomeCallsign();

        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int viewWidth = mapBean.getWidth();
        int viewHeight = mapBean.getHeight();

        // Grid overlay paints first so it sits under everything we draw below.
        if (MapDisplaySettings.getInstance().isShowGrid()) {
            UtmGridRenderer.paint(g2, proj, viewWidth, viewHeight,
                    MapDisplaySettings.getInstance().getGridSpacingMeters());
        }

        renderedPoints.clear();

        StationState[] stations = StationTracker.getInstance()
                .getCurrentTrackedObjectArray();
        if (stations == null) return;

        for (StationState ss : stations) {
            if (ss == null) continue;

            if (hiddenStations.contains(
                    ss.getIdentifier().toUpperCase().trim())) {
                continue;
            }

            double lat = ss.getLatitude();
            double lon = ss.getLongitude();
            if (lat == 0.0 && lon == 0.0) continue;

            Color stationColor = colorManager != null
                    ? colorManager.getColor(ss.getIdentifier())
                    : PUSHPIN_COLOR;

            // Apply per-station opacity
            if (colorManager != null) {
                int opacity = colorManager.getOpacity(ss.getIdentifier());
                if (opacity < 255) {
                    stationColor = new Color(stationColor.getRed(),
                            stationColor.getGreen(), stationColor.getBlue(),
                            opacity);
                }
            }

            List<double[]> trail = buildTrail(ss);

            // Phase-out check: find most recent point time
            int phaseOutMin = colorManager != null
                    ? colorManager.getPhaseOut(ss.getIdentifier())
                    : StationColorManager.DEFAULT_PHASE_OUT;
            if (phaseOutMin > 0) {
                long lastPointTime = 0;
                for (double[] pt : trail) {
                    long t = (long) pt[2];
                    if (t > lastPointTime) lastPointTime = t;
                }
                if (lastPointTime > 0) {
                    long elapsed = System.currentTimeMillis() - lastPointTime;
                    if (elapsed > phaseOutMin * 60000L) {
                        stationColor = PHASED_OUT_COLOR;
                    }
                }
            }

            int trailCount = colorManager != null
                    ? colorManager.getTrailCount(ss.getIdentifier())
                    : StationColorManager.DEFAULT_TRAIL_COUNT;
            if (trail.size() > 1 && trailCount > 0) {
                renderTrail(g2, proj, trail, viewWidth, viewHeight,
                        stationColor, ss.getIdentifier(), trailCount);
            }

            Point2D screenPt = proj.forward(lat, lon);
            float px = (float) screenPt.getX();
            float py = (float) screenPt.getY();

            if (px >= -100 && px <= viewWidth + 100 &&
                py >= -100 && py <= viewHeight + 100) {
                renderPushpin(g2, px, py, ss.getIdentifier(), stationColor);
                renderedPoints.add(new RenderedPoint(
                        px, py, ss.getIdentifier(), lat, lon, 0L, true,
                        Double.NaN, Double.NaN, true));
            }
        }

        // Render stations that exist ONLY in PointStore (not in YAAC's in-memory
        // tracker yet). Happens after restart/crash when persisted history is
        // ahead of the live APRS feed.
        long savedMaxAge = DEFAULT_TRAIL_AGE_MS;
        if (guiIfc instanceof GeographicalMap) {
            savedMaxAge = ((GeographicalMap) guiIfc).getMaxTrackDuration();
        }
        long cutoff = System.currentTimeMillis() - savedMaxAge;
        PointStore store = PointStore.getInstance();
        if (store != null) {
            // Build a set of callsigns we already drew via the live path so
            // we don't render duplicates.
            java.util.Set<String> liveCallsigns = new java.util.HashSet<>();
            for (StationState ss : stations) {
                if (ss != null) liveCallsigns.add(ss.getIdentifier());
            }
            for (String savedCs : store.getActiveCallsigns(cutoff)) {
                if (liveCallsigns.contains(savedCs)) continue;
                if (hiddenStations.contains(savedCs.toUpperCase().trim())) continue;
                // Don't restore phantom rows under the operator's old callsign.
                if (homeCallsign != null && homeCallsign.equalsIgnoreCase(savedCs)) continue;

                List<double[]> trail = store.getStationPositions(savedCs, cutoff);
                if (trail.isEmpty()) continue;

                // Apply min-distance filter for visual clarity (same rule as
                // live trails).
                int minDistMeters = StationColorManager.DEFAULT_MIN_DISTANCE;
                if (colorManager != null) {
                    minDistMeters = colorManager.getMinDistance(savedCs);
                }
                if (minDistMeters > 0 && trail.size() > 1) {
                    List<double[]> filtered = new ArrayList<>();
                    filtered.add(trail.get(0));
                    for (int j = 1; j < trail.size(); j++) {
                        double[] prev = filtered.get(filtered.size() - 1);
                        double[] cur = trail.get(j);
                        if (haversineMeters(prev[0], prev[1], cur[0], cur[1])
                                >= minDistMeters) {
                            filtered.add(cur);
                        }
                    }
                    trail = filtered;
                }

                double[] last = trail.get(trail.size() - 1);
                Point2D p = proj.forward(last[0], last[1]);
                float px = (float) p.getX();
                float py = (float) p.getY();
                if (!(px >= -50 && px <= viewWidth + 50
                        && py >= -50 && py <= viewHeight + 50)) {
                    continue;
                }

                Color savedColor = colorManager != null
                        ? colorManager.getColor(savedCs)
                        : PUSHPIN_COLOR;
                // Faded a touch so operators can distinguish saved-only from
                // currently-tracked stations at a glance.
                Color fadedColor = new Color(savedColor.getRed(),
                        savedColor.getGreen(), savedColor.getBlue(), 180);

                int trailCount = colorManager != null
                        ? colorManager.getTrailCount(savedCs)
                        : MAX_TRAIL_POINTS;
                if (trail.size() > 1 && trailCount > 0) {
                    renderTrail(g2, proj, trail, viewWidth, viewHeight,
                            fadedColor, savedCs, trailCount);
                }
                renderPushpin(g2, px, py, savedCs, fadedColor);
                renderedPoints.add(new RenderedPoint(
                        px, py, savedCs, last[0], last[1],
                        (long) last[2], false, Double.NaN, Double.NaN, false));
            }
        }

        // Draw SAR object markers
        if (sarManager != null) {
            for (SARObject sarObj : sarManager.getObjects()) {
                Point2D sp = proj.forward(sarObj.getLat(), sarObj.getLon());
                float sx = (float) sp.getX();
                float sy = (float) sp.getY();

                if (sx >= -50 && sx <= viewWidth + 50
                        && sy >= -50 && sy <= viewHeight + 50) {
                    renderSARMarker(g2, sx, sy, sarObj);
                    renderedPoints.add(new RenderedPoint(
                            sx, sy, "SAR:" + sarObj.getId(),
                            sarObj.getLat(), sarObj.getLon(), sarObj.getTimestamp(),
                            false, Double.NaN, Double.NaN, true));
                }
            }
        }

        // Draw highlight line (temp dashed line from clicked breadcrumb to next)
        if (!Double.isNaN(highlightFromLat) && highlightColor != null) {
            Point2D from = proj.forward(highlightFromLat, highlightFromLon);
            Point2D to = proj.forward(highlightToLat, highlightToLon);
            g2.setColor(new Color(highlightColor.getRed(),
                    highlightColor.getGreen(), highlightColor.getBlue(), 140));
            g2.setStroke(TRAIL_STROKE_DASHED);
            g2.drawLine((int) from.getX(), (int) from.getY(),
                        (int) to.getX(), (int) to.getY());
        }
    }

    /**
     * Haversine distance between two lat/lon points in meters.
     */
    private static double haversineMeters(double lat1, double lon1,
                                           double lat2, double lon2) {
        double R = 6371000.0; // Earth radius in meters
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Geographic bearing from one point to another (BluMap CalculateBearing).
     * Returns degrees 0-360 where 0=North, 90=East.
     */
    private static double calculateBearing(double lat1, double lon1,
                                            double lat2, double lon2) {
        double lat1r = Math.toRadians(lat1);
        double lat2r = Math.toRadians(lat2);
        double dLon = Math.toRadians(lon2 - lon1);

        double y = Math.sin(dLon) * Math.cos(lat2r);
        double x = Math.cos(lat1r) * Math.sin(lat2r)
                 - Math.sin(lat1r) * Math.cos(lat2r) * Math.cos(dLon);

        double bearing = Math.atan2(y, x) * 180.0 / Math.PI;
        return (bearing + 360) % 360;
    }

    /**
     * Build a trail of positions from the station's message history.
     * Returns list ordered oldest-first, with current position as last entry.
     * Each entry is {lat, lon, rcptTime}.
     * Consecutive points within minDistance meters are skipped to avoid
     * visual clutter (star patterns from stationary stations).
     */
    private List<double[]> buildTrail(StationState ss) {
        long maxAge = DEFAULT_TRAIL_AGE_MS;
        if (guiIfc instanceof GeographicalMap) {
            maxAge = ((GeographicalMap) guiIfc).getMaxTrackDuration();
        }
        long cutoff = System.currentTimeMillis() - maxAge;
        String ident = ss.getIdentifier();

        int minDistMeters = StationColorManager.DEFAULT_MIN_DISTANCE;
        if (colorManager != null) {
            minDistMeters = colorManager.getMinDistance(ident);
        }

        PointStore store = PointStore.getInstance();

        // ─── Phase 1: incrementally persist any positions newer than what
        // we've already synced for this station. The naive approach of
        // re-syncing every message on every paint stalled the EDT (the SQL
        // round-trip per row, multiplied by every position in YAAC's history,
        // multiplied by every paint cycle) and starved sibling layers like
        // PNGTileLayer of repaint time. We now keep an in-memory high-water
        // mark per callsign and only INSERT rows we haven't seen yet.
        // Skip persistence entirely for the operator's own callsign — it's
        // always live in YAAC's tracker so DB rows would only ever be dead
        // weight, and renaming the callsign in the Dashboard would leave
        // stale rows under the previous name.
        boolean isHome = homeCallsign != null && homeCallsign.equalsIgnoreCase(ident);

        if (store != null && !isHome) {
            long lastSynced = lastSyncedTime.getOrDefault(ident, 0L);
            long newWatermark = lastSynced;

            // Pass 1: historical PositionMessages from YAAC's message history.
            for (int i = 1; i < ss.size(); i++) {
                AX25Message msg = ss.get(i);
                if (msg == null) continue;
                if (msg.rcptTime < cutoff) continue;
                if (msg.rcptTime <= lastSynced) continue;    // already synced
                if (msg instanceof PositionMessage) {
                    if (msg instanceof ObjectReport) {
                        String objName = ((ObjectReport) msg).objectName;
                        if (objName != null && !objName.equals(ident)) continue;
                    }
                    PositionMessage pm = (PositionMessage) msg;
                    double lat = pm.decodeLatitude();
                    double lon = pm.decodeLongitude();
                    if (lat == 0.0 && lon == 0.0) continue;
                    store.addStationPosition(ident, lat, lon, msg.rcptTime,
                            null, null, null, null, null);
                    if (msg.rcptTime > newWatermark) newWatermark = msg.rcptTime;
                }
            }

            // Pass 2: current live position. For freshly-tracked stations the
            // current ss.getLatitude/Longitude isn't yet in the message-history
            // loop above — without this insert, a station that sent exactly one
            // beacon never reaches SQLite. The (callsign, timestamp) UNIQUE
            // constraint keeps repeat inserts cheap.
            long curTime = ss.getLastPosTime();
            if (curTime > 0 && curTime >= cutoff && curTime > lastSynced) {
                double curLat = ss.getLatitude();
                double curLon = ss.getLongitude();
                if (curLat != 0.0 || curLon != 0.0) {
                    store.addStationPosition(ident, curLat, curLon, curTime,
                            null, null, null, null, null);
                    if (curTime > newWatermark) newWatermark = curTime;
                }
            }

            if (newWatermark > lastSynced) {
                lastSyncedTime.put(ident, newWatermark);
            }
        }

        // ─── Phase 2: source the trail from SQLite (canonical) when
        // available, else fall back to the live in-memory history.
        List<double[]> trail;
        if (store != null) {
            trail = store.getStationPositions(ident, cutoff);
        } else {
            trail = new ArrayList<>();
            for (int i = 1; i < ss.size(); i++) {
                AX25Message msg = ss.get(i);
                if (msg == null) continue;
                if (msg.rcptTime < cutoff) continue;
                if (msg instanceof PositionMessage) {
                    if (msg instanceof ObjectReport) {
                        String objName = ((ObjectReport) msg).objectName;
                        if (objName != null && !objName.equals(ident)) continue;
                    }
                    PositionMessage pm = (PositionMessage) msg;
                    double lat = pm.decodeLatitude();
                    double lon = pm.decodeLongitude();
                    if (lat == 0.0 && lon == 0.0) continue;
                    trail.add(new double[]{lat, lon, (double) msg.rcptTime});
                }
            }
            trail.sort((a, b) -> Double.compare(a[2], b[2]));
        }

        // ─── Phase 3: visual min-distance filter (drops zigzag clutter
        // from stationary stations that report every minute).
        if (minDistMeters > 0 && trail.size() > 1) {
            List<double[]> filtered = new ArrayList<>();
            filtered.add(trail.get(0));
            for (int j = 1; j < trail.size(); j++) {
                double[] prev = filtered.get(filtered.size() - 1);
                double[] cur = trail.get(j);
                if (haversineMeters(prev[0], prev[1], cur[0], cur[1]) >= minDistMeters) {
                    filtered.add(cur);
                }
            }
            trail = filtered;
        }

        // ─── Phase 4: current position as sentinel.
        trail.add(new double[]{ss.getLatitude(), ss.getLongitude(),
                (double) Long.MAX_VALUE});
        return trail;
    }

    /**
     * Render BluMap-style breadcrumb trail with graduated styling.
     * <ul>
     *   <li>Newest segment (to current): solid line, filled breadcrumb</li>
     *   <li>Middle segment: dashed line, outline-only breadcrumb, alpha 0.6</li>
     *   <li>Oldest segment: dashed line, outline-only breadcrumb, alpha 0.35</li>
     * </ul>
     */
    private void renderTrail(Graphics2D g2, Projection proj,
                              List<double[]> trail, int viewWidth, int viewHeight,
                              Color pinColor, String callsign, int maxSegments) {
        Stroke origStroke = g2.getStroke();
        int segCount = trail.size() - 1;

        // Global track style — read once per trail render
        TrackSettings ts = TrackSettings.getInstance();
        TrackSettings.Shape       shape    = ts.getShape();
        TrackSettings.MarkerSize  szPref   = ts.getMarkerSize();
        TrackSettings.LineStyle   linePref = ts.getLineStyle();
        TrackSettings.Coverage    covPref  = ts.getCoverage();

        float baseSize = (szPref == TrackSettings.MarkerSize.SMALL)
                ? CRUMB_SIZE * 0.7f
                : (float) CRUMB_SIZE;

        // Coverage clamp: Last-3 caps visible line segments at 3.
        int effectiveMax = (covPref == TrackSettings.Coverage.LAST_3)
                ? Math.min(maxSegments, 3)
                : maxSegments;

        float[] sx = new float[trail.size()];
        float[] sy = new float[trail.size()];
        for (int i = 0; i < trail.size(); i++) {
            double[] pt = trail.get(i);
            Point2D sp = proj.forward(pt[0], pt[1]);
            sx[i] = (float) sp.getX();
            sy[i] = (float) sp.getY();
        }

        int baseAlpha = pinColor.getAlpha();
        Color crumbColor = new Color(pinColor.getRed(), pinColor.getGreen(),
                pinColor.getBlue(), baseAlpha);

        // Draw lines only for the last effectiveMax segments (graduated styling)
        int lineStart = Math.max(0, segCount - effectiveMax);
        for (int i = lineStart; i < segCount; i++) {
            int ageFromNewest = segCount - 1 - i;
            int visibleCount = segCount - lineStart;

            float alpha;
            Stroke lineStroke;
            if (ageFromNewest == 0) {
                alpha = 1.0f;
                // Newest segment: always solid (anchors the eye to "now")
                lineStroke = TRAIL_STROKE;
            } else {
                // Graduated: newest=1.0, oldest=0.35, linear fade between
                alpha = 1.0f - 0.65f * ((float) ageFromNewest / Math.max(1, visibleCount - 1));
                lineStroke = (linePref == TrackSettings.LineStyle.SOLID)
                        ? TRAIL_STROKE
                        : TRAIL_STROKE_DASHED;
            }

            Color trailColor = new Color(pinColor.getRed(), pinColor.getGreen(),
                    pinColor.getBlue(),
                    Math.min(255, (int) (180 * alpha * baseAlpha / 255.0)));
            g2.setColor(trailColor);
            g2.setStroke(lineStroke);
            g2.drawLine((int) sx[i], (int) sy[i],
                        (int) sx[i + 1], (int) sy[i + 1]);
        }

        // Draw markers at ALL historical positions
        for (int i = 0; i < segCount; i++) {
            if (sx[i] >= -50 && sx[i] <= viewWidth + 50 &&
                sy[i] >= -50 && sy[i] <= viewHeight + 50) {

                // Direction toward next point (used for triangle orientation)
                float dx = sx[i + 1] - sx[i];
                float dy = sy[i + 1] - sy[i];
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len >= 1f) {
                    float nx = dx / len;
                    float ny = dy / len;
                    renderBreadcrumb(g2, sx[i], sy[i], nx, ny, crumbColor,
                            shape, baseSize);
                }

                double[] pt = trail.get(i);
                // "Next" = toward current position (i+1 in trail)
                double[] nextPt = trail.get(i + 1);
                double nLat = nextPt[0];
                double nLon = nextPt[1];
                boolean hasLine = (i >= lineStart);
                renderedPoints.add(new RenderedPoint(
                        sx[i], sy[i], callsign,
                        pt[0], pt[1], (long) pt[2], false,
                        nLat, nLon, hasLine));
            }
        }

        g2.setStroke(origStroke);
    }

    /**
     * Render a breadcrumb marker — tip/center at (px,py). Shape and size are
     * taken from global TrackSettings. Triangles point toward (nx,ny);
     * circles ignore direction.
     */
    private void renderBreadcrumb(Graphics2D g2, float px, float py,
                                   float nx, float ny, Color crumbColor,
                                   TrackSettings.Shape shape, float baseSize) {
        g2.setColor(crumbColor);

        if (shape == TrackSettings.Shape.CIRCLE) {
            // Circles render smaller than triangles — no narrow tip means
            // they read as bulkier at the same base size. Large circle ≈
            // small triangle; small circle is another 30% smaller.
            float circleBase = baseSize * 0.7f;
            float radius = circleBase * 1.1f;
            int d = Math.round(radius * 2f);
            g2.fillOval(Math.round(px - radius), Math.round(py - radius), d, d);
            return;
        }

        // Triangle (default) — perpendicular to direction
        float perpX = -ny;
        float perpY = nx;

        float h = baseSize * 2.2f;  // long sides (height)
        float w = baseSize * 0.7f;  // base (half-width)

        // Tip at trail point, body extends BEHIND (opposite to direction)
        int[] triX = {
            (int) px,                                    // tip
            (int) (px - nx * h + perpX * w),             // base left
            (int) (px - nx * h - perpX * w)              // base right
        };
        int[] triY = {
            (int) py,                                    // tip
            (int) (py - ny * h + perpY * w),             // base left
            (int) (py - ny * h - perpY * w)              // base right
        };

        g2.fillPolygon(triX, triY, 3);
    }

    private void renderPushpin(Graphics2D g2, float px, float py,
                                String callsign, Color pinColor) {
        Stroke origStroke = g2.getStroke();

        g2.setColor(PUSHPIN_BORDER);
        g2.setStroke(STEM_STROKE);
        g2.drawLine((int) px, (int) py,
                    (int) px, (int) (py - PIN_HEIGHT));

        int headX = (int) (px - PIN_RADIUS);
        int headY = (int) (py - PIN_HEIGHT - PIN_RADIUS);
        int diameter = PIN_RADIUS * 2;
        g2.setColor(pinColor);
        g2.fillOval(headX, headY, diameter, diameter);

        g2.setColor(PUSHPIN_BORDER);
        g2.setStroke(origStroke);
        g2.drawOval(headX, headY, diameter, diameter);

        g2.setColor(PUSHPIN_HIGHLIGHT);
        int hlX = (int) (px - HIGHLIGHT_RADIUS);
        int hlY = (int) (py - PIN_HEIGHT - HIGHLIGHT_RADIUS - 1);
        g2.fillOval(hlX, hlY, HIGHLIGHT_RADIUS * 2, HIGHLIGHT_RADIUS * 2);

        if (callsign == null || callsign.isEmpty()) return;

        g2.setFont(LABEL_FONT);
        FontMetrics fm = g2.getFontMetrics();
        int textWidth = fm.stringWidth(callsign);
        int textHeight = fm.getHeight();

        int labelX = (int) (px + LABEL_OFFSET_X);
        int labelY = (int) (py - PIN_HEIGHT + LABEL_OFFSET_Y - textHeight);

        g2.setColor(TEXT_BG);
        g2.fillRoundRect(labelX - LABEL_PAD_X,
                          labelY - LABEL_PAD_Y,
                          textWidth + LABEL_PAD_X * 2,
                          textHeight + LABEL_PAD_Y * 2,
                          LABEL_ARC, LABEL_ARC);

        g2.setColor(PUSHPIN_BORDER);
        g2.drawRoundRect(labelX - LABEL_PAD_X,
                          labelY - LABEL_PAD_Y,
                          textWidth + LABEL_PAD_X * 2,
                          textHeight + LABEL_PAD_Y * 2,
                          LABEL_ARC, LABEL_ARC);

        g2.setColor(TEXT_COLOR);
        g2.drawString(callsign, labelX, labelY + fm.getAscent());
    }

    // --- Click hit-testing and popup ---

    private RenderedPoint findHitPoint(int mx, int my) {
        for (int i = renderedPoints.size() - 1; i >= 0; i--) {
            RenderedPoint rp = renderedPoints.get(i);
            float tx = rp.screenX;
            float ty = rp.screenY;
            if (rp.isCurrent) {
                ty -= PIN_HEIGHT;
            }
            float dx = mx - tx;
            float dy = my - ty;
            if (dx * dx + dy * dy <= HIT_RADIUS * HIT_RADIUS) {
                return rp;
            }
        }
        return null;
    }

    private void showPointPopup(Component invoker, int x, int y,
                                 RenderedPoint rp) {
        JPopupMenu popup = new JPopupMenu();
        popup.setLayout(new BorderLayout());

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        panel.setBackground(Color.WHITE);

        // Header: "FirstName - CALLSIGN" when we can resolve the operator,
        // otherwise just the callsign. Name comes from the LiaisonOS license
        // DB (same data used by et-logger / et-predict).
        String operatorName = CallsignDatabase.lookupName(rp.callsign);
        String header = rp.callsign;
        if (operatorName != null && !operatorName.isEmpty()) {
            String firstName = operatorName.trim().split("\\s+", 2)[0];
            if (!firstName.isEmpty()) {
                header = firstName + " - " + rp.callsign;
            }
        }
        JLabel callLabel = new JLabel(header);
        callLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        callLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(callLabel);

        panel.add(Box.createVerticalStrut(4));

        if (rp.rcptTime > 0) {
            String timeStr;
            synchronized (UTC_FORMAT) {
                timeStr = UTC_FORMAT.format(new Date(rp.rcptTime));
            }
            String ageStr = new Age(
                    System.currentTimeMillis() - rp.rcptTime).toString();
            JLabel timeLabel = new JLabel(
                    "Time: " + timeStr + "  (" + ageStr + ")");
            timeLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
            timeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(timeLabel);
        } else {
            JLabel timeLabel = new JLabel("Current position");
            timeLabel.setFont(new Font("SansSerif", Font.ITALIC, 11));
            timeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(timeLabel);
        }

        panel.add(Box.createVerticalStrut(2));

        UTMPoint utm = new UTMPoint(
                new LatLonPoint.Double(rp.lat, rp.lon));
        String utmStr = utm.zone_number + "" + utm.zone_letter + " "
                + Math.round(utm.easting) + "E "
                + Math.round(utm.northing) + "N";
        JLabel utmLabel = new JLabel("UTM: " + utmStr);
        utmLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        utmLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(utmLabel);

        popup.add(panel, BorderLayout.CENTER);

        // Clear highlight line when popup closes
        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {}
            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                highlightFromLat = Double.NaN;
                highlightColor = null;
                repaint();
            }
            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                highlightFromLat = Double.NaN;
                highlightColor = null;
                repaint();
            }
        });

        // Show popup at bottom-right of the map
        int popupW = panel.getPreferredSize().width + 16;
        int popupH = panel.getPreferredSize().height + 16;
        int mapW = invoker.getWidth();
        int mapH = invoker.getHeight();
        popup.show(invoker, mapW - popupW - 10, mapH - popupH - 10);
    }

    // --- SAR context menu and rendering ---

    private void showSARContextMenu(Component invoker, int x, int y) {
        Projection proj = getProjection();
        if (proj == null) return;

        Point2D latLon = proj.inverse(x, y);
        final double clickLat = latLon.getY();
        final double clickLon = latLon.getX();

        JPopupMenu menu = new JPopupMenu();
        JMenuItem addItem = new JMenuItem("Add SAR Object...");
        addItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Window parent = SwingUtilities.getWindowAncestor(invoker);
                SARObjectChooserDialog dialog = new SARObjectChooserDialog(
                        parent, sarManager.getCatalog(), clickLat, clickLon);
                dialog.setVisible(true);

                if (dialog.isConfirmed()) {
                    SARObject obj = new SARObject(
                            dialog.getCategory(), dialog.getSelectedType(),
                            clickLat, clickLon,
                            dialog.getFoundBy(), dialog.getNotes());
                    sarManager.addObject(obj);

                    if (dialog.isBroadcast()) {
                        int confirm = JOptionPane.showConfirmDialog(parent,
                                "This will transmit on air via APRS.\nProceed?",
                                "Confirm Broadcast",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.WARNING_MESSAGE);
                        if (confirm == JOptionPane.YES_OPTION) {
                            broadcastSARObject(obj);
                        }
                    }

                    repaint();
                }
            }
        });
        menu.add(addItem);

        menu.addSeparator();

        // Create APRS Object
        JMenuItem objItem = new JMenuItem("Create APRS Object...");
        objItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                YAAC.getGui().invokeObjectEditor("CreateObject.title", null,
                        x, y,
                        new CoreProvider.TransmitGeneratedObjectReport(null));
            }
        });
        menu.add(objItem);

        // Move Home Here
        JMenuItem homeItem = new JMenuItem("Move Home Here");
        homeItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                UTMPoint utm = new UTMPoint(
                        new LatLonPoint.Double(clickLat, clickLon));
                String pos = utm.zone_number + "" + utm.zone_letter + " "
                        + Math.round(utm.easting) + "E "
                        + Math.round(utm.northing) + "N";
                int answer = JOptionPane.showConfirmDialog(
                        SwingUtilities.getWindowAncestor(invoker),
                        "Move Home to " + pos + "?",
                        "Move Home Here",
                        JOptionPane.YES_NO_OPTION);
                if (answer == JOptionPane.YES_OPTION) {
                    for (java.util.Map.Entry<String, BeaconData> entry
                            : YAAC.getBeaconDataMap().entrySet()) {
                        BeaconData bd = entry.getValue();
                        bd.latitude = clickLat;
                        bd.longitude = clickLon;
                        bd.useGpsForPosition = false;
                        try {
                            bd.writeToPreferences(
                                    YAAC.getPreferences(), entry.getKey());
                        } catch (java.util.prefs.BackingStoreException ex) {
                            ex.printStackTrace(System.out);
                        }
                    }
                }
            }
        });
        menu.add(homeItem);

        // Set Map Center
        JMenuItem centerItem = new JMenuItem("Set Map Center");
        centerItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Window w = SwingUtilities.getWindowAncestor(invoker);
                if (w instanceof GeoMapGuiIfc) {
                    ((GeoMapGuiIfc) w).setCenter(clickLat, clickLon);
                }
            }
        });
        menu.add(centerItem);

        menu.show(invoker, x, y);
    }

    /**
     * Broadcast a SAR object as an APRS Bulletin.
     * One-shot text message with type, category, UTM, and notes.
     */
    private void broadcastSARObject(SARObject obj) {
        try {
            // Build UTM string
            UTMPoint utm = new UTMPoint(
                    new LatLonPoint.Double(obj.getLat(), obj.getLon()));
            String utmStr = utm.zone_number + "" + utm.zone_letter + " "
                    + Math.round(utm.easting) + "E "
                    + Math.round(utm.northing) + "N";

            // Build bulletin text: "SAR:Type UTM by Callsign notes"
            StringBuilder text = new StringBuilder();
            text.append("SAR:").append(obj.getType());
            text.append(" ").append(utmStr);
            if (obj.getFoundBy() != null && !obj.getFoundBy().isEmpty()) {
                text.append(" by ").append(obj.getFoundBy());
            }
            if (obj.getNotes() != null && !obj.getNotes().isEmpty()) {
                text.append(" ").append(obj.getNotes());
            }

            // APRS message body max 67 chars
            String body = text.toString();
            if (body.length() > 67) {
                body = body.substring(0, 67);
            }

            MessageMessage bulletin = new MessageMessage("BLN1SAR", body);
            SendableMessageWrapper wrapper = new SendableMessageWrapper(
                    bulletin,
                    new String[]{"WIDE1-1", "WIDE1-1,WIDE2-1"},
                    1);
            wrapper.queueForTransmission();

            System.out.println("SAR bulletin broadcast: " + body);
        } catch (Exception e) {
            System.err.println("SAR broadcast failed: " + e.getMessage());
        }
    }

    private void renderSARMarker(Graphics2D g2, float px, float py,
                                   SARObject obj) {
        Color catColor = SARObjectManager.getCategoryColor(obj.getCategory());

        // Diamond shape
        int[] dx = new int[4];
        int[] dy = new int[4];
        for (int i = 0; i < 4; i++) {
            dx[i] = (int) px + DIAMOND_X[i];
            dy[i] = (int) py + DIAMOND_Y[i];
        }

        g2.setColor(catColor);
        g2.fillPolygon(dx, dy, 4);
        g2.setColor(Color.DARK_GRAY);
        g2.drawPolygon(dx, dy, 4);

        // Label to the right
        String label = obj.getType();
        if (label == null || label.isEmpty()) return;

        g2.setFont(LABEL_FONT);
        FontMetrics fm = g2.getFontMetrics();
        int textWidth = fm.stringWidth(label);
        int textHeight = fm.getHeight();

        int labelX = (int) px + DIAMOND_HALF + 4;
        int labelY = (int) py - textHeight / 2;

        g2.setColor(TEXT_BG);
        g2.fillRoundRect(labelX - LABEL_PAD_X, labelY - LABEL_PAD_Y,
                textWidth + LABEL_PAD_X * 2, textHeight + LABEL_PAD_Y * 2,
                LABEL_ARC, LABEL_ARC);
        g2.setColor(Color.DARK_GRAY);
        g2.drawRoundRect(labelX - LABEL_PAD_X, labelY - LABEL_PAD_Y,
                textWidth + LABEL_PAD_X * 2, textHeight + LABEL_PAD_Y * 2,
                LABEL_ARC, LABEL_ARC);

        g2.setColor(TEXT_COLOR);
        g2.drawString(label, labelX, labelY + fm.getAscent());
    }

    private void showSARObjectPopup(Component invoker, int x, int y,
                                      RenderedPoint rp) {
        // Extract SAR object ID from "SAR:<uuid>"
        String sarId = rp.callsign.substring(4);
        SARObject obj = null;
        if (sarManager != null) {
            for (SARObject o : sarManager.getObjects()) {
                if (o.getId().equals(sarId)) {
                    obj = o;
                    break;
                }
            }
        }
        if (obj == null) return;

        JPopupMenu popup = new JPopupMenu();
        popup.setLayout(new BorderLayout());

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        panel.setBackground(Color.WHITE);

        // Type (bold)
        JLabel typeLabel = new JLabel(obj.getType());
        typeLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        typeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(typeLabel);
        panel.add(Box.createVerticalStrut(3));

        // Category with color indicator
        JLabel catLabel = new JLabel("Category: " + obj.getCategory());
        catLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        catLabel.setForeground(SARObjectManager.getCategoryColor(obj.getCategory()));
        catLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(catLabel);

        // Found By
        if (obj.getFoundBy() != null && !obj.getFoundBy().isEmpty()) {
            JLabel byLabel = new JLabel("Found By: " + obj.getFoundBy());
            byLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
            byLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(byLabel);
        }

        // Time UTC
        if (obj.getTimestamp() > 0) {
            String timeStr;
            synchronized (UTC_FORMAT) {
                timeStr = UTC_FORMAT.format(new Date(obj.getTimestamp()));
            }
            JLabel timeLabel = new JLabel("Time: " + timeStr);
            timeLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
            timeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(timeLabel);
        }

        // UTM
        panel.add(Box.createVerticalStrut(2));
        UTMPoint utm = new UTMPoint(
                new LatLonPoint.Double(obj.getLat(), obj.getLon()));
        String utmStr = utm.zone_number + "" + utm.zone_letter + " "
                + Math.round(utm.easting) + "E "
                + Math.round(utm.northing) + "N";
        JLabel utmLabel = new JLabel("UTM: " + utmStr);
        utmLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        utmLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(utmLabel);

        // Notes
        if (obj.getNotes() != null && !obj.getNotes().isEmpty()) {
            panel.add(Box.createVerticalStrut(3));
            JLabel notesLabel = new JLabel(
                    "<html><i>" + escapeHtml(obj.getNotes()) + "</i></html>");
            notesLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
            notesLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(notesLabel);
        }

        popup.add(panel, BorderLayout.CENTER);

        // Show popup at bottom-right of the map
        int popupW = panel.getPreferredSize().width + 16;
        int popupH = panel.getPreferredSize().height + 16;
        int mapW = invoker.getWidth();
        int mapH = invoker.getHeight();
        popup.show(invoker, mapW - popupW - 10, mapH - popupH - 10);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    // --- TrackerListener ---

    @Override
    public void stationAdded(StationState ss, int index) {
        repaint();
    }

    @Override
    public void stationUpdated(StationState ss) {
        repaint();
    }

    @Override
    public void stationDeleted(StationState ss, int index) {
        repaint();
    }

    @Override
    public void messageAdded(StationState ss, int index, AX25Message msg) {
        // no-op
    }

    @Override
    public void messageDeleted(StationState ss, int index, AX25Message msg) {
        // no-op
    }

    // --- Projection changes ---

    @Override
    public void projectionChanged(ProjectionEvent e) {
        setProjection(e);
        repaint();
    }

    // --- Enable/disable ---

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            syncStationRendererVisibility();
            repaint();
        }
    }

    private void syncStationRendererVisibility() {
        if (!(guiIfc instanceof GeographicalMap)) return;
        StationRenderer sr = ((GeographicalMap) guiIfc).getStationRenderer();
        if (sr != null) {
            sr.setVisible(!enabled);
        }
    }

    // --- RenderedPoint for hit-testing ---

    private static class RenderedPoint {
        final float screenX, screenY;
        final String callsign;
        final double lat, lon;
        final long rcptTime;
        final boolean isCurrent;
        final double nextLat, nextLon;
        final boolean hasLineToNext;

        RenderedPoint(float screenX, float screenY, String callsign,
                      double lat, double lon, long rcptTime,
                      boolean isCurrent, double nextLat, double nextLon,
                      boolean hasLineToNext) {
            this.screenX = screenX;
            this.screenY = screenY;
            this.callsign = callsign;
            this.lat = lat;
            this.lon = lon;
            this.rcptTime = rcptTime;
            this.isCurrent = isCurrent;
            this.nextLat = nextLat;
            this.nextLon = nextLon;
            this.hasLineToNext = hasLineToNext;
        }
    }
}
