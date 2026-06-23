package com.tmam.model;

import java.util.List;

public record StartResult(boolean success, String projectName, String message) {

	public static StartResult success(String name) {
		return new StartResult(true, name, "啟動成功");
	}

	public static StartResult timeout(String name, List<String> lastLogs) {
		return new StartResult(false, name, "啟動超時，最後日誌：\n" + String.join("\n", lastLogs));
	}

	public static StartResult failure(String name, String message) {
		return new StartResult(false, name, message);
	}

}
