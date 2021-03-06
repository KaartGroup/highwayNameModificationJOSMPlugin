package com.kaart.highwaynamemodification;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.openstreetmap.josm.actions.AutoScaleAction;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.IPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.WaySegment;
import org.openstreetmap.josm.gui.ConditionalOptionPaneUtil;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.Geometry;
import org.openstreetmap.josm.tools.Logging;

public class ModifyWays implements Runnable {

    HashMap<String, HashSet<OsmPrimitive>> downloadedWays = new HashMap<>();

    static final String ADDRSTREET = "addr:street";

    boolean downloadTask;

    Collection<? extends OsmPrimitive> wayChangingName;
    String originalName;
    boolean ignoreNewName;

    private static class InstanceHolder {
        static final ModifyWays INSTANCE = new ModifyWays();
    }

    private ModifyWays() {
        // Do nothing
    }

    public static ModifyWays getInstance() {
        return InstanceHolder.INSTANCE;
    }

    public synchronized void setDownloadTask(boolean b) {
        this.downloadTask = b;
    }

    public void setNameChangeInformation(OsmPrimitive osm, String originalName) {
        Collection<OsmPrimitive> tCollection = new HashSet<>();
        tCollection.add(osm);
        setNameChangeInformation(tCollection, originalName);
    }

    public void setNameChangeInformation(Collection<? extends OsmPrimitive> osmCollection, String originalName) {
        setNameChangeInformation(osmCollection, originalName, false);
    }

    /**
     * Initialize a new ModifyWays method
     *
     * @param osmCollection    The collection of ways that are changing names
     * @param originalName     The old name of the ways
     * @param ignoreNameChange If true, don't stop if the new name is the same as
     *                         the old name
     */
    public synchronized void setNameChangeInformation(Collection<? extends OsmPrimitive> osmCollection,
            String originalName, boolean ignoreNameChange) {
        wayChangingName = osmCollection;
        this.originalName = originalName;
        ignoreNewName = ignoreNameChange;
    }

    private static class DownloadAdditionalAsk implements Runnable {
        private boolean done;
        private boolean download;

        @Override
        public void run() {
            final String key = HighwayNameModification.NAME.concat(".downloadAdditional");
            final int answer = ConditionalOptionPaneUtil.showOptionDialog(key, MainApplication.getMainFrame(),
                    tr("{0}Should we download additional information for {1}? (WARNING: May be buggy!){2}",
                            "<html><h3>", HighwayNameModification.NAME, "</h3></html>"),
                    tr("Download additional information"), JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE, null, null);
            switch (answer) {
            case ConditionalOptionPaneUtil.DIALOG_DISABLED_OPTION:
            case JOptionPane.YES_OPTION:
                download = true;
                break;
            default:
            }
            done = true;
        }

        public boolean get() throws InterruptedException {
            while (!done) {
                Thread.sleep(100);
            }
            return download;
        }
    }

    @Override
    public void run() {
        try {
            synchronized (this) {
                if (downloadTask && !DownloadAdditionalWays.checkIfDownloaded(wayChangingName)) {
                    DownloadAdditionalAsk ask = new DownloadAdditionalAsk();
                    SwingUtilities.invokeAndWait(ask);
                    if (ask.get()) {
                        String[] oldNames = originalName != null ? new String[] { originalName } : null;
                        DownloadAdditionalWays.getAdditionalWays(wayChangingName, oldNames);
                    }
                }
                for (OsmPrimitive osm : wayChangingName) {
                    if (originalName != null) {
                        doRealRun(osm, originalName);
                    } else {
                        for (String key : osm.keySet()) {
                            if (key.contains("name") && !key.equals("name")) {
                                doRealRun(osm, osm.get(key));
                            }
                        }
                    }
                }
            }
        } catch (InterruptedException | InvocationTargetException e) {
            Thread.currentThread().interrupt();
            Logging.error(e);
        }
    }

    private static void doRealRun(final OsmPrimitive osm, final String name) {
        final String newName = osm.get("name");
        final Collection<OsmPrimitive> potentialAddrChange = osm.getDataSet()
                .getPrimitives(t -> name.equals(t.get(ADDRSTREET)));
        final Collection<OsmPrimitive> roads = osm.getDataSet().getPrimitives(
                t -> (t.hasKey("highway") && (name.equals(t.get("name")) || newName.equals(t.get("name")))));
        changeAddrTags(osm, name, potentialAddrChange, roads);
    }

