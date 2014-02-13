package org.jolokia.jvmagent;

/*
 * Copyright 2009-2013 Roland Huss
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

import com.sun.net.httpserver.Authenticator;
import org.jolokia.config.*;

/**
 * Configuration required for the JolokiaServer
 *
 * @author roland
 * @since 28.12.12
 */
public class JolokiaServerConfig {

    // Jolokia configuration is used for general jolokia config, the untyped configuration
    // is used for this agent only
    private StaticConfiguration jolokiaConfig;

    private String        protocol;
    private int           port;
    private int           backlog;
    private InetAddress   address;
    private String        executor;
    private int           threadNr;
    private String        keystore;
    private String        context;
    private boolean       useSslClientAuthentication;
    private char[]        keystorePassword;
    private Authenticator authenticator;

    /**
     * Constructor which prepares the server configuration from a map
     * of given config options (key: option name, value: option value).
     * Also, default values are used for any
     * parameter not provided ({@link #getDefaultConfig}).
     *
     * The given configuration consist of two parts: Any global options
     * as defined in {@link ConfigKey} are used for setting up the agent.
     * All other options are taken for preparing the HTTP server under
     * which the agent is served. The known properties are described in
     * the reference manual.
     *
     * All other options are ignored.
     *
     * @param pConfig the configuration options to use.
     */
    public JolokiaServerConfig(Map<String, String> pConfig) {
        init(pConfig,getDefaultConfig());
    }

    /**
     * Empty constructor useful for subclasses which want to do their own initialization. Note that
     * the subclass must call {@link #init} on its own.
     */
    protected JolokiaServerConfig() { }

    /**
     * Initialization
     *
     * @param pConfig original config
     * @param pDefaultConfig default config used as background
     */
    protected final void init(Map<String, String> pConfig,Map<String,String> pDefaultConfig) {
        Map<String, String> finalCfg = new HashMap<String, String>(pDefaultConfig);
        finalCfg.putAll(pConfig);

        prepareDetectorOptions(finalCfg);
        addJolokiaId(finalCfg);

        jolokiaConfig = new StaticConfiguration(finalCfg);
        initConfigAndValidate(finalCfg);
    }

    // Add a unique jolokia id for this agent
    private void addJolokiaId(Map<String, String> pFinalCfg) {
        if (!pFinalCfg.containsKey(ConfigKey.AGENT_ID.getKeyValue())) {
            String id = Integer.toHexString(hashCode()) + "-jvm";
            pFinalCfg.putAll(Collections.singletonMap(ConfigKey.AGENT_ID.getKeyValue(),
                                                      id));
        }
    }

    /**
     * Read in the default configuration from a properties resource
     * @return
     */
    protected final Map<String, String> getDefaultConfig() {
        InputStream is = getClass().getResourceAsStream("/default-jolokia-agent.properties");
        return readPropertiesFromInputStream(is, "default-jolokia-agent.properties");
    }

    /**
     * Get the Jolokia runtime configuration
     * @return jolokia configuration
     */
    public Configuration getJolokiaConfig() {
        return jolokiaConfig;
    }

    /**
     * Protocol to use
     *
     * @return protocol either 'http' or 'https'
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * Address to bind to, which is either used from the configuration option
     * "host" or by default from {@link InetAddress#getLocalHost()}
     *
     * @return the host's address
     */
    public InetAddress getAddress() {
        return address;
    }

    /**
     * Port for the server to listen to
     *
     * @return port
     */
    public int getPort() {
        return port;
    }

    /**
     * Return a basic authenticator if user or password is given in the configuration. You can override
     * this method if you want to provide an own authenticator.
     *
     * @return an authenticator if authentication is switched on, or null if no authentication should be used.
     */
    public Authenticator getAuthenticator() {
        return authenticator;
    }

    /**
     * Backlog of the HTTP server, which is the number of requests to keep before throwing them away
     * @return backlog
     */
    public int getBacklog() {
        return backlog;
    }

