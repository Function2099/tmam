package com.tmam.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
			@Value("${tmam.nginx.executable:}") String configuredExecutable,
			@Value("${tmam.nginx.config-path}") String configPath,
			@Value("${tmam.nginx.locations-fragment}") String locationsFragment,
			@Value("${tmam.nginx.listen-port:80}") int listenPort,
			@Value("${tmam.nginx.upstream-host:127.0.0.1}") String upstreamHost,
			NginxDiscoveryService nginxDiscoveryService) {
		this.enabled = enabled;
		this.executable = resolveExecutable(configuredExecutable, nginxDiscoveryService);
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
				"127.0.0.1",
				new NginxDiscoveryService());
	}

	private static String resolveExecutable(String configured, NginxDiscoveryService discoveryService) {
		if (configured != null && !configured.isBlank()) {
			return configured.trim();
		}
		return discoveryService.discover().orElse("");
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
		Files.writeString(configPath, buildMainConfig(buildContextMaps(config)));
		log.info("[writeConfig] 已寫入主設定 {}", configPath);
	}

	String buildLocationsFragment(TmamConfig config) {
		List<String> blocks = new ArrayList<>();
		Map<String, List<LegacyTarget>> legacyByKey = new LinkedHashMap<>();
		for (TomcatInstanceConfig instance : config.getTomcatInstances().values()) {
			List<TomcatServiceConfig> pathProxies = instance.getServices().values().stream()
					.filter(service -> service.getType() == TomcatServiceType.PATH_PROXY && service.isEnabled())
					.collect(Collectors.toList());
			for (TomcatServiceConfig service : pathProxies) {
				blocks.add(buildLocationBlock(service, instance.getGatewayPort()));
				String origin = upstreamOrigin(service, instance.getGatewayPort());
				for (String legacyPath : PathProxyValidator.normalizeLegacyPaths(service.getLegacyPaths())) {
					String key = legacyPath.toLowerCase(Locale.ROOT);
					legacyByKey.computeIfAbsent(key, ignored -> new ArrayList<>())
							.add(new LegacyTarget(legacyPath, origin, isLegacyFilePath(legacyPath)));
				}
			}
		}

		if (blocks.isEmpty()) {
			return "# TMAM managed locations (no PATH_PROXY services enabled)\n";
		}

		StringBuilder content = new StringBuilder("# TMAM managed locations\n");
		for (String block : blocks) {
			content.append(block).append('\n');
		}
		for (List<LegacyTarget> targets : legacyByKey.values()) {
			content.append('\n').append(buildLegacyLocation(targets)).append('\n');
		}
		return content.toString();
	}

	private static final String PROXY_HEADERS = """
			    proxy_set_header Host $host;
			    proxy_set_header X-Real-IP $remote_addr;
			    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
			    proxy_set_header X-Forwarded-Proto $scheme;
			    proxy_hide_header Strict-Transport-Security;""";

	private String buildLocationBlock(TomcatServiceConfig service, int gatewayPort) {
		String locationPrefix = PathProxyValidator.nginxLocationPrefix(service.getPathPrefix());
		String contextPath = PathProxyValidator.contextPathForTomcat(service.getPathPrefix());
		String upstreamContext = upstreamOrigin(service, gatewayPort);
		String proxyPass = upstreamContext + "/";

		StringBuilder block = new StringBuilder();
		block.append("location ").append(locationPrefix).append(" {\n");
		block.append("    proxy_pass ").append(proxyPass).append(";\n");
		block.append(PROXY_HEADERS).append('\n');
		block.append("    add_header Set-Cookie \"tmam_ctx=").append(contextPath)
				.append("; Path=/; SameSite=Lax\" always;\n");
		block.append("    proxy_connect_timeout 60s;\n");
		block.append("    proxy_read_timeout 300s;\n");
		if (!service.isProxyStripPrefix()) {
			appendLegacyRedirects(block, service, contextPath);
		}
		block.append("}\n");
		block.append("location = ").append(contextPath).append(" {\n");
		block.append("    return 301 ").append(locationPrefix).append(";\n");
		block.append('}');
		String indexPage = PathProxyValidator.normalizeIndexPage(service.getIndexPage());
		if (!indexPage.isBlank()) {
			block.append("\nlocation = ").append(locationPrefix).append(" {\n");
			block.append("    return 302 ").append(locationPrefix).append(indexPage).append("$is_args$args;\n");
			block.append('}');
		}
		return block.toString();
	}

	private static void appendLegacyRedirects(StringBuilder block, TomcatServiceConfig service, String contextPath) {
		for (String legacyPath : PathProxyValidator.normalizeLegacyPaths(service.getLegacyPaths())) {
			if (isLegacyFilePath(legacyPath)) {
				block.append("    proxy_redirect ").append(legacyPath).append(" ")
						.append(contextPath).append(legacyPath).append(";\n");
			}
			else {
				block.append("    proxy_redirect ").append(legacyPath).append("/ ")
						.append(contextPath).append(legacyPath).append("/;\n");
			}
		}
	}

	private String buildLegacyLocation(List<LegacyTarget> targets) {
		LegacyTarget first = targets.get(0);
		StringBuilder block = new StringBuilder();
		if (first.file()) {
			block.append("location = ").append(first.path()).append(" {\n");
		}
		else {
			block.append("location ").append(first.path()).append("/ {\n");
		}
		block.append("    if ($tmam_ctx = \"\") { return 404; }\n");
		block.append("    return 302 $tmam_ctx$request_uri;\n");
		block.append('}');
		return block.toString();
	}

	private String upstreamOrigin(TomcatServiceConfig service, int gatewayPort) {
		String base = "http://" + upstreamHost + ":" + gatewayPort;
		if (service.isProxyStripPrefix()) {
			return base;
		}
		return base + PathProxyValidator.contextPathForTomcat(service.getPathPrefix());
	}

	String buildContextMaps(TmamConfig config) {
		List<String> prefixes = new ArrayList<>();
		for (TomcatInstanceConfig instance : config.getTomcatInstances().values()) {
			for (TomcatServiceConfig service : instance.getServices().values()) {
				if (service.getType() != TomcatServiceType.PATH_PROXY || !service.isEnabled()) {
					continue;
				}
				prefixes.add(PathProxyValidator.contextPathForTomcat(service.getPathPrefix()));
			}
		}
		prefixes.sort(Comparator.comparingInt(String::length).reversed());
		StringBuilder map = new StringBuilder();
		map.append("    map $http_referer $tmam_ctx_from_referer {\n");
		map.append("        default \"\";\n");
		for (String prefix : prefixes) {
			map.append("        ~*").append(prefix).append("(/|\\?|$) ")
					.append(prefix).append(";\n");
		}
		map.append("    }\n");
		map.append("    map $cookie_tmam_ctx $tmam_ctx {\n");
		map.append("        \"\" $tmam_ctx_from_referer;\n");
		for (String prefix : prefixes) {
			map.append("        ").append(prefix).append(" ").append(prefix).append(";\n");
		}
		map.append("        default $tmam_ctx_from_referer;\n");
		map.append("    }\n");
		return map.toString();
	}

	private record LegacyTarget(String path, String origin, boolean file) {
	}

	/**
	 * Nginx 在 Windows 上 location 不分大小寫，/error 與 /Error 會變成 duplicate location。
	 */
	static boolean claimLegacyPath(Set<String> claimed, String path) {
		for (String existing : claimed) {
			if (existing.equalsIgnoreCase(path)) {
				return false;
			}
		}
		return claimed.add(path);
	}

	static boolean isLegacyFilePath(String path) {
		int slash = path.lastIndexOf('/');
		String name = slash >= 0 ? path.substring(slash + 1) : path;
		return name.indexOf('.') >= 0;
	}

	private Path nginxHome() {
		return Path.of(executable).toAbsolutePath().getParent();
	}

	private Path mimeTypesPath() {
		return nginxHome().resolve("conf/mime.types");
	}

	private String buildMainConfig(String refererMap) {
		String includePath = locationsFragment.toAbsolutePath().toString().replace("\\", "/");
		String mimeTypes = mimeTypesPath().toString().replace("\\", "/");
		Path nginxDir = configPath.toAbsolutePath().getParent();
		String pidPath = nginxDir.resolve("nginx.pid").toString().replace("\\", "/");
		String errorLog = nginxDir.resolve("error.log").toString().replace("\\", "/");
		return """
				worker_processes  1;
				pid %s;
				error_log %s;

				events {
				    worker_connections  1024;
				}

				http {
				    include       %s;
				    default_type  application/octet-stream;
				    sendfile        on;
				    keepalive_timeout  65;
				    absolute_redirect off;
				%s
				    server {
				        listen %d;
				        server_name localhost;

				        include %s;

				        location / {
				            default_type text/plain;
				            charset utf-8;
				            return 404 'TMAM: 沒有對應的路徑型系統。請使用 http://主機/路徑前綴/\\n';
				        }
				    }
				}
				""".formatted(pidPath, errorLog, mimeTypes, refererMap, listenPort, includePath);
	}

	public boolean isListening() {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", listenPort), 500);
			return true;
		}
		catch (IOException ex) {
			return false;
		}
	}

	public void testConfig() throws IOException, InterruptedException {
		runNginx("-t", "-c", configPath.toAbsolutePath().toString());
	}

	public void start() throws IOException, InterruptedException {
		if (isListening()) {
			log.warn("[start] :{} 仍被占用，可能不是 TMAM 的 Nginx", listenPort);
			return;
		}
		List<String> command = new ArrayList<>();
		command.add(Path.of(executable).toAbsolutePath().toString());
		command.add("-c");
		command.add(configPath.toAbsolutePath().toString());
		log.info("[start] {}", String.join(" ", command));
		ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.directory(nginxHome().toFile());
		processBuilder.redirectErrorStream(true);
		Process process = processBuilder.start();
		if (waitUntilListening(4000)) {
			return;
		}
		String output = "";
		if (!process.isAlive()) {
			output = new String(process.getInputStream().readAllBytes()).trim();
		}
		throw new IOException("Nginx 啟動後仍未監聽 :" + listenPort
				+ (output.isBlank() ? "" : " — " + output));
	}

	public void reload() throws IOException, InterruptedException {
		runNginx("-s", "reload", "-c", configPath.toAbsolutePath().toString());
	}

	public void reloadOrStart() throws IOException, InterruptedException {
		stopQuietly();
		start();
	}

	/**
	 * Windows 上 port 80 可被多個 nginx master 同時占用；只 reload 會留下舊行程，
	 * 請求落到預設 conf 就會出現 Welcome to nginx。套用前先停乾淨再啟動。
	 */
	void stopQuietly() {
		if (!isAvailable()) {
			return;
		}
		try {
			runNginx("-s", "quit", "-c", configPath.toAbsolutePath().toString());
		}
		catch (Exception ex) {
			log.debug("[stopQuietly] quit (tmam config): {}", ex.getMessage());
		}
		try {
			ProcessBuilder processBuilder = new ProcessBuilder(
					Path.of(executable).toAbsolutePath().toString(), "-s", "quit");
			processBuilder.directory(nginxHome().toFile());
			processBuilder.redirectErrorStream(true);
			Process process = processBuilder.start();
			if (!process.waitFor(10, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		}
		catch (Exception ex) {
			log.debug("[stopQuietly] quit (install prefix): {}", ex.getMessage());
		}
		waitUntilNotListening(3000);
		if (isListening()) {
			forceStopNginxProcesses();
			waitUntilNotListening(4000);
		}
	}

	private void forceStopNginxProcesses() {
		String os = System.getProperty("os.name", "").toLowerCase();
		if (!os.contains("win")) {
			return;
		}
		try {
			log.warn("[stopQuietly] :{} 仍被占用，強制結束 nginx.exe", listenPort);
			ProcessBuilder processBuilder = new ProcessBuilder("taskkill", "/F", "/IM", "nginx.exe");
			processBuilder.redirectErrorStream(true);
			Process process = processBuilder.start();
			if (!process.waitFor(10, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		}
		catch (Exception ex) {
			log.debug("[stopQuietly] taskkill: {}", ex.getMessage());
		}
	}

	private boolean waitUntilNotListening(int timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (!isListening()) {
				return true;
			}
			try {
				Thread.sleep(200);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return !isListening();
			}
		}
		return !isListening();
	}

	private boolean waitUntilListening(int timeoutMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (isListening()) {
				return true;
			}
			Thread.sleep(200);
		}
		return isListening();
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
