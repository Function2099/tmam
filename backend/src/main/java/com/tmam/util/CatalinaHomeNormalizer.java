package com.tmam.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CatalinaHomeNormalizer {

	private CatalinaHomeNormalizer() {
	}

	public static String comparisonKey(String catalinaHome) {
		if (catalinaHome == null || catalinaHome.isBlank()) {
			return "";
		}
		Path path = Path.of(catalinaHome.trim());
		try {
			if (Files.exists(path)) {
				path = path.toRealPath();
			}
			else {
				path = path.toAbsolutePath().normalize();
			}
		}
		catch (IOException e) {
			path = path.toAbsolutePath().normalize();
		}
		String normalized = path.toString();
		if (isWindows()) {
			return normalized.toLowerCase();
		}
		return normalized;
	}

	public static boolean isSame(String a, String b) {
		if (a == null || b == null || a.isBlank() || b.isBlank()) {
			return false;
		}
		if (comparisonKey(a).equals(comparisonKey(b))) {
			return true;
		}
		try {
			Path pa = Path.of(a.trim());
			Path pb = Path.of(b.trim());
			if (Files.exists(pa) && Files.exists(pb)) {
				return Files.isSameFile(pa, pb);
			}
		}
		catch (IOException ignored) {
			// fall through
		}
		return false;
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

}
