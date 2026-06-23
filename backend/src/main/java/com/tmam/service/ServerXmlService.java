package com.tmam.service;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.tmam.model.TomcatServiceConfig;
import com.tmam.model.TomcatServiceType;

@Service
public class ServerXmlService {

	private static final Logger log = LoggerFactory.getLogger(ServerXmlService.class);

	private static final String HEADER_FILE = "server-header.xml";

	private final String defaultCatalinaHome;
	private final String serverXmlBackupRelative;
	private final String fragmentsRoot;
	private final String pathGatewayServiceName;
	private final NativeTomcatEnvironmentService nativeTomcatEnvironmentService;

	public ServerXmlService(@Value("${tmam.default-catalina-home}") String defaultCatalinaHome,
			@Value("${tmam.server-xml-backup:conf/server.xml.tmam-original}") String serverXmlBackupRelative,
			@Value("${tmam.server-xml-fragments}") String fragmentsRoot,
			@Value("${tmam.path-gateway.service-name:PathGateway}") String pathGatewayServiceName,
			NativeTomcatEnvironmentService nativeTomcatEnvironmentService) {
		this.defaultCatalinaHome = defaultCatalinaHome;
		this.serverXmlBackupRelative = serverXmlBackupRelative;
		this.fragmentsRoot = fragmentsRoot;
		this.pathGatewayServiceName = pathGatewayServiceName;
		this.nativeTomcatEnvironmentService = nativeTomcatEnvironmentService;
	}

	public Path resolveCatalinaHome(String catalinaHome) {
		if (catalinaHome != null && !catalinaHome.isBlank()) {
			return Path.of(catalinaHome);
		}
		return Path.of(defaultCatalinaHome);
	}

	public Path serverXmlPath(String catalinaHome) {
		return resolveCatalinaHome(catalinaHome).resolve("conf/server.xml");
	}

	/** 可寫入的 server.xml（CATALINA_BASE），避免寫入 Program Files。 */
	public Path effectiveServerXmlPath(String catalinaHome) throws IOException {
		nativeTomcatEnvironmentService.ensureInitialized(resolveCatalinaHome(catalinaHome).toString());
		return nativeTomcatEnvironmentService.getNativeCatalinaBase().resolve("conf/server.xml");
	}

	public Path backupPath(String catalinaHome) {
		Path configured = Path.of(serverXmlBackupRelative);
		if (configured.isAbsolute()) {
			return configured;
		}
		return resolveCatalinaHome(catalinaHome).resolve(serverXmlBackupRelative);
	}

	public Path fragmentsDir() {
		return Path.of(fragmentsRoot);
	}

