package com.openpositioning.PositionMe.data.remote;

import com.openpositioning.PositionMe.data.remote.model.FloorplanVenue;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FloorplanServiceTest {

    @Test
    public void parseFloorplanResponse_validVenue_returnsVenueAndLevels() throws Exception {
        String json = "[" +
                "{\"name\":\"murchison_house\"," +
                "\"outline\":\"[[55.0,-3.0],[55.0,-3.1],[55.1,-3.1],[55.0,-3.0]]\"," +
                "\"map_shapes\":\"{\\\"gf\\\":[{\\\"type\\\":\\\"polygon\\\",\\\"points\\\":[[55.0,-3.0],[55.0,-3.01],[55.01,-3.01],[55.0,-3.0]]}]}\"}" +
                "]";

        List<FloorplanVenue> venues = FloorplanService.parseFloorplanResponse(json);

        assertEquals(1, venues.size());
        assertEquals("murchison_house", venues.get(0).getCampaign());
        assertFalse(venues.get(0).getLevels().isEmpty());
        assertFalse(venues.get(0).getLevels().get(0).getShapes().isEmpty());
    }

    @Test
    public void parseFloorplanResponse_malformedShape_keepsVenue() throws Exception {
        String json = "[{\"name\":\"nucleus_building\",\"outline\":\"[[55.0,-3.0],[55.1,-3.0],[55.0,-3.0]]\",\"map_shapes\":\"{\\\"gf\\\":[{\\\"bad\\\":1}]}\"}]";

        List<FloorplanVenue> venues = FloorplanService.parseFloorplanResponse(json);

        assertEquals(1, venues.size());
        assertEquals("nucleus_building", venues.get(0).getCampaign());
        assertEquals(1, venues.get(0).getLevels().size());
        assertTrue(venues.get(0).getLevels().get(0).getShapes().isEmpty());
    }

    @Test
    public void parseFloorplanResponse_empty_returnsEmptyList() throws Exception {
        List<FloorplanVenue> venues = FloorplanService.parseFloorplanResponse("[]");
        assertTrue(venues.isEmpty());
    }
}
