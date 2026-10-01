package com.tmam.config;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 入口頁不能快取，否則 Electron 會一直用舊的 index.html，連到上一版前端。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SpaCacheControlFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String path = request.getRequestURI();
		if (path == null || "/".equals(path) || path.endsWith(".html")) {
			response.setHeader("Cache-Control", "no-store");
		}
		filterChain.doFilter(request, response);
	}

}
