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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
	private static final Pattern SERVER_PORT_PATTERN = Pattern.compile("<Server\\s+port=\"(\\d+)\"");

	private final CatalinaHomeResolver catalinaHomeResolver;
	private final String pathGatewayServiceName;
	private final NativeTomcatEnvironmentService nativeTomcatEnvironmentService;

	public ServerXmlService(CatalinaHomeResolver catalinaHomeResolver,
			@Value("${tmam.path-gateway.service-name:PathGateway}") String pathGatewayServiceName,
			NativeTomcatEnvironmentService nativeTomcatEnvironmentService) {
		this.catalinaHomeResolver = catalinaHomeResolver;
		this.pathGatewayServiceName = pathGatewayServiceName;
		this.nativeTomcatEnvironmentService = nativeTomcatEnvironmentService;
	}

	public Path resolveCatalinaHome(String catalinaHome) {
		return catalinaHomeResolver.resolvePath(catalinaHome);
	}

	public Path serverXmlPath(String catalinaHome) {
		return resolveCatalinaHome(catalinaHome).resolve("conf/server.xml");
	}

	public Path effectiveServerXmlPath(String catalinaHome) {
		return serverXmlPath(catalinaHome);
	}

	public Path backupPath(String instanceId, String catalinaHome) {
		return nativeTomcatEnvironmentService.getBackupDir(instanceId).resolve("server.xml.tmam-original");
	}

	public Path fragmentsDir(String instanceId) {
		return nativeTomcatEnvironmentService.getFragmentsDir(instanceId);
	}

	public void backupOriginalIfNeeded(String instanceId, String catalinaHome) throws IOException {
		Path serverXml = serverXmlPath(catalinaHome);
		Path backup = backupPath(instanceId, catalinaHome);
		if (!Files.exists(serverXml)) {
			throw new IOException("server.xml not found: " + serverXml);
		}
		if (!Files.exists(backup)) {
			Files.createDirectories(backup.getParent());
			Files.copy(serverXml, backup, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public int readShutdownPortFromHeader(String instanceId) throws IOException {
		Path headerFile = fragmentsDir(instanceId).resolve(HEADER_FILE);
		if (!Files.exists(headerFile)) {
			return 8005;
		}
		String header = Files.readString(headerFile);
		Matcher matcher = SERVER_PORT_PATTERN.matcher(header);
		if (matcher.find()) {
			return Integer.parseInt(matcher.group(1));
		}
		return 8005;
	}

	public List<TomcatServiceConfig> importFromServerXml(String instanceId, String catalinaHome) throws Exception {
		backupOriginalIfNeeded(instanceId, catalinaHome);
		Path serverXml = serverXmlPath(catalinaHome);
		String content = Files.readString(serverXml);

		int firstService = indexOfServiceTag(content, 0);
		if (firstService < 0) {
			throw new IOException("No <Service> elements found in server.xml");
		}

		Path fragments = fragmentsDir(instanceId);
		Files.createDirectories(fragments);
		String header = content.substring(0, firstService).stripTrailing();
		Files.writeString(fragments.resolve(HEADER_FILE), header);

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
			Files.writeString(fragments.resolve(service.getName() + ".xml"), fragment);
			imported.add(service);
			position = indexOfServiceTag(content, serviceEnd);
		}
		return imported;
	}

	public void writeServiceFragment(String instanceId, String serviceName, String fragment) throws IOException {
		Path fragments = fragmentsDir(instanceId);
		Files.createDirectories(fragments);
		Files.writeString(fragments.resolve(serviceName + ".xml"), fragment);
	}

	/**
	 * 就地更新匯入的 IP 型 Service 片段，保留原有 Connector / Context / Valve 等設定，
	 * 避免用簡化模板覆寫後把 docBase、sessionCookieName 等清掉。
	 */
	public void patchLegacyIpFragment(String instanceId, String serviceName, TomcatServiceConfig service)
			throws IOException {
		Path fragmentFile = fragmentsDir(instanceId).resolve(serviceName + ".xml");
		if (!Files.exists(fragmentFile)) {
			throw new IOException("Missing service fragment: " + serviceName);
		}
		String content = Files.readString(fragmentFile);
		String updated = content;

		if (service.getAddress() != null && !service.getAddress().isBlank()) {
			updated = replaceXmlAttr(updated, "address", service.getAddress().trim());
		}
		if (service.getPort() > 0) {
			updated = replaceXmlAttr(updated, "port", String.valueOf(service.getPort()));
		}
		if (service.getDocBase() != null && !service.getDocBase().isBlank()) {
			updated = replaceXmlAttr(updated, "docBase", service.getDocBase().trim());
		}
		updated = upsertTmamOnlineParameter(updated, service.isOnline());

		Files.writeString(fragmentFile, updated);
		log.info("[patchLegacyIpFragment] instance={} service={} 已就地更新片段", instanceId, serviceName);
	}

	/**
	 * 從既有 fragment 讀取 docBase；若設定檔遺失可回填。
	 */
	public String readDocBaseFromFragment(String instanceId, String serviceName) throws IOException {
		Path fragmentFile = fragmentsDir(instanceId).resolve(serviceName + ".xml");
		if (!Files.exists(fragmentFile)) {
			return null;
		}
		Matcher matcher = Pattern.compile("docBase=\"([^\"]*)\"").matcher(Files.readString(fragmentFile));
		if (!matcher.find()) {
			return null;
		}
		String docBase = matcher.group(1);
		return docBase.isBlank() ? null : docBase;
	}

	private static String replaceXmlAttr(String xml, String attrName, String value) {
		String escaped = value.replace("&", "&amp;")
				.replace("\"", "&quot;")
				.replace("<", "&lt;")
				.replace(">", "&gt;");
		// 屬性名必須獨立，避免改 port 時連 redirectPort 一起被替換
		Pattern pattern = Pattern.compile("(?<![\\w:-])" + Pattern.quote(attrName) + "=\"[^\"]*\"");
		return pattern.matcher(xml).replaceAll(attrName + "=\"" + Matcher.quoteReplacement(escaped) + "\"");
	}

	private static String upsertTmamOnlineParameter(String xml, boolean online) {
		String value = online ? "true" : "false";
		Pattern existing = Pattern.compile(
				"<Parameter\\s+name=\"tmam\\.online\"\\s+value=\"(?:true|false)\"\\s+override=\"false\"\\s*/>");
		Matcher matcher = existing.matcher(xml);
		String parameter = "<Parameter name=\"tmam.online\" value=\"" + value + "\" override=\"false\"/>";
		if (matcher.find()) {
			return matcher.replaceFirst(Matcher.quoteReplacement(parameter));
		}
		int contextClose = xml.indexOf("</Context>");
		if (contextClose < 0) {
			// 自閉合 Context：改成有子元素的形式
			Pattern selfClosing = Pattern.compile("<Context([^>]*)\\s*/>");
			Matcher self = selfClosing.matcher(xml);
			if (self.find()) {
				String replacement = "<Context" + self.group(1) + ">\n          " + parameter
						+ "\n        </Context>";
				return self.replaceFirst(Matcher.quoteReplacement(replacement));
			}
			return xml;
		}
		return xml.substring(0, contextClose) + "    " + parameter + "\n    " + xml.substring(contextClose);
	}

	public void deleteServiceFragment(String instanceId, String serviceName) throws IOException {
		Files.deleteIfExists(fragmentsDir(instanceId).resolve(serviceName + ".xml"));
	}

	public void writeEffectiveServerXml(String instanceId, String catalinaHome,
			Map<String, TomcatServiceConfig> services) throws IOException {
		log.info("[writeEffectiveServerXml] instance={}, catalinaHome={}", instanceId, catalinaHome);
		boolean hasEnabledLegacy = services.values().stream()
				.anyMatch(service -> service.isLegacyIp() && service.isEnabled());
		boolean hasEnabledPathProxy = services.values().stream()
				.anyMatch(service -> service.isPathProxy() && service.isEnabled());
		if (!hasEnabledLegacy && !hasEnabledPathProxy) {
			throw new IllegalArgumentException("至少需要啟用一個 Service");
		}

		Path headerFile = fragmentsDir(instanceId).resolve(HEADER_FILE);
		if (!Files.exists(headerFile)) {
			throw new IOException("Service fragments not imported. Run import first.");
		}

		StringBuilder content = new StringBuilder(
				TomcatStartupOptimizer.optimizeServerHeader(Files.readString(headerFile))).append("\n");
		for (TomcatServiceConfig service : services.values()) {
			if (!service.isLegacyIp() || !service.isEnabled()) {
				continue;
			}
			Path fragmentFile = fragmentsDir(instanceId).resolve(service.getName() + ".xml");
			if (!Files.exists(fragmentFile)) {
				throw new IOException("Missing service fragment: " + service.getName());
			}
			content.append(TomcatStartupOptimizer.optimizeServiceFragment(Files.readString(fragmentFile)))
					.append("\n");
		}

		if (hasEnabledPathProxy) {
			Path gatewayFragment = fragmentsDir(instanceId).resolve(pathGatewayServiceName + ".xml");
			if (!Files.exists(gatewayFragment)) {
				throw new IOException("Missing PathGateway fragment. Add PATH_PROXY services first.");
			}
			content.append(TomcatStartupOptimizer.optimizeServiceFragment(Files.readString(gatewayFragment)))
					.append("\n");
		}

		content.append("</Server>\n");

		nativeTomcatEnvironmentService.ensureWritable(instanceId, catalinaHome);
		Path target = effectiveServerXmlPath(catalinaHome);
		try {
			Files.writeString(target, content.toString());
		}
		catch (IOException ex) {
			throw nativeTomcatEnvironmentService.wrapWriteFailure(target, ex);
		}
		log.info("[writeEffectiveServerXml] 已寫入 {}, 大小 {} bytes", target, content.length());
	}

	public void restoreOriginal(String instanceId, String catalinaHome) throws IOException {
		Path backup = backupPath(instanceId, catalinaHome);
		if (!Files.exists(backup)) {
			throw new IOException("Backup not found: " + backup);
		}
		nativeTomcatEnvironmentService.ensureWritable(instanceId, catalinaHome);
		Path target = effectiveServerXmlPath(catalinaHome);
		try {
			Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException ex) {
			throw nativeTomcatEnvironmentService.wrapWriteFailure(target, ex);
		}
		log.info("[restoreOriginal] 已還原 {} -> {}", backup, target);
	}

	public boolean hasImportedFragments(String instanceId) {
		return Files.exists(fragmentsDir(instanceId).resolve(HEADER_FILE));
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
				// 保留先前明確設定的 docBase；否則沿用剛匯入 fragment 的值，避免用 null 覆蓋
				if (previous.getDocBase() != null && !previous.getDocBase().isBlank()) {
					service.setDocBase(previous.getDocBase());
				}
				service.setProxyStripPrefix(previous.isProxyStripPrefix());
				service.setLegacyPaths(previous.getLegacyPaths());
				service.setIndexPage(previous.getIndexPage());
				service.setOnline(previous.isOnline());
				if (previous.isUserCreated()) {
					service.setUserCreated(true);
				}
			}
			else {
				service.setDisplayName(service.getName());
				service.setEnabled(true);
			}
			merged.put(service.getName(), service);
		}
		for (TomcatServiceConfig service : existing.values()) {
			if (service.isPathProxy() || service.isUserCreated()) {
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
		if (connector.docBase() != null) {
			config.setDocBase(connector.docBase());
		}
		return config;
	}

	private ConnectorInfo extractHttpConnector(Element service) throws IOException {
		NodeList connectors = service.getElementsByTagName("Connector");
		String docBase = extractDocBase(service);
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
				return new ConnectorInfo(address, Integer.parseInt(portValue), docBase);
			}
		}
		throw new IOException("No HTTP Connector found in service: " + service.getAttribute("name"));
	}

	private String extractDocBase(Element service) {
		NodeList contexts = service.getElementsByTagName("Context");
		if (contexts.getLength() == 0) {
			return null;
		}
		Element context = (Element) contexts.item(0);
		String docBase = context.getAttribute("docBase");
		return docBase.isBlank() ? null : docBase;
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

	private record ConnectorInfo(String address, int port, String docBase) {
	}

}
