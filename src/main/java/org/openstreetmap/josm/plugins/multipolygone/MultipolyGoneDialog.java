package org.openstreetmap.josm.plugins.multipolygone;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.swing.AbstractAction;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

import org.openstreetmap.josm.actions.AutoScaleAction;
import org.openstreetmap.josm.data.osm.DataSelectionListener;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.event.SelectionEventManager;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.dialogs.ToggleDialog;
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeEvent;
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeListener;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixableRelation;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

public class MultipolyGoneDialog extends ToggleDialog
        implements ActiveLayerChangeListener, DataSelectionListener {

    private final DefaultListModel<FixableRelation> listModel;
    private final JList<FixableRelation> list;

    private final AbstractAction refreshAction;
    private final AbstractAction goneAction;
    private final AbstractAction allGoneAction;

    /** Guard to prevent selection feedback loop (list click -> map select -> list select -> ...) */
    private boolean updatingSelection;

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
            if (!e.getValueIsAdjusting() && !updatingSelection) {
                updatingSelection = true;
                try {
                    List<FixableRelation> selected = list.getSelectedValuesList();
                    DataSet ds = getDataSet();
                    if (ds != null && !selected.isEmpty()) {
                        List<OsmPrimitive> primitives = new ArrayList<>();
                        for (FixableRelation fr : selected) {
                            primitives.add(fr.getRelation());
                        }
                        ds.setSelected(primitives);
                    }
                } finally {
                    updatingSelection = false;
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
        new ImageProvider("dialogs", "refresh").getResource().attachImageIcon(refreshAction, true);

        goneAction = new AbstractAction(tr("Gone")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                fixSelected();
            }
        };
        new ImageProvider("dialogs", "fix").getResource().attachImageIcon(goneAction, true);

        allGoneAction = new AbstractAction(tr("All Gone")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                fixAll();
            }
        };
        new ImageProvider("dialogs", "fix").getResource().attachImageIcon(allGoneAction, true);

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
        List<FixableRelation> all = new ArrayList<>();
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
        SelectionEventManager.getInstance().addSelectionListenerForEdt(this);
        refresh();
    }

    @Override
    public void hideNotify() {
        MainApplication.getLayerManager().removeActiveLayerChangeListener(this);
        SelectionEventManager.getInstance().removeSelectionListener(this);
    }

    @Override
    public void activeOrEditLayerChanged(ActiveLayerChangeEvent e) {
        refresh();
    }

    @Override
    public void selectionChanged(SelectionChangeEvent event) {
        if (updatingSelection) {
            return;
        }
        updatingSelection = true;
        try {
            Collection<? extends OsmPrimitive> selected = event.getSelection();
            Set<Relation> selectedRelations = selected.stream()
                .filter(Relation.class::isInstance)
                .map(Relation.class::cast)
                .collect(Collectors.toSet());

            if (selectedRelations.isEmpty()) {
                list.clearSelection();
                return;
            }

            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < listModel.size(); i++) {
                if (selectedRelations.contains(listModel.get(i).getRelation())) {
                    indices.add(i);
                }
            }

            if (indices.isEmpty()) {
                list.clearSelection();
            } else {
                int[] arr = indices.stream().mapToInt(Integer::intValue).toArray();
                list.setSelectedIndices(arr);
                list.ensureIndexIsVisible(arr[0]);
            }
        } finally {
            updatingSelection = false;
        }
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
