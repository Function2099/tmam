package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.tmam.model.PortConfig;
import com.tmam.model.ProjectConfig;

class XmlConfiguratorServiceTest {

	@TempDir
	Path tempDir;

	private XmlConfiguratorService xmlConfiguratorService;

	@BeforeEach
	void setUp() {
		Resource template = new DefaultResourceLoader().getResource("classpath:server-template.xml");
		xmlConfiguratorService = new XmlConfiguratorService(template);
	}

	@Test
	void generateWritesPortsToServerXml() throws Exception {
		ProjectConfig project = new ProjectConfig();
		project.setPorts(new PortConfig(8081, 8015, 8019));

		Path output = tempDir.resolve("conf/server.xml");
		xmlConfiguratorService.generate(project, output);

		assertTrue(Files.exists(output));

		Document doc = DocumentBuilderFactory.newInstance()
				.newDocumentBuilder()
				.parse(output.toFile());

		assertEquals("8015", doc.getDocumentElement().getAttribute("port"));

		NodeList connectors = doc.getElementsByTagName("Connector");
		String httpPort = null;
		String ajpPort = null;
		for (int i = 0; i < connectors.getLength(); i++) {
			Element connector = (Element) connectors.item(i);
			String protocol = connector.getAttribute("protocol");
			if ("HTTP/1.1".equals(protocol)) {
				httpPort = connector.getAttribute("port");
			}
			else if (protocol.startsWith("AJP")) {
				ajpPort = connector.getAttribute("port");
			}
		}

		assertEquals("8081", httpPort);
		assertEquals("8019", ajpPort);
	}

	@Test
	void copyFromHomeCopiesWebXml() throws Exception {
		Path catalinaHome = tempDir.resolve("home");
		Path catalinaBase = tempDir.resolve("base");
		Files.createDirectories(catalinaHome.resolve("conf"));
		Files.writeString(catalinaHome.resolve("conf/web.xml"), "<web-app></web-app>");

		xmlConfiguratorService.copyFromHome(catalinaHome, catalinaBase);

		assertTrue(Files.exists(catalinaBase.resolve("conf/web.xml")));
		assertEquals("<web-app></web-app>", Files.readString(catalinaBase.resolve("conf/web.xml")));
	}

}
