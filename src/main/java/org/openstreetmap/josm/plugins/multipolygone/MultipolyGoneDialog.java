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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
import org.openstreetmap.josm.actions.DownloadReferrersAction;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.UndoRedoHandler.CommandQueuePreciseListener;
import org.openstreetmap.josm.data.osm.DataSelectionListener;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.event.SelectionEventManager;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.dialogs.ToggleDialog;
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeEvent;
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeListener;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixOp;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixOpType;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixPlan;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

public class MultipolyGoneDialog extends ToggleDialog
        implements ActiveLayerChangeListener, DataSelectionListener {

    private final DefaultListModel<FixPlan> listModel;
    private final JList<FixPlan> list;

    private final AbstractAction refreshAction;
    private final AbstractAction goneAction;
    private final AbstractAction allGoneAction;
    private final AbstractAction downloadRefsAction;

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
        list.setCellRenderer(new FixPlanRenderer());

        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !updatingSelection) {
                updatingSelection = true;
                try {
                    List<FixPlan> selected = list.getSelectedValuesList();
                    DataSet ds = getDataSet();
                    if (ds != null && !selected.isEmpty()) {
                        List<OsmPrimitive> primitives = new ArrayList<>();
                        for (FixPlan fr : selected) {
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
                    FixPlan selected = list.getSelectedValue();
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

        downloadRefsAction = new AbstractAction("") {
            @Override
            public void actionPerformed(ActionEvent e) {
                downloadReferrersForSelected();
            }
        };
        downloadRefsAction.putValue(AbstractAction.SHORT_DESCRIPTION, tr("Download Refs"));
        new ImageProvider("download").getResource().attachImageIcon(downloadRefsAction, true);

        createLayout(new JScrollPane(list), false, Arrays.asList(
            new SideButton(refreshAction),
            new SideButton(goneAction),
            new SideButton(allGoneAction),
            new SideButton(downloadRefsAction)
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
            setTitle(tr("Multipoly-Gone"));
            updateButtonState();
            return;
        }
        List<FixPlan> fixables = MultipolygonAnalyzer.findFixableRelations(ds);
        for (FixPlan f : fixables) {
            listModel.addElement(f);
        }
        if (fixables.isEmpty()) {
            setTitle(tr("Multipoly-Gone"));
        } else {
            setTitle(tr("Multipoly-Gone: {0}", fixables.size()));
        }
        updateButtonState();
    }

    private void fixSelected() {
        List<FixPlan> selected = list.getSelectedValuesList();
        if (selected.isEmpty()) {
            return;
        }
        if (Config.getPref().getBoolean(MultipolyGonePreferences.PREF_PLAN_ONLY, false)) {
            logPlans(selected);
            return;
        }
        MultipolygonFixer.fixRelations(selected);
        refresh();
    }

    private void fixAll() {
        List<FixPlan> all = new ArrayList<>();
        for (int i = 0; i < listModel.size(); i++) {
            all.add(listModel.get(i));
        }
        if (all.isEmpty()) {
            return;
        }

        boolean debug = Config.getPref().getBoolean(MultipolyGonePreferences.PREF_DEBUG_MODE, false);
        if (debug) {
            runDebugDeterminismCheck();
        }

        if (Config.getPref().getBoolean(MultipolyGonePreferences.PREF_PLAN_ONLY, false)) {
            logPlans(all);
            return;
        }

        MultipolygonFixer.fixRelationsUntilConvergence(all);
        refresh();
    }

    private void downloadReferrersForSelected() {
        OsmDataLayer editLayer = MainApplication.getLayerManager().getEditLayer();
        if (editLayer == null) {
            return;
        }

        // Use selected plans, or all if none selected
        List<FixPlan> plans = list.getSelectedValuesList();
        if (plans.isEmpty()) {
            plans = new ArrayList<>();
            for (int i = 0; i < listModel.size(); i++) {
                plans.add(listModel.get(i));
            }
        }
        if (plans.isEmpty()) {
            return;
        }

        Set<String> insignificantTags = MultipolygonFixer.getInsignificantTags();

        // Collect source ways from CONSOLIDATE_RINGS and DECOMPOSE ops —
        // these are the ways that would be candidates for deletion during cleanup.
        // Only ways without significant tags need referrer data (tagged ways are kept regardless).
        Set<Way> waysNeedingReferrers = new java.util.LinkedHashSet<>();
        for (FixPlan plan : plans) {
            for (FixOp op : plan.getOperations()) {
                if (op.getType() == FixOpType.CONSOLIDATE_RINGS && op.getRings() != null) {
                    for (WayChainBuilder.Ring ring : op.getRings()) {
                        for (Way way : ring.getSourceWays()) {
                            if (!way.isReferrersDownloaded()
                                    && !MultipolygonFixer.hasSignificantTags(way, insignificantTags)) {
                                waysNeedingReferrers.add(way);
                            }
                        }
                    }
                }
                if (op.getType() == FixOpType.DECOMPOSE_SELF_INTERSECTIONS && op.getDecomposedRings() != null) {
                    for (var decomp : op.getDecomposedRings()) {
                        for (Way way : decomp.getOriginalRing().getSourceWays()) {
                            if (!way.isReferrersDownloaded()
                                    && !MultipolygonFixer.hasSignificantTags(way, insignificantTags)) {
                                waysNeedingReferrers.add(way);
                            }
                        }
                    }
                }
            }
        }

        if (waysNeedingReferrers.isEmpty()) {
            Logging.info("Multipoly-Gone: all cleanup-candidate ways already have referrers downloaded");
            return;
        }

        Logging.info("Multipoly-Gone: downloading referrers for {0} way(s)", waysNeedingReferrers.size());
        DownloadReferrersAction.downloadReferrers(editLayer, new ArrayList<>(waysNeedingReferrers));
    }

    private void logPlans(List<FixPlan> plans) {
        Logging.info("Multipoly-Gone PLAN ONLY: {0} plan(s)", plans.size());
        for (FixPlan plan : plans) {
            long id = plan.getRelation().getId();
            String idStr = id < 0 ? "new" : String.valueOf(id);
            Logging.info("  Relation {0}: {1} — {2}", idStr, plan.getDescription(), plan.fingerprint());
        }
    }

    /**
     * In debug mode, re-analyzes the DataSet N times and compares plan fingerprints
     * across runs to detect non-determinism. Logs discrepancies to the JOSM console.
     * The DataSet is not modified — this only runs the analysis (planning) phase.
     */
    private void runDebugDeterminismCheck() {
        DataSet ds = getDataSet();
        if (ds == null) return;

        int iterations = MultipolyGonePreferences.DEFAULT_DEBUG_ITERATIONS;
        Logging.info("Multipoly-Gone DEBUG: running {0} analysis iterations with shuffled ordering to check determinism",
            iterations);

        // Collect fingerprints per relation across all iterations
        // Key: relation ID, Value: map of fingerprint -> list of iteration indices that produced it
        Map<Long, Map<String, List<Integer>>> allFingerprints = new HashMap<>();
        // Also track validation failures per iteration
        List<String> validationFailures = new ArrayList<>();

        for (int iter = 0; iter < iterations; iter++) {
            try {
                // Each iteration uses a different seed to shuffle member/component ordering,
                // exposing any order-dependent behavior within a single JVM session
                Random rng = new Random(iter);
                List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds, rng);
                for (FixPlan plan : plans) {
                    long relId = plan.getRelation().getUniqueId();
                    String fp = plan.fingerprint();
                    allFingerprints
                        .computeIfAbsent(relId, k -> new HashMap<>())
                        .computeIfAbsent(fp, k -> new ArrayList<>())
                        .add(iter);
                }
            } catch (IllegalStateException e) {
                validationFailures.add("iteration " + iter + ": " + e.getMessage());
            }
        }

        // Report results
        boolean anyDiscrepancy = false;
        for (Map.Entry<Long, Map<String, List<Integer>>> entry : allFingerprints.entrySet()) {
            long relId = entry.getKey();
            Map<String, List<Integer>> fpMap = entry.getValue();
            if (fpMap.size() > 1) {
                anyDiscrepancy = true;
                Logging.warn("Multipoly-Gone DEBUG: NON-DETERMINISM for relation {0} — {1} distinct plans:",
                    relId, fpMap.size());
                for (Map.Entry<String, List<Integer>> fpEntry : fpMap.entrySet()) {
                    Logging.warn("  iterations {0}: {1}", fpEntry.getValue(), fpEntry.getKey());
                }
            }
        }

        if (!validationFailures.isEmpty()) {
            anyDiscrepancy = true;
            Logging.warn("Multipoly-Gone DEBUG: {0} validation failures across {1} iterations:",
                validationFailures.size(), iterations);
            for (String failure : validationFailures) {
                Logging.warn("  {0}", failure);
            }
        }

        if (!anyDiscrepancy) {
            Logging.info("Multipoly-Gone DEBUG: all {0} iterations produced identical plans — deterministic",
                iterations);
        }
    }

    private void updateButtonState() {
        boolean hasItems = !listModel.isEmpty();
        goneAction.setEnabled(hasItems);
        allGoneAction.setEnabled(hasItems);
        downloadRefsAction.setEnabled(hasItems);
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

            // If no relations are directly selected, check if any selected way
            // is a member of a fixable relation in our list
            if (selectedRelations.isEmpty()) {
                Set<Way> selectedWays = selected.stream()
                    .filter(Way.class::isInstance)
                    .map(Way.class::cast)
                    .collect(Collectors.toSet());
                if (!selectedWays.isEmpty()) {
                    for (int i = 0; i < listModel.size(); i++) {
                        Relation r = listModel.get(i).getRelation();
                        for (RelationMember m : r.getMembers()) {
                            if (m.isWay() && selectedWays.contains(m.getWay())) {
                                selectedRelations.add(r);
                                break;
                            }
                        }
                    }
                }
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

    private static class FixPlanRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> jlist, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                jlist, value, index, isSelected, cellHasFocus);
            if (value instanceof FixPlan fr) {
                long id = fr.getRelation().getId();
                String idStr = id < 0 ? "new" : String.valueOf(id);
                label.setText(String.format("Relation %s: %s", idStr, fr.getDescription()));
            }
            return label;
        }
    }
}