	public void backupOriginalIfNeeded(String catalinaHome) throws IOException {
		Path serverXml = serverXmlPath(catalinaHome);
		Path backup = backupPath(catalinaHome);
		if (!Files.exists(serverXml)) {
			throw new IOException("server.xml not found: " + serverXml);
		}
		if (!Files.exists(backup)) {
			Files.createDirectories(backup.getParent());
			Files.copy(serverXml, backup, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public List<TomcatServiceConfig> importFromServerXml(String catalinaHome) throws Exception {
		backupOriginalIfNeeded(catalinaHome);
		Path serverXml = serverXmlPath(catalinaHome);
		String content = Files.readString(serverXml);

		int firstService = indexOfServiceTag(content, 0);
		if (firstService < 0) {
			throw new IOException("No <Service> elements found in server.xml");
		}

		Files.createDirectories(fragmentsDir());
		String header = content.substring(0, firstService).stripTrailing();
		Files.writeString(fragmentsDir().resolve(HEADER_FILE), header);

		List<TomcatServiceConfig> imported = new ArrayList<>();
		int position = firstService;
		while (position >= 0) {
			int serviceEnd = content.indexOf("</Service>", position);
			if (serviceEnd < 0) {
				throw new IOException("Malformed server.xml: missing </Service>");
			}
			serviceEnd += "</Service>".length();
			String fragment = content.substring(position, serviceEnd);
			TomcatServiceConfig service = parseServiceFragment(fragment);
			if (pathGatewayServiceName.equals(service.getName())) {
				log.info("[importFromServerXml] 略過 TMAM 管理的 PathGateway Service");
				position = indexOfServiceTag(content, serviceEnd);
				continue;
			}
			Files.writeString(fragmentsDir().resolve(service.getName() + ".xml"), fragment);
			imported.add(service);
			position = indexOfServiceTag(content, serviceEnd);
		}
		return imported;
	}

	public void writeEffectiveServerXml(String catalinaHome, Map<String, TomcatServiceConfig> services)
			throws IOException {
		log.info("[writeEffectiveServerXml] 開始組裝 server.xml, catalinaHome={}", catalinaHome);
		boolean hasEnabledLegacy = services.values().stream()
				.anyMatch(service -> service.isLegacyIp() && service.isEnabled());
		boolean hasEnabledPathProxy = services.values().stream()
				.anyMatch(service -> service.isPathProxy() && service.isEnabled());
		if (!hasEnabledLegacy && !hasEnabledPathProxy) {
			log.warn("[writeEffectiveServerXml] 失敗：沒有啟用的 Service");
			throw new IllegalArgumentException("至少需要啟用一個 Service");
		}

		Path headerFile = fragmentsDir().resolve(HEADER_FILE);
		if (!Files.exists(headerFile)) {
			log.error("[writeEffectiveServerXml] 失敗：找不到 header 片段 {}", headerFile);
			throw new IOException("Service fragments not imported. Run import first.");
		}
		log.debug("[writeEffectiveServerXml] 讀取 header: {}", headerFile);

		StringBuilder content = new StringBuilder(Files.readString(headerFile)).append("\n");
		for (TomcatServiceConfig service : services.values()) {
			if (!service.isLegacyIp() || !service.isEnabled()) {
				continue;
			}
			Path fragmentFile = fragmentsDir().resolve(service.getName() + ".xml");
			if (!Files.exists(fragmentFile)) {
				log.error("[writeEffectiveServerXml] 失敗：找不到片段 {}", fragmentFile);
				throw new IOException("Missing service fragment: " + service.getName());
			}
			log.debug("[writeEffectiveServerXml] 加入 LEGACY_IP 片段: {}", fragmentFile);
			content.append(Files.readString(fragmentFile)).append("\n");
		}

		if (hasEnabledPathProxy) {
			Path gatewayFragment = fragmentsDir().resolve(pathGatewayServiceName + ".xml");
			if (!Files.exists(gatewayFragment)) {
				throw new IOException("Missing PathGateway fragment. Add PATH_PROXY services first.");
			}
			log.debug("[writeEffectiveServerXml] 加入 PathGateway 片段: {}", gatewayFragment);
			content.append(Files.readString(gatewayFragment)).append("\n");
		}

		content.append("</Server>\n");

		Path target = effectiveServerXmlPath(catalinaHome);
		Files.createDirectories(target.getParent());
		Files.writeString(target, content.toString());
		log.info("[writeEffectiveServerXml] 已寫入 {}, 大小 {} bytes", target, content.length());
	}

	public void restoreOriginal(String catalinaHome) throws IOException {
		Path backup = backupPath(catalinaHome);
		if (!Files.exists(backup)) {
			throw new IOException("Backup not found: " + backup);
		}
		Path target = effectiveServerXmlPath(catalinaHome);
		Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
		log.info("[restoreOriginal] 已還原 {} -> {}", backup, target);
	}

	public boolean hasImportedFragments() {
		return Files.exists(fragmentsDir().resolve(HEADER_FILE));
	}

	public Map<String, TomcatServiceConfig> mergeImportedServices(List<TomcatServiceConfig> imported,
			Map<String, TomcatServiceConfig> existing) {
		Map<String, TomcatServiceConfig> merged = new LinkedHashMap<>();
		for (TomcatServiceConfig service : imported) {
			TomcatServiceConfig previous = existing.get(service.getName());
			if (previous != null) {
				service.setEnabled(previous.isEnabled());
				service.setDisplayName(previous.getDisplayName() != null ? previous.getDisplayName() : service.getName());
				if (previous.getType() != null) {
					service.setType(previous.getType());
				}
				service.setPathPrefix(previous.getPathPrefix());
				service.setDocBase(previous.getDocBase());
				service.setProxyStripPrefix(previous.isProxyStripPrefix());
			}
			else {
				service.setDisplayName(service.getName());
				service.setEnabled(true);
			}
			merged.put(service.getName(), service);
		}
		for (TomcatServiceConfig service : existing.values()) {
			if (service.isPathProxy()) {
				merged.put(service.getName(), service);
			}
		}
		return merged;
	}

	private TomcatServiceConfig parseServiceFragment(String fragment) throws Exception {
		DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
		Document document = builder.parse(new InputSource(new StringReader(fragment)));
		Element service = document.getDocumentElement();
		String name = service.getAttribute("name");
		if (name == null || name.isBlank()) {
			throw new IOException("Service element missing name attribute");
		}

		ConnectorInfo connector = extractHttpConnector(service);
		TomcatServiceConfig config = new TomcatServiceConfig();
		config.setName(name);
		config.setDisplayName(name);
		config.setType(TomcatServiceType.LEGACY_IP);
		config.setAddress(connector.address());
		config.setPort(connector.port());
		config.setEnabled(true);
		return config;
	}

	private ConnectorInfo extractHttpConnector(Element service) throws IOException {
		NodeList connectors = service.getElementsByTagName("Connector");
		for (int i = 0; i < connectors.getLength(); i++) {
			Element connector = (Element) connectors.item(i);
			String protocol = connector.getAttribute("protocol");
			if (protocol == null || protocol.isBlank() || "HTTP/1.1".equals(protocol)) {
				String portValue = connector.getAttribute("port");
				if (portValue == null || portValue.isBlank()) {
					continue;
				}
				String address = connector.getAttribute("address");
				if (address == null || address.isBlank()) {
					address = "0.0.0.0";
				}
				return new ConnectorInfo(address, Integer.parseInt(portValue));
			}
		}
		throw new IOException("No HTTP Connector found in service: " + service.getAttribute("name"));
	}

	private int indexOfServiceTag(String content, int fromIndex) {
		int angle = content.indexOf("<Service", fromIndex);
		while (angle >= 0) {
			int next = angle + "<Service".length();
			if (next >= content.length()) {
				return angle;
			}
			char following = content.charAt(next);
			if (following == ' ' || following == '>' || following == '\t' || following == '\n' || following == '\r') {
				return angle;
			}
			angle = content.indexOf("<Service", angle + 1);
		}
		return -1;
	}

	private record ConnectorInfo(String address, int port) {
	}

}
