package com.tmam.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.tmam.model.ProjectConfig;

@Service
public class XmlConfiguratorService {

	private final Resource templateResource;

	public XmlConfiguratorService(@Value("classpath:server-template.xml") Resource templateResource) {
		this.templateResource = templateResource;
	}

	public void generate(ProjectConfig project, Path outputPath) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder = factory.newDocumentBuilder();
		Document doc = builder.parse(templateResource.getInputStream());

		doc.getDocumentElement()
				.setAttribute("port", String.valueOf(project.getPorts().shutdown()));

		NodeList connectors = doc.getElementsByTagName("Connector");
		for (int i = 0; i < connectors.getLength(); i++) {
			Element connector = (Element) connectors.item(i);
			String protocol = connector.getAttribute("protocol");
			if ("HTTP/1.1".equals(protocol)) {
				connector.setAttribute("port", String.valueOf(project.getPorts().http()));
			}
			else if (protocol.startsWith("AJP")) {
				connector.setAttribute("port", String.valueOf(project.getPorts().ajp()));
			}
		}

		TransformerFactory transformerFactory = TransformerFactory.newInstance();
		Transformer transformer = transformerFactory.newTransformer();
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

		Files.createDirectories(outputPath.getParent());
		transformer.transform(new DOMSource(doc), new StreamResult(outputPath.toFile()));
	}

	public void copyFromHome(Path catalinaHome, Path catalinaBase) throws IOException {
		Path homeConf = catalinaHome.resolve("conf");
		copyIfAbsent(homeConf.resolve("web.xml"), catalinaBase.resolve("conf/web.xml"));
		copyIfAbsent(homeConf.resolve("context.xml"), catalinaBase.resolve("conf/context.xml"));
		copyIfAbsent(homeConf.resolve("tomcat-users.xml"), catalinaBase.resolve("conf/tomcat-users.xml"));
		copyIfAbsent(homeConf.resolve("catalina.properties"), catalinaBase.resolve("conf/catalina.properties"));
		copyIfAbsent(homeConf.resolve("logging.properties"), catalinaBase.resolve("conf/logging.properties"));
	}

	private void copyIfAbsent(Path source, Path target) throws IOException {
		if (Files.exists(source) && !Files.exists(target)) {
			Files.createDirectories(target.getParent());
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

}
