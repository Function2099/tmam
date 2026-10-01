package com.tmam.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.tmam.model.InstanceStatus;
import com.tmam.util.LogFileReader;
import com.tmam.model.ProjectConfig;
import com.tmam.model.StartResult;
import com.tmam.model.TmamConfig;
import com.tmam.model.TomcatInstanceConfig;
import com.tmam.model.TomcatServiceConfig;

@Service
public class ProcessService {

	private static final Logger log = LoggerFactory.getLogger(ProcessService.class);

	private static final String SUCCESS_MARKER = "Server startup in";
	private static final String MANAGED_SUFFIX = ".managed";

	private final String instancesRoot;
	private final String pidsRoot;
	private final CatalinaHomeResolver catalinaHomeResolver;
	private final ConfigService configService;
	private final int startupTimeoutSec;
	private final NativeTomcatEnvironmentService nativeTomcatEnvironmentService;
	private final WindowsTomcatService windowsTomcatService;

	private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();

	public ProcessService(@Value("${tmam.instances-root}") String instancesRoot,
			@Value("${tmam.pids-root}") String pidsRoot,
			CatalinaHomeResolver catalinaHomeResolver,
			@Value("${tmam.startup-timeout-sec:0}") int startupTimeoutSec,
			ConfigService configService,
			NativeTomcatEnvironmentService nativeTomcatEnvironmentService,
			WindowsTomcatService windowsTomcatService) {
		this.instancesRoot = instancesRoot;
		this.pidsRoot = pidsRoot;
		this.catalinaHomeResolver = catalinaHomeResolver;
		this.startupTimeoutSec = startupTimeoutSec;
		this.configService = configService;
		this.nativeTomcatEnvironmentService = nativeTomcatEnvironmentService;
		this.windowsTomcatService = windowsTomcatService;
	}

	public StartResult start(ProjectConfig project) throws IOException, InterruptedException {
		String name = project.getName();
		if (status(name) == InstanceStatus.RUNNING) {
			return new StartResult(false, name, "實例已在運行中");
		}

		Path catalinaBase = Path.of(instancesRoot, name);
		Path catalinaHome = Path.of(resolveCatalinaHome(project));
		return startDetached(name, catalinaHome, catalinaBase,
				processBuilder -> applyJvmOpts(processBuilder, project),
				() -> stopViaCatalina(name));
	}

	public void stop(String projectName) throws IOException, InterruptedException {
		if (status(projectName) == InstanceStatus.STOPPED) {
			cleanup(projectName);
			return;
		}

		activeProcesses.remove(projectName);

		if (isHttpPortListening(projectName) || isPidAlive(projectName)) {
			stopViaCatalina(projectName);
			waitForPortClosed(projectName, 15);
		}

		forceKillIfAlive(projectName);
		cleanup(projectName);
	}

	public StartResult restart(ProjectConfig project) throws IOException, InterruptedException {
		stop(project.getName());
		Thread.sleep(1000);
		return start(project);
	}

