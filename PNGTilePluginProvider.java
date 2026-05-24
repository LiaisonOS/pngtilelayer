package org.ka2ddo.yaac.gui.tile;

import org.ka2ddo.yaac.gui.GeographicalMap;
import org.ka2ddo.yaac.gui.MainGui;
import org.ka2ddo.yaac.pluginapi.AbstractMenuAction;
import org.ka2ddo.yaac.pluginapi.Provider;

import javax.swing.JOptionPane;

/**
 * YAAC plugin provider that registers the PNG tile map layer
 * and the top-level "LiaisonOS" menu used to host all plugin actions.
 */
public class PNGTilePluginProvider extends Provider {

    /**
     * Top-level menu name for all plugin actions.
     * <p>
     * Stored under {@link AbstractMenuAction#LOCALIZED_MENU_HIERARCHY} in
     * each action so YAAC's {@code Localizer.getMsg()} is bypassed — we
     * supply the literal display text directly, since "LiaisonOS" has no
     * entry in YAAC's PropertyResourceBundle.
     */
    private static final String[] LIAISONOS_MENU      = new String[]{"LiaisonOS"};
    private static final String[] SNAP_SCALE_SUBMENU  = new String[]{"LiaisonOS", "Snap to Scale"};

    /** Standard cartographic 1:N scales, in display order. */
    private static final int[] SCALE_LADDER = {
            2_500, 5_000, 10_000, 25_000, 50_000, 100_000, 250_000
    };

    public PNGTilePluginProvider() {
        super("LiaisonSAR", "1.0", "VA2OPS",
                "<html>LiaisonSAR &mdash; SAR mapping for YAAC: offline PNG tile maps, "
                        + "operator pushpins &amp; trails, UTM grid, snap-to-scale, "
                        + "SAR object catalog.</html>");
    }

    @Override
    public boolean runInitializersBefore(int providerApiVersion) {
        return providerApiVersion >= 26;
    }

    @Override
    public String getInitFailureReason() {
        return buildNewerYaacNeededMsg(26);
    }

    @Override
    public int willPluginWorkAfterLiveInstallation(boolean isNewInstall) {
        return AFTERINSTALL_FULLY_READY;
    }

    @Override
    public void runInitializersAfter() {
        GeographicalMap.addMapLayer(new PNGTileLayerCreator());
        GeographicalMap.addMapLayer(new StationPushpinLayerCreator());
    }

    @Override
    public AbstractMenuAction[] getMenuItems() {
        java.util.List<AbstractMenuAction> items = new java.util.ArrayList<>();
        items.add(new MapSourcesMenuAction());
        items.add(new StationColorsMenuAction());
        items.add(new TracksSettingsMenuAction());
        items.add(new GrayscaleToggleMenuAction());
        items.add(new ShowGridToggleMenuAction());
        items.add(new CacheOnlyToggleMenuAction());
        for (int scale : SCALE_LADDER) {
            items.add(new SnapScaleMenuAction(scale));
        }
        items.add(new SARObjectsMenuAction());
        items.add(new ClearStationTracksMenuAction());
        items.add(new ClearSARObjectsMenuAction());
        return items.toArray(new AbstractMenuAction[0]);
    }

    @Override
    public String[] getAboutAttributions() {
        return new String[]{
                "LiaisonSAR — YAAC SAR mapping plugin © 2026 VA2OPS / LiaisonOS"
        };
    }

    // --- Menu actions --------------------------------------------------
    // All "Open dialog" actions defer heavy class references to
    // actionPerformed() to avoid class loading during plugin init
    // (before GUI is ready).

    private static class MapSourcesMenuAction extends AbstractMenuAction {

        MapSourcesMenuAction() {
            super("MapSources", LIAISONOS_MENU);
            putValue(NAME, "Map Sources...");
            putValue(LOCALIZED_MENU_HIERARCHY, LIAISONOS_MENU);
        }

