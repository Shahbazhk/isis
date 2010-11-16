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

package org.apache.isis.core.runtime.context;

import java.util.List;

import org.apache.isis.core.commons.components.TransactionScopedComponent;
import org.apache.isis.core.commons.debug.DebugInfo;
import org.apache.isis.core.commons.debug.DebugList;
import org.apache.isis.core.commons.debug.DebugString;
import org.apache.isis.core.commons.exceptions.IsisException;
import org.apache.isis.core.metamodel.authentication.AuthenticationSession;
import org.apache.isis.core.metamodel.config.ConfigurationException;
import org.apache.isis.core.metamodel.config.IsisConfiguration;
import org.apache.isis.core.metamodel.specloader.SpecificationLoader;
import org.apache.isis.core.runtime.authentication.AuthenticationManager;
import org.apache.isis.core.runtime.authorization.AuthorizationManager;
import org.apache.isis.core.runtime.imageloader.TemplateImageLoader;
import org.apache.isis.core.runtime.persistence.PersistenceSession;
import org.apache.isis.core.runtime.session.IsisSession;
import org.apache.isis.core.runtime.session.IsisSessionFactory;
import org.apache.isis.core.runtime.system.DeploymentType;
import org.apache.isis.core.runtime.transaction.IsisTransaction;
import org.apache.isis.core.runtime.transaction.IsisTransactionManager;
import org.apache.isis.core.runtime.transaction.messagebroker.MessageBroker;
import org.apache.isis.core.runtime.transaction.updatenotifier.UpdateNotifier;
import org.apache.isis.core.runtime.userprofile.UserProfile;
import org.apache.isis.core.runtime.userprofile.UserProfileLoader;
import org.apache.log4j.Logger;

/**
 * Provides singleton <i>access to</i> the current (session scoped) {@link IsisSession}, along with convenience methods
 * to obtain application-scoped components and also any transaction-scoped components {@link TransactionScopedComponent}
 * s if a {@link IsisTransaction} {@link IsisSession#getCurrentTransaction() is in progress}.
 * 
 * <p>
 * Somewhat analogous to (the static methods in) <tt>HibernateUtil</tt>.
 */
public abstract class IsisContext implements DebugInfo {

    private static final Logger LOG = Logger.getLogger(IsisContext.class);

    private static IsisContext singleton;

    private final IsisSessionFactory sessionFactory;
    private final ContextReplacePolicy replacePolicy;
    private final SessionClosePolicy sessionClosePolicy;

    private static IsisConfiguration configuration;

    // ///////////////////////////////////////////////////////////
    // Singleton & Constructor
    // ///////////////////////////////////////////////////////////

    /**
     * Returns the singleton providing access to the set of execution contexts.
     */
    public static IsisContext getInstance() {
        return singleton;
    }

    /**
     * Whether a singleton has been created using {@link #getInstance()}.
     */
    public static boolean exists() {
        return singleton != null;
    }

    /**
     * Resets the singleton, so another can created.
     * 
     * @see #Isis()
     */
    public static void testReset() {
        singleton = null;
    }

    protected static enum SessionClosePolicy {
        /**
         * Sessions must be explicitly closed.
         */
        EXPLICIT_CLOSE,
        /**
         * Sessions will be automatically closed.
         */
        AUTO_CLOSE;
    }

    /**
     * Whether the {@link IsisContext#getInstance() singleton} itself may be replaced.
     */
    protected static enum ContextReplacePolicy {
        NOT_REPLACEABLE, REPLACEABLE
    }

    /**
     * Creates a new instance of the {@link IsisSession} holder.
     * 
     * <p>
     * Will throw an exception if an instance has already been created and is not
     * {@link ContextReplacePolicy#REPLACEABLE}.
     */
    protected IsisContext(final ContextReplacePolicy replacePolicy, final SessionClosePolicy sessionClosePolicy,
        final IsisSessionFactory sessionFactory) {
        if (singleton != null && !singleton.isContextReplaceable()) {
            throw new IsisException("Isis Context already set up and cannot be replaced");
        }
        singleton = this;
        this.sessionFactory = sessionFactory;
        this.sessionClosePolicy = sessionClosePolicy;
        this.replacePolicy = replacePolicy;
    }

