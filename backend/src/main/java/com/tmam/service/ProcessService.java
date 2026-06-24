package com.tmam.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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

	private final String instancesRoot;
	private final String pidsRoot;
	private final String defaultCatalinaHome;
	private final ConfigService configService;
	private final int startupTimeoutSec;
	private final NativeTomcatEnvironmentService nativeTomcatEnvironmentService;

	private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();

	public ProcessService(@Value("${tmam.instances-root}") String instancesRoot,
			@Value("${tmam.pids-root}") String pidsRoot,
			@Value("${tmam.default-catalina-home}") String defaultCatalinaHome,
			@Value("${tmam.startup-timeout-sec:30}") int startupTimeoutSec,
			ConfigService configService,
			NativeTomcatEnvironmentService nativeTomcatEnvironmentService) {
		this.instancesRoot = instancesRoot;
		this.pidsRoot = pidsRoot;
		this.defaultCatalinaHome = defaultCatalinaHome;
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
		boolean windows = isWindows();

		// catalina run 可取得正確 JVM PID（Windows 上 start 會 fork 子進程）
		Path script = windows
				? catalinaHome.resolve("bin/catalina.bat")
				: catalinaHome.resolve("bin/catalina.sh");

		ProcessBuilder processBuilder = new ProcessBuilder(script.toString(), "run");
		processBuilder.environment().put("CATALINA_HOME", catalinaHome.toString());
		processBuilder.environment().put("CATALINA_BASE", catalinaBase.toString());
		applyJvmOpts(processBuilder, project);
		processBuilder.redirectErrorStream(true);

		Process process = processBuilder.start();
		activeProcesses.put(name, process);
		writePid(name, process.pid());

		AtomicBoolean started = new AtomicBoolean(false);
		Thread outputWatcher = new Thread(() -> watchProcessOutput(process, started));
		outputWatcher.setDaemon(true);
		outputWatcher.start();

		StartResult result = monitorStartup(name, catalinaBase, started);
		if (!result.success()) {
			stopProcess(name, process);
		}
		return result;
	}

	public void stop(String projectName) throws IOException, InterruptedException {
		if (status(projectName) == InstanceStatus.STOPPED) {
			cleanup(projectName);
			return;
		}

		Process tracked = activeProcesses.get(projectName);
		if (tracked != null && tracked.isAlive()) {
			stopProcess(projectName, tracked);
		}

		try {
			long pid = readPid(projectName);
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

		if (isHttpPortListening(projectName)) {
			stopViaCatalina(projectName);
			waitForPortClosed(projectName, 15);
		}

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
		boolean windows = isWindows();
		log.info("[startTomcatInstance] catalinaHome={}, catalinaBase={}", catalinaHome, catalinaBase);

		Path script = windows
				? catalinaHome.resolve("bin/catalina.bat")
				: catalinaHome.resolve("bin/catalina.sh");

		ProcessBuilder processBuilder = new ProcessBuilder(script.toString(), "run");
		processBuilder.environment().put("CATALINA_HOME", catalinaHome.toString());
		processBuilder.environment().put("CATALINA_BASE", catalinaBase.toString());
		applyInstanceJvmOpts(processBuilder, config, instance);
		processBuilder.redirectErrorStream(true);

		Process process = processBuilder.start();
		long pid = process.pid();
		activeProcesses.put(instanceId, process);
		writePid(instanceId, pid);

		AtomicBoolean started = new AtomicBoolean(false);
		Thread outputWatcher = new Thread(() -> watchProcessOutput(process, started));
		outputWatcher.setDaemon(true);
		outputWatcher.start();

		StartResult result = monitorStartup(instanceId, catalinaBase, started);
		if (!result.success()) {
			stopProcess(instanceId, process);
		}
		return new StartResult(result.success(), instanceId, result.message());
	}

	public void stopTomcatInstance(TmamConfig config, String instanceId) throws IOException, InterruptedException {
		InstanceStatus status = tomcatInstanceStatus(config, instanceId);
		log.info("[stopTomcatInstance] instance={}, status={}", instanceId, status);
		if (status == InstanceStatus.STOPPED) {
			cleanup(instanceId);
			return;
		}

		Process tracked = activeProcesses.get(instanceId);
		if (tracked != null && tracked.isAlive()) {
			stopProcess(instanceId, tracked);
		}

		try {
			long pid = readPid(instanceId);
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

		if (isAnyEnabledServiceListening(config.requireInstance(instanceId))) {
			stopInstanceViaCatalina(config, instanceId);
			waitForInstanceServicesClosed(config, instanceId, 30);
		}

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
		Process tracked = activeProcesses.get(instanceId);
		if (tracked != null && tracked.isAlive()) {
			return false;
		}
		try {
			long pid = readPid(instanceId);
			if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
				return false;
			}
		}
		catch (IOException ignored) {
			// no pid file
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
		if (instance.getCatalinaHome() != null && !instance.getCatalinaHome().isBlank()) {
			return instance.getCatalinaHome();
		}
		return defaultCatalinaHome;
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

	private StartResult monitorStartup(String name, Path catalinaBase, AtomicBoolean startedFromOutput)
			throws InterruptedException, IOException {
		long deadline = System.currentTimeMillis() + startupTimeoutSec * 1000L;
		Path logFile = resolveLogFile(catalinaBase);
		log.debug("[monitorStartup] 監控 {} 啟動, logFile={}", name, logFile);

		while (System.currentTimeMillis() < deadline) {
			if (startedFromOutput.get()) {
				log.info("[monitorStartup] {} 從進程輸出偵測到啟動成功", name);
				return StartResult.success(name);
			}

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

	private void watchProcessOutput(Process process, AtomicBoolean started) {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				log.debug("[catalina] {}", line);
				if (line.contains(SUCCESS_MARKER)) {
					started.set(true);
					break;
				}
			}
		}
		catch (IOException e) {
			log.debug("[catalina] 進程輸出已關閉");
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

	private void stopProcess(String projectName, Process process) throws InterruptedException, IOException {
		if (process.isAlive()) {
			process.destroy();
			if (!waitForProcessExit(process, 10)) {
				process.destroyForcibly();
			}
		}
		activeProcesses.remove(projectName);
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
		if (project.getCatalinaHome() != null && !project.getCatalinaHome().isBlank()) {
			return project.getCatalinaHome();
		}
		return defaultCatalinaHome;
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

	private void writePid(String name, long pid) throws IOException {
		Path pidsPath = Path.of(pidsRoot);
		Files.createDirectories(pidsPath);
		Files.writeString(pidsPath.resolve(name + ".pid"), String.valueOf(pid));
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

	private boolean waitForProcessExit(Process process, int timeoutSec) throws InterruptedException {
		return process.waitFor(timeoutSec, TimeUnit.SECONDS) && !process.isAlive();
	}

	private boolean isWindows() {
		return System.getProperty("os.name").toLowerCase().contains("win");
	}

}
