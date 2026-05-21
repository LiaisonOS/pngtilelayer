package org.ka2ddo.yaac.gui.tile;

import com.bbn.openmap.proj.Ellipsoid;
import com.bbn.openmap.proj.Projection;
import com.bbn.openmap.proj.coords.LatLonPoint;
import com.bbn.openmap.proj.coords.UTMPoint;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Point2D;

/**
 * Stateless UTM grid painter. Called directly from
 * {@link StationPushpinLayer#paint(java.awt.Graphics)} when
 * {@link MapDisplaySettings#isShowGrid()} is on. Lives in the pushpin
 * layer's paint pass so we don't depend on YAAC's plugin layer registry
 * picking up a separate Layer creator.
 */
public final class UtmGridRenderer {

    private static final Color  GRID_COLOR  = new Color(20, 20, 20, 140);
    private static final Stroke GRID_STROKE = new BasicStroke(1.0f);
    private static final Font   LABEL_FONT  = new Font("SansSerif", Font.PLAIN, 10);
    private static final Color  LABEL_BG    = new Color(255, 255, 255, 180);
    private static final Color  LABEL_FG    = new Color(20, 20, 20, 220);

    /** High-contrast palette used when the basemap is in grayscale — the
     *  default white-on-grey is hard to read against a gray map. */
    private static final Color  LABEL_BG_HILITE = new Color(255, 230, 0, 230);
    private static final Color  LABEL_FG_HILITE = Color.BLACK;

    /** Soft cap — when the configured step would exceed this, we auto-double
     *  until it fits. Cheap enough to render up to this many lines per axis. */
    private static final int MAX_LINES_PER_AXIS = 400;

    /** Absolute ceiling — if even 10 km steps blow past this, we give up. */
    private static final int MAX_LINES_HARD_CAP = 2000;

    private UtmGridRenderer() { }