    /**
     * Change the address tags of all buildings near the highway
     *
     * @param highway       The highway which changed names
     * @param oldAddrStreet The old name of the highway
     * @param primitives    The building primitives with addr:street tags
     * @param roads         The connecting roads to the highway with the highway's
     *                      old name
     */
    private static void changeAddrTags(OsmPrimitive highway, String oldAddrStreet, Collection<OsmPrimitive> primitives,
            Collection<OsmPrimitive> roads) {
        if (primitives == null || primitives.isEmpty()) {
            primitives = highway.getDataSet()
                    .getPrimitives(t -> t.hasKey(ADDRSTREET) && oldAddrStreet.equals(t.get(ADDRSTREET)));
        }
        if (roads == null || roads.isEmpty()) {
            roads = highway.getDataSet()
                    .getPrimitives(t -> t.hasKey("highway") && t.hasKey("name") && oldAddrStreet.equals(t.get("name")));
        }
        CreateGuiAskDialog dialog = new CreateGuiAskDialog(highway, primitives, roads);
        try {
            SwingUtilities.invokeAndWait(dialog);
        } catch (InvocationTargetException | InterruptedException e) {
            Thread.currentThread().interrupt();
            Logging.debug(e);
        }
    }

    protected static class CreateGuiAskDialog implements Runnable {
        OsmPrimitive highway;
        Collection<OsmPrimitive> primitives;
        Collection<OsmPrimitive> roads;

        public CreateGuiAskDialog(OsmPrimitive highway, Collection<OsmPrimitive> primitives,
                Collection<OsmPrimitive> roads) {
            this.highway = highway;
            this.primitives = primitives;
            this.roads = roads;
        }

        @Override
        public void run() {
            String newAddrStreet = highway.get("name");
            final String key = HighwayNameModification.NAME.concat(".changeAddrStreetTags");
            ConditionalOptionPaneUtil.startBulkOperation(key);
            boolean continueZooming = true;
            final ArrayList<OsmPrimitive> toChange = new ArrayList<>();
            final DataSet ds = primitives.iterator().next().getDataSet();
            final Collection<OsmPrimitive> initialSelection = ds.getSelected();
            for (final OsmPrimitive osm : primitives) {
                final OsmPrimitive closest = Geometry.getClosestPrimitive(osm, roads);
                if (!osm.hasKey(ADDRSTREET) || !highway.equals(closest) || osm.get(ADDRSTREET).equals(newAddrStreet)) {
                    continue; // TODO throw something
                }
                ds.setSelected(osm);
                ds.clearHighlightedWaySegments();
                final List<IPrimitive> zoomPrimitives = new ArrayList<>();
                if (closest instanceof Way) {
                    final WaySegment tWay = Geometry.getClosestWaySegment((Way) closest, osm);
                    final List<WaySegment> segments = new ArrayList<>();
                    segments.add(tWay);
                    ds.setHighlightedWaySegments(segments);
                    zoomPrimitives.add(tWay.getFirstNode());
                    zoomPrimitives.add(tWay.getSecondNode());
                }
                zoomPrimitives.add(osm);
                if (continueZooming)
                    AutoScaleAction.zoomTo(zoomPrimitives);
                final int answer = ConditionalOptionPaneUtil.showOptionDialog(key, MainApplication.getMainFrame(),
                        tr("{0}Should {1} be changed to {2}{3}", "<html><h3>", osm.get(ADDRSTREET), newAddrStreet,
                                "</h3></html>"),
                        tr("Highway name changed"), JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE,
                        null, null);
                switch (answer) {
                case ConditionalOptionPaneUtil.DIALOG_DISABLED_OPTION:
                case JOptionPane.YES_OPTION:
                    if (ConditionalOptionPaneUtil.isInBulkOperation(key)
                            && ConditionalOptionPaneUtil.getDialogReturnValue(key) >= 0) {
                        toChange.add(osm);
                        continueZooming = false;
                    } else {
                        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(osm, ADDRSTREET, newAddrStreet));
                    }
                    break;
                default:
                }
                ds.clearHighlightedWaySegments();
            }
            ConditionalOptionPaneUtil.endBulkOperation(key);
            ds.setSelected(initialSelection);
            if (toChange.isEmpty())
                return;
            AutoScaleAction.zoomTo(toChange);

            UndoRedoHandler.getInstance().add(new ChangePropertyCommand(toChange, ADDRSTREET, newAddrStreet));
        }
    }
}
