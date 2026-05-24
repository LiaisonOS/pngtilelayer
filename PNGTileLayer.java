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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

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

    // ── Tile-level paint infrastructure (BlueMap-style smooth rendering) ──
    /** Tiles currently being fetched async, keyed by "sourceName/z/x/y".
     *  Prevents duplicate fetches while a tile is in flight. */
    private final java.util.Set<String> pendingFetches =
            ConcurrentHashMap.newKeySet();

    /** Pool of fetch workers. Bounded so we don't hammer remote tile
     *  servers with dozens of parallel requests during a fast pan. */
    private final ExecutorService fetchExecutor = Executors.newFixedThreadPool(
            4,
            new ThreadFactory() {
                final AtomicInteger n = new AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "PNGTileLayer-fetch-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

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

    /**
     * Called by Swing when the layer is added to its parent component. By
     * the time YAAC creates our plugin layer, the initial projectionChanged
     * event has already fired for the map — so the layer registers but
     * never gets the wake-up call until the operator zooms (+/-). Force a
     * deferred regenerate here so tiles paint on first show, without the
     * operator having to tap zoom first. Symptom before the fix: blank
     * map until first +/- tap.
     */
    @Override
    public void addNotify() {
        super.addNotify();
        javax.swing.SwingUtilities.invokeLater(() -> {
            Projection p = getProjection();
            if (p == null) {
                // Projection not set yet — schedule one more attempt after
                // YAAC finishes wiring listeners.
                javax.swing.Timer t = new javax.swing.Timer(500, ae -> {
                    if (getProjection() != null && !blockRegenerate) {
                        regenerate("addNotify retry");
                    } else {
                        repaint();
                    }
                });
                t.setRepeats(false);
                t.start();
            } else if (!blockRegenerate) {
                regenerate("addNotify initial");
            }
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        // ── Tile-level direct paint (BlueMap-style) ──
        // Skip the legacy full-viewport renderStates path. Iterate visible
        // tiles, draw each one straight to g from cache. Missing tiles are
        // queued for async fetch and repainted when they arrive. This
        // eliminates the "whole map blanks during regen" flicker.
        Projection proj = getProjection();
        if (proj == null) return;
        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        Graphics2D g2 = (g instanceof Graphics2D) ? (Graphics2D) g : null;
        if (g2 == null) return;

        // Background — water blue, matches legacy behaviour
        g2.setColor(new Color(200, 220, 240));
        g2.fillRect(0, 0, viewW, viewH);

        TileSource source = usingFallback
                ? sourceManager.getFallbackSource()
                : sourceManager.getActiveSource();
        if (source == null) return;

        paintTilesDirect(g2, source, proj, viewW, viewH);

        // Attribution
        TileSource attrSource = sourceManager.getActiveSource();
        if (attrSource != null) {
            String attribution = attrSource.getAttribution();
            drawAttribution(g, attribution != null && !attribution.isEmpty()
                    ? attribution : attrSource.getName());
        }
    }

    /**
     * Iterate every tile that falls inside the viewport at the current
     * zoom and paint it directly to {@code g2}. Memory + SQLite cache
     * hits paint immediately. Misses paint a placeholder AND, unless
     * Cache Only is on, enqueue an async fetch that will land the tile
     * in memory and trigger another repaint when it arrives.
     */
    private void paintTilesDirect(Graphics2D g2, TileSource source,
                                   Projection proj, int viewW, int viewH) {
        int zoom = TileCoord.scaleToZoom(proj.getScale(),
                source.getMinZoom(), source.getMaxZoom());

        LatLonPoint ul = (LatLonPoint) proj.inverse(0.0, 0.0);
        LatLonPoint lr = (LatLonPoint) proj.inverse((double) viewW, (double) viewH);
        double topLat = ul.getY(), leftLon = ul.getX();
        double bottomLat = lr.getY(), rightLon = lr.getX();

        int minTileX = TileCoord.clampTileX(TileCoord.lonToTileX(leftLon, zoom), zoom);
        int maxTileX = TileCoord.clampTileX(TileCoord.lonToTileX(rightLon, zoom), zoom);
        int minTileY = TileCoord.clampTileY(TileCoord.latToTileY(topLat, zoom), zoom);
        int maxTileY = TileCoord.clampTileY(TileCoord.latToTileY(bottomLat, zoom), zoom);
        if (leftLon > rightLon) maxTileX = (1 << zoom) - 1;

        boolean grayscale = MapDisplaySettings.getInstance().isGrayscale();
        boolean cacheOnly = MapDisplaySettings.getInstance().isCacheOnly();

        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        for (int ty = minTileY; ty <= maxTileY; ty++) {
            for (int tx = minTileX; tx <= maxTileX; tx++) {
                // Resolve from cache (fast, sync, no network)
                BufferedImage tileImg = lookupTileFromCache(source, zoom, tx, ty);
                if (tileImg == null) {
                    // Cache miss — queue async fetch (unless cache-only)
                    if (!cacheOnly) {
                        requestTileAsync(source, zoom, tx, ty);
                    }
                    tileImg = makeMissingTilePlaceholder(zoom, tx, ty);
                }
                if (grayscale) tileImg = toGrayscale(tileImg);

                double tileLon  = TileCoord.tileXToLon(tx,     zoom);
                double tileLat  = TileCoord.tileYToLat(ty,     zoom);
                double nextLon  = TileCoord.tileXToLon(tx + 1, zoom);
                double nextLat  = TileCoord.tileYToLat(ty + 1, zoom);
                Point2D tl = proj.forward(tileLat, tileLon);
                Point2D br = proj.forward(nextLat, nextLon);

                int px = (int) tl.getX();
                int py = (int) tl.getY();
                int pw = (int) (br.getX() - tl.getX());
                int ph = (int) (br.getY() - tl.getY());
                if (pw > 0 && ph > 0) {
                    g2.drawImage(tileImg, px, py, pw, ph, null);
                }
            }
        }
    }

    /**
     * Look up a tile in memory + SQLite caches only. Never blocks, never
     * touches the network. Returns null if the tile isn't cached.
     */
    private BufferedImage lookupTileFromCache(TileSource source,
                                               int z, int x, int y) {
        String memKey = source.getName() + "/" + z + "/" + x + "/" + y;
        synchronized (memoryCache) {
            BufferedImage cached = memoryCache.get(memKey);
            if (cached != null) return cached;
        }
        if (!source.hasCache()) return null;
        BufferedImage img = tileCache.getTile(source.getCacheFile(), z, x, y);
        if (img != null) {
            synchronized (memoryCache) {
                memoryCache.put(memKey, img);
            }
        }
        return img;
    }

    /**
     * Queue an async fetch for a tile. Caller's paint thread is not
     * blocked. When the fetch completes (success or failure), the tile
     * lands in the memory cache and {@link #repaint} is called so paint
     * runs again and finds the tile this time.
     */
    private void requestTileAsync(TileSource source, int z, int x, int y) {
        final String memKey = source.getName() + "/" + z + "/" + x + "/" + y;
        if (!pendingFetches.add(memKey)) return; // already in flight
        fetchExecutor.submit(() -> {
            try {
                byte[] data = tileFetcher.fetchTile(source, z, x, y);
                if (data != null && data.length > 0) {
                    try {
                        BufferedImage img = ImageIO.read(
                                new ByteArrayInputStream(data));
                        if (img != null) {
                            if (source.hasCache()) {
                                tileCache.putTile(source.getCacheFile(),
                                        z, x, y, data);
                            }
                            synchronized (memoryCache) {
                                memoryCache.put(memKey, img);
                            }
                            // Repaint on the EDT
                            javax.swing.SwingUtilities.invokeLater(this::repaint);
                        }
                    } catch (Exception decodeErr) {
                        // ignore — tile stays missing for now
                    }
                }
            } finally {
                pendingFetches.remove(memKey);
            }
        });
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

    /**
     * Legacy entry point — kept for compatibility with all existing
     * callers (projectionChanged, componentResized, source change, etc.).
     *
     * In the tile-level paint architecture, the full-viewport
     * renderStates pipeline is no longer used; paintComponent draws every
     * tile directly. So all this method does now is request a repaint.
     * The old body below is dead and unreachable.
     */
    private void regenerate(String why) {
        repaint();
        if (true) return; // dead code below, kept for reference / rollback

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
        boolean cacheOnly = MapDisplaySettings.getInstance().isCacheOnly();

        // If we've switched to fallback, use fallback source for everything
        if (usingFallback) {
            TileSource fallback = sourceManager.getFallbackSource();
            if (fallback != null) {
                BufferedImage img = resolveTileFromSource(fallback, z, x, y);
                return img != null ? img : cachedPlaceholder(fallback, z, x, y);
            }
        }

        // Try primary source
        BufferedImage img = resolveTileFromSource(source, z, x, y);
        if (img != null) return img;

        // Primary failed — switch entire map to fallback ONLY when we're
        // doing live online fetches. In cache-only mode the "miss" isn't a
        // failure to talk to a server; it's just an absent tile, and
        // switching sources + clearing the memory cache would tank perf
        // (every paint would re-query SQLite for misses).
        if (!cacheOnly) {
            TileSource fallback = sourceManager.getFallbackSource();
            if (fallback != null && fallback != source) {
                usingFallback = true;
                clearMemoryCache();
                BufferedImage fb = resolveTileFromSource(fallback, z, x, y);
                if (fb != null) return fb;
            }
        }

        // Never return null — show a clear placeholder so the operator
        // knows immediately whether the cache covers this zoom/area.
        return cachedPlaceholder(source, z, x, y);
    }

    /**
     * Generate the placeholder for {@code (z,x,y)} and cache it in the
     * memory LRU so subsequent paints don't re-query SQLite for the same
     * miss. The memory cache is cleared whenever the operator toggles
     * Cache Only / source / etc., so stale negatives don't stick around.
     */
    private BufferedImage cachedPlaceholder(TileSource source, int z, int x, int y) {
        String memKey = source.getName() + "/" + z + "/" + x + "/" + y;
        synchronized (memoryCache) {
            BufferedImage cached = memoryCache.get(memKey);
            if (cached != null) return cached;
        }
        BufferedImage ph = makeMissingTilePlaceholder(z, x, y);
        synchronized (memoryCache) {
            memoryCache.put(memKey, ph);
        }
        return ph;
    }

    /**
     * Generate a 256×256 placeholder for a tile that couldn't be resolved
     * (cache miss AND online fetch failed). Shows the tile coords and a
     * hint so the operator can decide: zoom out (cache may not be built
     * deep at this level), or connect to internet.
     * <p>
     * NOT cached — generated fresh per call so that if the network comes
     * back, the next paint will retry and replace the placeholder with a
     * real tile.
     */
    private BufferedImage makeMissingTilePlaceholder(int z, int x, int y) {
        final int size = 256;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // Semi-transparent dark fill so adjacent real tiles still read
            g.setColor(new Color(35, 35, 35, 200));
            g.fillRect(0, 0, size, size);

            // Subtle border so tile boundaries are visible
            g.setColor(new Color(180, 90, 90, 200));
            g.drawRect(0, 0, size - 1, size - 1);

            FontMetrics fm;

            // Title
            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            g.setColor(new Color(255, 200, 100));
            String l1 = "TILE NOT IN CACHE";
            fm = g.getFontMetrics();
            g.drawString(l1, (size - fm.stringWidth(l1)) / 2, 110);

            // Coords
            g.setFont(new Font("Monospaced", Font.PLAIN, 12));
            g.setColor(new Color(225, 225, 225));
            String l2 = "z=" + z + "  x=" + x + "  y=" + y;
            fm = g.getFontMetrics();
            g.drawString(l2, (size - fm.stringWidth(l2)) / 2, 134);

            // Hint — depends on whether the operator is in cache-only mode
            g.setFont(new Font("SansSerif", Font.ITALIC, 11));
            g.setColor(new Color(180, 180, 180));
            String l3 = MapDisplaySettings.getInstance().isCacheOnly()
                    ? "cache-only mode — toggle off to fetch online"
                    : "zoom out, or connect to internet";
            fm = g.getFontMetrics();
            g.drawString(l3, (size - fm.stringWidth(l3)) / 2, 158);
        } finally {
            g.dispose();
        }
        return img;
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

        boolean cacheOnly = MapDisplaySettings.getInstance().isCacheOnly();

        // 2. Check SQLite cache
        if (source.hasCache()) {
            BufferedImage img = tileCache.getTile(source.getCacheFile(), z, x, y);
            if (img != null) {
                synchronized (memoryCache) {
                    memoryCache.put(memKey, img);
                }
                return img;
            }

            // Cache miss — try online unless operator has Cache Only mode
            // enabled (no network attempts in known-offline ops).
            if (cacheOnly) return null;
            BufferedImage onlineImg = fetchAndCache(source, z, x, y, memKey);
            if (onlineImg != null) return onlineImg;
        } else {
            // No cache file. In cache-only mode there's nothing to do.
            if (cacheOnly) return null;
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

    /**
     * Patch missing tiles for the current viewport at the current zoom,
     * PLUS a one-viewport-wide buffer on each side. So if the visible
     * area is 1 km × 1 km, this patches a 3 km × 3 km area centred on
     * the view. Operator's request — covers the case where you'd
     * otherwise have to pan + click "Patch" a dozen times to fill the
     * surrounding area before going offline.
     * <p>
     * For each tile in the expanded range not already cached, enqueues
     * an async fetch. Returns the count of tiles queued.
     */
    public int patchMissingTilesInView(TileSource source) {
        if (source == null) return 0;
        Projection proj = getProjection();
        if (proj == null) return 0;
        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) return 0;

        int zoom = TileCoord.scaleToZoom(proj.getScale(),
                source.getMinZoom(), source.getMaxZoom());

        LatLonPoint ul = (LatLonPoint) proj.inverse(0.0, 0.0);
        LatLonPoint lr = (LatLonPoint) proj.inverse((double) viewW, (double) viewH);
        int minX = TileCoord.clampTileX(TileCoord.lonToTileX(ul.getX(), zoom), zoom);
        int maxX = TileCoord.clampTileX(TileCoord.lonToTileX(lr.getX(), zoom), zoom);
        int minY = TileCoord.clampTileY(TileCoord.latToTileY(ul.getY(), zoom), zoom);
        int maxY = TileCoord.clampTileY(TileCoord.latToTileY(lr.getY(), zoom), zoom);
        if (ul.getX() > lr.getX()) maxX = (1 << zoom) - 1;

        // Expand by one viewport-width on each side (3× total area).
        int visibleW = maxX - minX + 1;
        int visibleH = maxY - minY + 1;
        int maxIdx = (1 << zoom) - 1;
        int expMinX = Math.max(0,      minX - visibleW);
        int expMaxX = Math.min(maxIdx, maxX + visibleW);
        int expMinY = Math.max(0,      minY - visibleH);
        int expMaxY = Math.min(maxIdx, maxY + visibleH);

        int queued = 0;
        for (int ty = expMinY; ty <= expMaxY; ty++) {
            for (int tx = expMinX; tx <= expMaxX; tx++) {
                if (lookupTileFromCache(source, zoom, tx, ty) == null) {
                    requestTileAsync(source, zoom, tx, ty);
                    queued++;
                }
            }
        }
        return queued;
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
