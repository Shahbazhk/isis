/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */


package org.apache.isis.core.runtime.installers;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.isis.core.commons.components.Installer;
import org.apache.isis.core.commons.ensure.Assert;
import org.apache.isis.core.commons.ensure.Ensure;
import org.apache.isis.core.commons.exceptions.IsisException;
import org.apache.isis.core.commons.factory.InstanceCreationClassException;
import org.apache.isis.core.commons.factory.InstanceCreationException;
import org.apache.isis.core.commons.factory.InstanceFactory;
import org.apache.isis.core.commons.factory.UnavailableClassException;
import org.apache.isis.core.commons.lang.CastUtils;
import org.apache.isis.core.commons.lang.StringUtils;
import org.apache.isis.core.metamodel.config.ConfigurationBuilder;
import org.apache.isis.core.metamodel.config.IsisConfiguration;
import org.apache.isis.core.metamodel.config.NotFoundPolicy;
import org.apache.isis.core.metamodel.specloader.FacetDecoratorInstaller;
import org.apache.isis.core.metamodel.specloader.ObjectReflectorInstaller;
import org.apache.isis.core.runtime.about.AboutIsis;
import org.apache.isis.core.runtime.about.ComponentDetails;
import org.apache.isis.core.runtime.authentication.AuthenticationManagerInstaller;
import org.apache.isis.core.runtime.authorization.AuthorizationManagerInstaller;
import org.apache.isis.core.runtime.fixturesinstaller.FixturesInstaller;
import org.apache.isis.core.runtime.imageloader.TemplateImageLoaderInstaller;
import org.apache.isis.core.runtime.persistence.PersistenceMechanismInstaller;
import org.apache.isis.core.runtime.persistence.services.ServicesInstaller;
import org.apache.isis.core.runtime.remoting.ClientConnectionInstaller;
import org.apache.isis.core.runtime.system.DeploymentType;
import org.apache.isis.core.runtime.system.SystemConstants;
import org.apache.isis.core.runtime.userprofile.UserProfileStoreInstaller;
import org.apache.isis.core.runtime.viewer.IsisViewerInstaller;
import org.apache.isis.core.runtime.web.EmbeddedWebServerInstaller;
import org.apache.log4j.Logger;

import com.google.inject.Inject;


/**
 * This class retrieves named {@link Installer}s from those loaded at creation, updating the
 * {@link IsisConfiguration} as it goes.
 * 
 * <p>
 * A list of possible classes are read in from the resource file <tt>installer-registry.properties</tt>. Each
 * installer has a unique name (with respect to its type) that will be compared when one of this classes
 * methods are called. These are instantiated when requested.
 * 
 * <p>
 * Note that it <i>is</i> possible to use an {@link Installer} implementation even if it has not been
 * registered in <tt>installer-registry.properties</tt> : just specify the {@link Installer}'s fully qualified
 * class name.
 */
public class InstallerLookupDefault implements InstallerLookup {

    private static final Logger LOG = Logger.getLogger(InstallerLookupDefault.class);

    public final String INSTALLER_REGISTRY_FILE = "installer-registry.properties";

    private final List<Installer> installerList = new ArrayList<Installer>();
    private final Class<?> cls;

    /**
     * A mutable representation of the {@link IsisConfiguration configuration}, injected prior to
     * {@link #init()}.
     * 
     * <p>
     * 
     * @see #setConfigurationBuilder(ConfigurationBuilder)
     */
    private ConfigurationBuilder configurationBuilder;

    // ////////////////////////////////////////////////////////
    // Constructor
    // ////////////////////////////////////////////////////////

    public InstallerLookupDefault(Class<?> cls) {
        this.cls = cls;
        loadInstallers();
    }

