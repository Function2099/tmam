package com.tmam.service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
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

	private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();

	public ProcessService(@Value("${tmam.instances-root}") String instancesRoot,
			@Value("${tmam.pids-root}") String pidsRoot,
			CatalinaHomeResolver catalinaHomeResolver,
			@Value("${tmam.startup-timeout-sec:30}") int startupTimeoutSec,
			ConfigService configService,
			NativeTomcatEnvironmentService nativeTomcatEnvironmentService) {
		this.instancesRoot = instancesRoot;
		this.pidsRoot = pidsRoot;
		this.catalinaHomeResolver = catalinaHomeResolver;
		this.startupTimeoutSec = startupTimeoutSec;
		this.configService = configService;
		this.nativeTomcatEnvironmentService = nativeTomcatEnvironmentService;
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
		nativeTomcatEnvironmentService.ensureInitialized(instanceId, catalinaHome.toString());
		Path catalinaBase = nativeTomcatEnvironmentService.getCatalinaBase(instanceId);
		log.info("[startTomcatInstance] catalinaHome={}, catalinaBase={}", catalinaHome, catalinaBase);

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
		return instance != null && isAnyEnabledServiceListening(instance);
	}

	public List<String> getTomcatInstanceLogs(String instanceId, int lines) throws IOException {
		Path catalinaBase = nativeTomcatEnvironmentService.getCatalinaBase(instanceId);
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
		Path catalinaBase = nativeTomcatEnvironmentService.getCatalinaBase(instanceId);
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
		String normalized = PathProxyValidator.normalizePathPrefix(pathPrefix);
		try {
			URL url = new URL("http://" + upstreamHost + ":" + gatewayPort + normalized + "/");
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setConnectTimeout(1000);
			connection.setReadTimeout(1000);
			connection.setRequestMethod("GET");
			connection.setInstanceFollowRedirects(false);
			int status = connection.getResponseCode();
			return status >= 200 && status < 400;
		}
		catch (IOException e) {
			return false;
		}
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

		Process launcher = processBuilder.start();
		launcher.waitFor(10, TimeUnit.SECONDS);

		StartResult result = monitorStartup(name, catalinaBase);
		if (result.success()) {
			writeManagedMarker(name);
			if (!isWindows() && Files.exists(pidFile)) {
				log.info("[startDetached] {} JVM pid={}", name, Files.readString(pidFile).trim());
			}
		}
		else {
			onFailureStop.run();
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

	private StartResult monitorStartup(String name, Path catalinaBase)
			throws InterruptedException, IOException {
		long deadline = System.currentTimeMillis() + startupTimeoutSec * 1000L;
		Path logFile = resolveLogFile(catalinaBase);
		log.debug("[monitorStartup] 監控 {} 啟動, logFile={}", name, logFile);

		while (System.currentTimeMillis() < deadline) {
			if (Files.exists(logFile)) {
				for (String line : LogFileReader.readLastLines(logFile, 200)) {
					if (line.contains(SUCCESS_MARKER)) {
						log.info("[monitorStartup] {} 從日誌檔偵測到啟動成功", name);
						return StartResult.success(name);
					}
				}
			}
			Thread.sleep(300);
		}

		List<String> lastLogs = getLastLines(logFile, 20);
		log.error("[monitorStartup] {} 啟動逾時（{}秒）, logFile={}, 最後 {} 行日誌",
				name, startupTimeoutSec, logFile, lastLogs.size());
		return StartResult.timeout(name, lastLogs);
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
