// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.ibm.streams.resourcemgr.mesos;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * General helper files for HDFS file system, copied from ibm stream yarn impl
 * 
 * @author Brian M Williams
 *
 */
public class HdfsFSUtils {
	private static final Logger LOG = LoggerFactory.getLogger(HdfsFSUtils.class);
	
	/** Returns the path on the HDFS for a particular application
	 * @param applicationID
	 * @param name
	 * @return HDFS path for application
	 */
	public static String getHDFSPath(String applicationID, String name) {
		return applicationID + Path.SEPARATOR + name;
	}
	
	public static Path copyToHDFS(FileSystem hdfs, String hdfsPathPrefix, String localPath, String name) throws IOException {
		Path hdfsPath = new Path(hdfs.getHomeDirectory(), getHDFSPath(hdfsPathPrefix, name));
		LOG.debug("copying local: " + localPath + " to hdfs: " + hdfsPath);
		hdfs.copyFromLocalFile(new Path(localPath),  hdfsPath);
		return hdfsPath;
	}
	
	/* Need to add arguments to supply or override constants */
	public static FileSystem getHDFSFileSystem() throws IOException {
		Configuration conf = new Configuration();
		LOG.debug("HDFS conf: " + conf.toString());
		LOG.debug("*** fs.default.name = " + conf.getRaw("fs.default.name"));
		
		FileSystem fs = FileSystem.get(conf);
		
		LOG.debug("fs.getHomeDirectory();: " + fs.getHomeDirectory());
		
		return fs;
	}
}
