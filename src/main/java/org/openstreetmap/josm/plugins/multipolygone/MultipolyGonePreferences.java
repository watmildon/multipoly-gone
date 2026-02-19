package org.openstreetmap.josm.plugins.multipolygone;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.openstreetmap.josm.gui.preferences.DefaultTabPreferenceSetting;
import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane;
import org.openstreetmap.josm.spi.preferences.Config;

public class MultipolyGonePreferences extends DefaultTabPreferenceSetting {

    public static final String PREF_INSIGNIFICANT_TAGS = "multipolygone.insignificantTags";
    public static final String DEFAULT_INSIGNIFICANT_TAGS = "source;created_by";

    public static final String PREF_USE_DISCARDABLE_KEYS = "multipolygone.useDiscardableKeys";

    private JTextField insignificantTagsField;
    private JCheckBox useDiscardableKeysCheckBox;

    public MultipolyGonePreferences() {
        super("preferences/multipoly-gone", tr("Multipoly-Gone"),
            tr("Settings for multipolygon dissolution"));
    }

    @Override
    public void addGui(PreferenceTabbedPane gui) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;

        // === Cleanup Section ===
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        JLabel cleanupLabel = new JLabel(tr("Unused Way Cleanup:"));
        cleanupLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
        panel.add(cleanupLabel, gbc);

        // Use JOSM discardable keys
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        useDiscardableKeysCheckBox = new JCheckBox(
            tr("Also treat JOSM discardable keys as insignificant (tiger:*, created_by, odbl, etc.)"));
        useDiscardableKeysCheckBox.setSelected(
            Config.getPref().getBoolean(PREF_USE_DISCARDABLE_KEYS, true));
        panel.add(useDiscardableKeysCheckBox, gbc);

        // Additional insignificant tags
        gbc.gridy = row++;
        gbc.gridwidth = 1;
        gbc.gridx = 0;
        panel.add(new JLabel(tr("Additional insignificant tag keys:")), gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        String currentTags = Config.getPref().get(PREF_INSIGNIFICANT_TAGS, DEFAULT_INSIGNIFICANT_TAGS);
        insignificantTagsField = new JTextField(currentTags, 30);
        panel.add(insignificantTagsField, gbc);
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        panel.add(new JLabel(tr("(semicolon-separated, e.g. source;created_by;note)")), gbc);

        // Explanatory text
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(20, 5, 5, 5);
        JLabel explanation = new JLabel("<html><body style='width: 450px'>" +
            tr("<b>Unused way cleanup:</b> After dissolving a multipolygon relation where the outer ways " +
               "are chained together, the original member ways may become orphaned. Ways that belong to " +
               "no other relations and have no significant tags will be deleted.") +
            "<br><br>" +
            tr("<b>JOSM discardable keys:</b> JOSM maintains a list of keys that are considered " +
               "auto-generated or unimportant (e.g. tiger:*, created_by, odbl). When this option is enabled, " +
               "ways that only have these keys are treated as untagged for cleanup purposes.") +
            "<br><br>" +
            tr("<b>Additional insignificant keys:</b> Add your own tag keys that should be ignored " +
               "when determining if a way is ''unused''. Common examples: source, note, fixme.") +
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
        Config.getPref().putBoolean(PREF_USE_DISCARDABLE_KEYS, useDiscardableKeysCheckBox.isSelected());
        Config.getPref().put(PREF_INSIGNIFICANT_TAGS, insignificantTagsField.getText().trim());
        return false;
    }
}