        @Override
        public void actionPerformed(Object e) {
            try {
                PNGTileLayer layer = PNGTileLayerCreator.getLastCreatedLayer();
                if (layer == null) return;
                TileSourceManager mgr = layer.getSourceManager();
                java.awt.Window parent = MainGui.getCurrentlyFocusedWindow();
                MapSourceManagerDialog dialog = new MapSourceManagerDialog(parent, mgr, layer);
                dialog.setVisible(true);
            } catch (Exception ex) {
                System.err.println("MapSourcesMenuAction: " + ex.getMessage());
            }
        }
    }

    private static class StationColorsMenuAction extends AbstractMenuAction {

        StationColorsMenuAction() {
            super("StationColors", LIAISONOS_MENU);
            putValue(NAME, "Station Settings...");
            putValue(LOCALIZED_MENU_HIERARCHY, LIAISONOS_MENU);
        }

        @Override
        public void actionPerformed(Object e) {
            try {
                StationPushpinLayer layer =
                        StationPushpinLayerCreator.getLastCreatedLayer();
                if (layer == null) return;
                StationColorManager mgr = layer.getColorManager();
                if (mgr == null) return;
                java.awt.Window parent = MainGui.getCurrentlyFocusedWindow();
                StationColorsDialog dialog =
                        new StationColorsDialog(parent, mgr, layer);
                dialog.setVisible(true);
            } catch (Exception ex) {
                System.err.println("StationColorsMenuAction: " + ex.getMessage());
            }
        }
    }

    private static class TracksSettingsMenuAction extends AbstractMenuAction {

        TracksSettingsMenuAction() {
            super("TracksSettings", LIAISONOS_MENU);
            putValue(NAME, "Tracks Settings...");
            putValue(LOCALIZED_MENU_HIERARCHY, LIAISONOS_MENU);
        }

        @Override
        public void actionPerformed(Object e) {
            try {
                StationPushpinLayer layer =
                        StationPushpinLayerCreator.getLastCreatedLayer();
                java.awt.Window parent = MainGui.getCurrentlyFocusedWindow();
                TrackSettingsDialog dialog =
                        new TrackSettingsDialog(parent, layer);
                dialog.setVisible(true);
            } catch (Exception ex) {
                System.err.println("TracksSettingsMenuAction: " + ex.getMessage());
            }
        }
    }

    /**
     * Toggles the map-tile grayscale filter. Affects basemap tiles only —
     * station pushpins, trails, and SAR objects keep their color.
     */
    private static class GrayscaleToggleMenuAction extends AbstractMenuAction {

        /** Re-entrancy guard: YAAC's MenuAction adapter re-fires actionPerformed
         *  on any property change we cause from inside actionPerformed. Without
         *  this guard, putValue(NAME, ...) for the label update loops back. */
        private volatile boolean inAction = false;

        GrayscaleToggleMenuAction() {
            super("MapGrayscale", LIAISONOS_MENU);
            putValue(LOCALIZED_MENU_HIERARCHY, LIAISONOS_MENU);
            refreshLabelRaw();
        }

        @Override
        public void actionPerformed(Object e) {
            if (inAction) return;
            inAction = true;
            try {
                MapDisplaySettings ds = MapDisplaySettings.getInstance();
                boolean next = !ds.isGrayscale();
                ds.setGrayscale(next);
                refreshLabelRaw();

                PNGTileLayer tile = PNGTileLayerCreator.getLastCreatedLayer();
                if (tile != null) tile.repaint();
                StationPushpinLayer pin =
                        StationPushpinLayerCreator.getLastCreatedLayer();
                if (pin != null) pin.repaint();
            } catch (Exception ex) {
                System.err.println("GrayscaleToggleMenuAction: " + ex.getMessage());
            } finally {
                inAction = false;
            }
        }

        private void refreshLabelRaw() {
            boolean on = MapDisplaySettings.getInstance().isGrayscale();
            putValue(NAME, on
                    ? "Map in Grayscale  [On]"
                    : "Map in Grayscale  [Off]");
        }
    }

