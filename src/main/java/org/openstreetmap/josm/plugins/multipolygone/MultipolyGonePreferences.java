package org.openstreetmap.josm.plugins.multipolygone;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BorderLayout;

import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;

import javax.swing.table.DefaultTableModel;

import org.openstreetmap.josm.gui.preferences.DefaultTabPreferenceSetting;
import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane;
import org.openstreetmap.josm.spi.preferences.Config;

public class MultipolyGonePreferences extends DefaultTabPreferenceSetting {

    // Legacy key — used for migration only
    public static final String PREF_INSIGNIFICANT_TAGS = "multipolygone.insignificantTags";
    public static final String DEFAULT_INSIGNIFICANT_TAGS = "source;created_by";

    public static final String PREF_INSIGNIFICANT_TAGS_MP = "multipolygone.insignificantTags.multipolygon";
    public static final String PREF_INSIGNIFICANT_TAGS_BOUNDARY = "multipolygone.insignificantTags.boundary";

    public static final String PREF_USE_DISCARDABLE_KEYS = "multipolygone.useDiscardableKeys";

    public static final String PREF_IDENTITY_TAGS = "multipolygone.identityTags";

    public static final String PREF_DOWNLOAD_BEFORE_FIX = "multipolygone.downloadBeforeFix";

    public static final String PREF_PLAN_ONLY = "multipolygone.planOnly";

    public static final String PREF_DEBUG_MODE = "multipolygone.debugMode";
    public static final int DEFAULT_DEBUG_ITERATIONS = 10;

    public static final String PREF_CENTERLINE_TAGS = "multipolygone.centerlineTags";
    public static final String DEFAULT_CENTERLINE_TAGS = "highway;waterway;railway";

    public static final String PREF_AREA_TAGS = "multipolygone.areaTags";
    public static final String DEFAULT_AREA_TAGS = "landuse;leisure;building;amenity;natural";

    public static final String PREF_ROAD_WIDTHS = "multipolygone.roadWidths";
    public static final String DEFAULT_ROAD_WIDTHS =
        "highway=motorway=12;highway=trunk=10;highway=primary=7;highway=secondary=7"
        + ";highway=tertiary=7;highway=unclassified=7;highway=residential=7"
        + ";highway=service=7;highway=track=3.5"
        + ";railway=7;waterway=stream=3.5;waterway=river=12";

    private DefaultTableModel identityTagsTableModel;
    private DefaultTableModel insignificantTagsTableModel;
    private DefaultTableModel centerlineTagsTableModel;
    private DefaultTableModel areaTagsTableModel;
    private DefaultTableModel roadWidthsTableModel;
    private JCheckBox useDiscardableKeysCheckBox;
    private JComboBox<String> downloadBeforeFixCombo;
    private JCheckBox planOnlyCheckBox;
    private JCheckBox debugModeCheckBox;

    public MultipolyGonePreferences() {
        super("preferences/multipoly-gone", tr("Multipoly-Gone"),
            tr("Settings for multipolygon dissolution"));
    }

    /**
     * Migrate the legacy single-value insignificant tags preference to the new
     * per-type keys. If the old key has a value and neither new key has been set,
     * copy the old value to both new keys and clear the old key.
     */
    static void migratePreferences() {
        String oldPref = Config.getPref().get(PREF_INSIGNIFICANT_TAGS, "");
        String newMp = Config.getPref().get(PREF_INSIGNIFICANT_TAGS_MP, "");
        String newBound = Config.getPref().get(PREF_INSIGNIFICANT_TAGS_BOUNDARY, "");
        if (!oldPref.isEmpty() && newMp.isEmpty() && newBound.isEmpty()) {
            Config.getPref().put(PREF_INSIGNIFICANT_TAGS_MP, oldPref);
            Config.getPref().put(PREF_INSIGNIFICANT_TAGS_BOUNDARY, oldPref);
            Config.getPref().put(PREF_INSIGNIFICANT_TAGS, null);
        }
    }

    /**
     * A configured tag filter with associated buffer width.
     * If {@code tagValue} is null, matches any way with the given key (key=*).
     * If {@code tagValue} is set, matches only key=value.
     */
    static class BreakTagWidth {
        final String tagKey;
        final String tagValue; // null means wildcard (any value)
        final double widthMeters;