    /**
     * Context path under which the agent is reachable. This path will always end with a "/" for technical
     * reasons.
     *
     * @return the context path.
     */
    public String getContextPath() {
        return context;
    }

    /**
     * Executor to use as provided by the 'executor' option or "single" as default
     * @return the executor model ("fixed", "single" or "cached")
     */
    public String getExecutor() {
        return executor;
    }

    /**
     * Thread number to use when executor model is "fixed"
     * @return number of fixed threads
     */
    public int getThreadNr() {
        return threadNr;
    }

    /**
     * When the protocol is 'https' then this property indicates whether SSL client certificate
     * authentication should be used or not
     *
     * @return true when ssl client authentication should be used
     */
    public boolean useSslClientAuthentication() {
        return useSslClientAuthentication;
    }

    /**
     * Name of the keystore for 'https', if any
     * @return name of keystore.
     */
    public String getKeystore() {
        return keystore;
    }

    /**
     * Password for keystore if a keystore is used. If not given, no password is assumed.
     *
     * @return the keystore password as char array or an empty array of no password is given
     */
    public char[] getKeystorePassword() {
        return keystorePassword;
    }

    // Initialise and validate early in order to fail fast in case of an configuration error
    private void initConfigAndValidate(Map<String,String> agentConfig) {
        initContext();
        initAuthenticator();
        initProtocol(agentConfig);
        initAddress(agentConfig);
        port = Integer.parseInt(agentConfig.get("port"));
        backlog = Integer.parseInt(agentConfig.get("backlog"));
        initExecutor(agentConfig);
        initThreadNr(agentConfig);
        initKeystore(agentConfig);

        String auth = agentConfig.get("useSslClientAuthentication");
        useSslClientAuthentication = auth != null && Boolean.getBoolean(auth);


        String password = agentConfig.get("keystorePassword");
        keystorePassword =  password != null ? password.toCharArray() : new char[0];
    }

    private void initAuthenticator() {
        initCustomAuthenticator();
        if (authenticator == null) {
            initDefaultAuthenticator();
        }
    }

    private void initCustomAuthenticator() {
        String authenticatorClass = jolokiaConfig.getConfig(ConfigKey.AUTHENTICATOR_CLASS);

        if (authenticatorClass != null) {
            Class authClass = getAuthenticatorClass(authenticatorClass);

            try {
                // prefer constructor that takes configuration
                authenticator = createFromConstructorWithConfigArg(authClass);
            } catch (NoSuchMethodException ignore) {
                // fallback to default constructor
                authenticator = createFromDefaultConstructor(authClass);
            }
        }
    }

    private Authenticator createFromConstructorWithConfigArg(Class pAuthClass) throws NoSuchMethodException {
        try {
            Constructor constructorThatTakesConfiguration = pAuthClass.getConstructor(Configuration.class);
            return (Authenticator) constructorThatTakesConfiguration.newInstance(this.jolokiaConfig);
        }
        catch (InvocationTargetException e) {
            throw new IllegalArgumentException("Cannot invoke 1-arg constructor for custom authenticator " + pAuthClass, e);
        } catch (InstantiationException e) {
            throw new IllegalArgumentException("Cannot create an instance of custom authenticator class " + pAuthClass, e);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("Cannot access 1-arg constructor for custom authenticator class" + pAuthClass +
                                               " (is the constructor 'private' ?)", e);
        }
    }

