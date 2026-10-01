package com.tmam.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 加速 Tomcat 啟動：Service 平行啟動，並略過多餘的 JAR TLD / SPI 掃描。
 */
final class TomcatStartupOptimizer {

	static final String START_STOP_THREADS = "0";

	static final String JAR_SCANNER = """
			<JarScanner scanClassPath="false" scanManifest="false">
			          <JarScanFilter tldSkip="*.jar" pluggabilitySkip="*.jar"
			            tldScan="jstl*.jar,standard*.jar,taglibs-standard*.jar,jakarta.servlet.jsp.jstl*.jar"
			            pluggabilityScan="spring-web*.jar,spring-webmvc*.jar"/>
			        </JarScanner>""";

	private static final Pattern SERVER_TAG = Pattern.compile("<Server\\b([^>]*)>");
	private static final Pattern HOST_TAG = Pattern.compile("<Host\\b([^>]*?)/?>");
	private static final Pattern CONTEXT_CLOSE = Pattern.compile("</Context>");
	private static final Pattern CONTEXT_SELF_CLOSING = Pattern.compile("<Context\\b([^>]*)\\s*/>");

	private TomcatStartupOptimizer() {
	}

	static String optimizeServerHeader(String header) {
		if (header == null || header.isBlank()) {
			return header;
		}
		Matcher matcher = SERVER_TAG.matcher(header);
		if (!matcher.find()) {
			return header;
		}
		String replacement = "<Server" + upsertAttribute(matcher.group(1), "startStopThreads", START_STOP_THREADS)
				+ ">";
		return header.substring(0, matcher.start()) + replacement + header.substring(matcher.end());
	}

	static String optimizeServiceFragment(String fragment) {
		if (fragment == null || fragment.isBlank()) {
			return fragment;
		}
		String updated = upsertAttributeOnTags(fragment, HOST_TAG, "Host", "startStopThreads", START_STOP_THREADS);
		updated = upsertAttributeOnTags(updated, HOST_TAG, "Host", "autoDeploy", "false");
		updated = upsertAttributeOnTags(updated, HOST_TAG, "Host", "deployOnStartup", "false");
		return ensureJarScanner(updated);
	}

	private static String ensureJarScanner(String fragment) {
		if (fragment.contains("<JarScanner")) {
			return fragment;
		}
		if (CONTEXT_CLOSE.matcher(fragment).find()) {
			return CONTEXT_CLOSE.matcher(fragment)
					.replaceAll(Matcher.quoteReplacement("    " + JAR_SCANNER + "\n        </Context>"));
		}
		Matcher selfClosing = CONTEXT_SELF_CLOSING.matcher(fragment);
		if (selfClosing.find()) {
			return selfClosing.replaceAll(
					"<Context$1>\n          " + Matcher.quoteReplacement(JAR_SCANNER) + "\n        </Context>");
		}
		return fragment;
	}

	private static String upsertAttributeOnTags(String xml, Pattern tagPattern, String tagName, String attrName,
			String value) {
		Matcher matcher = tagPattern.matcher(xml);
		StringBuilder result = new StringBuilder();
		int last = 0;
		while (matcher.find()) {
			result.append(xml, last, matcher.start());
			String original = matcher.group(0);
			boolean selfClosing = original.endsWith("/>");
			result.append('<').append(tagName)
					.append(upsertAttribute(matcher.group(1), attrName, value))
					.append(selfClosing ? "/>" : ">");
			last = matcher.end();
		}
		result.append(xml.substring(last));
		return result.toString();
	}

	private static String upsertAttribute(String attributes, String attrName, String value) {
		String attrs = attributes == null ? "" : attributes;
		Pattern existing = Pattern.compile("\\s" + Pattern.quote(attrName) + "=\"[^\"]*\"");
		Matcher matcher = existing.matcher(attrs);
		if (matcher.find()) {
			return matcher.replaceFirst(" " + attrName + "=\"" + Matcher.quoteReplacement(value) + "\"");
		}
		String trimmed = attrs.stripTrailing();
		if (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1).stripTrailing();
		}
		if (trimmed.isEmpty()) {
			return " " + attrName + "=\"" + value + "\"";
		}
		return trimmed + " " + attrName + "=\"" + value + "\"";
	}

}
