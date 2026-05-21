package org.ka2ddo.yaac.gui.tile;

import com.bbn.openmap.Layer;
import com.bbn.openmap.MapBean;
import com.bbn.openmap.event.ProjectionEvent;
import com.bbn.openmap.proj.Projection;
import com.bbn.openmap.proj.coords.LatLonPoint;

import org.ka2ddo.yaac.gui.GeographicalMap;
import org.ka2ddo.yaac.gui.osm.OSMLayer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PNG tile layer for YAAC — renders slippy map tiles (z/x/y) from
 * online tile servers and/or local SQLite tile caches.
 * <p>
 * Supports multiple configurable tile sources with cache-first,
 * online-fetch, fallback strategy for offline/online operation.
 * <p>
 * Follows YAAC's double-buffered rendering pattern (same as OSMLayer).
 */
public class PNGTileLayer extends Layer implements Runnable, ComponentListener {

    /** Standard web tile size in pixels */
    private static final int TILE_SIZE = 256;

    /** Max tiles to keep in memory LRU cache */
    private static final int MEMORY_CACHE_SIZE = 200;

    private static final Font ATTRIBUTION_FONT = new Font("SansSerif", Font.PLAIN, 10);

    // --- Double-buffered rendering (same pattern as OSMLayer) ---

    private final RenderState[] renderStates = new RenderState[2];
    private volatile int currentImage = 0;
    private transient Thread renderThread = null;
    private volatile boolean startAnotherRenderThread = false;
    private int numRenderThreads = 0;

    // --- Pan/zoom blocking ---
    private transient boolean blockRegenerate = false;
    private transient int offsetX = 0;
    private transient int offsetY = 0;

    // --- Tile subsystem ---
    private final MapBean mapBean;
    private final TileSourceManager sourceManager;
    private final TileCache tileCache;
    private final TileFetcher tileFetcher;

    /** In-memory LRU tile cache keyed by "sourceName/z/x/y" */
    private final Map<String, BufferedImage> memoryCache;

    /** Cached gray rendering of {@code current.img}, invalidated by reference change. */
    private transient BufferedImage graySrc;
    private transient BufferedImage grayOut;

    /**
     * Construct a PNGTileLayer.
     * Cache directory is read from the TileSourceManager config.
     *
     * @param mapBean         the OpenMap MapBean
     * @param sourceManager   tile source manager (already loaded)
     */
    public PNGTileLayer(MapBean mapBean, TileSourceManager sourceManager) {
        this.mapBean = mapBean;
        this.sourceManager = sourceManager;
        this.tileCache = new TileCache(sourceManager.getCacheDirectory());
        this.tileFetcher = new TileFetcher();

        // LRU memory cache
        this.memoryCache = new LinkedHashMap<String, BufferedImage>(MEMORY_CACHE_SIZE + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                return size() > MEMORY_CACHE_SIZE;
            }
        };

        // Initialize render states
        for (int i = 0; i < renderStates.length; i++) {
            renderStates[i] = new RenderState();
        }