    private void loadInstallers() {
        final InputStream in = getInstallerRegistryStream(INSTALLER_REGISTRY_FILE, cls);
        final BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                final String className = StringUtils.firstWord(line);
                if (className.length() == 0 || className.startsWith("#")) {
                    continue;
                }
                try {
                    final Installer object = (Installer) InstanceFactory.createInstance(className);
                    LOG.debug("created component installer: " + object.getName() + " - " + className);
                    installerList.add(object);
                } catch (final UnavailableClassException e) {
                    LOG.info("component installer not found; it will not be available: " + className);
                } catch (final InstanceCreationClassException e) {
                    LOG.info("instance creation exception: " + e.getMessage());
                } catch (final InstanceCreationException e) {
                    throw e;
                }
            }
        } catch (final IOException e) {
            throw new IsisException(e);
        } finally {
            close(reader);
        }

        List<ComponentDetails> installerVersionList = new ArrayList<ComponentDetails>();
        for (Installer installer : installerList) {
            installerVersionList.add(new InstallerVersion(installer));
        }
        AboutIsis.setComponentDetails(installerVersionList);
    }

    // ////////////////////////////////////////////////////////
    // InstallerRepository impl.
    // ////////////////////////////////////////////////////////

    /**
     * This method (and only this method) may be called prior to {@link #init() initialization}.
     */
    public Installer[] getInstallers(final Class<?> cls) {
        final List<Installer> list = new ArrayList<Installer>();
        for (final Installer comp : installerList) {
            if (cls.isAssignableFrom(comp.getClass())) {
                list.add(comp);
            }
        }
        return (Installer[]) list.toArray(new Installer[list.size()]);
    }

    // ////////////////////////////////////////////////////////
    // init, shutdown
    // ////////////////////////////////////////////////////////

    public void init() {
        ensureDependenciesInjected();
    }

    private void ensureDependenciesInjected() {
        Ensure.ensureThatState(configurationBuilder, is(not(nullValue())));
    }

    public void shutdown() {
    // nothing to do.
    }

    // ////////////////////////////////////////////////////////
    // Type-safe Lookups
    // ////////////////////////////////////////////////////////

    public AuthenticationManagerInstaller authenticationManagerInstaller(String requested, final DeploymentType deploymentType) {
        return getInstaller(AuthenticationManagerInstaller.class, requested, SystemConstants.AUTHENTICATION_INSTALLER_KEY, 
               deploymentType.isExploring() ? SystemConstants.AUTHENTICATION_EXPLORATION_DEFAULT : SystemConstants.AUTHENTICATION_DEFAULT );
    }

    public AuthorizationManagerInstaller authorizationManagerInstaller(String requested, final DeploymentType deploymentType) {
        return getInstaller(AuthorizationManagerInstaller.class, requested, SystemConstants.AUTHORIZATION_INSTALLER_KEY,
                !deploymentType.isProduction() ? SystemConstants.AUTHORIZATION_NON_PRODUCTION_DEFAULT : SystemConstants.AUTHORIZATION_DEFAULT);
    }

    public FixturesInstaller fixturesInstaller(String requested) {
        return getInstaller(FixturesInstaller.class, requested, SystemConstants.FIXTURES_INSTALLER_KEY,
                SystemConstants.FIXTURES_INSTALLER_DEFAULT);
    }

    public TemplateImageLoaderInstaller templateImageLoaderInstaller(String requested) {
        return getInstaller(TemplateImageLoaderInstaller.class, requested, SystemConstants.IMAGE_LOADER_KEY,
                SystemConstants.IMAGE_LOADER_DEFAULT);
    }

    public PersistenceMechanismInstaller persistenceMechanismInstaller(final String requested, final DeploymentType deploymentType) {
        String persistorDefault = deploymentType.isExploring() || deploymentType.isPrototyping() ? SystemConstants.OBJECT_PERSISTOR_NON_PRODUCTION_DEFAULT
                : SystemConstants.OBJECT_PERSISTOR_PRODUCTION_DEFAULT;
        return getInstaller(PersistenceMechanismInstaller.class, requested, SystemConstants.OBJECT_PERSISTOR_KEY,
                persistorDefault);
    }

    public UserProfileStoreInstaller userProfilePersistenceMechanismInstaller(String requested, DeploymentType deploymentType) {
        String profileStoreDefault = deploymentType.isExploring() || deploymentType.isPrototyping() ? SystemConstants.USER_PROFILE_STORE_NON_PRODUCTION_DEFAULT
                : SystemConstants.USER_PROFILE_STORE_PRODUCTION_DEFAULT;
        return getInstaller(UserProfileStoreInstaller.class, requested, SystemConstants.USER_PROFILE_STORE_KEY,
                profileStoreDefault);
    }

    public ObjectReflectorInstaller reflectorInstaller(final String requested) {
        return getInstaller(ObjectReflectorInstaller.class, requested, SystemConstants.REFLECTOR_KEY,
                SystemConstants.REFLECTOR_DEFAULT);
    }

    public EmbeddedWebServerInstaller embeddedWebServerInstaller(final String requested) {
        return getInstaller(EmbeddedWebServerInstaller.class, requested, SystemConstants.WEBSERVER_KEY,
                SystemConstants.WEBSERVER_DEFAULT);
    }

    /**
     * Client-side of <tt>remoting</tt>, specifying how to access the server.
     * 
     * <p>
     * This lookup is called in three different contexts:
     * <ul>
     * <li>the <tt>IsisSystemFactoryUsingInstallers</tt> uses this to lookup the
     * {@link PersistenceMechanismInstaller} (may be a <tt>ProxyPersistor</tt>)</li>
     * <li>the <tt>IsisSystemFactoryUsingInstallers</tt> also uses this to lookup the
     * {@link FacetDecoratorInstaller}; adds in remoting facets.</li>
     * <li>the <tt>IsisSystemUsingInstallers</tt> uses this to lookup the
     * {@link AuthenticationManagerInstaller}.</li>
     * </ul>
     * 
     * <p>
     * In addition to the usual {@link #mergeConfigurationFor(Installer) merging} of any {@link Installer}
     * -specific configuration files, this lookup also merges in any
     * {@link ClientConnectionInstaller#getRemoteProperties() remote properties} available.
     */
    public ClientConnectionInstaller clientConnectionInstaller(final String requested) {
        return getInstaller(ClientConnectionInstaller.class, requested, SystemConstants.CLIENT_CONNECTION_KEY,
                SystemConstants.CLIENT_CONNECTION_DEFAULT);
    }

    public IsisViewerInstaller viewerInstaller(final String name, final String defaultName) {
        String viewer;
        if (name == null) {
            viewer = getConfiguration().getString(SystemConstants.VIEWER_KEY, defaultName);
        } else {
            viewer = name;
        }
        if (viewer == null) {
            return null;
        }
        return getInstaller(IsisViewerInstaller.class, viewer);
    }

    public IsisViewerInstaller viewerInstaller(final String name) {
        final IsisViewerInstaller installer = getInstaller(IsisViewerInstaller.class, name);
        if (installer == null) {
            throw new IsisException("No viewer installer of type " + name);
        }
        return installer;
    }

    public ServicesInstaller servicesInstaller(final String requestedImplementationName) {
        return getInstaller(ServicesInstaller.class, requestedImplementationName, SystemConstants.SERVICES_INSTALLER_KEY,
                SystemConstants.SERVICES_INSTALLER_DEFAULT);
    }

    // ////////////////////////////////////////////////////////
    // Generic Lookups
    // ////////////////////////////////////////////////////////

    @SuppressWarnings("unchecked")
    public <T extends Installer> T getInstaller(final Class<T> cls, final String implName) {
        Assert.assertNotNull("No name specified", implName);
        for (final Installer installer : installerList) {
            if (cls.isAssignableFrom(installer.getClass()) && installer.getName().equals(implName)) {
                mergeConfigurationFor(installer);
                injectDependenciesInto(installer);
                return (T) installer;
            }
        }
        return (T) getInstaller(implName);
    }

    @SuppressWarnings("unchecked")
    public Installer getInstaller(final String implClassName) {
        try {
            Installer installer = CastUtils.cast(InstanceFactory.createInstance(implClassName));
            if (installer != null) {
                mergeConfigurationFor(installer);
                injectDependenciesInto(installer);
            }
            return installer;
        } catch (final InstanceCreationException e) {
            throw new InstanceCreationException("Specification error in " + INSTALLER_REGISTRY_FILE, e);
        } catch (final UnavailableClassException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends Installer> T getInstaller(final Class<T> installerCls) {
        try {
            T installer = (T) (InstanceFactory.createInstance(installerCls));
            if (installer != null) {
                mergeConfigurationFor(installer);
                injectDependenciesInto(installer);
            }
            return installer;
        } catch (final InstanceCreationException e) {
            throw new InstanceCreationException("Specification error in " + INSTALLER_REGISTRY_FILE, e);
        } catch (final UnavailableClassException e) {
            return null;
        }
    }

    // ////////////////////////////////////////////////////////
    // Helpers
    // ////////////////////////////////////////////////////////

    private <T extends Installer> T getInstaller(Class<T> requiredType, String reqImpl, String key, String defaultImpl) {
        if (reqImpl == null) {
            reqImpl = getConfiguration().getString(key, defaultImpl);
        }
        if (reqImpl == null) {
            return null;
        }
        T installer = getInstaller(requiredType, reqImpl);
        if (installer == null) {
            throw new InstanceCreationException("Failed to load installer class '" + reqImpl + "' (of type "
                    + requiredType.getName() + ")");
        }
        return installer;
    }

    private void close(final BufferedReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (final IOException e) {
                throw new IsisException(e);
            }
        }
    }

    private InputStream getInstallerRegistryStream(final String componentFile, Class<?> cls) {
        final InputStream in = cls.getResourceAsStream("/" + componentFile);
        if (in == null) {
            throw new IsisException("No resource found: " + componentFile);
        }
        return in;
    }

    // ////////////////////////////////////////////////////////
    // Configuration
    // ////////////////////////////////////////////////////////

    public IsisConfiguration getConfiguration() {
        return configurationBuilder.getConfiguration();
    }

    public void mergeConfigurationFor(Installer installer) {
        for (String installerConfigResource : installer.getConfigurationResources()) {
            configurationBuilder.addConfigurationResource(installerConfigResource, NotFoundPolicy.CONTINUE);
        }
    }

    public <T> T injectDependenciesInto(T candidate) {
        injectInto(candidate);
        return candidate;
    }

    // ////////////////////////////////////////////////////////////////////
    // Injectable
    // ////////////////////////////////////////////////////////////////////

    public void injectInto(Object candidate) {
        if (InstallerLookupAware.class.isAssignableFrom(candidate.getClass())) {
            InstallerLookupAware cast = InstallerLookupAware.class.cast(candidate);
            cast.setInstallerLookup(this);
        }
        configurationBuilder.injectInto(candidate);
    }

    // ////////////////////////////////////////////////////////
    // Dependencies (injected)
    // ////////////////////////////////////////////////////////

    public ConfigurationBuilder getConfigurationBuilder() {
        return configurationBuilder;
    }

    @Inject
    public void setConfigurationBuilder(final ConfigurationBuilder configurationLoader) {
        this.configurationBuilder = configurationLoader;
    }

}

