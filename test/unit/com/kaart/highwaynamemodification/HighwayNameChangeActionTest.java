package com.kaart.highwaynamemodification;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.Assert.*;

import javax.swing.JOptionPane;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.openstreetmap.josm.TestUtils;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.testutils.JOSMTestRules;

import com.github.tomakehurst.wiremock.WireMockServer;

public class HighwayNameChangeActionTest {
    @Rule
    public JOSMTestRules rule = new JOSMTestRules().projection().preferences();
    WireMockServer wireMock = new WireMockServer(options().usingFilesUnderDirectory("test/resources/wiremock"));

    @Before
    public void setUp() {
        wireMock.start();

        Config.getPref().put("download.overpass.server", wireMock.baseUrl());
        Config.getPref().putBoolean("message." + HighwayNameModification.NAME + ".downloadAdditional", false);
        Config.getPref().putInt("message." + HighwayNameModification.NAME + ".downloadAdditional" + ".value",
                JOptionPane.YES_OPTION);

    }

    @After
    public void tearDown() {
        wireMock.stop();
        Config.getPref().put("osm-server.url", Config.getUrls().getDefaultOsmApiUrl());

    }

    @Test
    public final void testActionPerformed() {
        HighwayNameListener tester = new HighwayNameListener();
        Way prim = TestUtils.newWay("highway=residential name=\"North 8th Street\"",
                new Node(new LatLon(39.084616, -108.559293)), new Node(new LatLon(39.0854611, -108.5592888)));
        DataSet newDataset = new DataSet();
        prim.getNodes().forEach(newDataset::addPrimitive);
        newDataset.addPrimitive(prim);
        newDataset.addDataSetListener(tester);
        wireMock.startRecording(Config.getUrls().getDefaultOsmApiUrl());
        wireMock.saveMappings();
        prim.put("name", "Road 2");
        wireMock.stopRecording();
        wireMock.saveMappings();
        assertTrue(newDataset.getWays().stream().filter(way -> way.hasTag("name"))
                .allMatch(way -> "Road 2".equals(way.get("name"))));
        wireMock.startRecording(Config.getUrls().getDefaultOsmApiUrl());
        wireMock.saveMappings();
        prim.put("highway", "residential");
        wireMock.stopRecording();
        wireMock.saveMappings();
        assertTrue(newDataset.getWays().stream().filter(way -> way.hasTag("name"))
                .allMatch(way -> "Road 2".equals(way.get("name"))));
    }

}