    // ///////////////////////////////////////////////////////////
    // SessionFactory
    // ///////////////////////////////////////////////////////////

    /**
     * As injected in constructor.
     */
    public final IsisSessionFactory getSessionFactoryInstance() {
        return sessionFactory;
    }

    // ///////////////////////////////////////////////////////////
    // Policies
    // ///////////////////////////////////////////////////////////

    /**
     * Whether a context singleton can simply be replaced or not.
     */
    public final boolean isContextReplaceable() {
        return replacePolicy == ContextReplacePolicy.REPLACEABLE;
    }

    /**
     * Whether any open session can be automatically {@link #closeSessionInstance() close}d on
     * {@link #openSessionInstance(AuthenticationSession) open}.
     */
    public final boolean isSessionAutocloseable() {
        return sessionClosePolicy == SessionClosePolicy.AUTO_CLOSE;
    }

    /**
     * Helper method for subclasses' implementation of {@link #openSessionInstance(AuthenticationSession)}.
     */
    protected void applySessionClosePolicy() {
        if (getSessionInstance() == null) {
            return;
        }
        if (!isSessionAutocloseable()) {
            throw new IllegalStateException("Session already open and context not configured for autoclose");
        }
        closeSessionInstance();
    }

    // ///////////////////////////////////////////////////////////
    // open / close / shutdown
    // ///////////////////////////////////////////////////////////

    /**
     * Creates a new {@link IsisSession} and binds into the current context.
     * 
     * @throws IllegalStateException
     *             if already opened.
     */
    public abstract IsisSession openSessionInstance(AuthenticationSession session);

    /**
     * Closes the {@link IsisSession} for the current context.
     * 
     * <p>
     * Ignored if already closed.
     * 
     * <p>
     * This method is <i>not</i> marked <tt>final</tt> so it can be overridden if necessarily. Generally speaking this
     * shouldn't be necessary; one case where it might though is if an implementation has multiple concurrent uses of a
     * session, in which case "closing" the session really means just deregistering the usage of it by a particular
     * thread; only when all threads have finished with a session can it really be closed.
     */
    public void closeSessionInstance() {
        if (getSessionInstance() != null) {
            getSessionInstance().close();
            doClose();
        }
    }

    /**
     * Overridable hook method called from {@link #closeSessionInstance()}, allowing subclasses to clean up (for example
     * datastructures).
     * 
     * <p>
     * The {@link #getSessionInstance() current} {@link IsisSession} will already have been {@link IsisSession#close()
     * closed}.
     */
    protected void doClose() {
    }

    /**
     * Shutdown the application.
     */
    protected abstract void closeAllSessionsInstance();

    // ///////////////////////////////////////////////////////////
    // getSession()
    // ///////////////////////////////////////////////////////////

    /**
     * Locates the current {@link IsisSession}.
     * 
     * <p>
     * This might just be a singleton (eg {@link IsisContextStatic}), or could be retrieved from the thread (eg
     * {@link IsisContextThreadLocal}).
     */
    public abstract IsisSession getSessionInstance();

    /**
     * The {@link IsisSession} for specified {@link IsisSession#getId()}.
     */
    protected abstract IsisSession getSessionInstance(String sessionId);

    /**
     * All known session Ids.
     * 
     * <p>
     * Provided primarily for debugging.
     */
    public abstract String[] allSessionIds();

    // ///////////////////////////////////////////////////////////
    // Static Convenience methods (session management)
    // ///////////////////////////////////////////////////////////

