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
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
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

    public static final String PREF_PLAN_ONLY = "multipolygone.planOnly";

    public static final String PREF_DEBUG_MODE = "multipolygone.debugMode";
    public static final int DEFAULT_DEBUG_ITERATIONS = 10;

    private DefaultTableModel insignificantTagsTableModel;
    private JCheckBox useDiscardableKeysCheckBox;
    private JTextField identityTagsField;
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

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;

        // === Identity Tags Section ===
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        JLabel identityLabel = new JLabel(tr("Relation Identity Protection:"));
        identityLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
        panel.add(identityLabel, gbc);

        gbc.gridy = row++;
        gbc.gridwidth = 1;
        gbc.gridx = 0;
        panel.add(new JLabel(tr("Identity tag keys:")), gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        String currentIdentityTags = Config.getPref().get(PREF_IDENTITY_TAGS,
            MultipolygonAnalyzer.DEFAULT_IDENTITY_TAGS);
        identityTagsField = new JTextField(currentIdentityTags, 30);
        panel.add(identityTagsField, gbc);
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        panel.add(new JLabel(tr("(semicolon-separated; use * suffix for prefix match, e.g. name:*)")), gbc);

        // === Cleanup Section ===
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        gbc.insets = new Insets(15, 5, 5, 5);
        JLabel cleanupLabel = new JLabel(tr("Unused Way Cleanup:"));
        cleanupLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
        panel.add(cleanupLabel, gbc);
        gbc.insets = new Insets(5, 5, 5, 5);

        // Use JOSM discardable keys
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        useDiscardableKeysCheckBox = new JCheckBox(
            tr("Also treat JOSM discardable keys as insignificant (tiger:*, created_by, odbl, etc.)"));
        useDiscardableKeysCheckBox.setSelected(
            Config.getPref().getBoolean(PREF_USE_DISCARDABLE_KEYS, true));
        panel.add(useDiscardableKeysCheckBox, gbc);

        // Insignificant tags table
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        gbc.gridx = 0;
        panel.add(new JLabel(tr("Additional insignificant tag keys:")), gbc);

        gbc.gridy = row++;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        insignificantTagsTableModel = new DefaultTableModel(
                new String[]{tr("Tag key"), tr("Multipolygons"), tr("Boundaries")}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 ? String.class : Boolean.class;
            }
        };

        // Populate from preferences
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
        panel.add(scrollPane, gbc);

        // Add/Remove buttons
        gbc.gridy = row++;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
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
        panel.add(buttonPanel, gbc);

        // === Debug Section ===
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        gbc.insets = new Insets(15, 5, 5, 5);
        JLabel debugLabel = new JLabel(tr("Developer:"));
        debugLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
        panel.add(debugLabel, gbc);
        gbc.insets = new Insets(5, 5, 5, 5);

        gbc.gridy = row++;
        gbc.gridwidth = 2;
        planOnlyCheckBox = new JCheckBox(
            tr("Plan only (log plans to console, don''t execute fixes)"));
        planOnlyCheckBox.setSelected(
            Config.getPref().getBoolean(PREF_PLAN_ONLY, false));
        panel.add(planOnlyCheckBox, gbc);

        gbc.gridy = row++;
        gbc.gridwidth = 2;
        debugModeCheckBox = new JCheckBox(
            tr("Enable debug mode (determinism checks, verbose logging)"));
        debugModeCheckBox.setSelected(
            Config.getPref().getBoolean(PREF_DEBUG_MODE, false));
        panel.add(debugModeCheckBox, gbc);

        // Explanatory text
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(20, 5, 5, 5);
        JLabel explanation = new JLabel("<html><body style='width: 450px'>" +
            tr("<b>Identity tag protection:</b> Relations with identity tags (name, wikidata, ref, etc.) " +
               "represent a unified real-world feature. When such a relation has multiple disjoint outer " +
               "rings, the plugin will consolidate ways but will not dissolve or split the relation into " +
               "separate independent features. Use * suffix for prefix matching (e.g. name:* matches " +
               "name:en, name:fr, etc.).") +
            "<br><br>" +
            tr("<b>Unused way cleanup:</b> After dissolving a multipolygon relation where the outer ways " +
               "are chained together, the original member ways may become orphaned. Ways that belong to " +
               "no other relations and have no significant tags will be deleted.") +
            "<br><br>" +
            tr("<b>JOSM discardable keys:</b> JOSM maintains a list of keys that are considered " +
               "auto-generated or unimportant (e.g. tiger:*, created_by, odbl). When this option is enabled, " +
               "ways that only have these keys are treated as untagged for cleanup purposes.") +
            "<br><br>" +
            tr("<b>Additional insignificant keys:</b> Add tag keys that should be ignored when determining " +
               "if a way is ''unused''. Use the checkboxes to control whether each key applies to " +
               "multipolygon relations, boundary relations, or both.") +
            "</body></html>");
        panel.add(explanation, gbc);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(panel, BorderLayout.NORTH);
        GridBagConstraints tabConstraints = new GridBagConstraints();
        tabConstraints.fill = GridBagConstraints.BOTH;
        tabConstraints.weightx = 1.0;
        tabConstraints.weighty = 1.0;
        gui.createPreferenceTab(this).add(wrapper, tabConstraints);
    }

    @Override
    public boolean ok() {
        Config.getPref().put(PREF_IDENTITY_TAGS, identityTagsField.getText().trim());
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

        Config.getPref().putBoolean(PREF_PLAN_ONLY, planOnlyCheckBox.isSelected());
        Config.getPref().putBoolean(PREF_DEBUG_MODE, debugModeCheckBox.isSelected());
        return false;
    }
}
