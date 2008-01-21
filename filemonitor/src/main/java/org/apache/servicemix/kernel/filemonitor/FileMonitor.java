/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.servicemix.kernel.filemonitor;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.jar.Attributes;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.taskdefs.Jar;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.packageadmin.PackageAdmin;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Watches a deploy directory for files that are added, updated or removed then processing them.
 * Currently we support OSGi bundles, OSGi configuration files and expanded directories of OSGi bundles.
 *
 * @version $Revision: 1.1 $
 */
public class FileMonitor {
	
    public final static String CONFIG_DIR = "org.apache.servicemix.filemonitor.configDir";
    public final static String DEPLOY_DIR = "org.apache.servicemix.filemonitor.monitorDir";
    public final static String GENERATED_JAR_DIR = "org.apache.servicemix.filemonitor.generatedJarDir";
    public final static String SCAN_INTERVAL = "org.apache.servicemix.filemonitor.scanInterval";
    protected static final String ALIAS_KEY = "_alias_factory_pid";
    private static Log logger = LogFactory.getLog(FileMonitor.class);
    private FileMonitorActivator activator;
    private File configDir = new File("./etc");
    private File deployDir = new File("./deploy");
    private File generateDir = new File("./data/generated-bundles");
    private Scanner scanner = new Scanner();
    private Project project = new Project();
    private long scanInterval = 500L;
    private boolean loggedConfigAdminWarning;
    private List<Bundle> changedBundles = new ArrayList<Bundle>();
    private List<Bundle> bundlesToStart = new ArrayList<Bundle>();
    private List<Bundle> bundlesToUpdate = new ArrayList<Bundle>();
    private Map<String, String> artifactToBundle = new HashMap<String, String>();

    public FileMonitor() {
    }

    public FileMonitor(FileMonitorActivator activator, Dictionary properties) {
        this.activator = activator;

        File value = getFileValue(properties, CONFIG_DIR);
        if (value != null) {
            configDir = value;
        }
        value = getFileValue(properties, DEPLOY_DIR);
        if (value != null) {
            deployDir = value;
        }
        value = getFileValue(properties, GENERATED_JAR_DIR);
        if (value != null) {
            generateDir = value;
        }
        Long i = getLongValue(properties, SCAN_INTERVAL);
        if (i != null) {
            scanInterval = i;
        }
    }

    public void start() {
        if (configDir != null) {
            configDir.mkdirs();
        }
        deployDir.mkdirs();
        generateDir.mkdirs();

        List<File> dirs = new ArrayList<File>();
        if (configDir != null) {
            dirs.add(configDir);
        }
        dirs.add(deployDir);
        scanner.setScanDirs(dirs);
        scanner.setScanInterval(scanInterval);

        scanner.addListener(new Scanner.BulkListener() {
            public void filesChanged(List<String> filenames) throws Exception {
                onFilesChanged(filenames);
            }
        });

        logger.info("Starting to monitor the deploy directory: " + deployDir + " every " + scanInterval + " millis");
        if (configDir != null) {
            logger.info("Config directory is at: " + configDir);
        }
        logger.info("Will generate bundles from expanded source directories to: " + generateDir);

        scanner.start();
    }

    public void stop() {
        scanner.stop();
    }

    // Properties
    //-------------------------------------------------------------------------

    public BundleContext getContext() {
        return activator.getContext();
    }

    public FileMonitorActivator getActivator() {
        return activator;
    }

    public void setActivator(FileMonitorActivator activator) {
        this.activator = activator;
    }

    public File getConfigDir() {
        return configDir;
    }

    public void setConfigDir(File configDir) {
        this.configDir = configDir;
    }

    public File getDeployDir() {
        return deployDir;
    }

    public void setDeployDir(File deployDir) {
        this.deployDir = deployDir;
    }

    public File getGenerateDir() {
        return generateDir;
    }

