package com.tmam.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.tmam.util.LogFileReader;

@Service
public class AppLogService {

	private final String appLogPath;

	public AppLogService(@Value("${tmam.app-log-path}") String appLogPath) {
		this.appLogPath = appLogPath;
	}

	public List<String> getLastLines(int lines) throws IOException {
		Path logFile = Path.of(appLogPath);
		if (!Files.exists(logFile)) {
			return List.of("（尚無 TMAM 後端日誌，請先執行一次套用或啟動操作）");
		}
		return LogFileReader.readLastLines(logFile, lines);
	}

}