        BreakTagWidth(String tagKey, String tagValue, double widthMeters) {
            this.tagKey = tagKey;
            this.tagValue = tagValue;
            this.widthMeters = widthMeters;
        }

        /** Returns true if the given way matches this tag filter. */
        boolean matches(org.openstreetmap.josm.data.osm.Way w) {
            if (tagValue == null || "*".equals(tagValue)) {
                return w.hasKey(tagKey);
            }
            return tagValue.equals(w.get(tagKey));
        }

        /** Returns the filter as a display string: "key=value" or "key". */
        String filterString() {
            return tagValue != null ? tagKey + "=" + tagValue : tagKey;
        }
    }

    /** Default width in meters when no width is specified for a tag filter. */
    static final double DEFAULT_TAG_WIDTH = 3.5;

    /**
     * Parses the break-tag widths preference.
     * <p>Supported formats per semicolon-delimited entry:
     * <ul>
     *   <li>{@code key=value=width} — match specific tag value with explicit width</li>
     *   <li>{@code key=width} — match any value of key (wildcard) with explicit width</li>
     *   <li>{@code key=value} — match specific value, default width (3.5m)</li>
     *   <li>{@code key} — match any value of key, default width (3.5m)</li>
     *   <li>{@code key=*=width} — explicit wildcard with width (same as key=width)</li>
     *   <li>{@code key=*} — explicit wildcard, default width</li>
     * </ul>
     * The last "=" separates the width when the trailing segment is a valid number;
     * otherwise the entire entry is treated as a tag filter with the default width.
     */
    static java.util.List<BreakTagWidth> getBreakTagWidths() {
        String pref = Config.getPref().get(PREF_ROAD_WIDTHS, DEFAULT_ROAD_WIDTHS);
        java.util.List<BreakTagWidth> result = new java.util.ArrayList<>();
        for (String entry : pref.split(";")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;

            int lastEq = trimmed.lastIndexOf('=');
            if (lastEq <= 0) {
                // Bare key (e.g. "count") — wildcard match, default width
                result.add(new BreakTagWidth(trimmed, null, DEFAULT_TAG_WIDTH));
                continue;
            }

            String beforeLast = trimmed.substring(0, lastEq).trim();
            String afterLast = trimmed.substring(lastEq + 1).trim();

            // Try to parse the part after the last '=' as a width
            double width = -1;
            try {
                width = Double.parseDouble(afterLast);
            } catch (NumberFormatException e) {
                // Not a number — treat entire string as tag filter with default width
            }

            if (width > 0) {
                // afterLast is a valid width — beforeLast is the tag filter
                int eqIdx = beforeLast.indexOf('=');
                if (eqIdx > 0) {
                    String key = beforeLast.substring(0, eqIdx).trim();
                    String value = beforeLast.substring(eqIdx + 1).trim();
                    result.add(new BreakTagWidth(key, value, width));
                } else {
                    result.add(new BreakTagWidth(beforeLast, null, width));
                }
            } else {
                // afterLast is not a number — treat as key=value with default width
                // (e.g. "waterway=river" or "waterway=*")
                result.add(new BreakTagWidth(beforeLast, afterLast, DEFAULT_TAG_WIDTH));
            }
        }
        return result;
    }


