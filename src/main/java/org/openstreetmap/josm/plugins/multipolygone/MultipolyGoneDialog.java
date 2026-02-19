package org.openstreetmap.josm.plugins.multipolygone;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

import org.openstreetmap.josm.actions.AutoScaleAction;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.dialogs.ToggleDialog;
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeEvent;
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeListener;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixableRelation;
import org.openstreetmap.josm.tools.Shortcut;

public class MultipolyGoneDialog extends ToggleDialog implements ActiveLayerChangeListener {

    private final DefaultListModel<FixableRelation> listModel;
    private final JList<FixableRelation> list;

    private final AbstractAction refreshAction;
    private final AbstractAction goneAction;
    private final AbstractAction allGoneAction;

    public MultipolyGoneDialog() {
        super(
            tr("Multipoly-Gone"),
            "multipoly-gone",
            tr("List unnecessary multipolygon relations for simplification"),
            Shortcut.registerShortcut(
                "subwindow:multipolygone",
                tr("Windows: {0}", tr("Multipoly-Gone")),
                KeyEvent.VK_M, Shortcut.ALT_SHIFT
            ),
            150
        );

        listModel = new DefaultListModel<>();
        list = new JList<>(listModel);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setCellRenderer(new FixableRelationRenderer());

        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                FixableRelation selected = list.getSelectedValue();
                if (selected != null && selected.getRelation().getDataSet() != null) {
                    selected.getRelation().getDataSet().setSelected(selected.getRelation());
                }
            }
        });

        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    FixableRelation selected = list.getSelectedValue();
                    if (selected != null) {
                        AutoScaleAction.zoomTo(Collections.singleton(selected.getRelation()));
                    }
                }
            }
        });

        refreshAction = new AbstractAction(tr("Refresh")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                refresh();
            }
        };

        goneAction = new AbstractAction(tr("Gone")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                fixSelected();
            }
        };

        allGoneAction = new AbstractAction(tr("All Gone")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                fixAll();
            }
        };

        createLayout(new JScrollPane(list), false, Arrays.asList(
            new SideButton(refreshAction),
            new SideButton(goneAction),
            new SideButton(allGoneAction)
        ));
    }

    private DataSet getDataSet() {
        OsmDataLayer editLayer = MainApplication.getLayerManager().getEditLayer();
        return editLayer != null ? editLayer.getDataSet() : null;
    }

    public void refresh() {
        listModel.clear();
        DataSet ds = getDataSet();
        if (ds == null) {
            updateButtonState();
            return;
        }
        List<FixableRelation> fixables = MultipolygonAnalyzer.findFixableRelations(ds);
        for (FixableRelation f : fixables) {
            listModel.addElement(f);
        }
        updateButtonState();
    }

    private void fixSelected() {
        List<FixableRelation> selected = list.getSelectedValuesList();
        if (selected.isEmpty()) {
            return;
        }
        MultipolygonFixer.fixRelations(selected);
        refresh();
    }

    private void fixAll() {
        List<FixableRelation> all = new java.util.ArrayList<>();
        for (int i = 0; i < listModel.size(); i++) {
            all.add(listModel.get(i));
        }
        if (all.isEmpty()) {
            return;
        }
        MultipolygonFixer.fixRelations(all);
        refresh();
    }

    private void updateButtonState() {
        boolean hasItems = !listModel.isEmpty();
        goneAction.setEnabled(hasItems);
        allGoneAction.setEnabled(hasItems);
    }

    @Override
    public void showNotify() {
        MainApplication.getLayerManager().addActiveLayerChangeListener(this);
        refresh();
    }

    @Override
    public void hideNotify() {
        MainApplication.getLayerManager().removeActiveLayerChangeListener(this);
    }

    @Override
    public void activeOrEditLayerChanged(ActiveLayerChangeEvent e) {
        refresh();
    }

    private static class FixableRelationRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> jlist, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                jlist, value, index, isSelected, cellHasFocus);
            if (value instanceof FixableRelation fr) {
                long id = fr.getRelation().getId();
                String idStr = id < 0 ? "new" : String.valueOf(id);
                label.setText(String.format("Relation %s: %s", idStr, fr.getDescription()));
            }
            return label;
        }
    }
}
