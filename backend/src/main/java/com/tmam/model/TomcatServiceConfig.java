package com.tmam.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TomcatServiceConfig {

	private String name;
	private TomcatServiceType type = TomcatServiceType.LEGACY_IP;
	private String address;
	private int port;
	private boolean enabled = true;
	private String displayName;
	private String pathPrefix;
	private String docBase;
	private boolean proxyStripPrefix = false;
	/** Nginx 將根路徑的子資料夾轉到本系統前綴下（例如 /images → /Web/images）。 */
	private List<String> legacyPaths = new ArrayList<>();
	/** 造訪前綴根路徑時導向的首頁（例如 index_Login.jsp）。空白則維持轉發目錄本身。 */
	private String indexPage;
	/** 上線模式：應用可依此決定是否強制 HTTPS 等正式環境行為；本機測試請關閉。 */
	private boolean online = false;
	private boolean userCreated = false;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public TomcatServiceType getType() {
		return type;
	}

	public void setType(TomcatServiceType type) {
		this.type = type;
	}

	public String getAddress() {
		return address;
	}

	public void setAddress(String address) {
		this.address = address;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getPathPrefix() {
		return pathPrefix;
	}

	public void setPathPrefix(String pathPrefix) {
		this.pathPrefix = pathPrefix;
	}

	public String getDocBase() {
		return docBase;
	}

	public void setDocBase(String docBase) {
		this.docBase = docBase;
	}

	public boolean isProxyStripPrefix() {
		return proxyStripPrefix;
	}

	public void setProxyStripPrefix(boolean proxyStripPrefix) {
		this.proxyStripPrefix = proxyStripPrefix;
	}

	public List<String> getLegacyPaths() {
		return legacyPaths;
	}

	public void setLegacyPaths(List<String> legacyPaths) {
		this.legacyPaths = legacyPaths != null ? legacyPaths : new ArrayList<>();
	}

	public String getIndexPage() {
		return indexPage;
	}

	public void setIndexPage(String indexPage) {
		this.indexPage = indexPage;
	}

	public boolean isOnline() {
		return online;
	}

	public void setOnline(boolean online) {
		this.online = online;
	}

	public boolean isUserCreated() {
		return userCreated;
	}

	public void setUserCreated(boolean userCreated) {
		this.userCreated = userCreated;
	}

	@JsonIgnore
	public boolean isPathProxy() {
		return type == TomcatServiceType.PATH_PROXY;
	}

	@JsonIgnore
	public boolean isLegacyIp() {
		return type == null || type == TomcatServiceType.LEGACY_IP;
	}

}
