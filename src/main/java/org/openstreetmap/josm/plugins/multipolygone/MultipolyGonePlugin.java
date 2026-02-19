package org.openstreetmap.josm.plugins.multipolygone;

import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.preferences.PreferenceSetting;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;

public class MultipolyGonePlugin extends Plugin {

    public MultipolyGonePlugin(PluginInformation info) {
        super(info);
    }

    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        if (newFrame != null) {
            newFrame.addToggleDialog(new MultipolyGoneDialog());
        }
    }

    @Override
    public PreferenceSetting getPreferenceSetting() {
        return new MultipolyGonePreferences();
    }
}
