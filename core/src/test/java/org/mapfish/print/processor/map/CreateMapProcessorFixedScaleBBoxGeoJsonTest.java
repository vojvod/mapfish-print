/*
 * Copyright (C) 2014  Camptocamp
 *
 * This file is part of MapFish Print
 *
 * MapFish Print is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MapFish Print is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with MapFish Print.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mapfish.print.processor.map;

import org.junit.Test;
import org.mapfish.print.AbstractMapfishSpringTest;
import org.mapfish.print.config.Configuration;
import org.mapfish.print.config.ConfigurationFactory;
import org.mapfish.print.config.Template;
import org.mapfish.print.output.Values;
import org.mapfish.print.parser.MapfishParser;
import org.mapfish.print.test.util.ImageSimilarity;
import org.mapfish.print.wrapper.json.PJsonObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.ClientHttpRequestFactory;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Basic test of the Map processor.
 * <p/>
 * Created by Jesse on 3/26/14.
 */
public class CreateMapProcessorFixedScaleBBoxGeoJsonTest extends AbstractMapfishSpringTest {
    private static final String BASE_DIR ="bbox_geojson_fixedscale/";

    @Autowired
    private ConfigurationFactory configurationFactory;
    @Autowired
    private MapfishParser parser;
    @Autowired
    private ClientHttpRequestFactory httpRequestFactory;

    @Test
    public void testExecute() throws Exception {
        final Configuration config = configurationFactory.getConfig(getFile(BASE_DIR + "config.yaml"));
        final Template template = config.getTemplate("main");
        PJsonObject requestData = loadJsonRequestData();
        Values values = new Values(requestData, template, this.parser, getTaskDirectory(), this.httpRequestFactory);
        template.getProcessorGraph().createTask(values).invoke();

        @SuppressWarnings("unchecked")
        List<URI> layerGraphics = (List<URI>) values.getObject("layerGraphics", List.class);
        assertEquals(1, layerGraphics.size());

//        Files.copy(new File(layerGraphics.get(0)), new File("/tmp/" + getClass().getSimpleName() + ".svg"));
        new ImageSimilarity(ImageSimilarity.convertFromSvg(layerGraphics.get(0), 500, 100), 2)
                .assertSimilarity(getFile(BASE_DIR + "expectedSimpleImage.tiff"), 0);
    }

    public static PJsonObject loadJsonRequestData() throws IOException {
        return parseJSONObjectFromFile(CreateMapProcessorFixedScaleBBoxGeoJsonTest.class, BASE_DIR + "requestData.json");
    }
}