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

    private DefaultTableModel identityTagsTableModel;
    private DefaultTableModel insignificantTagsTableModel;
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

        // === Section 4: Developer ===
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

        outerGbc.gridy = 3;
        outerPanel.add(devPanel, outerGbc);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(outerPanel, BorderLayout.NORTH);
        GridBagConstraints tabConstraints = new GridBagConstraints();
        tabConstraints.fill = GridBagConstraints.BOTH;
        tabConstraints.weightx = 1.0;
        tabConstraints.weighty = 1.0;
        gui.createPreferenceTab(this).add(wrapper, tabConstraints);
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

        Config.getPref().putBoolean(PREF_PLAN_ONLY, planOnlyCheckBox.isSelected());
        Config.getPref().putBoolean(PREF_DEBUG_MODE, debugModeCheckBox.isSelected());
        return false;
    }
}