    /**
     * Convenience method to open a new {@link IsisSession}.
     * 
     * @see #openSessionInstance(AuthenticationSession)
     */
    public static IsisSession openSession(final AuthenticationSession authenticationSession) {
        return getInstance().openSessionInstance(authenticationSession);
    }

    /**
     * Convenience method to close the current {@link IsisSession}.
     * 
     * @see #closeSessionInstance()
     */
    public static void closeSession() {
        getInstance().closeSessionInstance();
    }

    /**
     * Convenience method to return {@link IsisSession} for specified {@link IsisSession#getId()}.
     * 
     * <p>
     * Provided primarily for debugging.
     * 
     * @see #getSessionInstance(String)
     */
    public static IsisSession getSession(final String sessionId) {
        return getInstance().getSessionInstance(sessionId);
    }

    /**
     * Convenience method to close all sessions.
     */
    public static void closeAllSessions() {
        LOG.info("closing all instances");
        IsisContext instance = getInstance();
        if (instance != null) {
            instance.closeAllSessionsInstance();
        }
    }

    // ///////////////////////////////////////////////////////////
    // Static Convenience methods (application scoped)
    // ///////////////////////////////////////////////////////////

    /**
     * Convenience method returning the {@link IsisSessionFactory} of the current {@link #getSession() session}.
     */
    public static IsisSessionFactory getSessionFactory() {
        return getInstance().getSessionFactoryInstance();
    }

    /**
     * Convenience method.
     * 
     * @see IsisSessionFactory#getConfiguration()
     */
    public static IsisConfiguration getConfiguration() {
        if (configuration == null) {
            throw new ConfigurationException("No configuration available");
        }
        // REVIEW
        return configuration;
        // return getSessionFactory().getConfiguration();
    }

    public static void setConfiguration(IsisConfiguration configuration) {
        IsisContext.configuration = configuration;
    }

    /**
     * Convenience method.
     * 
     * @see IsisSessionFactory#getDeploymentType()
     */
    public static DeploymentType getDeploymentType() {
        return getSessionFactory().getDeploymentType();
    }

    /**
     * Convenience method.
     * 
     * @see IsisSessionFactory#getSpecificationLoader()
     */
    public static SpecificationLoader getSpecificationLoader() {
        return getSessionFactory().getSpecificationLoader();
    }

    /**
     * Convenience method.
     * 
     * @see IsisSessionFactory#getAuthenticationManager()
     */
    public static AuthenticationManager getAuthenticationManager() {
        return getSessionFactory().getAuthenticationManager();
    }

    /**
     * Convenience method.
     * 
     * @see IsisSessionFactory#getAuthorizationManager()
     */
    public static AuthorizationManager getAuthorizationManager() {
        return getSessionFactory().getAuthorizationManager();
    }

    /**
     * Convenience method.
     * 
     * @see IsisSessionFactory#getTemplateImageLoader()
     */
    public static TemplateImageLoader getTemplateImageLoader() {
        return getSessionFactory().getTemplateImageLoader();
    }

    public static UserProfileLoader getUserProfileLoader() {
        return getSessionFactory().getUserProfileLoader();
    }

    public static List<Object> getServices() {
        return getSessionFactory().getServices();
    }

    // ///////////////////////////////////////////////////////////
    // Static Convenience methods (session scoped)
    // ///////////////////////////////////////////////////////////

    public static boolean inSession() {
        IsisSession session = getInstance().getSessionInstance();
        return session != null;
    }

    /**
     * Convenience method returning the current {@link IsisSession}.
     */
    public static IsisSession getSession() {
        IsisSession session = getInstance().getSessionInstance();
        if (session == null) {
            throw new IllegalStateException("No Session opened for this thread");
        }
        return session;
    }

    /**
     * Convenience method to return the {@link #getSession() current} {@link IsisSession}'s {@link IsisSession#getId()
     * id}.
     * 
     * @see IsisSession#getId()
     */
    public static String getSessionId() {
        return getSession().getId();
    }

