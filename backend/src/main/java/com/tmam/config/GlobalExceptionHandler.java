package com.tmam.config;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
		log.warn("請求參數錯誤: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
		log.warn("狀態錯誤: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(java.nio.file.AccessDeniedException.class)
	public ResponseEntity<Map<String, String>> handleAccessDenied(java.nio.file.AccessDeniedException ex) {
		log.error("檔案權限不足: {}", ex.getFile(), ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Map.of("message", "無法寫入 " + ex.getFile()
						+ "。若 Tomcat 安裝在 Program Files，請確認 TMAM 已使用可寫入的 CATALINA_BASE（%USERPROFILE%\\.tmam\\native-tomcat）。"));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, String>> handleGeneral(Exception ex) {
		log.error("未處理的例外", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Map.of("message", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
	}

}
