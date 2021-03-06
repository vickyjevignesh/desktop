/*
 * Syncany, www.syncany.org
 * Copyright (C) 2011 Philipp C. Heckel <philipp.heckel@gmail.com> 
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.stacksync.desktop;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;
import javax.swing.UIManager;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.log4j.Logger;
import com.stacksync.desktop.config.ConfigNode;
import com.stacksync.desktop.config.Device;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.w3c.dom.Document;
import sun.net.www.protocol.file.FileURLConnection;

/**
 *
 * @author Philipp C. Heckel
 */
public class Environment {
    private final Logger logger = Logger.getLogger(Environment.class.getName());
    private static Environment instance;
    
    public enum OperatingSystem { Windows, Linux, Mac };

    private OperatingSystem operatingSystem;
    private String architecture;
    
    private String defaultUserHome;
    private File defaultUserConfDir;
    private File defaultUserConfigFile;
    
    private File appDir;
    private File appBinDir;
    private File appResDir;
    private File appConfDir;
    
    /**
     * Local computer name / host name.
     */
    private static String deviceName;

    /**
     * Local user name (login-name).
     */
    private static String userName;


    public synchronized static Environment getInstance() {
        if (instance == null) {
            instance = new Environment();
        }
	
        return instance;
    }

    private Environment() {

        String homePath= "";
        // Check must-haves
        if (System.getProperty("stacksync.home") == null){            
            if(System.getProperty("user.dir") != null){
                homePath = System.getProperty("user.dir");
            } else{
                throw new RuntimeException("Property 'stacksync.home' must be set.");	
            }
        } else{
            homePath = System.getProperty("stacksync.home");
            File tryPath = new File(homePath + File.separator + "res");
            if(!tryPath.exists()){
                homePath = System.getProperty("user.dir");
            }
        }       

        // Architecture
        if ("32".equals(System.getProperty("sun.arch.data.model"))) {
            architecture = "i386";
        } else if ("64".equals(System.getProperty("sun.arch.data.model"))) {
            architecture = "amd64";
        } else {
            throw new RuntimeException("Stacksync only supports 32bit and 64bit systems, not '"+System.getProperty("sun.arch.data.model")+"'.");	
        }
           
        // Do initialization!
        defaultUserHome = System.getProperty("user.home") + File.separator;
        
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("linux")) {
            operatingSystem = OperatingSystem.Linux;
            defaultUserConfDir = new File(defaultUserHome + "."+ Constants.APPLICATION_NAME.toLowerCase());	    
        } else if (osName.contains("windows")) {
            operatingSystem = OperatingSystem.Windows;
            if(osName.contains("xp")){//windows xp
                defaultUserConfDir = new File(defaultUserHome + "Application Data" + File.separator + Constants.APPLICATION_NAME.toLowerCase());
            } else { //windows 7, 8
                defaultUserConfDir = new File(defaultUserHome + "AppData" + File.separator + "Roaming" + File.separator + Constants.APPLICATION_NAME.toLowerCase());
            }
        } else if (osName.contains("mac os x")) {
            operatingSystem = OperatingSystem.Mac;
            defaultUserConfDir = new File(defaultUserHome + "." + Constants.APPLICATION_NAME.toLowerCase());	    
        } else {
            throw new RuntimeException("Your system is not supported at the moment: " + System.getProperty("os.name"));
        }

        // Common values
        defaultUserConfigFile = new File(defaultUserConfDir.getAbsoluteFile() + File.separator + Constants.CONFIG_FILENAME);

        appDir = new File(homePath);
        appBinDir = new File(defaultUserConfDir.getAbsoluteFile()+File.separator+"bin");
        appBinDir.mkdirs();
        appResDir = new File(defaultUserConfDir.getAbsoluteFile()+File.separator+"res");
        appResDir.mkdirs();
        appConfDir = new File(defaultUserConfDir.getAbsoluteFile()+File.separator+"conf");
        appConfDir.mkdirs();
        
        try {
            File binFolder, resFolder, confFolder;
            if (operatingSystem == OperatingSystem.Mac || operatingSystem == OperatingSystem.Linux || operatingSystem == OperatingSystem.Windows) {
                binFolder = appBinDir;
                resFolder = appResDir;
                confFolder = appConfDir;
            } else {
                binFolder = resFolder = confFolder = defaultUserConfDir;
            }
            URL resource = Environment.class.getResource("/bin");
            copyResourcesRecursively(resource, binFolder);
            resource = Environment.class.getResource("/res");
            copyResourcesRecursively(resource, resFolder);
            resource = Environment.class.getResource("/conf");
            copyResourcesRecursively(resource, confFolder);
        } catch (Exception ex) {
            throw new RuntimeException("Could not copy resources from JAR: "+ex);
        }
        
        // Errors
        if (!appDir.exists() ) {
            throw new RuntimeException("Could not find application directory at "+appDir);
        }
        
        if (!appResDir.exists() ) {
            throw new RuntimeException("Could not find application resources directory at "+appResDir);
        }
               
        String defaultDeviceName;
        try { 
            defaultDeviceName = InetAddress.getLocalHost().getHostName();

            if(defaultDeviceName.length() > 10){
                defaultDeviceName = InetAddress.getLocalHost().getHostName().substring(0, 9);
            }
        } catch (UnknownHostException ex) { 
            logger.error("aplicationstarter#ERROR: cannot find host", ex);
            defaultDeviceName = "(unknown)"; 
        }
                
