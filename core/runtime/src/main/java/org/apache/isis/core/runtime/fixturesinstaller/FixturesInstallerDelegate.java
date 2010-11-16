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


package org.apache.isis.core.runtime.fixturesinstaller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.log4j.Logger;
import org.apache.isis.applib.fixtures.CompositeFixture;
import org.apache.isis.applib.fixtures.FixtureType;
import org.apache.isis.applib.fixtures.InstallableFixture;
import org.apache.isis.applib.fixtures.LogonFixture;
import org.apache.isis.applib.profiles.ProfileService;
import org.apache.isis.applib.profiles.ProfileServiceAware;
import org.apache.isis.core.commons.lang.CastUtils;
import org.apache.isis.core.metamodel.services.ServicesInjector;
import org.apache.isis.core.runtime.context.IsisContext;
import org.apache.isis.core.runtime.persistence.PersistenceSession;
import org.apache.isis.core.runtime.system.DeploymentType;
import org.apache.isis.core.runtime.transaction.IsisTransactionManager;


/**
 * Helper for {@link FixturesInstaller} implementations.
 * 
 * <p>
 * Does the mechanics of actually installing the fixtures.
 */
public class FixturesInstallerDelegate {

	private static final Logger LOG = Logger.getLogger(FixturesInstallerDelegate.class);

    protected final List<Object> fixtures = new ArrayList<Object>();
    private final SwitchUserServiceImpl switchUserService = new SwitchUserServiceImpl();
    private final ProfileService perspectivePersistenceService = new ProfileServiceImpl();

    /**
     * Optionally injected in {@link #FixtureBuilderImpl(PersistenceSession) constructor}.
     */
    private PersistenceSession persistenceSession;


    /**
     * The requested {@link LogonFixture}, if any.
     * 
     * <p>
     * Each fixture is inspected as it is {@link #installFixture(Object)}; if it
     * implements {@link LogonFixture} then it is remembered so that it can be
     * used later to automatically logon.
     */
    private LogonFixture logonFixture;
    
    
    ///////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////
    
    
    /**
     * The {@link PersistenceSession} used will be that from {@link IsisContext#getPersistenceSession() IsisContext}.
     */
    public FixturesInstallerDelegate() {
    	this(null);
    }

    /**
     * For testing, supply own {@link PersistenceSession} rather than lookup from context.
     * 
     * @param persistenceSession
     */
    public FixturesInstallerDelegate(final PersistenceSession persistenceSession) {
    	this.persistenceSession = persistenceSession;
    }

    
    ///////////////////////////////////////////////////////////
    // addFixture, getFixtures, clearFixtures
    ///////////////////////////////////////////////////////////
    
    /**
     * Automatically flattens any {@link List}s, recursively (depth-first) if necessary.
     */
    public void addFixture(final Object fixture) {
        if (fixture instanceof List) {
            final List<Object> fixtureList = CastUtils.listOf(fixture, Object.class);
            for(Object eachFixture: fixtureList) {
            	addFixture(eachFixture);
            }
        } else {
        	fixtures.add(fixture);
        }
    }


    /**
     * Returns all fixtures that have been {@link #addFixture(Object) added}.
     */
    protected List<Object> getFixtures() {
        return Collections.unmodifiableList(fixtures);
    }

    /**
     * Allows the set of fixtures to be cleared (for whatever reason).
     */
    public void clearFixtures() {
        fixtures.clear();
    }



    ///////////////////////////////////////////////////////////
    // installFixtures
    ///////////////////////////////////////////////////////////

    /**
     * Installs all {{@link #addFixture(Object) added fixtures} fixtures (ie as returned by
     * {@link #getFixtures()}).
     * 
     * <p>
     * The set of fixtures (as per {@link #getFixtures()}) is <i>not</i> cleared after installation; this
     * allows the {@link FixtureBuilderAbstract} to be reused across multiple tests.
     */
    public final void installFixtures() {
        preInstallFixtures(getPersistenceSession());
        installFixtures(getFixtures());
        postInstallFixtures(getPersistenceSession());
    }

    /////////////////////////////////////////
    // preInstallFixtures
    /////////////////////////////////////////

    /**
     * Hook - default does nothing.
     */
    protected void preInstallFixtures(final PersistenceSession persistenceSession) {}

