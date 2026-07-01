package com.tmam.service;

import java.nio.file.Path;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CatalinaHomeResolver {

	private final TomcatDiscoveryService tomcatDiscoveryService;
	private final String configuredDefault;

	public CatalinaHomeResolver(
			TomcatDiscoveryService tomcatDiscoveryService,
			@Value("${tmam.default-catalina-home:}") String configuredDefault) {
		this.tomcatDiscoveryService = tomcatDiscoveryService;
		this.configuredDefault = configuredDefault;
	}

	public String resolve(String explicit) {
		if (explicit != null && !explicit.isBlank()) {
			return explicit.trim();
		}
		if (configuredDefault != null && !configuredDefault.isBlank()) {
			return configuredDefault.trim();
		}
		String catalinaHomeEnv = System.getenv("CATALINA_HOME");
		if (catalinaHomeEnv != null && !catalinaHomeEnv.isBlank()
				&& tomcatDiscoveryService.isTomcatHome(Path.of(catalinaHomeEnv.trim()))) {
			return catalinaHomeEnv.trim();
		}
		return tomcatDiscoveryService.discover().stream()
				.findFirst()
				.map(view -> view.catalinaHome())
				.orElse("");
	}

	public Path resolvePath(String explicit) {
		String resolved = resolve(explicit);
		if (resolved.isBlank()) {
			throw new IllegalStateException(
					"找不到 Tomcat 安裝路徑。請於 TMAM 新增 Tomcat 實例，或設定 CATALINA_HOME / tmam.default-catalina-home");
		}
		return Path.of(resolved);
	}

	public Optional<String> discoverFirst() {
		String resolved = resolve(null);
		return resolved.isBlank() ? Optional.empty() : Optional.of(resolved);
	}

}
