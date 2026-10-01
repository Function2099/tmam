package com.tmam.service;

import com.tmam.model.TomcatServiceConfig;

public final class LegacyIpFragmentBuilder {

	private LegacyIpFragmentBuilder() {
	}

	public static String build(TomcatServiceConfig service) {
		String name = service.getName();
		String address = service.getAddress();
		String docBase = service.getDocBase() != null ? service.getDocBase() : "";
		int port = service.getPort();

		return """
				   <Service name="%s">
				    <Connector URIEncoding="utf-8" address="%s" port="%d" protocol="HTTP/1.1"
				      acceptCount="500"
				      connectionTimeout="20000"
				      maxThreads="500"
				      redirectPort="443" />

				    <Engine name="%s" defaultHost="%s">
				      <Realm className="org.apache.catalina.realm.UserDatabaseRealm" resourceName="UserDatabase"/>
				      <Host name="%s" unpackWARs="true" autoDeploy="false" deployOnStartup="false" startStopThreads="%s">
				        <Context path="" docBase="%s" reloadable="true" crossContext="true">
				          <Parameter name="tmam.online" value="%s" override="false"/>
				          <Parameter name="WebPath" value="" override="true"/>
				          %s
				        </Context>
				      </Host>
				    </Engine>
				  </Service>"""
				.formatted(name, address, port, name, name, name, TomcatStartupOptimizer.START_STOP_THREADS,
						escapeXmlAttr(docBase), service.isOnline() ? "true" : "false",
						TomcatStartupOptimizer.JAR_SCANNER);
	}

	private static String escapeXmlAttr(String value) {
		return value.replace("&", "&amp;")
				.replace("\"", "&quot;")
				.replace("<", "&lt;")
				.replace(">", "&gt;");
	}

}
