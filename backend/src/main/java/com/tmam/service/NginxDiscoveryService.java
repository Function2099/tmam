package com.tmam.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class NginxDiscoveryService {

	private static final Logger log = LoggerFactory.getLogger(NginxDiscoveryService.class);

	private static final List<String> COMMON_WINDOWS_PATHS = List.of(
			"C:/nginx/nginx.exe",
			"C:/Program Files/nginx/nginx.exe",
			"C:/tools/nginx/nginx.exe");

	public Optional<String> discover() {
		for (Path candidate : candidatePaths()) {
			if (isNginxExecutable(candidate)) {
				return Optional.of(candidate.toAbsolutePath().toString());
			}
		}
		return findViaWhereCommand();
	}

	public boolean isNginxExecutable(Path path) {
		return path != null && Files.isRegularFile(path);
	}

	private List<Path> candidatePaths() {
		Set<Path> candidates = new LinkedHashSet<>();
		boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
		String executableName = windows ? "nginx.exe" : "nginx";

		String nginxHome = System.getenv("NGINX_HOME");
		if (nginxHome != null && !nginxHome.isBlank()) {
			candidates.add(Path.of(nginxHome.trim(), executableName));
		}

		findOnPath(executableName).ifPresent(candidates::add);

		if (windows) {
			for (String commonPath : COMMON_WINDOWS_PATHS) {
				candidates.add(Path.of(commonPath));
			}
		}
		else {
			candidates.add(Path.of("/usr/sbin/nginx"));
			candidates.add(Path.of("/usr/local/nginx/sbin/nginx"));
		}

		return new ArrayList<>(candidates);
	}

	private Optional<Path> findOnPath(String executableName) {
		String pathEnv = System.getenv("PATH");
		if (pathEnv == null || pathEnv.isBlank()) {
			return Optional.empty();
		}
		for (String dir : pathEnv.split(File.pathSeparator)) {
			if (dir == null || dir.isBlank()) {
				continue;
			}
			Path candidate = Path.of(dir.trim(), executableName);
			if (isNginxExecutable(candidate)) {
				return Optional.of(candidate);
			}
		}
		return Optional.empty();
	}

	Optional<String> findViaWhereCommand() {
		if (!System.getProperty("os.name").toLowerCase().contains("win")) {
			return Optional.empty();
		}
		try {
			Process process = new ProcessBuilder("where", "nginx").redirectErrorStream(true).start();
			if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return Optional.empty();
			}
			if (process.exitValue() != 0) {
				return Optional.empty();
			}
			String output = new String(process.getInputStream().readAllBytes()).trim();
			for (String line : output.split("\\R")) {
				Path candidate = Path.of(line.trim());
				if (isNginxExecutable(candidate)) {
					return Optional.of(candidate.toAbsolutePath().toString());
				}
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.debug("[discover] where nginx 中斷");
		}
		catch (IOException e) {
			log.debug("[discover] where nginx 失敗: {}", e.getMessage());
		}
		return Optional.empty();
	}

}
