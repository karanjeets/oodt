/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.oodt.cas.catalog.server;

//JDK imports
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

//OODT imports
import org.apache.oodt.cas.cli.CmdLineUtility;

/**
 * @author bfoster
 * @version $Revision$
 *
 * <p>
 * Utility for launching CommunicationChannelServers
 * <p>
 */
public class CatalogServiceServerLauncher {
		
	private CatalogServiceServerLauncher() {}
	
	public static void main(String[] args) throws IOException {
      // Load Catalog Service properties.
      String propertiesFile = System
            .getProperty("org.apache.oodt.cas.catalog.properties.file");
      if (propertiesFile != null) {
         System.getProperties().load(new FileInputStream(propertiesFile));
      }

      // Run Command line.
      CmdLineUtility cmdLineUtility = new CmdLineUtility();
      cmdLineUtility.run(args);
	}
}
