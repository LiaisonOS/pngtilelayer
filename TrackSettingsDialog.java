package org.ka2ddo.yaac.gui.tile;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionEvent;

/**
 * Modeless dialog for global track rendering settings — shape, marker size,
 * line style, and trail coverage. Applies to every station, no per-station
 * override. Live-applies on OK and Apply (repaints the layer immediately).
 */
public class TrackSettingsDialog extends JDialog {

    private final StationPushpinLayer pushpinLayer;

    private final JRadioButton shapeTriangle = new JRadioButton("Triangle");
    private final JRadioButton shapeCircle   = new JRadioButton("Circle");

    private final JRadioButton sizeLarge = new JRadioButton("Large");
    private final JRadioButton sizeSmall = new JRadioButton("Small");

    private final JRadioButton lineDashed = new JRadioButton("Dashed");
    private final JRadioButton lineSolid  = new JRadioButton("Solid");

    private final JRadioButton covAll   = new JRadioButton("All points");
    private final JRadioButton covLast3 = new JRadioButton("Last 3 points");

    public TrackSettingsDialog(Window parent, StationPushpinLayer pushpinLayer) {
        super(parent, "Tracks Settings", Dialog.ModalityType.MODELESS);
        this.pushpinLayer = pushpinLayer;
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        gc.insets = new Insets(4, 4, 4, 4);
        gc.gridx = 0;
        gc.gridy = 0;

        content.add(buildGroup("Marker Shape",
                shapeTriangle, shapeCircle), gc);

        gc.gridy++;
        content.add(buildGroup("Marker Size",
                sizeLarge, sizeSmall), gc);

        gc.gridy++;
        content.add(buildGroup("Line Style",
                lineDashed, lineSolid), gc);

        gc.gridy++;
        content.add(buildGroup("Trail Coverage",
                covAll, covLast3), gc);

        // Button row
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton apply  = new JButton("Apply");
        JButton ok     = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        buttons.add(apply);
        buttons.add(cancel);
        buttons.add(ok);

        gc.gridy++;
        gc.fill = GridBagConstraints.NONE;
        gc.anchor = GridBagConstraints.EAST;
        content.add(buttons, gc);

        setContentPane(content);

        // Wire actions
        apply.addActionListener((ActionEvent e) -> applyToModel());
        ok.addActionListener((ActionEvent e) -> { applyToModel(); dispose(); });
        cancel.addActionListener((ActionEvent e) -> dispose());

        loadFromModel();

        pack();
        setLocationRelativeTo(parent);
    }

    private static JPanel buildGroup(String title,
                                     JRadioButton a, JRadioButton b) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        p.setBorder(new TitledBorder(title));
        ButtonGroup bg = new ButtonGroup();
        bg.add(a);
        bg.add(b);
        p.add(a);
        p.add(b);
        return p;
    }

    private void loadFromModel() {
        TrackSettings ts = TrackSettings.getInstance();
        (ts.getShape()      == TrackSettings.Shape.TRIANGLE   ? shapeTriangle : shapeCircle).setSelected(true);
        (ts.getMarkerSize() == TrackSettings.MarkerSize.LARGE ? sizeLarge     : sizeSmall  ).setSelected(true);
        (ts.getLineStyle()  == TrackSettings.LineStyle.DASHED ? lineDashed    : lineSolid  ).setSelected(true);
        (ts.getCoverage()   == TrackSettings.Coverage.ALL     ? covAll        : covLast3   ).setSelected(true);
    }

    private void applyToModel() {
        TrackSettings.Shape      shape = shapeTriangle.isSelected()
                ? TrackSettings.Shape.TRIANGLE : TrackSettings.Shape.CIRCLE;
        TrackSettings.MarkerSize size  = sizeLarge.isSelected()
                ? TrackSettings.MarkerSize.LARGE : TrackSettings.MarkerSize.SMALL;
        TrackSettings.LineStyle  line  = lineDashed.isSelected()
                ? TrackSettings.LineStyle.DASHED : TrackSettings.LineStyle.SOLID;
        TrackSettings.Coverage   cov   = covAll.isSelected()
                ? TrackSettings.Coverage.ALL : TrackSettings.Coverage.LAST_3;

        TrackSettings.getInstance().setAll(shape, size, line, cov);
        if (pushpinLayer != null) pushpinLayer.repaint();
    }
}
