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
import com.tmam.model.TomcatServiceConfig;

@Service
public class ProcessService {

	private static final Logger log = LoggerFactory.getLogger(ProcessService.class);

	private static final String SUCCESS_MARKER = "Server startup in";
	private static final String NATIVE_TOMCAT_KEY = "tomcat";

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

	public StartResult startNativeTomcat(TmamConfig config) throws IOException, InterruptedException {
		log.info("[startNativeTomcat] 開始啟動 native Tomcat");
		if (nativeTomcatStatus(config) == InstanceStatus.RUNNING) {
			log.warn("[startNativeTomcat] 失敗：Tomcat 已在運行中");
			return StartResult.failure(NATIVE_TOMCAT_KEY, "Tomcat 已在運行中");
		}

		Path catalinaHome = Path.of(resolveNativeCatalinaHome(config));
		nativeTomcatEnvironmentService.ensureInitialized(catalinaHome.toString());
		Path catalinaBase = nativeTomcatEnvironmentService.getNativeCatalinaBase();
		boolean windows = isWindows();
		log.info("[startNativeTomcat] catalinaHome={}, catalinaBase={}, windows={}", catalinaHome, catalinaBase,
				windows);

		Path script = windows
				? catalinaHome.resolve("bin/catalina.bat")
				: catalinaHome.resolve("bin/catalina.sh");
		log.info("[startNativeTomcat] 執行腳本: {} run", script);

		ProcessBuilder processBuilder = new ProcessBuilder(script.toString(), "run");
		processBuilder.environment().put("CATALINA_HOME", catalinaHome.toString());
		processBuilder.environment().put("CATALINA_BASE", catalinaBase.toString());
		applyNativeJvmOpts(processBuilder, config);
		processBuilder.redirectErrorStream(true);

		Process process = processBuilder.start();
		long pid = process.pid();
		log.info("[startNativeTomcat] 進程已啟動, PID={}", pid);
		activeProcesses.put(NATIVE_TOMCAT_KEY, process);
		writePid(NATIVE_TOMCAT_KEY, pid);

		AtomicBoolean started = new AtomicBoolean(false);
		Thread outputWatcher = new Thread(() -> watchProcessOutput(process, started));
		outputWatcher.setDaemon(true);
		outputWatcher.start();

		log.info("[startNativeTomcat] 等待啟動完成（逾時 {} 秒）...", startupTimeoutSec);
		StartResult result = monitorStartup(NATIVE_TOMCAT_KEY, catalinaBase, started);
		if (!result.success()) {
			log.error("[startNativeTomcat] 啟動失敗: {}", result.message());
			stopProcess(NATIVE_TOMCAT_KEY, process);
		}
		else {
			log.info("[startNativeTomcat] 啟動成功");
		}
		return new StartResult(result.success(), NATIVE_TOMCAT_KEY, result.message());
	}

	public void stopNativeTomcat(TmamConfig config) throws IOException, InterruptedException {
		InstanceStatus status = nativeTomcatStatus(config);
		log.info("[stopNativeTomcat] 開始停止, 目前狀態={}", status);
		if (status == InstanceStatus.STOPPED) {
			log.info("[stopNativeTomcat] 已停止，清理 PID 檔");
			cleanup(NATIVE_TOMCAT_KEY);
			return;
		}

		Process tracked = activeProcesses.get(NATIVE_TOMCAT_KEY);
		if (tracked != null && tracked.isAlive()) {
			log.info("[stopNativeTomcat] 停止 TMAM 追蹤的進程 PID={}", tracked.pid());
			stopProcess(NATIVE_TOMCAT_KEY, tracked);
		}

		try {
			long pid = readPid(NATIVE_TOMCAT_KEY);
			Optional<ProcessHandle> handle = ProcessHandle.of(pid);
			if (handle.isPresent() && handle.get().isAlive()) {
				log.info("[stopNativeTomcat] 透過 PID 檔停止進程 PID={}", pid);
				handle.get().destroy();
				if (!waitForExit(handle.get(), 10)) {
					log.warn("[stopNativeTomcat] PID {} 未在 10 秒內結束，強制終止", pid);
					handle.get().destroyForcibly();
				}
			}
		}
		catch (IOException ignored) {
			log.debug("[stopNativeTomcat] 無 PID 檔");
		}

		if (isAnyEnabledServiceListening(config)) {
			log.info("[stopNativeTomcat] 仍有 Service 埠在監聽，執行 catalina stop");
			stopNativeViaCatalina(config);
			waitForNativeServicesClosed(config, 30);
			log.info("[stopNativeTomcat] catalina stop 完成");
		}

		cleanup(NATIVE_TOMCAT_KEY);
		log.info("[stopNativeTomcat] 停止完成");
	}