    public void setGenerateDir(File generateDir) {
        this.generateDir = generateDir;
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public long getScanInterval() {
        return scanInterval;
    }

    public void setScanInterval(long scanInterval) {
        this.scanInterval = scanInterval;
    }

    // Implementation methods
    //-------------------------------------------------------------------------

    protected void onFilesChanged(List<String> filenames) {
        changedBundles.clear();
        bundlesToStart.clear();
        bundlesToUpdate.clear();
        Set<File> bundleJarsCreated = new HashSet<File>();

        for (Object filename : filenames) {
            String name = filename.toString();
            boolean added = true;
            
            File file = new File(name);
            try {
                logger.debug("File changed: " + filename + " with type: " + filename.getClass().getName());

                // Handle config files
                if (isValidConfigFile(file)) {
                    if (added) {
                        updateConfiguration(file);
                    }
                    else {
                        deleteConfiguration(file);
                    }
                    continue;
                }

                if (file.exists()) {
                    file = transformArtifact(file);
                    if (file == null) {
                        logger.warn("Unsupported deployment: " + name);
                        continue;
                    }
                } else {
                	added = false;
                	String transformedFile = artifactToBundle.get(name);
                	if (transformedFile != null) {
                		file = new File(transformedFile);
                		if (file.exists()) {
                			file.delete();
                		}
                	}
                }

                // Check artifacts
                // now lets iterate to find the parent directory
                File jardir = getExpandedBundleRootDirectory(file);
                if (jardir != null) {
                    if (file.exists() && !bundleJarsCreated.contains(jardir)) {
                        bundleJarsCreated.add(jardir);
                        File newBundle = createBundleJar(jardir);
                        deployBundle(newBundle);
                    }
                }
                else if (isValidArtifactFile(file)) {
                    if (added) {
                        deployBundle(file);
                    }
                    else {
                        undeployBundle(file);
                    }
                }
                else if (file.getName().equals("MANIFEST.MF")) {
                    File parentFile = file.getParentFile();
                    if (parentFile.getName().equals("META-INF")) {
                        File bundleDir = parentFile.getParentFile();
                        if (isValidBundleSourceDirectory(bundleDir)) {
                            undeployBundle(bundleDir);
                        }
                    }
                }
            }
            catch (Exception e) {
                logger.warn("Failed to process: " + file + ". Reason: " + e, e);
            }
        }
        refreshPackagesAndStartOrUpdateBundles();
    }
    
    
	private File transformArtifact(File file) throws Exception {
        // Handle OSGi bundles with the default deployer
        JarFile jar = new JarFile(file);
        Manifest m = jar.getManifest();
        if (m.getMainAttributes().getValue(new Attributes.Name("Bundle-SymbolicName")) != null &&
            m.getMainAttributes().getValue(new Attributes.Name("Bundle-Version")) != null) {
            return file;
        }
        jar.close();
        // Else delegate to registered deployers
        ServiceReference[] srvRefs = getContext().getAllServiceReferences(DeploymentListener.class.getName(), null);
		if(srvRefs != null) {
		    for(ServiceReference sr : srvRefs) {
		    	try {
		    		DeploymentListener deploymentListener = (DeploymentListener)getContext().getService(sr);
		    		if (deploymentListener.canHandle(file)) {
		    			File transformedFile =  deploymentListener.handle(file, getGenerateDir());
		    			artifactToBundle.put(file.getAbsolutePath(), transformedFile.getAbsolutePath());
		    			return transformedFile;
		    		}
		    	} finally {
		    		getContext().ungetService(sr);
		    	}
		    }
		}
		return null;
	}

    protected void deployBundle(File file) throws IOException, BundleException {
        logger.info("Deloying: " + file.getCanonicalPath());

        InputStream in = new FileInputStream(file);

        try {
            Bundle bundle = getBundleForJarFile(file);
            if (bundle != null) {
                changedBundles.add(bundle);
                bundlesToUpdate.add(bundle);
            }
            else {
                bundle = getContext().installBundle(file.getCanonicalFile().toURI().toString(), in);
                if (!isBundleFragment(bundle)) {
                    bundlesToStart.add(bundle);
                }
            }
        }
        finally {
            closeQuietly(in);
        }
    }

    protected void undeployBundle(File file) throws BundleException, IOException {
        logger.info("Undeploying: " + file.getCanonicalPath());
        Bundle bundle = getBundleForJarFile(file);

        if (bundle == null) {
            logger.warn("Could not find Bundle for file: " + file.getCanonicalPath());
        }
        else {
            changedBundles.add(bundle);
            bundle.stop();
            bundle.uninstall();
        }
    }

    protected Bundle getBundleForJarFile(File file) throws IOException {
        String absoluteFilePath = file.getAbsolutePath();
        Bundle bundles[] = getContext().getBundles();
        for (int i = 0; i < bundles.length; i++) {
            Bundle bundle = bundles[i];
            String location = bundle.getLocation();
            if (location.endsWith(absoluteFilePath)) {
                return bundle;
            }
        }
        return null;
    }

    protected void updateConfiguration(File file) throws IOException, InvalidSyntaxException {
        ConfigurationAdmin configurationAdmin = activator.getConfigurationAdmin();
        if (configurationAdmin == null) {
            if (!loggedConfigAdminWarning) {
                logger.warn("No ConfigurationAdmin so cannot deploy configurations");
                loggedConfigAdminWarning = true;
            }
        }
        else {
            Properties properties = new Properties();
            InputStream in = new FileInputStream(file);
            try {
                properties.load(in);
                closeQuietly(in);
                String[] pid = parsePid(file);
                Hashtable<Object, Object> hashtable = new Hashtable<Object, Object>();
                hashtable.putAll(properties);
                if (pid[1] != null) {
                    hashtable.put(ALIAS_KEY, pid[1]);
                }

                Configuration config = getConfiguration(pid[0], pid[1]);
                if (config.getBundleLocation() != null) {
                    config.setBundleLocation(null);
                }
                config.update(hashtable);
            }
            finally {
                closeQuietly(in);
            }
        }
    }

    protected void deleteConfiguration(File file) throws IOException, InvalidSyntaxException {
        String[] pid = parsePid(file);
        Configuration config = getConfiguration(pid[0], pid[1]);
        config.delete();
    }

    protected Configuration getConfiguration(String pid, String factoryPid) throws IOException, InvalidSyntaxException {
        ConfigurationAdmin configurationAdmin = activator.getConfigurationAdmin();
        if (factoryPid != null) {
            Configuration[] configs = configurationAdmin.listConfigurations("(|(" + ALIAS_KEY + "=" + pid + ")(.alias_factory_pid=" + factoryPid + "))");
            if (configs == null || configs.length == 0) {
                return configurationAdmin.createFactoryConfiguration(pid, null);
            }
            else {
                return configs[0];
            }
        }
        else {
            return configurationAdmin.getConfiguration(pid, null);
        }
    }

    protected String[] parsePid(File file) {
        String path = file.getName();
        String pid = path.substring(0, path.length() - 4);
        int n = pid.indexOf('-');
        if (n > 0) {
            String factoryPid = pid.substring(n + 1);
            pid = pid.substring(0, n);
            return new String[]{pid, factoryPid};
        }
        else {
            return new String[]{pid, null};
        }
    }

    protected PackageAdmin getPackageAdmin() {
        ServiceTracker packageAdminTracker = activator.getPackageAdminTracker();
        if (packageAdminTracker != null) {
            try {
                return (PackageAdmin) packageAdminTracker.waitForService(5000L);
            }
            catch (InterruptedException e) {
                // ignore
            }
        }
        return null;
    }

    protected boolean isBundleFragment(Bundle bundle) {
        PackageAdmin packageAdmin = getPackageAdmin();
        if (packageAdmin != null) {
            return packageAdmin.getBundleType(bundle) == PackageAdmin.BUNDLE_TYPE_FRAGMENT;
        }
        return false;
    }

    protected void refreshPackagesAndStartOrUpdateBundles() {
        PackageAdmin packageAdmin = getPackageAdmin();
        if (packageAdmin != null) {
            Bundle[] bundles = new Bundle[changedBundles.size()];
            changedBundles.toArray(bundles);
            packageAdmin.refreshPackages(bundles);
        }
        changedBundles.clear();

        for (Bundle bundle : bundlesToUpdate) {
            try {
                bundle.update();
                logger.info("Updated: " + bundle);

            }
            catch (BundleException e) {
                logger.warn("Failed to update bundle: " + bundle + ". Reason: " + e, e);
            }
        }
        for (Bundle bundle : bundlesToStart) {
            try {
                bundle.start();
                logger.info("Started: " + bundle);
            }
            catch (BundleException e) {
                logger.warn("Failed to start bundle: " + bundle + ". Reason: " + e, e);
            }
        }
    }

    protected File createBundleJar(File dir) throws BundleException, IOException {
        Jar jar = new Jar();
        jar.setProject(project);
        File destFile = new File(generateDir, dir.getName() + ".jar");
        if (destFile.exists()) {
            undeployBundle(destFile);
            destFile.delete();
        }
        logger.info("Creating jar:  " + destFile + " from dir: " + dir);
        jar.setDestFile(destFile);
        jar.setManifest(new File(new File(dir, "META-INF"), "MANIFEST.MF"));
        jar.setBasedir(dir);

        jar.init();
        jar.perform();
        return destFile;
    }

    /**
     * Returns the root directory of the expanded OSGi bundle if the file is part of an expanded OSGi bundle
     * or null if it is not
     */
    protected File getExpandedBundleRootDirectory(File file) throws IOException {
        File parent = file.getParentFile();
        if (file.isDirectory()) {
            String rootPath = deployDir.getCanonicalPath();
            if (file.getCanonicalPath().equals(rootPath)) {
                return null;
            }
            if (containsManifest(file)) {
                return file;
            }
        }
        if (isValidBundleSourceDirectory(parent)) {
            return getExpandedBundleRootDirectory(parent);
        }
        return null;
    }

    /**
     * Returns true if the given directory is a valid child directory within the {@link #deployDir}
     */
    protected boolean isValidBundleSourceDirectory(File dir) throws IOException {
        if (dir != null) {
            String parentPath = dir.getCanonicalPath();
            String rootPath = deployDir.getCanonicalPath();
            return !parentPath.equals(rootPath) && parentPath.startsWith(rootPath);
        }
        else {
            return false;
        }
    }

    /**
     * Returns true if the given directory is a valid child file within the {@link #deployDir} or a child of {@link #generateDir}
     */
    protected boolean isValidArtifactFile(File file) throws IOException {
        if (file != null) {
            String filePath = file.getParentFile().getCanonicalPath();
            String deployPath = deployDir.getCanonicalPath();
            String generatePath = generateDir.getCanonicalPath();
            return filePath.equals(deployPath) || filePath.startsWith(generatePath);
        }
        else {
            return false;
        }
    }

    /**
     * Returns true if the given directory is a valid child file within the {@link #configDir}
     */
    protected boolean isValidConfigFile(File file) throws IOException {
        if (file != null && file.getName().endsWith(".cfg")) {
            String filePath = file.getParentFile().getCanonicalPath();
            String configPath = configDir.getCanonicalPath();
            return filePath.equals(configPath);
        }
        else {
            return false;
        }
    }

    /**
     * Returns true if the given directory contains a valid manifest file
     */
    protected boolean containsManifest(File dir) {
        File metaInfDir = new File(dir, "META-INF");
        if (metaInfDir.exists() && metaInfDir.isDirectory()) {
            File manifest = new File(metaInfDir, "MANIFEST.MF");
            return manifest.exists() && !manifest.isDirectory();
        }
        return false;
    }

    protected File getFileValue(Dictionary properties, String key) {
        Object value = properties.get(key);
        if (value instanceof File) {
            return (File) value;
        }
        else if (value != null) {
            return new File(value.toString());
        }
        return null;
    }

    protected Long getLongValue(Dictionary properties, String key) {
        Object value = properties.get(key);
        if (value instanceof Long) {
            return (Long) value;
        }
        else if (value != null) {
            return Long.parseLong(value.toString());
        }
        return null;
    }

    protected void closeQuietly(Closeable in) {
        try {
            in.close();
        }
        catch (IOException e) {
            logger.warn("Failed to close stream. " + e, e);
        }
    }

}