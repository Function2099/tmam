package com.tmam.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.tmam.model.TomcatServiceConfig;
import com.tmam.model.TomcatServiceType;

@Service
public class PathGatewayService {

	private static final Logger log = LoggerFactory.getLogger(PathGatewayService.class);

	private final String serviceName;
	private final String upstreamHost;
	private final int gatewayPort;
	private final String fragmentsRoot;

	public PathGatewayService(
			@Value("${tmam.path-gateway.service-name:PathGateway}") String serviceName,
			@Value("${tmam.nginx.upstream-host:127.0.0.1}") String upstreamHost,
			@Value("${tmam.nginx.gateway-port:8080}") int gatewayPort,
			@Value("${tmam.server-xml-fragments}") String fragmentsRoot) {
		this.serviceName = serviceName;
		this.upstreamHost = upstreamHost;
		this.gatewayPort = gatewayPort;
		this.fragmentsRoot = fragmentsRoot;
	}

	public String getServiceName() {
		return serviceName;
	}

	public int getGatewayPort() {
		return gatewayPort;
	}

	public String getUpstreamHost() {
		return upstreamHost;
	}

	public Path fragmentPath() {
		return Path.of(fragmentsRoot).resolve(serviceName + ".xml");
	}

	public void writeFragment(Collection<TomcatServiceConfig> services) throws IOException {
		List<TomcatServiceConfig> enabled = services.stream()
				.filter(service -> service.getType() == TomcatServiceType.PATH_PROXY && service.isEnabled())
				.collect(Collectors.toList());

		Path target = fragmentPath();
		Files.createDirectories(target.getParent());

		if (enabled.isEmpty()) {
			Files.deleteIfExists(target);
			log.info("[writeFragment] 無啟用的 PATH_PROXY，已移除 {}", target);
			return;
		}

		String fragment = buildFragment(enabled);
		Files.writeString(target, fragment);
		log.info("[writeFragment] 已寫入 PathGateway 片段 {} ({} 個 Context)", target, enabled.size());
	}

	String buildFragment(List<TomcatServiceConfig> enabledServices) {
		StringBuilder xml = new StringBuilder();
		xml.append("  <Service name=\"").append(escapeXml(serviceName)).append("\">\n");
		xml.append("    <Connector address=\"").append(escapeXml(upstreamHost))
				.append("\" port=\"").append(gatewayPort)
				.append("\" protocol=\"HTTP/1.1\" connectionTimeout=\"20000\" />\n");
		xml.append("    <Engine name=\"").append(escapeXml(serviceName))
				.append("\" defaultHost=\"").append(escapeXml(serviceName)).append("\">\n");
		xml.append("      <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\" resourceName=\"UserDatabase\" />\n");
		xml.append("      <Host name=\"").append(escapeXml(serviceName))
				.append("\" unpackWARs=\"true\" autoDeploy=\"true\">\n");

		for (TomcatServiceConfig service : enabledServices) {
			String contextPath = PathProxyValidator.contextPathForTomcat(service.getPathPrefix());
			xml.append("        <Context path=\"").append(escapeXml(contextPath))
					.append("\" docBase=\"").append(escapeXml(service.getDocBase()))
					.append("\" reloadable=\"true\" crossContext=\"true\" />\n");
		}

		xml.append("      </Host>\n");
		xml.append("    </Engine>\n");
		xml.append("  </Service>\n");
		return xml.toString();
	}

	private String escapeXml(String value) {
		return value.replace("&", "&amp;")
				.replace("\"", "&quot;")
				.replace("<", "&lt;")
				.replace(">", "&gt;");
	}

}