    /** Toggle the UTM grid overlay on/off. */
    private static class ShowGridToggleMenuAction extends AbstractMenuAction {

        private volatile boolean inAction = false;

        ShowGridToggleMenuAction() {
            super("ShowGrid", LIAISONOS_MENU);
            putValue(LOCALIZED_MENU_HIERARCHY, LIAISONOS_MENU);
            refreshLabelRaw();
        }

        @Override
        public void actionPerformed(Object e) {
            if (inAction) return;
            inAction = true;
            try {
                MapDisplaySettings ds = MapDisplaySettings.getInstance();
                boolean next = !ds.isShowGrid();
                ds.setShowGrid(next);
                refreshLabelRaw();

                StationPushpinLayer pin =
                        StationPushpinLayerCreator.getLastCreatedLayer();
                if (pin != null) pin.repaint();
            } catch (Exception ex) {
                System.err.println("ShowGridToggleMenuAction: " + ex.getMessage());
            } finally {
                inAction = false;
            }
        }

        private void refreshLabelRaw() {
            boolean on = MapDisplaySettings.getInstance().isShowGrid();
            putValue(NAME, on ? "Show Grid  [On]" : "Show Grid  [Off]");
        }
    }

    /**
     * Toggles cache-only mode for tile fetching. When ON, the plugin skips
     * the online fetch entirely on cache miss — tiles either come from
     * the local SQLite cache or show as "TILE NOT IN CACHE" placeholders.
     * Critical for known-offline SAR operations where waiting on dead
     * network timeouts would slow every paint to a crawl.
     */
    private static class CacheOnlyToggleMenuAction extends AbstractMenuAction {

        private volatile boolean inAction = false;

        CacheOnlyToggleMenuAction() {
            super("CacheOnly", LIAISONOS_MENU);
            putValue(LOCALIZED_MENU_HIERARCHY, LIAISONOS_MENU);
            refreshLabelRaw();
        }

        @Override
        public void actionPerformed(Object e) {
            if (inAction) return;
            inAction = true;
            try {
                MapDisplaySettings ds = MapDisplaySettings.getInstance();
                boolean next = !ds.isCacheOnly();
                ds.setCacheOnly(next);
                refreshLabelRaw();

                PNGTileLayer tile = PNGTileLayerCreator.getLastCreatedLayer();
                if (tile != null) {
                    tile.clearMemoryCache();
                    tile.repaint();
                }
            } catch (Exception ex) {
                System.err.println("CacheOnlyToggleMenuAction: " + ex.getMessage());
            } finally {
                inAction = false;
            }
        }

        private void refreshLabelRaw() {
            boolean on = MapDisplaySettings.getInstance().isCacheOnly();
            putValue(NAME, on ? "Cache Only  [On]" : "Cache Only  [Off]");
        }
    }

    /**
     * Sets the OpenMap MapBean scale to the configured 1:N value AND
     * auto-updates the grid spacing per {@link MapDisplaySettings#SCALE_GRID_LADDER}.
     */
    private static class SnapScaleMenuAction extends AbstractMenuAction {

        private final int scale;

        SnapScaleMenuAction(int scale) {
            super("SnapScale_" + scale, SNAP_SCALE_SUBMENU);
            this.scale = scale;
            putValue(NAME, "1:" + formatScale(scale));
            putValue(LOCALIZED_MENU_HIERARCHY, SNAP_SCALE_SUBMENU);
        }

        @Override
        public void actionPerformed(Object e) {
            try {
                PNGTileLayer tile = PNGTileLayerCreator.getLastCreatedLayer();
                if (tile != null) tile.setMapScale((float) scale);

                int gridStep = MapDisplaySettings.gridStepForScale(scale);
                if (gridStep > 0) {
                    MapDisplaySettings.getInstance().setGridSpacingMeters(gridStep);
                }

                StationPushpinLayer pin =
                        StationPushpinLayerCreator.getLastCreatedLayer();
                if (pin != null) pin.repaint();
            } catch (Exception ex) {
                System.err.println("SnapScaleMenuAction: " + ex.getMessage());
            }
        }

