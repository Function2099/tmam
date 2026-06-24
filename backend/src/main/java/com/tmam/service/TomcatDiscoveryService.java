package com.tmam.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.tmam.dto.TomcatDiscoveryView;
import com.tmam.util.CatalinaHomeNormalizer;

@Service
public class TomcatDiscoveryService {

	private static final Logger log = LoggerFactory.getLogger(TomcatDiscoveryService.class);

	public List<TomcatDiscoveryView> discover() {
		List<TomcatDiscoveryView> found = new ArrayList<>();
		List<Path> candidates = new ArrayList<>();

		String catalinaHomeEnv = System.getenv("CATALINA_HOME");
		if (catalinaHomeEnv != null && !catalinaHomeEnv.isBlank()) {
			candidates.add(Path.of(catalinaHomeEnv));
		}

		scanDirectory(Path.of("C:/Program Files"), "apache-tomcat-*", candidates);
		scanDirectory(Path.of("C:/"), "apache-tomcat-*", candidates);
		scanDirectory(Path.of("D:/"), "apache-tomcat-*", candidates);

		for (Path candidate : candidates) {
			if (isTomcatHome(candidate)) {
				found.add(new TomcatDiscoveryView(
						candidate.toString(),
						candidate.getFileName().toString(),
						readVersion(candidate)));
			}
		}
		found.sort((a, b) -> a.catalinaHome().compareToIgnoreCase(b.catalinaHome()));
		return found.stream()
				.collect(java.util.stream.Collectors.toMap(
						v -> CatalinaHomeNormalizer.comparisonKey(v.catalinaHome()),
						v -> v,
						(a, b) -> a,
						java.util.LinkedHashMap::new))
				.values().stream().toList();
	}

	private void scanDirectory(Path parent, String glob, List<Path> candidates) {
		if (!Files.isDirectory(parent)) {
			return;
		}
		try (var paths = Files.newDirectoryStream(parent, glob)) {
			for (Path path : paths) {
				candidates.add(path);
			}
		}
		catch (IOException e) {
			log.debug("[discover] 無法掃描 {}: {}", parent, e.getMessage());
		}
	}

	public boolean isTomcatHome(Path path) {
		if (path == null || !Files.isDirectory(path)) {
			return false;
		}
		boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
		Path script = windows ? path.resolve("bin/catalina.bat") : path.resolve("bin/catalina.sh");
		return Files.isRegularFile(script) && Files.isRegularFile(path.resolve("conf/server.xml"));
	}

	private String readVersion(Path home) {
		Path versionFile = home.resolve("RELEASE-NOTES");
		if (!Files.isRegularFile(versionFile)) {
			return "unknown";
		}
		try {
			List<String> lines = Files.readAllLines(versionFile);
			for (String line : lines) {
				if (line.toLowerCase().contains("apache tomcat version")) {
					return line.trim();
				}
			}
		}
		catch (IOException ignored) {
			// fall through
		}
		return home.getFileName().toString();
	}

}
