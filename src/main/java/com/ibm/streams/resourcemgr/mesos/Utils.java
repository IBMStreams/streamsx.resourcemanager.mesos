//
// *******************************************************************************
// * Copyright (C)2015, International Business Machines Corporation and *
// * others. All Rights Reserved. *
// *******************************************************************************
//
package com.ibm.streams.resourcemgr.mesos;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/** Contains general helper functions.
 *
 */
public class Utils {

	/** Returns the path on the FS for a class
	 * @param _class Java class
	 * @return Path on the FS
	 */
	public static String getClassPath(Class<?> _class) {
		String classPath = _class.getName().replaceAll("\\.", "/") + ".class";
		return _class.getClassLoader().getResource(classPath).toString().split("!")[0];
	}

	/** Returns the path to the JAR for a class
	 * @param _class Java class
	 * @return Path to JAR on FS
	 */
	public static String getJarPath(Class<?> _class) {
		return getClassPath(_class).substring(9);
	}

	/** Returns the hostname of the current host
	 * @return Hostname
	 * @throws UnknownHostException
	 */
	public static String getHostName() throws UnknownHostException {
		return InetAddress.getLocalHost().getHostName();
	}

	public static String getHostName(String host) throws UnknownHostException {
		return InetAddress.getByName(host).getCanonicalHostName();
	}

	/** Deprecated
	 * @param command
	 * @return Process object
	 * @throws IOException
	 */
	public static Process launchProcess(String[] command) throws IOException {
		ProcessBuilder processBuilder = new ProcessBuilder(command);
		return processBuilder.start();
	}

	/** Deprecated
	 * @param command
	 * @return Output of process
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public static String launchProcessAndGetOutput(String[] command) throws IOException, InterruptedException {
		Process process = launchProcess(command);
		process.waitFor();
		return(inputStreamToString(process.getInputStream()));
	}

	/** Deprecated
	 * @param input
	 * @return String form of input stream
	 * @throws IOException
	 */
	public static String inputStreamToString(InputStream input) throws IOException {
		if (input != null) {
			StringWriter writer = new StringWriter();
			char[] buffer = new char[1024];
			try {
				Reader reader = new BufferedReader(new InputStreamReader(input));
				int n;
				while ((n = reader.read(buffer)) != -1) {
					writer.write(buffer, 0, n);
				}
			} finally {
				input.close();
			}
			return writer.toString().trim();
		}
		else {
			return "";
		}
	}


	//find an available port
	public static int getAvailablePort(int defaultPort) throws IOException {

		try {
			ServerSocket ss = new ServerSocket(defaultPort);
			ss.close();
			return defaultPort;
		} catch (IOException e) {
		}
		ServerSocket ss = new ServerSocket(0);
		int port = ss.getLocalPort();
		ss.close();
		return port;
	}

	public static void sleepABit(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {}
	}
	public static String getProperty(Properties p, String name, String def) {
		if(p.containsKey(name))
			return  p.getProperty(name);
		return def;
	}
	public static int getProperty(Properties p, String name, int def) {
		if(p.containsKey(name))
			return Integer.parseInt(p.getProperty(name));
		return def;
	}
	public static boolean hasProperty(Properties p, String name)  {
		return p.containsKey(name);
	}
	public static String getProperty(Properties p, String name)  {
		if(!p.containsKey(name))
			throw new RuntimeException("Property \"" + name + "\" not specified");
		return p.getProperty(name);
	}
	public static int getIntProperty(Properties p, String name)  {
		return Integer.parseInt(getProperty(p, name).trim());
	}
	public static double getDoubleProperty(Properties p, String name)  {
		return Double.parseDouble(getProperty(p, name).trim());
	}
	public static long getLongProperty(Properties p, String name)  {
		return Long.parseLong(getProperty(p, name).trim());
	}
	public static boolean getBooleanProperty(Properties p, String name) {
		return Boolean.parseBoolean(getProperty(p, name).trim());
	}
	static Map<String, AtomicLong> idMap = new HashMap<String, AtomicLong>();
	public static String generateNextId (String name) {
		synchronized(idMap) {
			if(!idMap.containsKey(name))
				idMap.put(name, new AtomicLong(0));
		}
		return name + "_" + idMap.get(name).getAndIncrement();
	}

	public static boolean createDirectory(String name) {
		File folder = new File(name);
		return folder.mkdirs();
	}
	public static void deleteDirectory(String name) {
		File folder = new File(name);
		if(folder.exists()) {
			File[] files = folder.listFiles();
			if(files!=null) {
				for(File f: files) {
					if(f.isDirectory()) {
						deleteDirectory(f.getAbsolutePath());
					} else {
						f.delete();
					}
				}
			}
			folder.delete();
		}
	}
	
	
	// Convert String collection to comma separated
	public static String toCsv(Collection<String> items) {
		StringBuilder builder = new StringBuilder();
		for (String item : items) {
			if (builder.length() > 0) {
				builder.append(",");
			}
			builder.append(item);
		}
		return builder.toString();
	}
	
	// Convert from comma separated to string collection
	public static Collection<String> fromCsv(String csv) {
		Collection<String> items = new HashSet<String>();
		if (items != null && !items.isEmpty()) {
			for (String item : csv.split(",")) {
				items.add(item);			
			}
		}
		return items;
	}
	
}
