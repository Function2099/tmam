package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.tmam.model.TomcatServiceConfig;
import com.tmam.model.TomcatServiceType;

class LegacyIpFragmentBuilderTest {

	@Test
	void buildsValidServiceFragment() {
		TomcatServiceConfig service = new TomcatServiceConfig();
		service.setName("Test_Service");
		service.setType(TomcatServiceType.LEGACY_IP);
		service.setAddress("192.168.10.99");
		service.setPort(9090);
		service.setDocBase("D:\\webapp");

		String fragment = LegacyIpFragmentBuilder.build(service);

		assertTrue(fragment.contains("<Service name=\"Test_Service\">"));
		assertTrue(fragment.contains("address=\"192.168.10.99\""));
		assertTrue(fragment.contains("port=\"9090\""));
		assertTrue(fragment.contains("docBase=\"D:\\webapp\""));
	}

}