	public InstanceStatus status(String projectName) {
		try {
			Process tracked = activeProcesses.get(projectName);
			if (tracked != null && tracked.isAlive()) {
				return InstanceStatus.RUNNING;
			}

			try {
				long pid = readPid(projectName);
				if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
					return InstanceStatus.RUNNING;
				}
			}
			catch (IOException ignored) {
				// no pid file
			}

			if (isHttpPortListening(projectName)) {
				return InstanceStatus.RUNNING;
			}
			return InstanceStatus.STOPPED;
		}
		catch (IOException e) {
			return InstanceStatus.STOPPED;
		}
	}

	public List<String> getLastLines(String projectName, int lines) throws IOException {
		Path catalinaBase = Path.of(instancesRoot, projectName);
		Path logFile = resolveLogFile(catalinaBase);
		if (!Files.exists(logFile)) {
			return List.of();
		}
		return getLastLines(logFile, lines);
	}

	public StartResult startTomcatInstance(TmamConfig config, String instanceId) throws IOException, InterruptedException {
		TomcatInstanceConfig instance = config.requireInstance(instanceId);
		log.info("[startTomcatInstance] instance={}", instanceId);
		if (tomcatInstanceStatus(config, instanceId) == InstanceStatus.RUNNING) {
			return StartResult.failure(instanceId, "Tomcat 實例已在運行中");
		}

		Path catalinaHome = Path.of(resolveInstanceCatalinaHome(instance));
		nativeTomcatEnvironmentService.ensureWritable(instanceId, catalinaHome.toString());
		Path catalinaBase = nativeTomcatEnvironmentService.getCatalinaBase(catalinaHome.toString());
		log.info("[startTomcatInstance] catalinaHome={}, catalinaBase={}", catalinaHome, catalinaBase);

		Optional<String> windowsService = windowsTomcatService.findServiceName(catalinaHome);
		if (windowsService.isPresent()) {
			log.info("[startTomcatInstance] 以 Windows 服務 {} 啟動（與「服務」開的是同一台）", windowsService.get());
			long logOffset = currentLogSize(catalinaBase);
			windowsTomcatService.start(windowsService.get());
			StartResult result = monitorStartup(instanceId, catalinaBase, logOffset,
					() -> !windowsTomcatService.isRunning(windowsService.get()));
			if (result.success()) {
				writeManagedMarker(instanceId);
			}
			else if (!isTimeout(result)) {
				windowsTomcatService.stop(windowsService.get());
			}
			else {
				writeManagedMarker(instanceId);
				log.warn("[startTomcatInstance] {} 等待啟動標記逾時，行程仍在運行，不強制停止", instanceId);
			}
			return new StartResult(result.success(), instanceId, result.message());
		}

		StartResult result = startDetached(instanceId, catalinaHome, catalinaBase,
				processBuilder -> applyInstanceJvmOpts(processBuilder, config, instance),
				() -> stopInstanceViaCatalina(config, instanceId));
		return new StartResult(result.success(), instanceId, result.message());
	}

	public void stopTomcatInstance(TmamConfig config, String instanceId) throws IOException, InterruptedException {
		InstanceStatus status = tomcatInstanceStatus(config, instanceId);
		log.info("[stopTomcatInstance] instance={}, status={}", instanceId, status);
		if (status == InstanceStatus.STOPPED) {
			cleanup(instanceId);
			return;
		}

		activeProcesses.remove(instanceId);
		TomcatInstanceConfig instance = config.requireInstance(instanceId);
		Path catalinaHome = Path.of(resolveInstanceCatalinaHome(instance));

		Optional<String> windowsService = windowsTomcatService.findServiceName(catalinaHome);
		if (windowsService.isPresent()) {
			log.info("[stopTomcatInstance] 以 Windows 服務 {} 停止", windowsService.get());
			windowsTomcatService.stop(windowsService.get());
			waitForInstanceServicesClosed(config, instanceId, 30);
			cleanup(instanceId);
			return;
		}

		if (isAnyEnabledServiceListening(instance) || isPidAlive(instanceId)) {
			stopInstanceViaCatalina(config, instanceId);
			waitForInstanceServicesClosed(config, instanceId, 30);
		}

		forceKillIfAlive(instanceId);
		cleanup(instanceId);
	}

	public InstanceStatus tomcatInstanceStatus(TmamConfig config, String instanceId) {
		try {
			Process tracked = activeProcesses.get(instanceId);
			if (tracked != null && tracked.isAlive()) {
				return InstanceStatus.RUNNING;
			}

			try {
				long pid = readPid(instanceId);
				if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
					return InstanceStatus.RUNNING;
				}
			}
			catch (IOException ignored) {
				// no pid file
			}

			TomcatInstanceConfig instance = config.getTomcatInstances().get(instanceId);
			if (instance != null && isAnyEnabledServiceListening(instance)) {
				return InstanceStatus.RUNNING;
			}
			return InstanceStatus.STOPPED;
		}
		catch (Exception e) {
			return InstanceStatus.STOPPED;
		}
	}

	public boolean isExternallyManaged(TmamConfig config, String instanceId, InstanceStatus tomcatStatus) {
		if (tomcatStatus != InstanceStatus.RUNNING) {
			return false;
		}
		if (isManagedByTmam(instanceId)) {
			return false;
		}
		TomcatInstanceConfig instance = config.getTomcatInstances().get(instanceId);
		if (instance == null) {
			return false;
		}
		Path catalinaHome = Path.of(resolveInstanceCatalinaHome(instance));
		if (windowsTomcatService.findServiceName(catalinaHome).isPresent()) {
			return false;
		}
		return isAnyEnabledServiceListening(instance);
	}

	public List<String> getTomcatInstanceLogs(String instanceId, int lines) throws IOException {
		TmamConfig config = configService.load();
		TomcatInstanceConfig instance = config.requireInstance(instanceId);
		Path catalinaBase = nativeTomcatEnvironmentService.getCatalinaBase(
				resolveInstanceCatalinaHome(instance));
		return getLastLines(resolveLogFile(catalinaBase), lines);
	}

	/** @deprecated 使用 {@link #startTomcatInstance} */
	public StartResult startNativeTomcat(TmamConfig config) throws IOException, InterruptedException {
		return startTomcatInstance(config, TomcatInstanceConfig.DEFAULT_ID);
	}

	/** @deprecated 使用 {@link #stopTomcatInstance} */
	public void stopNativeTomcat(TmamConfig config) throws IOException, InterruptedException {
		stopTomcatInstance(config, TomcatInstanceConfig.DEFAULT_ID);
	}

	/** @deprecated 使用 {@link #tomcatInstanceStatus} */
	public InstanceStatus nativeTomcatStatus(TmamConfig config) {
		return tomcatInstanceStatus(config, TomcatInstanceConfig.DEFAULT_ID);
	}

	/** @deprecated */
	public boolean isExternallyManaged(TmamConfig config, InstanceStatus tomcatStatus) {
		return isExternallyManaged(config, TomcatInstanceConfig.DEFAULT_ID, tomcatStatus);
	}

	/** @deprecated */
	public List<String> getNativeTomcatLogs(int lines) throws IOException {
		return getTomcatInstanceLogs(TomcatInstanceConfig.DEFAULT_ID, lines);
	}

	private boolean isAnyEnabledServiceListening(TomcatInstanceConfig instance) {
		if (instance.getServices() == null || instance.getServices().isEmpty()) {
			return false;
		}
		return instance.getServices().values().stream()
				.filter(TomcatServiceConfig::isEnabled)
				.anyMatch(service -> {
					if (service.isPathProxy()) {
						return isServicePortListening("127.0.0.1", instance.getGatewayPort());
					}
					return isServicePortListening(service.getAddress(), service.getPort());
				});
	}

	private void stopInstanceViaCatalina(TmamConfig config, String instanceId) throws IOException, InterruptedException {
		TomcatInstanceConfig instance = config.requireInstance(instanceId);
		Path catalinaHome = Path.of(resolveInstanceCatalinaHome(instance));
		Path catalinaBase = nativeTomcatEnvironmentService.getCatalinaBase(catalinaHome.toString());
		Path script = isWindows()
				? catalinaHome.resolve("bin/catalina.bat")
				: catalinaHome.resolve("bin/catalina.sh");

		ProcessBuilder processBuilder = new ProcessBuilder(script.toString(), "stop");
		processBuilder.environment().put("CATALINA_HOME", catalinaHome.toString());
		processBuilder.environment().put("CATALINA_BASE", catalinaBase.toString());
		applyInstanceJvmOpts(processBuilder, config, instance);
		Process stopProcess = processBuilder.start();
		stopProcess.waitFor(30, TimeUnit.SECONDS);
	}

	private void waitForInstanceServicesClosed(TmamConfig config, String instanceId, int timeoutSec)
			throws InterruptedException {
		TomcatInstanceConfig instance = config.requireInstance(instanceId);
		long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
		while (System.currentTimeMillis() < deadline) {
			if (!isAnyEnabledServiceListening(instance)) {
				return;
			}
			Thread.sleep(500);
		}
	}

	private void applyInstanceJvmOpts(ProcessBuilder processBuilder, TmamConfig config,
			TomcatInstanceConfig instance) {
		String jvmOpts = instance.getJvmOpts();
		if (jvmOpts == null || jvmOpts.isBlank()) {
			jvmOpts = config.getDefaults() != null ? config.getDefaults().getJvmOpts() : null;
		}
		if (jvmOpts != null && !jvmOpts.isBlank()) {
			processBuilder.environment().put("JAVA_OPTS", jvmOpts);
			processBuilder.environment().put("CATALINA_OPTS", jvmOpts);
		}
	}

	private String resolveInstanceCatalinaHome(TomcatInstanceConfig instance) {
		return catalinaHomeResolver.resolve(instance.getCatalinaHome());
	}

	public boolean isServicePortListening(String address, int port) {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(address, port), 500);
			return true;
		}
		catch (IOException e) {
			return false;
		}
	}

	public boolean isPathProxyHealthy(String upstreamHost, int gatewayPort, String pathPrefix) {
		// 只探 TCP，不要 GET 應用首頁：portal 的 Fail-to-Ban 會把 127.0.0.1 封鎖，
		// 接著 sendRedirect("http://"+ip) 讓瀏覽器落到 Nginx 歡迎頁。
		return isServicePortListening(upstreamHost, gatewayPort);
	}

	private boolean isHttpPortListening(String projectName) throws IOException {
		ProjectConfig project = configService.load().getProjects().get(projectName);
		if (project == null || project.getPorts() == null) {
			return false;
		}
		int port = project.getPorts().http();
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
			return true;
		}
		catch (IOException e) {
			return false;
		}
	}

	private StartResult startDetached(String name, Path catalinaHome, Path catalinaBase,
			Consumer<ProcessBuilder> configure, StopAction onFailureStop)
			throws IOException, InterruptedException {
		Path script = isWindows()
				? catalinaHome.resolve("bin/catalina.bat")
				: catalinaHome.resolve("bin/catalina.sh");
		Path pidFile = Path.of(pidsRoot, name + ".pid");

		ProcessBuilder processBuilder;
		if (isWindows()) {
			// 獨立行程啟動，關閉 TMAM 後 Tomcat 仍可繼續運行
			processBuilder = new ProcessBuilder(
					"cmd.exe", "/c", "start", "\"Tomcat\"", "/MIN", script.toString(), "run");
		}
		else {
			processBuilder = new ProcessBuilder(script.toString(), "start");
			processBuilder.environment().put("CATALINA_PID", pidFile.toAbsolutePath().toString());
		}
		processBuilder.environment().put("CATALINA_HOME", catalinaHome.toString());
		processBuilder.environment().put("CATALINA_BASE", catalinaBase.toString());
		configure.accept(processBuilder);
		processBuilder.redirectErrorStream(true);
		processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);

		long logOffset = currentLogSize(catalinaBase);
		Process launcher = processBuilder.start();
		launcher.waitFor(10, TimeUnit.SECONDS);

		StartResult result = monitorStartup(name, catalinaBase, logOffset, () -> isPidFileDead(name));
		if (result.success()) {
			writeManagedMarker(name);
			if (!isWindows() && Files.exists(pidFile)) {
				log.info("[startDetached] {} JVM pid={}", name, Files.readString(pidFile).trim());
			}
		}
		else if (!isTimeout(result)) {
			onFailureStop.run();
		}
		else {
			writeManagedMarker(name);
			log.warn("[startDetached] {} 等待啟動標記逾時，行程仍在運行，不強制停止", name);
		}
		return result;
	}

	private boolean isManagedByTmam(String name) {
		Process tracked = activeProcesses.get(name);
		if (tracked != null && tracked.isAlive()) {
			return true;
		}
		if (Files.exists(managedMarkerPath(name))) {
			return true;
		}
		return isPidAlive(name);
	}

	private boolean isPidAlive(String name) {
		try {
			long pid = readPid(name);
			return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
		}
		catch (IOException ignored) {
			return false;
		}
	}

	private void forceKillIfAlive(String name) throws InterruptedException {
		try {
			long pid = readPid(name);
			Optional<ProcessHandle> handle = ProcessHandle.of(pid);
			if (handle.isPresent() && handle.get().isAlive()) {
				handle.get().destroy();
				if (!waitForExit(handle.get(), 10)) {
					handle.get().destroyForcibly();
				}
			}
		}
		catch (IOException ignored) {
			// no pid file
		}
	}

	private Path managedMarkerPath(String name) {
		return Path.of(pidsRoot, name + MANAGED_SUFFIX);
	}

	private void writeManagedMarker(String name) throws IOException {
		Path pidsPath = Path.of(pidsRoot);
		Files.createDirectories(pidsPath);
		Files.writeString(managedMarkerPath(name), String.valueOf(System.currentTimeMillis()));
	}

	private StartResult monitorStartup(String name, Path catalinaBase, long logOffsetBeforeStart)
			throws InterruptedException, IOException {
		return monitorStartup(name, catalinaBase, logOffsetBeforeStart, () -> false);
	}

	private StartResult monitorStartup(String name, Path catalinaBase, long logOffsetBeforeStart,
			BooleanSupplier aborted) throws InterruptedException, IOException {
		boolean unlimited = startupTimeoutSec <= 0;
		long startedAt = System.currentTimeMillis();
		long deadline = unlimited ? Long.MAX_VALUE : startedAt + startupTimeoutSec * 1000L;
		Path logFile = resolveLogFile(catalinaBase);
		log.info("[monitorStartup] 監控 {} 啟動, timeout={}, logFile={}, offset={}",
				name, unlimited ? "unlimited" : startupTimeoutSec + "s", logFile, logOffsetBeforeStart);

		long lastProgressLog = startedAt;
		while (unlimited || System.currentTimeMillis() < deadline) {
			if (hasNewStartupMarker(logFile, logOffsetBeforeStart)) {
				log.info("[monitorStartup] {} 從日誌檔偵測到啟動成功（{}秒）",
						name, (System.currentTimeMillis() - startedAt) / 1000);
				return StartResult.success(name);
			}
			if (aborted != null && aborted.getAsBoolean()) {
				List<String> lastLogs = getLastLines(logFile, 20);
				String crashHint = recentJvmCrashHint(catalinaBase);
				log.error("[monitorStartup] {} 啟動過程中行程已結束, logFile={}, 最後 {} 行日誌{}",
						name, logFile, lastLogs.size(), crashHint == null ? "" : "，" + crashHint);
				String message = "啟動過程中行程已結束。";
				if (crashHint != null) {
					message += crashHint;
				}
				message += "\n最後日誌：\n" + String.join("\n", lastLogs);
				return StartResult.failure(name, message);
			}
			long now = System.currentTimeMillis();
			if (now - lastProgressLog >= 15_000L) {
				log.info("[monitorStartup] {} 仍在啟動中（已等待 {} 秒）", name, (now - startedAt) / 1000);
				lastProgressLog = now;
			}
			Thread.sleep(300);
		}

		List<String> lastLogs = getLastLines(logFile, 20);
		log.warn("[monitorStartup] {} 啟動等待逾時（{}秒）但不會強制停止, logFile={}, 最後 {} 行日誌",
				name, startupTimeoutSec, logFile, lastLogs.size());
		return StartResult.timeout(name, lastLogs);
	}

	private static boolean isTimeout(StartResult result) {
		return result != null && !result.success() && result.message() != null
				&& result.message().startsWith("啟動超時");
	}

	private boolean isPidFileDead(String name) {
		try {
			long pid = readPid(name);
			return ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
		}
		catch (IOException noPidYet) {
			return false;
		}
	}

	static boolean hasNewStartupMarker(Path logFile, long offsetBeforeStart) throws IOException {
		if (!Files.exists(logFile)) {
			return false;
		}
		long size = Files.size(logFile);
		long offset = size < offsetBeforeStart ? 0 : offsetBeforeStart;
		if (size <= offset) {
			return false;
		}
		long length = size - offset;
		int toRead = (int) Math.min(length, 2 * 1024 * 1024);
		long startPos = size - toRead;
		byte[] chunk = new byte[toRead];
		try (java.nio.channels.SeekableByteChannel channel = Files.newByteChannel(logFile,
				java.nio.file.StandardOpenOption.READ)) {
			channel.position(startPos);
			java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(chunk);
			while (buffer.hasRemaining()) {
				if (channel.read(buffer) < 0) {
					break;
				}
			}
			String text = new String(chunk, 0, buffer.position(), java.nio.charset.StandardCharsets.UTF_8);
			return text.contains(SUCCESS_MARKER);
		}
	}

	private long currentLogSize(Path catalinaBase) {
		try {
			Path logFile = resolveLogFile(catalinaBase);
			return Files.exists(logFile) ? Files.size(logFile) : 0L;
		}
		catch (IOException e) {
			return 0L;
		}
	}

	private void stopViaCatalina(String projectName) throws IOException, InterruptedException {
		ProjectConfig project = configService.load().getProjects().get(projectName);
		if (project == null) {
			return;
		}

		Path catalinaBase = Path.of(instancesRoot, projectName);
		Path catalinaHome = Path.of(resolveCatalinaHome(project));
		Path script = isWindows()
				? catalinaHome.resolve("bin/catalina.bat")
				: catalinaHome.resolve("bin/catalina.sh");

		ProcessBuilder processBuilder = new ProcessBuilder(script.toString(), "stop");
		processBuilder.environment().put("CATALINA_HOME", catalinaHome.toString());
		processBuilder.environment().put("CATALINA_BASE", catalinaBase.toString());
		applyJvmOpts(processBuilder, project);
		Process stopProcess = processBuilder.start();
		stopProcess.waitFor(30, TimeUnit.SECONDS);
	}

	private void waitForPortClosed(String projectName, int timeoutSec) throws InterruptedException, IOException {
		long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
		while (System.currentTimeMillis() < deadline) {
			if (!isHttpPortListening(projectName)) {
				return;
			}
			Thread.sleep(500);
		}
	}

	private void cleanup(String projectName) throws IOException {
		activeProcesses.remove(projectName);
		Files.deleteIfExists(Path.of(pidsRoot, projectName + ".pid"));
		Files.deleteIfExists(managedMarkerPath(projectName));
	}

	private void applyJvmOpts(ProcessBuilder processBuilder, ProjectConfig project) {
		String jvmOpts = project.getJvmOpts();
		if (jvmOpts == null || jvmOpts.isBlank()) {
			try {
				jvmOpts = configService.load().getDefaults().getJvmOpts();
			}
			catch (IOException ignored) {
				jvmOpts = "";
			}
		}
		if (jvmOpts != null && !jvmOpts.isBlank()) {
			processBuilder.environment().put("JAVA_OPTS", jvmOpts);
			processBuilder.environment().put("CATALINA_OPTS", jvmOpts);
		}
	}

	private String resolveCatalinaHome(ProjectConfig project) {
		return catalinaHomeResolver.resolve(project.getCatalinaHome());
	}

	private Path resolveLogFile(Path catalinaBase) throws IOException {
		Path logsDir = catalinaBase.resolve("logs");
		Path catalinaOut = logsDir.resolve("catalina.out");
		if (!Files.exists(logsDir)) {
			return catalinaOut;
		}
		if (Files.exists(catalinaOut)) {
			return catalinaOut;
		}

		try (Stream<Path> files = Files.list(logsDir)) {
			return files
					.filter(path -> {
						String fileName = path.getFileName().toString();
						return fileName.startsWith("catalina.") && fileName.endsWith(".log");
					})
					.max(Comparator.comparingLong(path -> {
						try {
							return Files.getLastModifiedTime(path).toMillis();
						}
						catch (IOException e) {
							return 0L;
						}
					}))
					.orElse(catalinaOut);
		}
	}

	static String jvmCrashHint(String hsErrText) {
		if (hsErrText == null || hsErrText.isBlank()) {
			return null;
		}
		if (hsErrText.contains("insufficient memory") || hsErrText.contains("Out of Memory Error")) {
			return "原因是 Java 原生記憶體不足，Tomcat 行程已崩潰。請先關閉其他佔用記憶體的程式，或調低 Tomcat 服務的 -Xmx 後再啟動。";
		}
		if (hsErrText.contains("A fatal error has been detected")) {
			return "原因是 Java 行程異常終止。請查看 Tomcat 目錄下的 hs_err_pid 記錄。";
		}
		return null;
	}

	private String recentJvmCrashHint(Path catalinaBase) {
		if (catalinaBase == null || !Files.isDirectory(catalinaBase)) {
			return null;
		}
		long cutoff = System.currentTimeMillis() - 60_000L;
		try (Stream<Path> files = Files.list(catalinaBase)) {
			Optional<Path> newest = files
					.filter(path -> {
						String fileName = path.getFileName().toString();
						return fileName.startsWith("hs_err_pid") && fileName.endsWith(".log");
					})
					.filter(path -> {
						try {
							return Files.getLastModifiedTime(path).toMillis() >= cutoff;
						}
						catch (IOException e) {
							return false;
						}
					})
					.max(Comparator.comparingLong(path -> {
						try {
							return Files.getLastModifiedTime(path).toMillis();
						}
						catch (IOException e) {
							return 0L;
						}
					}));
			if (newest.isEmpty()) {
				return null;
			}
			String text = Files.readString(newest.get());
			if (text.length() > 4000) {
				text = text.substring(0, 4000);
			}
			return jvmCrashHint(text);
		}
		catch (IOException e) {
			return null;
		}
	}

	private List<String> getLastLines(Path logFile, int lines) throws IOException {
		return LogFileReader.readLastLines(logFile, lines);
	}

	private long readPid(String name) throws IOException {
		return Long.parseLong(Files.readString(Path.of(pidsRoot, name + ".pid")).trim());
	}

	private boolean waitForExit(ProcessHandle handle, int timeoutSec) throws InterruptedException {
		try {
			handle.onExit().get(timeoutSec, TimeUnit.SECONDS);
			return !handle.isAlive();
		}
		catch (ExecutionException | TimeoutException e) {
			return false;
		}
	}

	private boolean isWindows() {
		return System.getProperty("os.name").toLowerCase().contains("win");
	}

	@FunctionalInterface
	private interface StopAction {
		void run() throws IOException, InterruptedException;
	}

}