    private static Set<String> parseTagSet(String pref) {
        Set<String> tags = new LinkedHashSet<>();
        for (String tag : pref.split(";")) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                tags.add(trimmed);
            }
        }
        return tags;
    }

    @Override
    public void addGui(PreferenceTabbedPane gui) {
        migratePreferences();

        JPanel outerPanel = new JPanel(new GridBagLayout());
        GridBagConstraints outerGbc = new GridBagConstraints();
        outerGbc.gridx = 0;
        outerGbc.fill = GridBagConstraints.HORIZONTAL;
        outerGbc.weightx = 1.0;
        outerGbc.insets = new Insets(2, 0, 2, 0);

        // === Section 1: Relation Identity Protection ===
        JPanel identityPanel = new JPanel(new GridBagLayout());
        identityPanel.setBorder(BorderFactory.createTitledBorder(tr("Relation Identity Protection")));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 5, 3, 5);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;

        // Info label with tooltip
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 4;
        JLabel identityInfo = new JLabel(
            tr("Identity tag keys (use * suffix for prefix match, e.g. name:*)"));
        identityInfo.setToolTipText(
            tr("Relations with these tags represent a unified feature. "
               + "The plugin will consolidate ways but will not dissolve or split the relation."));
        identityPanel.add(identityInfo, gbc);
        gbc.gridwidth = 1;

        // Identity tags table (single column, editable)
        identityTagsTableModel = new DefaultTableModel(
                new String[]{tr("Tag key")}, 0);

        String currentIdentityTags = Config.getPref().get(PREF_IDENTITY_TAGS,
            MultipolygonAnalyzer.DEFAULT_IDENTITY_TAGS);
        for (String key : parseTagSet(currentIdentityTags)) {
            identityTagsTableModel.addRow(new Object[]{key});
        }

        JTable identityTable = new JTable(identityTagsTableModel);
        identityTable.setRowHeight(22);
        JScrollPane identityScrollPane = new JScrollPane(identityTable);
        identityScrollPane.setPreferredSize(new Dimension(400, 132));

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 4;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        identityPanel.add(identityScrollPane, gbc);
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        gbc.gridwidth = 1;

        // Add/Remove buttons
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 4;
        JPanel identityButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JButton identityAddButton = new JButton(tr("Add"));
        identityAddButton.addActionListener(e -> {
            identityTagsTableModel.addRow(new Object[]{""});
            int newRow = identityTagsTableModel.getRowCount() - 1;
            identityTable.editCellAt(newRow, 0);
            identityTable.getSelectionModel().setSelectionInterval(newRow, newRow);
        });
        JButton identityRemoveButton = new JButton(tr("Remove"));
        identityRemoveButton.addActionListener(e -> {
            int[] selected = identityTable.getSelectedRows();
            for (int i = selected.length - 1; i >= 0; i--) {
                identityTagsTableModel.removeRow(selected[i]);
            }
        });
        identityButtonPanel.add(identityAddButton);
        identityButtonPanel.add(identityRemoveButton);
        identityPanel.add(identityButtonPanel, gbc);
        gbc.gridwidth = 1;

        outerGbc.gridy = 0;
        outerPanel.add(identityPanel, outerGbc);

        // === Section 2: Unused Way Cleanup ===
        JPanel cleanupPanel = new JPanel(new GridBagLayout());
        cleanupPanel.setBorder(BorderFactory.createTitledBorder(tr("Unused Way Cleanup")));
        gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 5, 3, 5);
        gbc.anchor = GridBagConstraints.WEST;
        row = 0;

        useDiscardableKeysCheckBox = new JCheckBox(
            tr("Treat JOSM discardable keys as insignificant"));
        useDiscardableKeysCheckBox.setToolTipText(
            tr("JOSM maintains a list of auto-generated/unimportant keys (tiger:*, created_by, odbl). "
               + "Ways with only these keys are treated as untagged for cleanup."));
        useDiscardableKeysCheckBox.setSelected(
            Config.getPref().getBoolean(PREF_USE_DISCARDABLE_KEYS, true));
        addCheckBox(cleanupPanel, gbc, row++, useDiscardableKeysCheckBox);

        // Insignificant tags table label
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 4;
        cleanupPanel.add(new JLabel(tr("Additional insignificant tag keys:")), gbc);
        gbc.gridwidth = 1;

        // Table
        insignificantTagsTableModel = new DefaultTableModel(
                new String[]{tr("Tag key"), tr("Multipolygons"), tr("Boundaries")}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 ? String.class : Boolean.class;
            }
        };

        String mpPref = Config.getPref().get(PREF_INSIGNIFICANT_TAGS_MP, DEFAULT_INSIGNIFICANT_TAGS);
        String boundPref = Config.getPref().get(PREF_INSIGNIFICANT_TAGS_BOUNDARY, DEFAULT_INSIGNIFICANT_TAGS);
        Set<String> mpSet = parseTagSet(mpPref);
        Set<String> boundSet = parseTagSet(boundPref);
        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(mpSet);
        allKeys.addAll(boundSet);
        for (String key : allKeys) {
            insignificantTagsTableModel.addRow(new Object[]{key, mpSet.contains(key), boundSet.contains(key)});
        }

        JTable table = new JTable(insignificantTagsTableModel);
        table.getColumnModel().getColumn(0).setPreferredWidth(200);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
        table.setRowHeight(22);
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(400, 132));

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 4;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        cleanupPanel.add(scrollPane, gbc);
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        gbc.gridwidth = 1;

        // Add/Remove buttons
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 4;
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JButton addButton = new JButton(tr("Add"));
        addButton.addActionListener(e -> {
            insignificantTagsTableModel.addRow(new Object[]{"", true, true});
            int newRow = insignificantTagsTableModel.getRowCount() - 1;
            table.editCellAt(newRow, 0);
            table.getSelectionModel().setSelectionInterval(newRow, newRow);
        });
        JButton removeButton = new JButton(tr("Remove"));
        removeButton.addActionListener(e -> {
            int[] selected = table.getSelectedRows();
            for (int i = selected.length - 1; i >= 0; i--) {
                insignificantTagsTableModel.removeRow(selected[i]);
            }
        });
        buttonPanel.add(addButton);
        buttonPanel.add(removeButton);
        cleanupPanel.add(buttonPanel, gbc);
        gbc.gridwidth = 1;

        outerGbc.gridy = 1;
        outerPanel.add(cleanupPanel, outerGbc);

        // === Section 3: Data Download ===
        JPanel downloadPanel = new JPanel(new GridBagLayout());
        downloadPanel.setBorder(BorderFactory.createTitledBorder(tr("Data Download")));
        gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 5, 3, 5);
        gbc.anchor = GridBagConstraints.WEST;
        row = 0;

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        JLabel downloadLabel = new JLabel(tr("Before fixing:"));
        downloadLabel.setToolTipText(
            tr("Controls whether the plugin downloads referrers for cleanup-candidate ways before simplifying. "
               + "Downloading ensures ways shared with other relations are not accidentally deleted."));
        downloadPanel.add(downloadLabel, gbc);

        gbc.gridx = 1;
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.NONE;
        String[] downloadOptions = {tr("Always prompt"), tr("Always download"), tr("Never download")};
        downloadBeforeFixCombo = new JComboBox<>(downloadOptions);
        String currentDownloadPref = Config.getPref().get(PREF_DOWNLOAD_BEFORE_FIX, "prompt");
        downloadBeforeFixCombo.setSelectedIndex(
            "always".equals(currentDownloadPref) ? 1 : "never".equals(currentDownloadPref) ? 2 : 0);
        downloadPanel.add(downloadBeforeFixCombo, gbc);
        gbc.gridwidth = 1;
        gbc.weightx = 0;

        outerGbc.gridy = 2;
        outerPanel.add(downloadPanel, outerGbc);

        // === Section 4: Centerline Offset Widths ===
        JPanel breakPanel = new JPanel(new GridBagLayout());
        breakPanel.setBorder(BorderFactory.createTitledBorder(tr("Centerline Offset Widths")));
        gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 5, 3, 5);
        gbc.anchor = GridBagConstraints.WEST;
        row = 0;

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 4;
        JLabel roadWidthsInfo = new JLabel(
            tr("Tag filters and widths (meters) for centerline features"));
        roadWidthsInfo.setToolTipText(
            tr("<html>Used by both <b>Break Polygon</b> and <b>Unglue</b>.<br>"
               + "Ways matching these tag filters are treated as centerline features.<br>"
               + "Use <b>key=value</b> (e.g. highway=motorway) to match a specific tag,<br>"
               + "<b>key=*</b> or <b>key</b> alone (e.g. waterway) to match any value of that key.<br>"
               + "Width is optional \u2014 leave blank to use the default (3.5m).<br>"
               + "The polygon boundary is offset by half the configured width.</html>"));
        breakPanel.add(roadWidthsInfo, gbc);
        gbc.gridwidth = 1;

        roadWidthsTableModel = new DefaultTableModel(
                new String[]{tr("Tag filter"), tr("Width (m)")}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return String.class;
            }
        };

        String currentRoadWidths = Config.getPref().get(PREF_ROAD_WIDTHS, DEFAULT_ROAD_WIDTHS);
        for (String entry : currentRoadWidths.split(";")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            int lastEq = trimmed.lastIndexOf('=');
            if (lastEq <= 0) {
                // Bare key (e.g. "count") — no width
                roadWidthsTableModel.addRow(new Object[]{trimmed, ""});
            } else {
                String afterLast = trimmed.substring(lastEq + 1).trim();
                try {
                    Double.parseDouble(afterLast);
                    // Valid number — it's the width
                    roadWidthsTableModel.addRow(new Object[]{
                        trimmed.substring(0, lastEq).trim(), afterLast});
                } catch (NumberFormatException e) {
                    // Not a number — entire string is the filter (e.g. "waterway=*")
                    roadWidthsTableModel.addRow(new Object[]{trimmed, ""});
                }
            }
        }

        JTable roadWidthsTable = new JTable(roadWidthsTableModel);
        roadWidthsTable.getColumnModel().getColumn(0).setPreferredWidth(200);
        roadWidthsTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        roadWidthsTable.setRowHeight(22);
        JScrollPane roadWidthsScrollPane = new JScrollPane(roadWidthsTable);
        roadWidthsScrollPane.setPreferredSize(new Dimension(400, 132));

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 4;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        breakPanel.add(roadWidthsScrollPane, gbc);
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        gbc.gridwidth = 1;

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 4;
        JPanel roadWidthsButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JButton roadWidthsAddButton = new JButton(tr("Add"));
        roadWidthsAddButton.addActionListener(e -> {
            roadWidthsTableModel.addRow(new Object[]{"", ""});
            int newRow = roadWidthsTableModel.getRowCount() - 1;
            roadWidthsTable.editCellAt(newRow, 0);
            roadWidthsTable.getSelectionModel().setSelectionInterval(newRow, newRow);
        });
        JButton roadWidthsRemoveButton = new JButton(tr("Remove"));
        roadWidthsRemoveButton.addActionListener(e -> {
            int[] selected = roadWidthsTable.getSelectedRows();
            for (int i = selected.length - 1; i >= 0; i--) {
                roadWidthsTableModel.removeRow(selected[i]);
            }
        });
        roadWidthsButtonPanel.add(roadWidthsAddButton);
        roadWidthsButtonPanel.add(roadWidthsRemoveButton);
        breakPanel.add(roadWidthsButtonPanel, gbc);
        gbc.gridwidth = 1;

        outerGbc.gridy = 3;
        outerPanel.add(breakPanel, outerGbc);

        // === Section 5: Unglue ===
        JPanel ungluePanel = new JPanel(new GridBagLayout());
        ungluePanel.setBorder(BorderFactory.createTitledBorder(tr("Unglue")));
        gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 5, 3, 5);
        gbc.anchor = GridBagConstraints.WEST;
        row = 0;

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 4;
        JLabel centerlineInfo = new JLabel(
            tr("Centerline tag keys (ways with these tags are treated as centerlines to unglue from)"));
        centerlineInfo.setToolTipText(
            tr("Area boundaries sharing nodes with ways that have these tag keys "
               + "will be offset away from the centerline by half the feature width."));
        ungluePanel.add(centerlineInfo, gbc);
        gbc.gridwidth = 1;

        centerlineTagsTableModel = new DefaultTableModel(
                new String[]{tr("Tag key")}, 0);

        String currentCenterlineTags = Config.getPref().get(PREF_CENTERLINE_TAGS,
            DEFAULT_CENTERLINE_TAGS);
        for (String key : parseTagSet(currentCenterlineTags)) {
            centerlineTagsTableModel.addRow(new Object[]{key});
        }

        JTable centerlineTable = new JTable(centerlineTagsTableModel);
        centerlineTable.setRowHeight(22);
        JScrollPane centerlineScrollPane = new JScrollPane(centerlineTable);
        centerlineScrollPane.setPreferredSize(new Dimension(400, 132));

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 4;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        ungluePanel.add(centerlineScrollPane, gbc);
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        gbc.gridwidth = 1;

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 4;
        JPanel centerlineButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JButton centerlineAddButton = new JButton(tr("Add"));
        centerlineAddButton.addActionListener(e -> {
            centerlineTagsTableModel.addRow(new Object[]{""});
            int newRow = centerlineTagsTableModel.getRowCount() - 1;
            centerlineTable.editCellAt(newRow, 0);
            centerlineTable.getSelectionModel().setSelectionInterval(newRow, newRow);
        });
        JButton centerlineRemoveButton = new JButton(tr("Remove"));
        centerlineRemoveButton.addActionListener(e -> {
            int[] selected = centerlineTable.getSelectedRows();
            for (int i = selected.length - 1; i >= 0; i--) {
                centerlineTagsTableModel.removeRow(selected[i]);
            }
        });
        centerlineButtonPanel.add(centerlineAddButton);
        centerlineButtonPanel.add(centerlineRemoveButton);
        ungluePanel.add(centerlineButtonPanel, gbc);
        gbc.gridwidth = 1;

        // Area tag keys sub-section
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 4;
        JLabel areaTagsInfo = new JLabel(
            tr("Area tag keys (primitives with these tags are included in \"Unglue All\")"));
        areaTagsInfo.setToolTipText(
            tr("Only closed ways and relations tagged with at least one of these keys "
               + "will be processed by \"Unglue All\". Single-selection unglue is not affected."));
        ungluePanel.add(areaTagsInfo, gbc);
        gbc.gridwidth = 1;

        areaTagsTableModel = new DefaultTableModel(
                new String[]{tr("Tag key")}, 0);

        String currentAreaTags = Config.getPref().get(PREF_AREA_TAGS, DEFAULT_AREA_TAGS);
        for (String key : parseTagSet(currentAreaTags)) {
            areaTagsTableModel.addRow(new Object[]{key});
        }

        JTable areaTagsTable = new JTable(areaTagsTableModel);
        areaTagsTable.setRowHeight(22);
        JScrollPane areaTagsScrollPane = new JScrollPane(areaTagsTable);
        areaTagsScrollPane.setPreferredSize(new Dimension(400, 132));

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 4;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        ungluePanel.add(areaTagsScrollPane, gbc);
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        gbc.gridwidth = 1;

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 4;
        JPanel areaTagsButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JButton areaTagsAddButton = new JButton(tr("Add"));
        areaTagsAddButton.addActionListener(e -> {
            areaTagsTableModel.addRow(new Object[]{""});
            int newRow = areaTagsTableModel.getRowCount() - 1;
            areaTagsTable.editCellAt(newRow, 0);
            areaTagsTable.getSelectionModel().setSelectionInterval(newRow, newRow);
        });
        JButton areaTagsRemoveButton = new JButton(tr("Remove"));
        areaTagsRemoveButton.addActionListener(e -> {
            int[] selected = areaTagsTable.getSelectedRows();
            for (int i = selected.length - 1; i >= 0; i--) {
                areaTagsTableModel.removeRow(selected[i]);
            }
        });
        areaTagsButtonPanel.add(areaTagsAddButton);
        areaTagsButtonPanel.add(areaTagsRemoveButton);
        ungluePanel.add(areaTagsButtonPanel, gbc);
        gbc.gridwidth = 1;

        outerGbc.gridy = 4;
        outerPanel.add(ungluePanel, outerGbc);

        // === Section 6: Developer ===
        JPanel devPanel = new JPanel(new GridBagLayout());
        devPanel.setBorder(BorderFactory.createTitledBorder(tr("Developer")));
        gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 5, 3, 5);
        gbc.anchor = GridBagConstraints.WEST;
        row = 0;

        planOnlyCheckBox = new JCheckBox(tr("Plan only"));
        planOnlyCheckBox.setToolTipText(
            tr("Log plans to console without executing fixes. Useful for inspecting what the plugin would do."));
        planOnlyCheckBox.setSelected(
            Config.getPref().getBoolean(PREF_PLAN_ONLY, false));
        addCheckBox(devPanel, gbc, row++, planOnlyCheckBox);

        debugModeCheckBox = new JCheckBox(tr("Debug mode"));
        debugModeCheckBox.setToolTipText(
            tr("Enable determinism checks and verbose logging."));
        debugModeCheckBox.setSelected(
            Config.getPref().getBoolean(PREF_DEBUG_MODE, false));
        addCheckBox(devPanel, gbc, row++, debugModeCheckBox);

        outerGbc.gridy = 5;
        outerPanel.add(devPanel, outerGbc);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(outerPanel, BorderLayout.NORTH);
        JScrollPane outerScrollPane = new JScrollPane(wrapper);
        outerScrollPane.setBorder(BorderFactory.createEmptyBorder());
        outerScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        GridBagConstraints tabConstraints = new GridBagConstraints();
        tabConstraints.fill = GridBagConstraints.BOTH;
        tabConstraints.weightx = 1.0;
        tabConstraints.weighty = 1.0;
        gui.createPreferenceTab(this).add(outerScrollPane, tabConstraints);
    }

    private static void addCheckBox(JPanel panel, GridBagConstraints gbc, int row, JCheckBox checkBox) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 4;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        panel.add(checkBox, gbc);
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        gbc.gridwidth = 1;
    }

    @Override
    public boolean ok() {
        // Serialize the identity tags table into a semicolon-delimited preference string
        StringBuilder identityTags = new StringBuilder();
        for (int i = 0; i < identityTagsTableModel.getRowCount(); i++) {
            String key = ((String) identityTagsTableModel.getValueAt(i, 0)).trim();
            if (key.isEmpty()) continue;
            if (identityTags.length() > 0) identityTags.append(';');
            identityTags.append(key);
        }
        Config.getPref().put(PREF_IDENTITY_TAGS, identityTags.toString());
        Config.getPref().putBoolean(PREF_USE_DISCARDABLE_KEYS, useDiscardableKeysCheckBox.isSelected());

        // Serialize the insignificant tags table into two preference strings
        StringBuilder mpTags = new StringBuilder();
        StringBuilder boundTags = new StringBuilder();
        for (int i = 0; i < insignificantTagsTableModel.getRowCount(); i++) {
            String key = ((String) insignificantTagsTableModel.getValueAt(i, 0)).trim();
            if (key.isEmpty()) continue;
            boolean forMp = (Boolean) insignificantTagsTableModel.getValueAt(i, 1);
            boolean forBound = (Boolean) insignificantTagsTableModel.getValueAt(i, 2);
            if (forMp) {
                if (mpTags.length() > 0) mpTags.append(';');
                mpTags.append(key);
            }
            if (forBound) {
                if (boundTags.length() > 0) boundTags.append(';');
                boundTags.append(key);
            }
        }
        Config.getPref().put(PREF_INSIGNIFICANT_TAGS_MP, mpTags.toString());
        Config.getPref().put(PREF_INSIGNIFICANT_TAGS_BOUNDARY, boundTags.toString());

        String[] downloadValues = {"prompt", "always", "never"};
        Config.getPref().put(PREF_DOWNLOAD_BEFORE_FIX, downloadValues[downloadBeforeFixCombo.getSelectedIndex()]);

        // Serialize centerline tags table
        StringBuilder centerlineTags = new StringBuilder();
        for (int i = 0; i < centerlineTagsTableModel.getRowCount(); i++) {
            String key = ((String) centerlineTagsTableModel.getValueAt(i, 0)).trim();
            if (key.isEmpty()) continue;
            if (centerlineTags.length() > 0) centerlineTags.append(';');
            centerlineTags.append(key);
        }
        Config.getPref().put(PREF_CENTERLINE_TAGS, centerlineTags.toString());

        // Serialize area tags table
        StringBuilder areaTags = new StringBuilder();
        for (int i = 0; i < areaTagsTableModel.getRowCount(); i++) {
            String key = ((String) areaTagsTableModel.getValueAt(i, 0)).trim();
            if (key.isEmpty()) continue;
            if (areaTags.length() > 0) areaTags.append(';');
            areaTags.append(key);
        }
        Config.getPref().put(PREF_AREA_TAGS, areaTags.toString());

        // Serialize break-tag widths table
        StringBuilder roadWidths = new StringBuilder();
        for (int i = 0; i < roadWidthsTableModel.getRowCount(); i++) {
            String filter = ((String) roadWidthsTableModel.getValueAt(i, 0)).trim();
            String width = ((String) roadWidthsTableModel.getValueAt(i, 1)).trim();
            if (filter.isEmpty()) continue;
            if (roadWidths.length() > 0) roadWidths.append(';');
            if (width.isEmpty()) {
                // Key-only or key=value without explicit width
                roadWidths.append(filter);
            } else {
                roadWidths.append(filter).append('=').append(width);
            }
        }
        Config.getPref().put(PREF_ROAD_WIDTHS, roadWidths.toString());

        Config.getPref().putBoolean(PREF_PLAN_ONLY, planOnlyCheckBox.isSelected());
        Config.getPref().putBoolean(PREF_DEBUG_MODE, debugModeCheckBox.isSelected());
        return false;
    }
}