        if(defaultUserConfigFile.exists()){
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();            
            try {
                DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
                
                Document doc = dBuilder.parse(defaultUserConfigFile);
                ConfigNode self = new ConfigNode(doc.getDocumentElement());
                Device device = new Device();
                device.load(self.findChildByName("device"));
                deviceName = device.getName();
                
                if(deviceName == null || deviceName.isEmpty()){
                    deviceName = defaultDeviceName;
                }                
            } catch (Exception ex) {
                logger.error("ERROR: cant set machineName", ex);
                deviceName = defaultDeviceName;
            }
        } else{        
            deviceName = defaultDeviceName.replace("-", "_");
        }
        
        userName = System.getProperty("user.name");

        // GUI 
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                    //java.util.Enumeration keys = UIManager.getDefaults().keys();
                    
            /*while (keys.hasMoreElements()) {
              Object key = keys.nextElement();
              Object value = UIManager.get (key);
                
              if (value instanceof FontUIResource) {
                  FontUIResource f = (FontUIResource) value;
                  f = new FontUIResource(f.getFamily(), f.getStyle(), f.getSize()-2);
                  System.out.println(key +" = "+value);
                    UIManager.put (key, f);
              
              }
            }*/
        } catch (Exception ex) {
            logger.error("aplicationstarter#Couldn't set native look and feel.", ex);
        }
    }
    
    public void copyResourcesRecursively(URL originUrl, File destination) throws Exception {
        URLConnection urlConnection = originUrl.openConnection();
        if (urlConnection instanceof JarURLConnection) {
            copyResourcesFromJar((JarURLConnection) urlConnection, destination);
        } else if (urlConnection instanceof FileURLConnection) {
            // I know that this is not so beatiful... I'll try to change in a future...
            if (operatingSystem == OperatingSystem.Mac || operatingSystem == OperatingSystem.Linux) {
                destination = defaultUserConfDir;
            }
            FileUtils.copyDirectoryToDirectory(new File(originUrl.getPath()), destination);
        } else {
            throw new Exception("URLConnection[" + urlConnection.getClass().getSimpleName() +
                    "] is not a recognized/implemented connection type.");
        }
    }

    public void copyResourcesFromJar(JarURLConnection jarConnection, File destDir) {

        try {
            JarFile jarFile = jarConnection.getJarFile();

            /**
             * Iterate all entries in the jar file.
             */
            for (Enumeration<JarEntry> e = jarFile.entries(); e.hasMoreElements();) {

                JarEntry jarEntry = e.nextElement();
                String jarEntryName = jarEntry.getName();
                String jarConnectionEntryName = jarConnection.getEntryName();

                /**
                 * Extract files only if they match the path.
                 */
                if (jarEntryName.startsWith(jarConnectionEntryName)) {

                    String filename = jarEntryName.startsWith(jarConnectionEntryName) ? jarEntryName.substring(jarConnectionEntryName.length()) : jarEntryName;
                    File currentFile = new File(destDir, filename);

                    if (jarEntry.isDirectory()) {
                        currentFile.mkdirs();
                    } else {
                        InputStream is = jarFile.getInputStream(jarEntry);
                        OutputStream out = FileUtils.openOutputStream(currentFile);
                        IOUtils.copy(is, out);
                        is.close();
                        out.close();
                    }
                }
            }
        } catch (IOException e) {
            // TODO add logger
            e.printStackTrace();
        }

    }

    public File getAppConfDir() {
        return appConfDir;
    }

    public File getAppDir() {
        return appDir;
    }

    public File getAppBinDir() {
        return appBinDir;
    }
    
    public File getAppResDir() {
        return appResDir;
    }
    
    public File getDefaultUserConfigFile() {
        return defaultUserConfigFile;
    }

    public File getDefaultUserConfigDir() {
        return defaultUserConfDir;
    }
    
    public String getDeviceName() {
        return deviceName.replace("-", "_");
    }

    public String getDeviceNameWithTimestamp() {
        // Machine stuff        
        java.util.Date date = new java.util.Date(); 
        java.text.SimpleDateFormat sdf=new java.text.SimpleDateFormat("yyyyMMddHHmm");
        
        return deviceName+sdf.format(date);
    }
    
    public String getUserName() {
        return userName;
    }

    public OperatingSystem getOperatingSystem() {
        return operatingSystem;
    }

    public String getArchitecture() {
        return architecture;
    }    
    
    public String getDefaultUserHome() {
        return defaultUserHome;
    }
    
    public void main(String[] args) {
        Properties properties = System.getProperties();

        Enumeration e = properties.propertyNames();

        System.out.println("Properties");
        System.out.println("---------------");

        while (e.hasMoreElements()) {
            String key = (String) e.nextElement();
            System.out.println(key+" = "+System.getProperty(key));	    
        }

        System.out.println("ENV");
        System.out.println("---------------");

        for (Map.Entry<String,String> es : System.getenv().entrySet()) {
            System.out.println(es.getKey()+" = "+es.getValue());	
        }	
    }
}