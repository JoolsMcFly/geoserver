/* (c) 2021 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.featurestemplating.readers;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.IOException;
import java.util.Date;
import org.geoserver.featurestemplating.builders.impl.RootBuilder;
import org.geoserver.platform.resource.FileSystemResourceStore;
import org.geoserver.platform.resource.Resource;
import org.junit.Before;
import org.junit.Test;
import org.xml.sax.helpers.NamespaceSupport;

public class JSONMergesTest {

    FileSystemResourceStore store;

    @Before
    public void setupStore() {
        store = new FileSystemResourceStore(new File("src/test/resources/jsonMerge"));
    }

    @Test
    public void testRecursionLimited() {
        RuntimeException ex = checkThrowingTemplate("recurse.json");
        assertThat(
                ex.getMessage(),
                containsString(
                        "Went beyond maximum expansion depth (51), chain is: [recurse.json"));
    }

    @Test
    public void testDanglingInclude() {
        RuntimeException ex = checkThrowingTemplate("dangling.json");
        assertEquals("$merge resource notThere.json could not be found", ex.getMessage());
    }

    @Test
    public void testRecursionPingPong() {
        // ping and pong import each other in an infinite recursion
        RuntimeException ex = checkThrowingTemplate("ping.json");
        assertThat(
                ex.getMessage(),
                containsString("Went beyond maximum expansion depth (51), chain is: [ping.json"));
        assertThat(ex.getMessage(), containsString("pong.json"));
    }

    private RuntimeException checkThrowingTemplate(String s) {
        return assertThrows(
                RuntimeException.class, () -> new RecursiveJSONParser(store.get(s)).parse());
    }

    @Test
    public void testSimpleMerge() throws IOException {
        RecursiveJSONParser parser = new RecursiveJSONParser(store.get("simpleOverride.json"));
        ObjectNode parsed = (ObjectNode) parser.parse();

        // a has been replaced by an object
        assertEquals(JsonNodeType.OBJECT, parsed.get("a").getNodeType());
        ObjectNode a = (ObjectNode) parsed.get("a");
        assertEquals(1, a.get("a1").intValue());
        assertEquals(2, a.get("a2").intValue());

        // b has been removed
        assertFalse(parsed.has("b"));

        // c left has is
        assertEquals(JsonNodeType.STRING, parsed.get("c").getNodeType());
        assertEquals("theCValue", parsed.get("c").textValue());

        // array is no longer an array
        assertEquals(JsonNodeType.STRING, parsed.get("array").getNodeType());
        assertEquals("notAnArray", parsed.get("array").textValue());
    }

    /**
     * A real world example
     *
     * @throws IOException
     */
    @Test
    public void testEOExample() throws IOException {
        RecursiveJSONParser parser = new RecursiveJSONParser(store.get("eoOverride.json"));
        ObjectNode parsed = (ObjectNode) parser.parse();

        ArrayNode array = (ArrayNode) parsed.get("features");
        ObjectNode feature = (ObjectNode) array.get(0);

        // check some non overridden properties have been preserved
        assertEquals("Feature", feature.get("type").textValue());
        assertEquals("${eop:identifier}", feature.get("id").textValue());

        // check the overridden ones
        ObjectNode properties = (ObjectNode) feature.get("properties");
        assertEquals("Maja", properties.get("constellation").textValue());
        ArrayNode instruments = (ArrayNode) properties.get("instruments");
        assertEquals("myMajaInstrument1", instruments.get(0).textValue());
        assertEquals("myMajaInstrument2", instruments.get(1).textValue());
    }

    @Test
    public void testMergeModificationsAreDetected() throws IOException, InterruptedException {
        RecursiveJSONParser parser = new RecursiveJSONParser(store.get("simpleOverride.json"));
        TemplateReaderConfiguration configuration =
                new TemplateReaderConfiguration(new NamespaceSupport());
        JSONTemplateReader reader =
                new JSONTemplateReader(parser.parse(), configuration, parser.getWatchers());
        RootBuilder rootBuilder = reader.getRootBuilder();
        assertFalse(rootBuilder.needsReload());
        Resource mergeBase = store.get("simpleBase.json");
        File file = mergeBase.file();
        file.setLastModified(new Date().getTime());
        Thread.sleep(1000);

        assertTrue(rootBuilder.needsReload());
    }
}