    public static void paint(Graphics2D g2, Projection proj,
                             int viewWidth, int viewHeight, int stepMeters) {
        if (proj == null || stepMeters <= 0
                || viewWidth <= 0 || viewHeight <= 0) {
            return;
        }

        Object prevAA = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        Stroke prevStroke = g2.getStroke();
        Color prevColor = g2.getColor();
        Font prevFont = g2.getFont();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            LatLonPoint center = (LatLonPoint) proj.inverse(
                    viewWidth / 2.0, viewHeight / 2.0);
            UTMPoint centerUtm = new UTMPoint(center, Ellipsoid.WGS_84);
            int zone = centerUtm.zone_number;
            char zoneLetter = centerUtm.zone_letter;
            boolean isNorthern = zoneLetter >= 'N';
            double centerEasting = centerUtm.easting;
            double centerNorthing = centerUtm.northing;

            LatLonPoint ul = (LatLonPoint) proj.inverse(0.0, 0.0);
            LatLonPoint ur = (LatLonPoint) proj.inverse((double) viewWidth, 0.0);
            LatLonPoint ll = (LatLonPoint) proj.inverse(0.0, (double) viewHeight);
            LatLonPoint lr = (LatLonPoint) proj.inverse(
                    (double) viewWidth, (double) viewHeight);

            UTMPoint ulU = forceZone(ul, zone);
            UTMPoint urU = forceZone(ur, zone);
            UTMPoint llU = forceZone(ll, zone);
            UTMPoint lrU = forceZone(lr, zone);

            double eMin = min4(ulU.easting,  urU.easting,  llU.easting,  lrU.easting);
            double eMax = max4(ulU.easting,  urU.easting,  llU.easting,  lrU.easting);
            double nMin = min4(ulU.northing, urU.northing, llU.northing, lrU.northing);
            double nMax = max4(ulU.northing, urU.northing, llU.northing, lrU.northing);

            // Auto-expand step until line count fits the soft cap. Picks the
            // next 'nice' value from the standard ladder so the grid always
            // shows SOMETHING regardless of zoom.
            stepMeters = pickEffectiveStep(stepMeters, eMin, eMax, nMin, nMax);
            if (stepMeters <= 0) return; // truly hopeless view

            long firstE = ((long) Math.floor(eMin / stepMeters)) * stepMeters;
            long lastE  = ((long) Math.ceil(eMax  / stepMeters)) * stepMeters;
            long firstN = ((long) Math.floor(nMin / stepMeters)) * stepMeters;
            long lastN  = ((long) Math.ceil(nMax  / stepMeters)) * stepMeters;

            g2.setColor(GRID_COLOR);
            g2.setStroke(GRID_STROKE);

            LatLonPoint reuseLL = new LatLonPoint.Double();

            // NOTE: OpenMap's UTMtoLL signature is (Ellipsoid, NORTHING, EASTING, ...)
            // — fields-declaration order, not the standard easting-first convention.

            // Screen-axis-aligned rendering: project each grid line at the view
            // centre to find its screen X (vertical lines) or Y (horizontal
            // lines), then draw the line as a perfect vertical/horizontal span
            // across the whole view. Avoids the slight skew that comes from
            // UTM-in-Mercator at non-central longitudes. Drift from true UTM
            // position is negligible at field-op scales (1:5,000–1:25,000)
            // and the cell spacing remains accurate at the centre.

            // Vertical lines (constant easting) — find screen X at centre row
            for (long e = firstE; e <= lastE; e += stepMeters) {
                UTMPoint.UTMtoLL(Ellipsoid.WGS_84,
                        centerNorthing, (double) e, zone, isNorthern, reuseLL);
                Point2D p = proj.forward(reuseLL.getLatitude(),
                        reuseLL.getLongitude());
                int x = (int) p.getX();
                g2.drawLine(x, 0, x, viewHeight);
            }

            // Horizontal lines (constant northing) — find screen Y at centre col
            for (long n = firstN; n <= lastN; n += stepMeters) {
                UTMPoint.UTMtoLL(Ellipsoid.WGS_84,
                        (double) n, centerEasting, zone, isNorthern, reuseLL);
                Point2D p = proj.forward(reuseLL.getLatitude(),
                        reuseLL.getLongitude());
                int y = (int) p.getY();
                g2.drawLine(0, y, viewWidth, y);
            }

            // Edge labels — easting along the top, northing down the left.
            // When the basemap is grayscale, switch to a yellow/black palette
            // so the labels stay readable against the desaturated tiles.
            boolean hilite = MapDisplaySettings.getInstance().isGrayscale();
            Color labelBg = hilite ? LABEL_BG_HILITE : LABEL_BG;
            Color labelFg = hilite ? LABEL_FG_HILITE : LABEL_FG;

            g2.setFont(LABEL_FONT);
            FontMetrics fm = g2.getFontMetrics();
            for (long e = firstE; e <= lastE; e += stepMeters) {
                UTMPoint.UTMtoLL(Ellipsoid.WGS_84,
                        centerNorthing, (double) e, zone, isNorthern, reuseLL);
                Point2D p = proj.forward(reuseLL.getLatitude(),
                        reuseLL.getLongitude());
                drawLabel(g2, fm, String.valueOf(e),
                        (int) p.getX() + 3, 12, labelBg, labelFg);
            }
            for (long n = firstN; n <= lastN; n += stepMeters) {
                UTMPoint.UTMtoLL(Ellipsoid.WGS_84,
                        (double) n, centerEasting, zone, isNorthern, reuseLL);
                Point2D p = proj.forward(reuseLL.getLatitude(),
                        reuseLL.getLongitude());
                drawLabel(g2, fm, String.valueOf(n),
                        3, (int) p.getY() - 3, labelBg, labelFg);
            }

            String zoneLbl = "UTM " + zone + zoneLetter
                    + "  " + stepMeters + " m grid";
            drawLabel(g2, fm, zoneLbl,
                    viewWidth - fm.stringWidth(zoneLbl) - 6, viewHeight - 6,
                    labelBg, labelFg);
        } finally {
            g2.setStroke(prevStroke);
            g2.setColor(prevColor);
            g2.setFont(prevFont);
            if (prevAA != null) {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, prevAA);
            }
        }
    }

    /**
     * 'Nice' step ladder in metres. We pick the smallest step in this list
     * that's &gt;= the user's preference AND keeps the visible line count
     * under {@link #MAX_LINES_PER_AXIS}.
     */
    private static final int[] NICE_STEPS = {
            10, 25, 50, 100, 200, 250, 500,
            1_000, 2_000, 2_500, 5_000,
            10_000, 25_000, 50_000, 100_000
    };

    /**
     * Returns the smallest step &gt;= {@code preferred} that fits the view
     * within the soft cap. If even the largest ladder value exceeds the hard
     * cap, returns 0 (caller bails).
     */
    private static int pickEffectiveStep(int preferred,
                                          double eMin, double eMax,
                                          double nMin, double nMax) {
        for (int step : NICE_STEPS) {
            if (step < preferred) continue;
            long countE = (long) Math.ceil((eMax - eMin) / step) + 1;
            long countN = (long) Math.ceil((nMax - nMin) / step) + 1;
            if (countE > MAX_LINES_HARD_CAP || countN > MAX_LINES_HARD_CAP) {
                continue;
            }
            if (countE <= MAX_LINES_PER_AXIS && countN <= MAX_LINES_PER_AXIS) {
                return step;
            }
        }
        // Nothing in the ladder fits — caller will bail.
        return 0;
    }

    private static UTMPoint forceZone(LatLonPoint ll, int zone) {
        UTMPoint dest = new UTMPoint();
        return UTMPoint.LLtoUTM(ll, Ellipsoid.WGS_84, dest, zone, true);
    }

    private static void drawLabel(Graphics2D g2, FontMetrics fm,
                                  String text, int x, int y,
                                  Color bg, Color fg) {
        int w = fm.stringWidth(text);
        int h = fm.getHeight();
        Color saved = g2.getColor();
        g2.setColor(bg);
        g2.fillRect(x - 1, y - fm.getAscent(), w + 2, h);
        g2.setColor(fg);
        g2.drawString(text, x, y);
        g2.setColor(saved);
    }

    private static double min4(double a, double b, double c, double d) {
        return Math.min(Math.min(a, b), Math.min(c, d));
    }

    private static double max4(double a, double b, double c, double d) {
        return Math.max(Math.max(a, b), Math.max(c, d));
    }
}