    private Authenticator createFromDefaultConstructor(Class pAuthClass) {
        try {
            Constructor defaultConstructor = pAuthClass.getConstructor();
            return (Authenticator) defaultConstructor.newInstance();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Cannot create an instance of custom authenticator class, " +
                                               "no default constructor available for " + pAuthClass, e);
        } catch (InvocationTargetException e) {
            throw new IllegalArgumentException("Cannot invoke default constructor for custom authenticator " + pAuthClass, e);
        } catch (InstantiationException e) {
            throw new IllegalArgumentException("Cannot create an instance of custom authenticator class " + pAuthClass, e);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("Cannot access default constructor for custom authenticator class" + pAuthClass +
                                               " (is the constructor 'private' ?)", e);
        }
    }

    private Class getAuthenticatorClass(String pAuthenticatorClass) {
        try {
            Class authClass = Class.forName(pAuthenticatorClass);
            if (!Authenticator.class.isAssignableFrom(authClass)) {
                throw new IllegalArgumentException("Provided authenticator class [" + pAuthenticatorClass +
                                                   "] is not a subclass of Authenticator");
            }
            return authClass;
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Cannot find authenticator class", e);
        }
    }

    private void initDefaultAuthenticator() {
        String user = jolokiaConfig.getConfig(ConfigKey.USER);
        String password = jolokiaConfig.getConfig(ConfigKey.PASSWORD);

        authenticator = (user != null && password != null) ?
                new UserPasswordAuthenticator(user,password) :
                null;
    }

    private void initProtocol(Map<String, String> agentConfig) {
        protocol = agentConfig.containsKey("protocol") ? agentConfig.get("protocol") : "http";
        if (!protocol.equals("http") && !protocol.equals("https")) {
            throw new IllegalArgumentException("Invalid protocol '" + protocol + "'. Must be either 'http' or 'https'");
        }
    }

    private void initContext() {
        context = jolokiaConfig.getConfig(ConfigKey.AGENT_CONTEXT);
        if (context == null) {
            context = ConfigKey.AGENT_CONTEXT.getDefaultValue();
        }
        if (!context.endsWith("/")) {
            context += "/";
        }
    }

    private void initKeystore(Map<String, String> agentConfig) {
        // keystore
        keystore = agentConfig.get("keystore");
        if (protocol.equals("https") && keystore == null) {
            throw new IllegalArgumentException("No keystore defined for HTTPS protocol. " +
                                               "Please use the 'keystore' option to point to a valid keystore");
        }
    }

    private void initThreadNr(Map<String, String> agentConfig) {
        // Thread-Nr
        String threadNrS =  agentConfig.get("threadNr");
        threadNr = threadNrS != null ? Integer.parseInt(threadNrS) : 5;
    }

    private void initExecutor(Map<String, String> agentConfig) {
        executor = agentConfig.containsKey("executor") ? agentConfig.get("executor") : "single";
        if (!"single".equalsIgnoreCase(executor) &&
                !"fixed".equalsIgnoreCase(executor) &&
                !"cached".equalsIgnoreCase(executor)) {
            throw new IllegalArgumentException("Executor model can be '" + executor +
                                               "' but most be either 'single', 'fixed' or 'cached'");
        }
    }


    private void initAddress(Map<String, String> agentConfig) {
        String host = agentConfig.get("host");
        try {
            if ("*".equals(host) || "0.0.0.0".equals(host)) {
                address = null; // null is the wildcard
            } else if (host != null) {
                address = InetAddress.getByName(host); // some specific host
            } else {
                address = InetAddress.getByName(null); // secure alternative -- if no host, use *loopback*
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Can not lookup " + (host != null ? host : "loopback interface") + ": " + e,e);
        }
    }

    protected final Map<String, String> readPropertiesFromInputStream(InputStream pIs, String pLabel) {
        Map ret = new HashMap<String, String>();
        if (pIs == null) {
            return ret;
        }
        Properties props = new Properties();
        try {
            props.load(pIs);
            ret.putAll(props);
        } catch (IOException e) {
            throw new IllegalArgumentException("jolokia: Cannot load properties " + pLabel + " : " + e,e);
        }
        return ret;
    }

    // Add detector specific options if given on the command line
    private void prepareDetectorOptions(Map<String, String> pConfig) {
        StringBuffer detectorOpts = new StringBuffer("{");
        if (pConfig.containsKey("bootAmx") && Boolean.parseBoolean(pConfig.get("bootAmx"))) {
            detectorOpts.append("\"glassfish\" : { \"bootAmx\" : true }");
        }
        if (detectorOpts.length() > 1) {
            detectorOpts.append("}");
            pConfig.put(ConfigKey.DETECTOR_OPTIONS.getKeyValue(),detectorOpts.toString());
        }
    }
}
