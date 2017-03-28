package com.ibm.streams.resourcemgr.mesos.command;

import com.ibm.streams.resourcemgr.mesos.StreamsMesosConstants;


public class Version {
	static public void main(String[] args) {
		//System.out.println("Version: " + getImplementationVersion());
		System.out.println(getTitleAndVersionString());
	}

        // Get value of Implementation-Title and Version from jar MANIFEST.mf file
        // This needs to be in the outer-most jar file
        // In this build, we create a jar-with-depencencies, so it needs
        // to be in that jar file
        static private String getImplementationVersion() {
                //Package p = getClass().getPackage();
                Package p = Version.class.getPackage();
                StringBuilder str = new StringBuilder();
                String version = p.getImplementationVersion();
                                if (version != null && version.length() > 0)
                                        str.append(version);
                                else
                                        str.append("not specified");
                return str.toString();
        }

        static private String getImplementationTitle() {
                Package p = Version.class.getPackage();
                StringBuilder str = new StringBuilder();
                String title = p.getImplementationTitle();
                                if (title != null && title.length() > 0)
                                        str.append(title);
                                else
                                        str.append(StreamsMesosConstants.RESOURCE_MANAGER_NAME);
                return str.toString();
        }

        static private String getTitleAndVersionString() {
                StringBuilder str = new StringBuilder();
                str.append("Resource Manager: ");
                str.append(getImplementationTitle());
                str.append(", Version: ");
                str.append(getImplementationVersion());
                return str.toString();
        }


}
