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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * General helper functions for Posix/Linux file system support
 * In separate file to avoid confusion of java.nio.files.Path and hadoop.Path
 * @author Brian M Williams
 *
 */
public class LocalFSUtils {
	public static String copyToLocal(String fromFile, String toDir) throws IOException {
		Path source = Paths.get(fromFile);
		Path destPath = Paths.get(toDir, source.getFileName().toString());
		Files.copy(source, destPath,StandardCopyOption.REPLACE_EXISTING);
		
		return destPath.toString();
	}
}