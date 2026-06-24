package com.tmam.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.tmam.model.TmamConfig;
import com.tmam.model.TomcatInstanceConfig;
import com.tmam.model.TomcatServiceConfig;
import com.tmam.model.TomcatServiceType;

@Service
public class NginxConfigService {

	private static final Logger log = LoggerFactory.getLogger(NginxConfigService.class);

	private final boolean enabled;
	private final String executable;
	private final Path configPath;
	private final Path locationsFragment;
	private final int listenPort;
	private final String upstreamHost;

	public NginxConfigService(
			@Value("${tmam.nginx.enabled:true}") boolean enabled,
			@Value("${tmam.nginx.executable:C:/nginx/nginx.exe}") String executable,
			@Value("${tmam.nginx.config-path}") String configPath,
			@Value("${tmam.nginx.locations-fragment}") String locationsFragment,
			@Value("${tmam.nginx.listen-port:80}") int listenPort,
			@Value("${tmam.nginx.upstream-host:127.0.0.1}") String upstreamHost) {
		this.enabled = enabled;
		this.executable = executable;
		this.configPath = Path.of(configPath);
		this.locationsFragment = Path.of(locationsFragment);
		this.listenPort = listenPort;
		this.upstreamHost = upstreamHost;
	}

	static NginxConfigService forTest(Path tempDir) {
		return new NginxConfigService(
				true,
				tempDir.resolve("missing-nginx.exe").toString(),
				tempDir.resolve("nginx/nginx.conf").toString(),
				tempDir.resolve("nginx/tmam-locations.conf").toString(),
				80,
				"127.0.0.1");
	}

	public boolean isEnabled() {
		return enabled;
	}

	public int getListenPort() {
		return listenPort;
	}

	public String getExecutable() {
		return executable;
	}

	public Path getConfigPath() {
		return configPath;
	}

	public Path getLocationsFragment() {
		return locationsFragment;
	}

	public String getUpstreamHost() {
		return upstreamHost;
	}

	public boolean isAvailable() {
		if (!enabled) {
			return false;
		}
		Path nginx = Path.of(executable);
		return Files.isRegularFile(nginx);
	}

	public void writeConfig(TmamConfig config) throws IOException {
		if (!enabled) {
			log.info("[writeConfig] Nginx 已停用，略過寫入");
			return;
		}

		Files.createDirectories(locationsFragment.getParent());
		String locations = buildLocationsFragment(config);
		Files.writeString(locationsFragment, locations);
		log.info("[writeConfig] 已寫入 location 片段 {}", locationsFragment);

		Files.createDirectories(configPath.getParent());
		Files.writeString(configPath, buildMainConfig());
		log.info("[writeConfig] 已寫入主設定 {}", configPath);
	}

	String buildLocationsFragment(TmamConfig config) {
		List<String> blocks = new ArrayList<>();
		for (TomcatInstanceConfig instance : config.getTomcatInstances().values()) {
			List<TomcatServiceConfig> pathProxies = instance.getServices().values().stream()
					.filter(service -> service.getType() == TomcatServiceType.PATH_PROXY && service.isEnabled())
					.collect(Collectors.toList());
			for (TomcatServiceConfig service : pathProxies) {
				blocks.add(buildLocationBlock(service, instance.getGatewayPort()));
			}
		}

		if (blocks.isEmpty()) {
			return "# TMAM managed locations (no PATH_PROXY services enabled)\n";
		}

		StringBuilder content = new StringBuilder("# TMAM managed locations\n");
		for (String block : blocks) {
			content.append(block).append('\n');
		}
		return content.toString();
	}

	private String buildLocationBlock(TomcatServiceConfig service, int gatewayPort) {
		String locationPrefix = PathProxyValidator.nginxLocationPrefix(service.getPathPrefix());
		String contextPath = PathProxyValidator.contextPathForTomcat(service.getPathPrefix());
		String upstreamBase = "http://" + upstreamHost + ":" + gatewayPort;
		String proxyPass = service.isProxyStripPrefix()
				? upstreamBase + "/"
				: upstreamBase + contextPath + "/";

		return """
				location %s {
				    proxy_pass %s;
				    proxy_set_header Host $host;
				    proxy_set_header X-Real-IP $remote_addr;
				    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
				    proxy_set_header X-Forwarded-Proto $scheme;
				}""".formatted(locationPrefix, proxyPass);
	}

	private Path nginxHome() {
		return Path.of(executable).toAbsolutePath().getParent();
	}

	private Path mimeTypesPath() {
		return nginxHome().resolve("conf/mime.types");
	}

	private String buildMainConfig() {
		String includePath = locationsFragment.toAbsolutePath().toString().replace("\\", "/");
		String mimeTypes = mimeTypesPath().toString().replace("\\", "/");
		return """
				worker_processes  1;

				events {
				    worker_connections  1024;
				}

				http {
				    include       %s;
				    default_type  application/octet-stream;
				    sendfile        on;
				    keepalive_timeout  65;

				    server {
				        listen %d;
				        server_name localhost;

				        include %s;
				    }
				}
				""".formatted(mimeTypes, listenPort, includePath);
	}

	public void testConfig() throws IOException, InterruptedException {
		runNginx("-t", "-c", configPath.toAbsolutePath().toString());
	}

	public void start() throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add(Path.of(executable).toAbsolutePath().toString());
		command.add("-c");
		command.add(configPath.toAbsolutePath().toString());
		log.info("[start] {}", String.join(" ", command));
		ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.directory(nginxHome().toFile());
		processBuilder.redirectErrorStream(true);
		processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
		processBuilder.start();
		Thread.sleep(1000);
	}

	public void reload() throws IOException, InterruptedException {
		runNginx("-s", "reload", "-c", configPath.toAbsolutePath().toString());
	}

	public void reloadOrStart() throws IOException, InterruptedException {
		try {
			reload();
		}
		catch (IOException ex) {
			log.info("[reloadOrStart] reload 失敗，改為啟動 Nginx: {}", ex.getMessage());
			start();
		}
	}

	public void apply(TmamConfig config) throws IOException, InterruptedException {
		writeConfig(config);
		if (!isAvailable()) {
			log.warn("[apply] Nginx 執行檔不存在: {}", executable);
			return;
		}
		testConfig();
		reloadOrStart();
	}

	private void runNginx(String... args) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add(Path.of(executable).toAbsolutePath().toString());
		for (String arg : args) {
			command.add(arg);
		}
		log.info("[runNginx] {}", String.join(" ", command));
		ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.directory(nginxHome().toFile());
		processBuilder.redirectErrorStream(true);
		Process process = processBuilder.start();
		String output = new String(process.getInputStream().readAllBytes());
		if (!process.waitFor(30, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IOException("Nginx 命令逾時: " + String.join(" ", command));
		}
		if (process.exitValue() != 0) {
			throw new IOException("Nginx 命令失敗 (exit " + process.exitValue() + "): " + output.trim());
		}
		if (!output.isBlank()) {
			log.debug("[runNginx] output: {}", output.trim());
		}
	}

}