    /**
     * @see IsisSession#getAuthenticationSession()
     */
    public static AuthenticationSession getAuthenticationSession() {
        return getSession().getAuthenticationSession();
    }

    /**
     * Convenience method.
     * 
     * @see IsisSession#getPersistenceSession()
     */
    public static PersistenceSession getPersistenceSession() {
        return getSession().getPersistenceSession();
    }

    /**
     * Convenience method.
     * 
     * @see IsisSession#getUserProfile()
     */
    public static UserProfile getUserProfile() {
        return getSession().getUserProfile();
    }

    /**
     * Convenience methods
     * 
     * @see IsisSession#getPersistenceSession()
     * @see PersistenceSession#getTransactionManager()
     */
    public static IsisTransactionManager getTransactionManager() {
        return getPersistenceSession().getTransactionManager();
    }

    // ///////////////////////////////////////////////////////////
    // Static Convenience methods (transaction scoped)
    // ///////////////////////////////////////////////////////////

    public static boolean inTransaction() {
        return inSession() && getCurrentTransaction() != null && !getCurrentTransaction().getState().isComplete();
    }

    /**
     * Convenience method, returning the current {@link IsisTransaction transaction} (if any).
     * 
     * <p>
     * Transactions are managed using the {@link IsisTransactionManager} obtainable from the {@link IsisSession's}
     * {@link PersistenceSession}.
     * 
     * @see IsisSession#getCurrentTransaction()
     * @see PersistenceSession#getTransactionManager()
     */
    public static IsisTransaction getCurrentTransaction() {
        return getSession().getCurrentTransaction();
    }

    /**
     * Convenience method, returning the {@link MessageBroker} of the {@link #getCurrentTransaction() current
     * transaction}.
     */
    public static MessageBroker getMessageBroker() {
        return getCurrentTransaction().getMessageBroker();
    }

    /**
     * Convenience method, returning the {@link UpdateNotifier} of the {@link #getCurrentTransaction() current
     * transaction}.
     */
    public static UpdateNotifier getUpdateNotifier() {
        return getCurrentTransaction().getUpdateNotifier();
    }

    // ///////////////////////////////////////////////////////////
    // Debug
    // ///////////////////////////////////////////////////////////

    public static DebugInfo[] debugSystem() {
        DebugList debugList = new DebugList("Apache Isis System");
        debugList.add("Context", getInstance());
        debugList.add("Apache Isis session factory", getSessionFactory());
        debugList.add("  Authentication manager", getSessionFactory().getAuthenticationManager());
        debugList.add("  Persistence session factory", getSessionFactory().getPersistenceSessionFactory());
        debugList.add("User profile loader", getUserProfileLoader());

        debugList.add("Reflector", getSpecificationLoader());
        debugList.add("Template image loader", getTemplateImageLoader());

        debugList.add("Deployment type", getDeploymentType().getDebug());
        debugList.add("Configuration", getConfiguration());

        debugList.add("Services", getServices());
        return debugList.debug();
    }

    public static DebugInfo[] debugSession() {
        DebugList debugList = new DebugList("Apache Isis Session");
        debugList.add("Apache Isis session", getSession());
        debugList.add("Authentication session", getAuthenticationSession());
        debugList.add("User profile", getUserProfile());

        debugList.add("Persistence Session", getPersistenceSession());
        debugList.add("Transaction Manager", getTransactionManager());

        debugList.add("Service injector", getPersistenceSession().getServicesInjector());
        debugList.add("Adapter factory", getPersistenceSession().getAdapterFactory());
        debugList.add("Object factory", getPersistenceSession().getObjectFactory());
        debugList.add("OID generator", getPersistenceSession().getOidGenerator());
        debugList.add("Adapter manager", getPersistenceSession().getAdapterManager());
        debugList.add("Services", getPersistenceSession().getServices());
        return debugList.debug();
    }

    @Override
    public void debugData(final DebugString debug) {
        debug.appendln("context ", this);
    }

}