        private static String formatScale(int scale) {
            // Insert thousand separators: 25000 → 25,000
            return String.format("%,d", scale);
        }
    }

    private static class SARObjectsMenuAction extends AbstractMenuAction {

        SARObjectsMenuAction() {
            super("SARObjects", LIAISONOS_MENU);
            putValue(NAME, "SAR Objects...");
            putValue(LOCALIZED_MENU_HIERARCHY, LIAISONOS_MENU);
        }

        @Override
        public void actionPerformed(Object e) {
            try {
                StationPushpinLayer layer =
                        StationPushpinLayerCreator.getLastCreatedLayer();
                if (layer == null) return;
                SARObjectManager mgr = layer.getSARObjectManager();
                if (mgr == null) return;
                java.awt.Window parent = MainGui.getCurrentlyFocusedWindow();
                SARObjectsDialog dialog =
                        new SARObjectsDialog(parent, mgr, layer);
                dialog.setVisible(true);
            } catch (Exception ex) {
                System.err.println("SARObjectsMenuAction: " + ex.getMessage());
            }
        }
    }

    /** Clear every persisted station-position row, then redraw the map. */
    private static class ClearStationTracksMenuAction extends AbstractMenuAction {

        ClearStationTracksMenuAction() {
            super("ClearStationTracks", LIAISONOS_MENU);
            putValue(NAME, "Clear All Station Tracks...");
            putValue(LOCALIZED_MENU_HIERARCHY, LIAISONOS_MENU);
        }

        @Override
        public void actionPerformed(Object e) {
            try {
                java.awt.Window parent = MainGui.getCurrentlyFocusedWindow();
                int choice = JOptionPane.showConfirmDialog(parent,
                        "This will erase every station position saved by the plugin.\n" +
                        "YAAC's live station list is unaffected — only the persistent\n" +
                        "trail history (used for replay after restart) is cleared.\n\n" +
                        "Continue?",
                        "Clear All Station Tracks",
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.OK_OPTION) return;

                PointStore store = PointStore.getInstance();
                int removed = (store != null) ? store.removeAllStationPositions() : 0;

                JOptionPane.showMessageDialog(parent,
                        "Cleared " + removed + " station position rows.",
                        "Clear Complete",
                        JOptionPane.INFORMATION_MESSAGE);

                StationPushpinLayer layer =
                        StationPushpinLayerCreator.getLastCreatedLayer();
                if (layer != null) layer.repaint();
            } catch (Exception ex) {
                System.err.println("ClearStationTracksMenuAction: " + ex.getMessage());
            }
        }
    }

    /** Clear every SAR object marker placed on the map. */
    private static class ClearSARObjectsMenuAction extends AbstractMenuAction {

        ClearSARObjectsMenuAction() {
            super("ClearSARObjects", LIAISONOS_MENU);
            putValue(NAME, "Clear All SAR Objects...");
            putValue(LOCALIZED_MENU_HIERARCHY, LIAISONOS_MENU);
        }

        @Override
        public void actionPerformed(Object e) {
            try {
                java.awt.Window parent = MainGui.getCurrentlyFocusedWindow();
                int choice = JOptionPane.showConfirmDialog(parent,
                        "This will remove every SAR evidence marker placed on the map.\n\n" +
                        "Continue?",
                        "Clear All SAR Objects",
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.OK_OPTION) return;

                StationPushpinLayer layer =
                        StationPushpinLayerCreator.getLastCreatedLayer();
                SARObjectManager mgr = (layer != null) ? layer.getSARObjectManager() : null;
                if (mgr != null) {
                    mgr.clearAll();
                }

                JOptionPane.showMessageDialog(parent,
                        "All SAR objects removed.",
                        "Clear Complete",
                        JOptionPane.INFORMATION_MESSAGE);

                if (layer != null) layer.repaint();
            } catch (Exception ex) {
                System.err.println("ClearSARObjectsMenuAction: " + ex.getMessage());
            }
        }
    }

}