    /////////////////////////////////////////
    // installFixtures, installFixture
    /////////////////////////////////////////

    private void installFixtures(final List<Object> fixtures) {
        for (final Object fixture: fixtures) {
            installFixtureInTransaction(fixture);
        }
    }

    private void installFixtureInTransaction(final Object fixture) {
        getServicesInjector().injectDependencies(fixture);

        installFixtures(getFixtures(fixture));

        // now, install the fixture itself
        try {
            LOG.info("installing fixture: " + fixture);
            getTransactionManager().startTransaction();
            installFixture(fixture);
            saveLogonFixtureIfRequired(fixture);
            getTransactionManager().endTransaction();
            LOG.info("fixture installed");
        } catch (final RuntimeException e) {
            LOG.error("installing fixture " + fixture.getClass().getName() + " failed; aborting ", e);
            try {
                getTransactionManager().abortTransaction();
            } catch (final Exception e2) {
                LOG.error("failure during abort", e2);
            }
            throw e;
        }
    }

    /**
     * Obtain any child fixtures for this fixture.
     * 
     * @param fixture
     */
    private List<Object> getFixtures(final Object fixture) {
    	if (fixture instanceof CompositeFixture) {
			CompositeFixture compositeFixture = (CompositeFixture) fixture;
    		return compositeFixture.getFixtures();
    	}
    	return Collections.emptyList();
    }

    private void installFixture(final Object fixture) {
    	switchUserService.injectInto(fixture);
    	if (fixture instanceof ProfileServiceAware) {
			ProfileServiceAware serviceAware = (ProfileServiceAware) fixture;
			serviceAware.setService(perspectivePersistenceService);
    	}

    	if (fixture instanceof InstallableFixture) {
    		InstallableFixture installableFixture = (InstallableFixture) fixture;
    		if (shouldInstallFixture(installableFixture)) {
    			installableFixture.install();
    		}
    	}

    	if (fixture instanceof LogonFixture) {
            this.logonFixture = (LogonFixture) fixture;
        }
    }

	private boolean shouldInstallFixture(InstallableFixture installableFixture) {
		final FixtureType fixtureType = installableFixture.getType();
		
		if(fixtureType == FixtureType.OBJECT_STORE) {
			return !IsisContext.getPersistenceSession().isFixturesInstalled();
		}
		
		if(fixtureType == FixtureType.USER_PROFILE) {
			return !IsisContext.getUserProfileLoader().isFixturesInstalled();
		}

		// fixtureType is OTHER; always install.
		return true;
	}

	private void saveLogonFixtureIfRequired(final Object fixture) {
		if (fixture instanceof LogonFixture) {
			if (logonFixture != null) {
				LOG.warn("Already specified logon fixture, using latest provided");
			}
			this.logonFixture = (LogonFixture) fixture;
		}
	}

    /////////////////////////////////////////
    // postInstallFixtures
    /////////////////////////////////////////

    /**
     * Hook - default does nothing.
     */
    protected void postInstallFixtures(final PersistenceSession persistenceSession) {}



    ///////////////////////////////////////////////////////////
    // LogonFixture
    ///////////////////////////////////////////////////////////

    /**
     * The {@link LogonFixture}, if any.
     * 
     * <p>
     * Used to automatically logon if in {@link DeploymentType#PROTOTYPE} mode.
     */
    public LogonFixture getLogonFixture() {
		return logonFixture;
	}

    
    ///////////////////////////////////////////////////////////
    // Dependencies (from either context or via constructor)
    ///////////////////////////////////////////////////////////

    /**
     * Returns either the {@link IsisContext#getPersistenceSession() singleton } persistor or the
     * persistor {@link #AbstractFixtureBuilder(PersistenceSession) specified by the constructor} if
     * specified.
     * 
     * <p>
     * Note: if a {@link PersistenceSession persistor} was specified via the constructor, this is not cached
     * (to do so would cause the usage of tests that use the singleton to break).
     */
    private PersistenceSession getPersistenceSession() {
        return persistenceSession != null ? persistenceSession : IsisContext.getPersistenceSession();
    }

    private ServicesInjector getServicesInjector() {
        return getPersistenceSession().getServicesInjector();
    }

    private IsisTransactionManager getTransactionManager() {
        return getPersistenceSession().getTransactionManager();
    }


}
