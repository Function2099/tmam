package com.tmam.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class TomcatServiceConfigTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void roundTripDoesNotSerializeComputedTypeFlags() throws Exception {
		TomcatServiceConfig service = new TomcatServiceConfig();
		service.setName("Portal_Area");
		service.setType(TomcatServiceType.LEGACY_IP);
		service.setAddress("192.168.10.10");
		service.setPort(36);

		String json = mapper.writeValueAsString(service);

		assertFalse(json.contains("legacyIp"));
		assertFalse(json.contains("pathProxy"));
	}

	@Test
	void ignoresLegacyComputedFieldsOnRead() throws Exception {
		String json = """
				{
				  "name": "Portal_Area",
				  "type": "LEGACY_IP",
				  "address": "192.168.10.10",
				  "port": 36,
				  "legacyIp": true,
				  "pathProxy": false
				}
				""";

		TomcatServiceConfig service = mapper.readValue(json, TomcatServiceConfig.class);

		assertEquals("Portal_Area", service.getName());
		assertTrue(service.isLegacyIp());
		assertFalse(service.isPathProxy());
	}

}