	public InstanceStatus nativeTomcatStatus(TmamConfig config) {
		try {
			Process tracked = activeProcesses.get(NATIVE_TOMCAT_KEY);
			if (tracked != null && tracked.isAlive()) {
				return InstanceStatus.RUNNING;
			}

			try {
				long pid = readPid(NATIVE_TOMCAT_KEY);
				if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
					return InstanceStatus.RUNNING;
				}
			}
			catch (IOException ignored) {
				// no pid file
			}

			if (isAnyEnabledServiceListening(config)) {
				return InstanceStatus.RUNNING;
			}
			return InstanceStatus.STOPPED;
		}
		catch (Exception e) {
			return InstanceStatus.STOPPED;
		}
	}

	public boolean isExternallyManaged(TmamConfig config, InstanceStatus tomcatStatus) {
		if (tomcatStatus != InstanceStatus.RUNNING) {
			return false;
		}
		Process tracked = activeProcesses.get(NATIVE_TOMCAT_KEY);
		if (tracked != null && tracked.isAlive()) {
			return false;
		}
		try {
			long pid = readPid(NATIVE_TOMCAT_KEY);
			if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
				return false;
			}
		}
		catch (IOException ignored) {
			// no pid file
		}
		return isAnyEnabledServiceListening(config);
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

	public List<String> getNativeTomcatLogs(int lines) throws IOException {
		Path catalinaBase = nativeTomcatEnvironmentService.getNativeCatalinaBase();
		return getLastLines(resolveLogFile(catalinaBase), lines);
	}

	private boolean isAnyEnabledServiceListening(TmamConfig config) {
		if (config.getServices() == null || config.getServices().isEmpty()) {
			return false;
		}
		return config.getServices().values().stream()
				.filter(TomcatServiceConfig::isEnabled)
				.anyMatch(service -> isServicePortListening(service.getAddress(), service.getPort()));
	}

	private void stopNativeViaCatalina(TmamConfig config) throws IOException, InterruptedException {
		Path catalinaHome = Path.of(resolveNativeCatalinaHome(config));
		Path catalinaBase = nativeTomcatEnvironmentService.getNativeCatalinaBase();
		Path script = isWindows()
				? catalinaHome.resolve("bin/catalina.bat")
				: catalinaHome.resolve("bin/catalina.sh");

		ProcessBuilder processBuilder = new ProcessBuilder(script.toString(), "stop");
		processBuilder.environment().put("CATALINA_HOME", catalinaHome.toString());
		processBuilder.environment().put("CATALINA_BASE", catalinaBase.toString());
		applyNativeJvmOpts(processBuilder, config);
		Process stopProcess = processBuilder.start();
		stopProcess.waitFor(30, TimeUnit.SECONDS);
	}

	private void waitForNativeServicesClosed(TmamConfig config, int timeoutSec) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
		while (System.currentTimeMillis() < deadline) {
			if (!isAnyEnabledServiceListening(config)) {
				return;
			}
			Thread.sleep(500);
		}
	}

	private void applyNativeJvmOpts(ProcessBuilder processBuilder, TmamConfig config) {
		String jvmOpts = config.getDefaults() != null ? config.getDefaults().getJvmOpts() : null;
		if (jvmOpts != null && !jvmOpts.isBlank()) {
			processBuilder.environment().put("JAVA_OPTS", jvmOpts);
			processBuilder.environment().put("CATALINA_OPTS", jvmOpts);
		}
	}

	private String resolveNativeCatalinaHome(TmamConfig config) {
		if (config.getCatalinaHome() != null && !config.getCatalinaHome().isBlank()) {
			return config.getCatalinaHome();
		}
		return defaultCatalinaHome;
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