        setName("PNGTileLayer");
        mapBean.addComponentListener(this);
    }

    // ==========================================
    // Layer lifecycle
    // ==========================================

    @Override
    public void projectionChanged(ProjectionEvent e) {
        this.setProjection(e);
        Projection projection = e.getProjection();

        for (RenderState rs : renderStates) {
            if (rs != null && !rs.isSameProjection(projection, getWidth(), getHeight())) {
                rs.isValid = false;
                if (renderThread != null) {
                    rs.aborted = true;
                }
            }
        }

        if (!blockRegenerate) {
            regenerate("projectionChanged()");
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        RenderState current = renderStates[currentImage];

        if (current.isValid && current.img != null &&
                current.isSameProjection(getProjection(), getWidth(), getHeight())) {

            boolean grayscale = MapDisplaySettings.getInstance().isGrayscale();
            BufferedImage toDraw = grayscale
                    ? toGrayscale(current.img)
                    : current.img;

            if (blockRegenerate) {
                g.drawImage(toDraw, offsetX, offsetY, this);
            } else {
                Point2D skew = current.getSkew(getProjection(), getSize());
                g.drawImage(toDraw,
                        (int) skew.getX(), (int) skew.getY(), this);
            }

            // Draw attribution
            TileSource activeSource = sourceManager.getActiveSource();
            if (activeSource != null) {
                String attribution = activeSource.getAttribution();
                if (attribution != null && !attribution.isEmpty()) {
                    drawAttribution(g, attribution);
                } else {
                    drawAttribution(g, activeSource.getName());
                }
            }

        } else if (!blockRegenerate && renderThread == null) {
            regenerate("current image not valid");
        }
    }

    /**
     * Return a TYPE_BYTE_GRAY rendering of {@code src}. Cached by reference —
     * the composite only changes when the projection/size changes, so we
     * keep the gray buffer between paints. Reliable across JDK versions
     * because the destination color model is byte-gray, not a quirky
     * ColorConvertOp-derived ARGB.
     */
    private BufferedImage toGrayscale(BufferedImage src) {
        if (src == graySrc && grayOut != null
                && grayOut.getWidth() == src.getWidth()
                && grayOut.getHeight() == src.getHeight()) {
            return grayOut;
        }
        BufferedImage out = new BufferedImage(
                src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D gg = out.createGraphics();
        try {
            gg.drawImage(src, 0, 0, null);
        } finally {
            gg.dispose();
        }
        graySrc = src;
        grayOut = out;
        return out;
    }

    private void drawAttribution(Graphics g, String text) {
        g.setFont(ATTRIBUTION_FONT);
        Dimension mapSize = getSize();
        FontMetrics fm = g.getFontMetrics();
        int width = fm.stringWidth(text);
        g.setColor(new Color(255, 255, 255, 180));
        g.fillRect(0, mapSize.height - fm.getHeight(), width + 4, fm.getHeight());
        g.setColor(Color.DARK_GRAY);
        g.drawString(text, 2, mapSize.height - fm.getDescent());
    }

    // ==========================================
    // Pan blocking (same interface as OSMLayer)
    // ==========================================

    public void stopRegenerate(int offsetX, int offsetY) {
        this.blockRegenerate = true;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    public void startRegenerate() {
        this.blockRegenerate = false;
        regenerate("startRegenerate()");
    }

    // ==========================================
    // Background rendering
    // ==========================================

    private void regenerate(String why) {
        Projection projection = getProjection();
        if (projection == null) return;

        Dimension beanSize = mapBean.getSize();
        if (beanSize.width <= 0 || beanSize.height <= 0) return;

        int newImgIdx = (currentImage + 1) & 1;
        RenderState rs = renderStates[newImgIdx];

        // Resize image buffer if needed
        if (rs.img != null &&
                (rs.img.getWidth() != beanSize.width || rs.img.getHeight() != beanSize.height)) {
            rs.img = null;
            rs.isValid = false;
            rs.aborted = true;
        }

        if (renderThread == null) {
            createRenderThread(why);
        } else if (!rs.isSameProjection(projection, beanSize.width, beanSize.height)) {
            startAnotherRenderThread = true;
        }
    }

    private synchronized void createRenderThread(String why) {
        renderThread = new Thread(this, "PNGTileLayer Renderer " + (++numRenderThreads));
        renderThread.setDaemon(true);
        startAnotherRenderThread = false;
        renderThread.start();
    }

    @Override
    public final void run() {
        Thread thisThread = Thread.currentThread();
        int newImgIdx = 1 - currentImage;

        try {
            RenderState rs = renderStates[newImgIdx];

            // Wait for previous render to finish
            if (rs.isRendering) {
                rs.aborted = true;
                rs.waitUntilIdle();
                rs.isRendering = false;
            }
            rs.aborted = false;

            Projection projection = getProjection();
            if (projection == null) return;

            startAnotherRenderThread = false;
            Dimension beanSize = mapBean.getSize();
            if (beanSize.width <= 0 || beanSize.height <= 0) return;

            // Store projection state
            rs.scale = projection.getScale();
            LatLonPoint center = (LatLonPoint) projection.getCenter();
            rs.centerLat = center.getLatitude();
            rs.centerLon = center.getLongitude();
            rs.beanWidth = beanSize.width;
            rs.beanHeight = beanSize.height;

            rs.isRendering = true;

            // Allocate image if needed
            if (rs.img == null) {
                rs.img = new BufferedImage(beanSize.width, beanSize.height, BufferedImage.TYPE_INT_ARGB);
            }

            // Check for preemption
            if (!rs.isSameProjection(getProjection(), beanSize.width, beanSize.height)) {
                rs.isRendering = false;
                return;
            }

            // Render tiles
            boolean aborted = renderTiles(rs, projection, beanSize);
            if (aborted) {
                rs.isRendering = false;
                return;
            }

            // Swap buffers
            currentImage = newImgIdx;
            rs.isValid = true;
            rs.isRendering = false;
            offsetX = 0;
            offsetY = 0;

            repaint();
            mapBean.repaint();

        } finally {
            if (thisThread == renderThread) {
                renderThread = null;
                if (startAnotherRenderThread) {
                    createRenderThread("startAnotherRenderThread==true");
                }
            }
        }
    }

    /**
     * Render all visible tiles onto the render image.
     *
     * @return true if rendering was aborted (projection changed)
     */
    private boolean renderTiles(RenderState rs, Projection projection, Dimension beanSize) {
        TileSource source = sourceManager.getActiveSource();
        if (source == null) {
            rs.isRendering = false;
            return false;
        }

        // Calculate zoom level from projection scale
        int zoom = TileCoord.scaleToZoom(rs.scale, source.getMinZoom(), source.getMaxZoom());

        // Get viewport bounds from projection corners
        LatLonPoint ul = (LatLonPoint) projection.inverse(0.0, 0.0);
        LatLonPoint lr = (LatLonPoint) projection.inverse(beanSize.width, beanSize.height);

        double topLat = ul.getY();
        double leftLon = ul.getX();
        double bottomLat = lr.getY();
        double rightLon = lr.getX();

        // Calculate tile range
        int minTileX = TileCoord.clampTileX(TileCoord.lonToTileX(leftLon, zoom), zoom);
        int maxTileX = TileCoord.clampTileX(TileCoord.lonToTileX(rightLon, zoom), zoom);
        int minTileY = TileCoord.clampTileY(TileCoord.latToTileY(topLat, zoom), zoom);
        int maxTileY = TileCoord.clampTileY(TileCoord.latToTileY(bottomLat, zoom), zoom);

        // Handle date line wrapping
        if (leftLon > rightLon) {
            maxTileX = (1 << zoom) - 1;
        }

        // Create graphics context
        Graphics2D g = rs.img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            // Clear to background
            g.setColor(new Color(200, 220, 240)); // light blue water color
            g.fillRect(0, 0, beanSize.width, beanSize.height);

            // Render each tile
            for (int ty = minTileY; ty <= maxTileY; ty++) {
                for (int tx = minTileX; tx <= maxTileX; tx++) {
                    // Check for abort
                    if (rs.aborted) return true;

                    BufferedImage tileImg = resolveTile(source, zoom, tx, ty);
                    if (tileImg == null) continue;

                    // Calculate pixel position of tile's top-left corner
                    double tileLon = TileCoord.tileXToLon(tx, zoom);
                    double tileLat = TileCoord.tileYToLat(ty, zoom);
                    Point2D tileTopLeft = projection.forward(tileLat, tileLon);

                    // Calculate pixel position of tile's bottom-right corner
                    double nextLon = TileCoord.tileXToLon(tx + 1, zoom);
                    double nextLat = TileCoord.tileYToLat(ty + 1, zoom);
                    Point2D tileBottomRight = projection.forward(nextLat, nextLon);

                    int px = (int) tileTopLeft.getX();
                    int py = (int) tileTopLeft.getY();
                    int pw = (int) (tileBottomRight.getX() - tileTopLeft.getX());
                    int ph = (int) (tileBottomRight.getY() - tileTopLeft.getY());

                    // Draw tile scaled to projection size
                    if (pw > 0 && ph > 0) {
                        g.drawImage(tileImg, px, py, pw, ph, null);
                    }
                }
            }
        } finally {
            g.dispose();
        }

        return false;
    }

    /**
     * Resolve a tile using the cache-first → online → fallback strategy.
     *
     * @return tile image or null if unavailable
     */
    /** When true, rendering uses fallback source for all tiles */
    private volatile boolean usingFallback = false;

    private BufferedImage resolveTile(TileSource source, int z, int x, int y) {
        // If we've switched to fallback, use fallback source for everything
        if (usingFallback) {
            TileSource fallback = sourceManager.getFallbackSource();
            if (fallback != null) {
                return resolveTileFromSource(fallback, z, x, y);
            }
        }

        // Try primary source
        BufferedImage img = resolveTileFromSource(source, z, x, y);
        if (img != null) return img;

        // Primary failed — switch entire map to fallback
        TileSource fallback = sourceManager.getFallbackSource();
        if (fallback != null && fallback != source) {
            usingFallback = true;
            clearMemoryCache();
            return resolveTileFromSource(fallback, z, x, y);
        }

        return null;
    }

    /**
     * Resolve a single tile from a specific source: memory → cache → online.
     */
    private BufferedImage resolveTileFromSource(TileSource source, int z, int x, int y) {
        String memKey = source.getName() + "/" + z + "/" + x + "/" + y;

        // 1. Check memory cache
        synchronized (memoryCache) {
            BufferedImage cached = memoryCache.get(memKey);
            if (cached != null) return cached;
        }

        // 2. Check SQLite cache
        if (source.hasCache()) {
            BufferedImage img = tileCache.getTile(source.getCacheFile(), z, x, y);
            if (img != null) {
                synchronized (memoryCache) {
                    memoryCache.put(memKey, img);
                }
                return img;
            }

            // Cache miss — try online, save to cache for next time
            BufferedImage onlineImg = fetchAndCache(source, z, x, y, memKey);
            if (onlineImg != null) return onlineImg;
        } else {
            // No cache file — fetch online only
            BufferedImage onlineImg = fetchAndCache(source, z, x, y, memKey);
            if (onlineImg != null) return onlineImg;
        }

        return null;
    }

    /**
     * Fetch a tile from online and store in SQLite + memory cache.
     */
    private BufferedImage fetchAndCache(TileSource source, int z, int x, int y, String memKey) {
        byte[] data = tileFetcher.fetchTile(source, z, x, y);
        if (data == null || data.length == 0) return null;

        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img != null) {
                // Store in SQLite cache
                if (source.hasCache()) {
                    tileCache.putTile(source.getCacheFile(), z, x, y, data);
                }
                // Store in memory cache
                synchronized (memoryCache) {
                    memoryCache.put(memKey, img);
                }
                return img;
            }
        } catch (Exception e) {
            System.err.println("PNGTileLayer: error decoding tile " + z + "/" + x + "/" + y +
                    ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Snap the map to a fixed 1:N cartographic scale. The float is the
     * denominator (e.g. {@code 5000f} → 1:5,000). Forwarded directly to
     * the OpenMap MapBean.
     */
    public void setMapScale(float scale) {
        if (mapBean != null && scale > 0f) {
            mapBean.setScale(scale);
        }
    }

    // ==========================================
    // Public API for source switching
    // ==========================================

    public void setActiveSource(TileSource source) {
        sourceManager.setActiveSource(source);
        usingFallback = false;
        clearMemoryCache();
        regenerate("source changed to " + source.getName());
    }

    public TileSource getActiveSource() {
        return sourceManager.getActiveSource();
    }

    public List<TileSource> getAvailableSources() {
        return sourceManager.getEnabledSources();
    }

    public TileSourceManager getSourceManager() {
        return sourceManager;
    }

    public TileCache getTileCache() {
        return tileCache;
    }

    public void clearMemoryCache() {
        synchronized (memoryCache) {
            memoryCache.clear();
        }
    }

    // ==========================================
    // ComponentListener (resize handling)
    // ==========================================

    @Override
    public void componentResized(ComponentEvent e) {
        for (RenderState rs : renderStates) {
            rs.isValid = false;
        }
        if (!blockRegenerate) {
            regenerate("componentResized()");
        }
    }

    @Override
    public void componentMoved(ComponentEvent e) { }

    @Override
    public void componentShown(ComponentEvent e) {
        regenerate("componentShown()");
    }

    @Override
    public void componentHidden(ComponentEvent e) { }

    // ==========================================
    // OSM vector layer toggle
    // ==========================================

    /**
     * Find the OSMLayer via the MapBean's parent GeographicalMap.
     */
    private OSMLayer findOSMLayer() {
        try {
            Container parent = mapBean.getParent();
            while (parent != null) {
                if (parent instanceof GeographicalMap) {
                    return ((GeographicalMap) parent).getOSMLayer();
                }
                parent = parent.getParent();
            }
        } catch (Exception e) {
            System.err.println("PNGTileLayer: could not find OSMLayer: " + e.getMessage());
        }
        return null;
    }

    /**
     * Check if the OSM vector layer is currently visible.
     */
    public boolean isOSMLayerVisible() {
        OSMLayer osm = findOSMLayer();
        return osm != null && osm.isShowMap();
    }

    /**
     * Toggle between OSM vector layer and PNG tile layer.
     * When vector map is shown, tile layer hides (and vice versa)
     * since tiles at z-order 15 would cover vectors at z-order 10.
     */
    public void setOSMLayerVisible(boolean visible) {
        OSMLayer osm = findOSMLayer();
        if (osm != null) {
            osm.setShowMap(visible);
            if (visible) {
                osm.regenerateAndRepaint();
            }
        }
        // Hide/show our tile layer (opposite of OSM)
        this.setVisible(!visible);
        if (!visible) {
            regenerate("switched back to tile layer");
        }
    }

    // ==========================================
    // Cleanup
    // ==========================================

    public void dispose() {
        tileCache.close();
        clearMemoryCache();
    }

    // ==========================================
    // Inner class: RenderState (double-buffer)
    // ==========================================

    /**
     * Holds one rendered image and the projection state it was rendered for.
     * Two instances are used for double-buffering.
     */
    private static class RenderState {
        BufferedImage img;
        volatile boolean isValid;
        volatile boolean isRendering;
        volatile boolean aborted;

        // Projection state this image was rendered for
        float scale;
        double centerLat;
        double centerLon;
        int beanWidth;
        int beanHeight;

        /**
         * Check if this render state matches the current projection.
         */
        boolean isSameProjection(Projection proj, int width, int height) {
            if (proj == null) return false;
            if (width != beanWidth || height != beanHeight) {
                isValid = false;
                return false;
            }
            if (this.scale != proj.getScale()) {
                isValid = false;
                return false;
            }
            if (proj.getWidth() != width || proj.getHeight() != height) {
                isValid = false;
                return false;
            }
            LatLonPoint center = (LatLonPoint) proj.getCenter();
            if (Math.abs(centerLat - center.getLatitude()) > 1.0E-4 ||
                    Math.abs(centerLon - center.getLongitude()) > 1.0E-4) {
                isValid = false;
                return false;
            }
            return true;
        }

        /**
         * Get pixel offset for drawing the cached image at current projection.
         */
        Point2D getSkew(Projection proj, Dimension sz) {
            Point2D skew = proj.forward(centerLat, centerLon);
            skew.setLocation(
                    skew.getX() - (double) (sz.width / 2),
                    skew.getY() - (double) (sz.height / 2));
            return skew;
        }

        /**
         * Block until rendering completes.
         */
        synchronized void waitUntilIdle() {
            while (isRendering) {
                try {
                    wait(1000L);
                } catch (InterruptedException ignored) { }
            }
        }
    }
}
