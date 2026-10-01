package com.tmam.service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 以 Windows 服務（例如 Tomcat9）啟停安裝目錄裡的那台 Tomcat，避免再起一顆 catalina.bat JVM。
 */
@Service
public class WindowsTomcatService {

	private static final Logger log = LoggerFactory.getLogger(WindowsTomcatService.class);

	public Optional<String> findServiceName(Path catalinaHome) {
		if (!isWindows() || catalinaHome == null) {
			return Optional.empty();
		}
		Path home = catalinaHome.toAbsolutePath().normalize();
		for (String candidate : candidateNames(home)) {
			Optional<String> binaryPath = queryBinaryPath(candidate);
			if (binaryPath.isPresent() && matchesHome(binaryPath.get(), home)) {
				log.info("[findServiceName] {} -> {}", home, candidate);
				return Optional.of(candidate);
			}
		}
		return Optional.empty();
	}

	public boolean isRunning(String serviceName) {
		return "RUNNING".equalsIgnoreCase(queryState(serviceName).orElse(""));
	}

	public void start(String serviceName) throws IOException, InterruptedException {
		if (isRunning(serviceName)) {
			log.info("[start] {} 已在執行，先停止再啟動以套用設定", serviceName);
			stop(serviceName);
		}
		log.info("[start] sc start {}", serviceName);
		CommandResult result = runSc("start", serviceName);
		if (indicatesAlreadyRunning(result.output())) {
			log.info("[start] {} 回報已在執行，先停止再啟動以套用設定", serviceName);
			stop(serviceName);
			result = runSc("start", serviceName);
		}
		if (result.exitCode() != 0 && !indicatesAlreadyRunning(result.output())) {
			throw new IOException(describeFailure("啟動", serviceName, result.output()));
		}
		waitForState(serviceName, "RUNNING", 60);
	}

	public void stop(String serviceName) throws IOException, InterruptedException {
		log.info("[stop] sc stop {}", serviceName);
		CommandResult result = runSc("stop", serviceName);
		if (result.exitCode() != 0 && !indicatesNotStarted(result.output())) {
			throw new IOException(describeFailure("停止", serviceName, result.output()));
		}
		waitForState(serviceName, "STOPPED", 60);
	}

	static boolean indicatesAlreadyRunning(String output) {
		if (output == null || output.isBlank()) {
			return false;
		}
		String lower = output.toLowerCase(Locale.ROOT);
		return lower.contains("1056")
				|| lower.contains("already running")
				|| lower.contains("already been started")
				|| output.contains("已在執行");
	}

	static boolean indicatesNotStarted(String output) {
		if (output == null || output.isBlank()) {
			return false;
		}
		String lower = output.toLowerCase(Locale.ROOT);
		return lower.contains("1062")
				|| lower.contains("not been started")
				|| lower.contains("is not started")
				|| output.contains("尚未啟動");
	}

	static boolean indicatesAccessDenied(String output) {
		if (output == null || output.isBlank()) {
			return false;
		}
		String lower = output.toLowerCase(Locale.ROOT);
		return lower.contains("access is denied")
				|| output.contains("存取被拒")
				|| output.contains("拒絕存取")
				|| lower.contains("failed 5:")
				|| output.contains("失敗 5:");
	}

	static String describeFailure(String action, String serviceName, String output) {
		String detail = output == null ? "" : output.trim().replaceAll("\\s+", " ");
		String message = "無法" + action + " Windows 服務 " + serviceName;
		if (!detail.isEmpty()) {
			message += "：" + detail;
		}
		if (indicatesAccessDenied(detail)) {
			message += "。" + NativeTomcatEnvironmentService.ADMIN_REQUIRED_HINT;
		}
		return message;
	}

	static boolean matchesHome(String binaryPath, Path catalinaHome) {
		if (binaryPath == null || binaryPath.isBlank() || catalinaHome == null) {
			return false;
		}
		String path = binaryPath.replace("/", "\\").toLowerCase(Locale.ROOT);
		String home = catalinaHome.toAbsolutePath().normalize().toString().replace("/", "\\").toLowerCase(Locale.ROOT);
		return path.contains(home);
	}

	private List<String> candidateNames(Path catalinaHome) {
		Set<String> names = new LinkedHashSet<>();
		names.add("Tomcat9");
		names.add("Tomcat8");
		names.add("Tomcat10");
		Path bin = catalinaHome.resolve("bin");
		if (Files.isDirectory(bin)) {
			try (Stream<Path> files = Files.list(bin)) {
				files.map(path -> path.getFileName().toString())
						.filter(name -> name.toLowerCase(Locale.ROOT).matches("tomcat\\d*\\.exe"))
						.map(name -> name.substring(0, name.length() - 4))
						.forEach(names::add);
			}
			catch (IOException ignored) {
				// keep defaults
			}
		}
		return new ArrayList<>(names);
	}

	private Optional<String> queryBinaryPath(String serviceName) {
		try {
			CommandResult result = runSc("qc", serviceName);
			if (result.exitCode() != 0) {
				return Optional.empty();
			}
			for (String line : result.output().split("\\R")) {
				String trimmed = line.trim();
				if (trimmed.toUpperCase(Locale.ROOT).startsWith("BINARY_PATH_NAME")) {
					int colon = trimmed.indexOf(':');
					if (colon >= 0) {
						return Optional.of(trimmed.substring(colon + 1).trim());
					}
				}
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
		catch (IOException ignored) {
			return Optional.empty();
		}
		return Optional.empty();
	}

	private Optional<String> queryState(String serviceName) {
		try {
			CommandResult result = runSc("query", serviceName);
			if (result.exitCode() != 0) {
				return Optional.empty();
			}
			for (String line : result.output().split("\\R")) {
				String upper = line.toUpperCase(Locale.ROOT);
				if (upper.contains("RUNNING")) {
					return Optional.of("RUNNING");
				}
				if (upper.contains("STOPPED")) {
					return Optional.of("STOPPED");
				}
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
		catch (IOException ignored) {
			return Optional.empty();
		}
		return Optional.empty();
	}

	private void waitForState(String serviceName, String expected, int timeoutSec) throws InterruptedException, IOException {
		long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
		while (System.currentTimeMillis() < deadline) {
			if (expected.equalsIgnoreCase(queryState(serviceName).orElse(""))) {
				return;
			}
			Thread.sleep(500);
		}
		throw new IOException("等待 Windows 服務 " + serviceName + " 進入 " + expected + " 逾時");
	}

	private CommandResult runSc(String command, String serviceName) throws IOException, InterruptedException {
		Process process = new ProcessBuilder("sc.exe", command, serviceName)
				.redirectErrorStream(true)
				.start();
		String output = new String(process.getInputStream().readAllBytes(), scOutputCharset());
		process.waitFor(20, TimeUnit.SECONDS);
		return new CommandResult(process.exitValue(), output);
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	/** sc.exe 用系統原生字碼輸出，Java 18 之後預設 UTF-8 會把中文解成亂碼。 */
	private static Charset scOutputCharset() {
		String nativeEncoding = System.getProperty("native.encoding");
		if (nativeEncoding != null && !nativeEncoding.isBlank()) {
			try {
				return Charset.forName(nativeEncoding);
			}
			catch (Exception ignored) {
				// 落到 JVM 預設字集
			}
		}
		return Charset.defaultCharset();
	}

	private record CommandResult(int exitCode, String output) {
	}

}
