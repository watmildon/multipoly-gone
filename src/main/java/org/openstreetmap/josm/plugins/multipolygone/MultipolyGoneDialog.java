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
import java.util.LinkedHashMap;
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
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import org.openstreetmap.josm.actions.AutoScaleAction;
import org.openstreetmap.josm.actions.DownloadReferrersAction;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.UndoRedoHandler.CommandQueuePreciseListener;
import org.openstreetmap.josm.data.osm.DataSelectionListener;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.PrimitiveId;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.event.SelectionEventManager;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.dialogs.ToggleDialog;
import org.openstreetmap.josm.gui.io.DownloadPrimitivesTask;
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeEvent;
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeListener;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.AnalysisResult;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixOp;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixOpType;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixPlan;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.SkipReason;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.SkipResult;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

public class MultipolyGoneDialog extends ToggleDialog
        implements ActiveLayerChangeListener, DataSelectionListener, CommandQueuePreciseListener {

    private final DefaultListModel<FixPlan> listModel;
    private final JList<FixPlan> list;

    private final JTabbedPane tabbedPane;
    private final DefaultMutableTreeNode skippedRoot;
    private final JTree skippedTree;
    private List<SkipResult> currentSkipResults = new ArrayList<>();

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

        // --- Fixable list (Tab 1) ---
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

        // --- Skipped tree (Tab 2) ---
        skippedRoot = new DefaultMutableTreeNode("Skipped");
        skippedTree = new JTree(skippedRoot);
        skippedTree.setRootVisible(false);
        skippedTree.setShowsRootHandles(true);
        skippedTree.setCellRenderer(new SkipResultTreeRenderer());
        ToolTipManager.sharedInstance().registerComponent(skippedTree);

        skippedTree.addTreeSelectionListener(e -> {
            if (updatingSelection) return;
            TreePath path = e.getPath();
            if (path == null) return;
            Object userObj = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
            if (userObj instanceof SkipResult sr) {
                updatingSelection = true;
                try {
                    DataSet ds = getDataSet();
                    if (ds != null) {
                        ds.setSelected(Collections.singleton(sr.getRelation()));
                    }
                } finally {
                    updatingSelection = false;
                }
            }
        });

        skippedTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = skippedTree.getPathForLocation(e.getX(), e.getY());
                    if (path == null) return;
                    Object userObj = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                    if (userObj instanceof SkipResult sr) {
                        AutoScaleAction.zoomTo(Collections.singleton(sr.getRelation()));
                    }
                }
            }
        });

        // --- Tabbed pane ---
        tabbedPane = new JTabbedPane();
        tabbedPane.addTab(tr("Fixable"), new JScrollPane(list));
        tabbedPane.addTab(tr("Skipped"), new JScrollPane(skippedTree));
        tabbedPane.addChangeListener(e -> updateButtonState());

        // --- Actions ---
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

        createLayout(tabbedPane, false, Arrays.asList(
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
        currentSkipResults.clear();
        DataSet ds = getDataSet();
        if (ds == null) {
            setTitle(tr("Multipoly-Gone"));
            updateTabTitles(0, 0);
            updateButtonState();
            rebuildSkippedTree();
            return;
        }
        AnalysisResult result = MultipolygonAnalyzer.analyzeAll(ds, null);
        for (FixPlan f : result.getFixPlans()) {
            listModel.addElement(f);
        }
        currentSkipResults = result.getSkipResults();

        int fixCount = result.getFixPlans().size();
        int skipCount = currentSkipResults.size();
        if (fixCount == 0 && skipCount == 0) {
            setTitle(tr("Multipoly-Gone"));
        } else if (skipCount == 0) {
            setTitle(tr("Multipoly-Gone: {0}", fixCount));
        } else {
            setTitle(tr("Multipoly-Gone: {0} fixable, {1} skipped", fixCount, skipCount));
        }
        updateTabTitles(fixCount, skipCount);
        rebuildSkippedTree();
        updateButtonState();
    }

    private void updateTabTitles(int fixCount, int skipCount) {
        if (tabbedPane.getTabCount() >= 2) {
            tabbedPane.setTitleAt(0, tr("Fixable ({0})", fixCount));
            tabbedPane.setTitleAt(1, tr("Skipped ({0})", skipCount));
        }
    }

    private void rebuildSkippedTree() {
        skippedRoot.removeAllChildren();

        // Group by reason, maintaining enum declaration order
        Map<SkipReason, List<SkipResult>> grouped = new LinkedHashMap<>();
        for (SkipResult sr : currentSkipResults) {
            grouped.computeIfAbsent(sr.getReason(), k -> new ArrayList<>()).add(sr);
        }

        for (Map.Entry<SkipReason, List<SkipResult>> entry : grouped.entrySet()) {
            DefaultMutableTreeNode categoryNode = new DefaultMutableTreeNode(entry.getKey());
            for (SkipResult sr : entry.getValue()) {
                categoryNode.add(new DefaultMutableTreeNode(sr));
            }
            skippedRoot.add(categoryNode);
        }

        ((DefaultTreeModel) skippedTree.getModel()).reload();
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
        if (shouldDownloadBeforeFix(selected, () -> {
            MultipolygonFixer.fixRelations(selected);
            refresh();
        })) {
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

        if (shouldDownloadBeforeFix(all, () -> {
            MultipolygonFixer.fixRelationsUntilConvergence(all);
            refresh();
        })) {
            return;
        }

        MultipolygonFixer.fixRelationsUntilConvergence(all);
        refresh();
    }

    /**
     * Finds source ways from CONSOLIDATE_RINGS and DECOMPOSE ops that are candidates
     * for deletion during cleanup but don't have referrers downloaded yet.
     * Only ways without significant tags need referrer data (tagged ways are kept regardless).
     */
    private Set<Way> findWaysNeedingReferrers(List<FixPlan> plans) {
        Set<String> mpInsignificantTags = MultipolygonFixer.getInsignificantTagsForMultipolygon();
        Set<String> boundaryInsignificantTags = MultipolygonFixer.getInsignificantTagsForBoundary();

        Set<Way> result = new java.util.LinkedHashSet<>();
        for (FixPlan plan : plans) {
            Set<String> insignificantTags = plan.isBoundary()
                ? boundaryInsignificantTags : mpInsignificantTags;
            for (FixOp op : plan.getOperations()) {
                if (op.getType() == FixOpType.CONSOLIDATE_RINGS && op.getRings() != null) {
                    for (WayChainBuilder.Ring ring : op.getRings()) {
                        for (Way way : ring.getSourceWays()) {
                            if (!way.isReferrersDownloaded()
                                    && !MultipolygonFixer.hasSignificantTags(way, insignificantTags)) {
                                result.add(way);
                            }
                        }
                    }
                }
                if (op.getType() == FixOpType.DECOMPOSE_SELF_INTERSECTIONS && op.getDecomposedRings() != null) {
                    for (var decomp : op.getDecomposedRings()) {
                        for (Way way : decomp.getOriginalRing().getSourceWays()) {
                            if (!way.isReferrersDownloaded()
                                    && !MultipolygonFixer.hasSignificantTags(way, insignificantTags)) {
                                result.add(way);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private void downloadReferrersForSelected() {
        OsmDataLayer editLayer = MainApplication.getLayerManager().getEditLayer();
        if (editLayer == null) {
            return;
        }

        if (tabbedPane.getSelectedIndex() == 1) {
            downloadForSkippedRelations(editLayer);
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

        Set<Way> waysNeedingReferrers = findWaysNeedingReferrers(plans);

        if (waysNeedingReferrers.isEmpty()) {
            Logging.info("Multipoly-Gone: all cleanup-candidate ways already have referrers downloaded");
            return;
        }

        Logging.info("Multipoly-Gone: downloading referrers for {0} way(s)", waysNeedingReferrers.size());
        DownloadReferrersAction.downloadReferrers(editLayer, new ArrayList<>(waysNeedingReferrers));
    }

    /**
     * Checks the download-before-fix preference and prompts or downloads as needed.
     * Returns true if the caller should NOT proceed with the fix (download was initiated
     * asynchronously, or user cancelled). Returns false if the caller should proceed
     * immediately (no download needed, user chose to skip, or preference is "never").
     */
    private boolean shouldDownloadBeforeFix(List<FixPlan> plans, Runnable fixAction) {
        Set<Way> waysNeeding = findWaysNeedingReferrers(plans);
        if (waysNeeding.isEmpty()) {
            return false;
        }

        String pref = Config.getPref().get(
            MultipolyGonePreferences.PREF_DOWNLOAD_BEFORE_FIX, "prompt");

        if ("never".equals(pref)) {
            return false;
        }

        if ("always".equals(pref)) {
            downloadThenFix(waysNeeding, fixAction);
            return true;
        }

        // "prompt" — show dialog
        int choice = showDownloadPrompt(waysNeeding.size());
        if (choice == 0) { // Download and Fix
            downloadThenFix(waysNeeding, fixAction);
            return true;
        }
        if (choice == 1) { // Fix Without Download
            return false;
        }
        return true; // Cancel or closed — don't proceed
    }

    private int showDownloadPrompt(int wayCount) {
        String message = tr("{0} way(s) do not have referrers downloaded.\n"
            + "Downloading referrers ensures that ways shared with other\n"
            + "relations are not accidentally deleted.", wayCount);
        String[] options = {
            tr("Download and Fix"),
            tr("Fix Without Download"),
            tr("Cancel")
        };
        return JOptionPane.showOptionDialog(
            MainApplication.getMainFrame(),
            message,
            tr("Multipoly-Gone"),
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null, options, options[0]);
    }

    private void downloadThenFix(Set<Way> ways, Runnable fixAction) {
        OsmDataLayer editLayer = MainApplication.getLayerManager().getEditLayer();
        if (editLayer == null) {
            return;
        }
        DownloadReferrersAction.downloadReferrers(editLayer, new ArrayList<>(ways));
        // Chain the fix after download completes, then run on EDT
        MainApplication.worker.submit(() -> SwingUtilities.invokeLater(fixAction));
    }

    private void downloadForSkippedRelations(OsmDataLayer editLayer) {
        // Get selected skipped results, or all if none selected
        List<SkipResult> targets = getSelectedSkipResults();
        if (targets.isEmpty()) {
            targets = currentSkipResults;
        }

        List<PrimitiveId> toDownload = new ArrayList<>();

        for (SkipResult sr : targets) {
            switch (sr.getReason()) {
                case INCOMPLETE_MEMBERS -> {
                    // Download the relation with all members
                    toDownload.add(sr.getRelation().getPrimitiveId());
                }
                case DELETED_OR_INCOMPLETE_WAY -> {
                    // Download incomplete member ways
                    for (RelationMember m : sr.getRelation().getMembers()) {
                        if (m.isWay()) {
                            Way w = m.getWay();
                            if (w.isIncomplete()) {
                                toDownload.add(w.getPrimitiveId());
                            }
                        }
                    }
                }
                default -> { } // structural issues — downloading more data won't help
            }
        }

        if (toDownload.isEmpty()) {
            Logging.info("Multipoly-Gone: no downloadable data for selected skipped relations");
            return;
        }

        Logging.info("Multipoly-Gone: downloading {0} primitive(s) for skipped relations", toDownload.size());
        DownloadPrimitivesTask task = new DownloadPrimitivesTask(editLayer, toDownload, true);
        MainApplication.worker.submit(task);
        // Refresh after download completes
        MainApplication.worker.submit(() -> SwingUtilities.invokeLater(this::refresh));
    }

    private List<SkipResult> getSelectedSkipResults() {
        List<SkipResult> results = new ArrayList<>();
        TreePath[] paths = skippedTree.getSelectionPaths();
        if (paths == null) return results;
        for (TreePath path : paths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            Object userObj = node.getUserObject();
            if (userObj instanceof SkipResult sr) {
                results.add(sr);
            } else if (userObj instanceof SkipReason) {
                // Category node selected — include all children
                for (int i = 0; i < node.getChildCount(); i++) {
                    DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                    if (child.getUserObject() instanceof SkipResult sr) {
                        results.add(sr);
                    }
                }
            }
        }
        return results;
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
        boolean onFixableTab = tabbedPane.getSelectedIndex() == 0;
        boolean hasFixableItems = !listModel.isEmpty();
        goneAction.setEnabled(onFixableTab && hasFixableItems);
        allGoneAction.setEnabled(onFixableTab && hasFixableItems);

        boolean hasDownloadableSkips = currentSkipResults.stream()
            .anyMatch(sr -> sr.getReason() == SkipReason.INCOMPLETE_MEMBERS
                || sr.getReason() == SkipReason.DELETED_OR_INCOMPLETE_WAY);
        downloadRefsAction.setEnabled(
            (onFixableTab && hasFixableItems) || (!onFixableTab && hasDownloadableSkips));
    }

    @Override
    public void showNotify() {
        MainApplication.getLayerManager().addActiveLayerChangeListener(this);
        SelectionEventManager.getInstance().addSelectionListenerForEdt(this);
        UndoRedoHandler.getInstance().addCommandQueuePreciseListener(this);
        refresh();
    }

    @Override
    public void hideNotify() {
        MainApplication.getLayerManager().removeActiveLayerChangeListener(this);
        SelectionEventManager.getInstance().removeSelectionListener(this);
        UndoRedoHandler.getInstance().removeCommandQueuePreciseListener(this);
    }

    @Override
    public void commandAdded(UndoRedoHandler.CommandAddedEvent e) {
        // no-op: fixSelected/fixAll already call refresh() after executing commands
    }

    @Override
    public void commandUndone(UndoRedoHandler.CommandUndoneEvent e) {
        refresh();
    }

    @Override
    public void commandRedone(UndoRedoHandler.CommandRedoneEvent e) {
        refresh();
    }

    @Override
    public void cleaned(UndoRedoHandler.CommandQueueCleanedEvent e) {
        refresh();
    }

    @Override
    public void activeOrEditLayerChanged(ActiveLayerChangeEvent e) {
        refresh();
    }

    @Override
    public void preferenceChanged(org.openstreetmap.josm.spi.preferences.PreferenceChangeEvent e) {
        super.preferenceChanged(e);
        if (e.getKey().startsWith("multipolygone.") && !listModel.isEmpty()) {
            refresh();
        }
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
            // is a member of a relation in our fixable or skipped lists
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
                    for (SkipResult sr : currentSkipResults) {
                        Relation r = sr.getRelation();
                        for (RelationMember m : r.getMembers()) {
                            if (m.isWay() && selectedWays.contains(m.getWay())) {
                                selectedRelations.add(r);
                                break;
                            }
                        }
                    }
                }
            }

            // Sync fixable list selection
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

            // Sync skipped tree selection (no tab switching)
            syncSkippedTreeSelection(selectedRelations);
        } finally {
            updatingSelection = false;
        }
    }

    /**
     * Highlights matching leaf nodes in the skipped tree without switching tabs.
     */
    private void syncSkippedTreeSelection(Set<Relation> selectedRelations) {
        skippedTree.clearSelection();
        if (selectedRelations.isEmpty()) return;

        for (int catIdx = 0; catIdx < skippedRoot.getChildCount(); catIdx++) {
            DefaultMutableTreeNode catNode = (DefaultMutableTreeNode) skippedRoot.getChildAt(catIdx);
            for (int leafIdx = 0; leafIdx < catNode.getChildCount(); leafIdx++) {
                DefaultMutableTreeNode leafNode = (DefaultMutableTreeNode) catNode.getChildAt(leafIdx);
                if (leafNode.getUserObject() instanceof SkipResult sr) {
                    if (selectedRelations.contains(sr.getRelation())) {
                        TreePath path = new TreePath(leafNode.getPath());
                        skippedTree.addSelectionPath(path);
                    }
                }
            }
        }
    }

    /**
     * Extracts the operation description from a FixPlan, stripping the trailing
     * "(primaryTag)" suffix that is baked into the description string.
     */
    private static String getOperationDescription(FixPlan plan) {
        String desc = plan.getDescription();
        String primaryTag = MultipolygonAnalyzer.getPrimaryTag(plan.getRelation());
        String suffix = " (" + primaryTag + ")";
        if (desc.endsWith(suffix)) {
            return desc.substring(0, desc.length() - suffix.length());
        }
        return desc;
    }

    /** Tags that describe identity/metadata rather than the feature's topical classification. */
    private static final Set<String> NON_TOPICAL_TAGS = Set.of(
        "type", "name", "note", "description", "source", "created_by", "ref"
    );

    static String formatIdentityTag(Relation r) {
        String boundary = r.get("boundary");
        String adminLevel = r.get("admin_level");
        if (boundary != null) {
            StringBuilder sb = new StringBuilder("boundary=").append(boundary);
            if (adminLevel != null) {
                sb.append(", AL").append(adminLevel);
            }
            return sb.toString();
        }
        // Prefer topical tags (natural, landuse, leisure, etc.) over name/metadata
        for (String key : r.getKeys().keySet()) {
            if (!NON_TOPICAL_TAGS.contains(key) && !key.startsWith("_")) {
                return key + "=" + r.get(key);
            }
        }
        return MultipolygonAnalyzer.getPrimaryTag(r);
    }

    /**
     * Formats a relation for display in both the fixable and skipped lists.
     * Returns "Id {id}: {name} [{identity}]" or "Id {id}: {identity}" if no name.
     */
    static String formatRelationLabel(Relation r) {
        long id = r.getId();
        String idStr = id < 0 ? "new" : String.valueOf(id);
        String name = r.get("name");
        String identity = formatIdentityTag(r);

        StringBuilder sb = new StringBuilder();
        sb.append("Id ").append(idStr).append(": ");
        if (name != null && !name.isEmpty()) {
            sb.append(name);
            if (!name.equals(identity) && !identity.equals("name=" + name)) {
                sb.append(" [").append(identity).append("]");
            }
        } else {
            sb.append(identity);
        }
        return sb.toString();
    }

    private static class FixPlanRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> jlist, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                jlist, value, index, isSelected, cellHasFocus);
            if (value instanceof FixPlan fr) {
                String relLabel = formatRelationLabel(fr.getRelation());
                String opDesc = getOperationDescription(fr);
                label.setText(relLabel + " \u2192 " + opDesc);
            }
            return label;
        }
    }

    private static class SkipResultTreeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            if (!(value instanceof DefaultMutableTreeNode node)) return this;
            Object userObj = node.getUserObject();

            if (userObj instanceof SkipReason reason) {
                int count = node.getChildCount();
                setText(reason.getMessage() + " (" + count + ")");
                setToolTipText(reason.getHint());
                setIcon(null);
            } else if (userObj instanceof SkipResult sr) {
                String relLabel = formatRelationLabel(sr.getRelation());
                if (sr.getDetail() != null) {
                    setText(relLabel + " \u2014 " + sr.getDetail());
                } else {
                    setText(relLabel);
                }
                setToolTipText(sr.getReason().getHint());
            }
            return this;
        }
    }
}
