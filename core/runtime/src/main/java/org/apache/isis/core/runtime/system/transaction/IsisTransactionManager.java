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

package org.apache.isis.core.runtime.system.transaction;

import static org.apache.isis.core.commons.ensure.Ensure.ensureThatArg;
import static org.apache.isis.core.commons.ensure.Ensure.ensureThatState;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;

import java.util.List;

import org.apache.isis.applib.Identifier;
import org.apache.isis.applib.annotation.PublishedAction;
import org.apache.isis.applib.annotation.PublishedObject;
import org.apache.isis.applib.annotation.PublishedObject.EventCanonicalizer;
import org.apache.isis.applib.services.audit.AuditingService;
import org.apache.isis.applib.services.publish.CanonicalEvent;
import org.apache.isis.applib.services.publish.PublishingService;
import org.apache.isis.core.commons.authentication.AuthenticationSession;
import org.apache.isis.core.commons.components.SessionScopedComponent;
import org.apache.isis.core.commons.debug.DebugBuilder;
import org.apache.isis.core.commons.exceptions.IsisException;
import org.apache.isis.core.metamodel.adapter.ObjectAdapter;
import org.apache.isis.core.metamodel.services.ServicesInjectorSpi;
import org.apache.isis.core.runtime.persistence.objectstore.transaction.PersistenceCommand;
import org.apache.isis.core.runtime.persistence.objectstore.transaction.PublishingServiceWithCanonicalizers;
import org.apache.isis.core.runtime.persistence.objectstore.transaction.TransactionalResource;
import org.apache.isis.core.runtime.system.context.IsisContext;
import org.apache.isis.core.runtime.system.session.IsisSession;
import org.apache.log4j.Logger;

public class IsisTransactionManager implements SessionScopedComponent {


    private static final Logger LOG = Logger.getLogger(IsisTransactionManager.class);

    private final EnlistedObjectDirtying objectPersistor;
    private final TransactionalResource transactionalResource;

    private int transactionLevel;
    
    /**
     * Could be null.
     */
    private final AuditingService auditingService;
    /**
     * Could be null.
     */
    private final PublishingServiceWithCanonicalizers publishingService;

    private IsisSession session;

    /**
     * Holds the current or most recently completed transaction.
     */
    private IsisTransaction transaction;


    // ////////////////////////////////////////////////////////////////
    // constructor
    // ////////////////////////////////////////////////////////////////

    public IsisTransactionManager(final EnlistedObjectDirtying objectPersistor, final TransactionalResource objectStore, final ServicesInjectorSpi servicesInjectorSpi) {
        this.objectPersistor = objectPersistor;
        this.transactionalResource = objectStore;
        
        this.auditingService = (AuditingService) servicesInjectorSpi.lookupService(AuditingService.class);
        this.publishingService = getPublishingServiceIfAny(servicesInjectorSpi);
    }
    
    
    public TransactionalResource getTransactionalResource() {
        return transactionalResource;
    }
    
    // ////////////////////////////////////////////////////////////////
    // open, close
    // ////////////////////////////////////////////////////////////////

    @Override
    public void open() {
        ensureThatState(session, is(notNullValue()), "session is required");
    }

    @Override
    public void close() {
        if (getTransaction() != null) {
            try {
                abortTransaction();
            } catch (final Exception e2) {
                LOG.error("failure during abort", e2);
            }
        }
        session = null;
    }

    // //////////////////////////////////////////////////////
    // current transaction (if any)
    // //////////////////////////////////////////////////////

    /**
     * The current transaction, if any.
     */
    public IsisTransaction getTransaction() {
        return transaction;
    }

    public int getTransactionLevel() {
        return transactionLevel;
    }


    
    /**
     * Convenience method returning the {@link UpdateNotifier} of the
     * {@link #getTransaction() current transaction}.
     */
    protected UpdateNotifier getUpdateNotifier() {
        return getTransaction().getUpdateNotifier();
    }

    /**
     * Convenience method returning the {@link MessageBroker} of the
     * {@link #getTransaction() current transaction}.
     */
    protected MessageBroker getMessageBroker() {
        return getTransaction().getMessageBroker();
    }

    
    // ////////////////////////////////////////////////////////////////
    // Transactional Execution
    // ////////////////////////////////////////////////////////////////

    /**
     * Run the supplied {@link Runnable block of code (closure)} in a
     * {@link IsisTransaction transaction}.
     * 
     * <p>
     * If a transaction is {@link IsisContext#inTransaction() in progress}, then
     * uses that. Otherwise will {@link #startTransaction() start} a transaction
     * before running the block and {@link #endTransaction() commit} it at the
     * end. If the closure throws an exception, then will
     * {@link #abortTransaction() abort} the transaction.
     */
    public void executeWithinTransaction(final TransactionalClosure closure) {
        final boolean initiallyInTransaction = inTransaction();
        if (!initiallyInTransaction) {
            startTransaction();
        }
        try {
            closure.preExecute();
            closure.execute();
            closure.onSuccess();
            if (!initiallyInTransaction) {
                endTransaction();
            }
        } catch (final RuntimeException ex) {
            closure.onFailure();
            if (!initiallyInTransaction) {
                // temp TODO fix swallowing of exception
                // System.out.println(ex.getMessage());
                // ex.printStackTrace();
                try {
                    abortTransaction();
                } catch (final Exception e) {
                    LOG.error("Abort failure after exception", e);
                    // System.out.println(e.getMessage());
                    // e.printStackTrace();
                    throw new IsisTransactionManagerException("Abort failure: " + e.getMessage(), ex);
                }
            }
            throw ex;
        }
    }

    /**
     * Run the supplied {@link Runnable block of code (closure)} in a
     * {@link IsisTransaction transaction}.
     * 
     * <p>
     * If a transaction is {@link IsisContext#inTransaction() in progress}, then
     * uses that. Otherwise will {@link #startTransaction() start} a transaction
     * before running the block and {@link #endTransaction() commit} it at the
     * end. If the closure throws an exception, then will
     * {@link #abortTransaction() abort} the transaction.
     */
    public <Q> Q executeWithinTransaction(final TransactionalClosureWithReturn<Q> closure) {
        final boolean initiallyInTransaction = inTransaction();
        if (!initiallyInTransaction) {
            startTransaction();
        }
        try {
            closure.preExecute();
            final Q retVal = closure.execute();
            closure.onSuccess();
            if (!initiallyInTransaction) {
                endTransaction();
            }
            return retVal;
        } catch (final RuntimeException ex) {
            closure.onFailure();
            if (!initiallyInTransaction) {
                abortTransaction();
            }
            throw ex;
        }
    }

    public boolean inTransaction() {
        return getTransaction() != null && !getTransaction().getState().isComplete();
    }

    // ////////////////////////////////////////////////////////////////
    // create transaction, + hooks
    // ////////////////////////////////////////////////////////////////

    /**
     * Creates a new transaction and saves, to be accessible in
     * {@link #getTransaction()}.
     */
    protected final IsisTransaction createTransaction() {
        return this.transaction = createTransaction(createMessageBroker(), createUpdateNotifier());
    }


    /**
     * The provided {@link MessageBroker} and {@link UpdateNotifier} are
     * obtained from the hook methods ( {@link #createMessageBroker()} and
     * {@link #createUpdateNotifier()}).
     * 
     * @see #createMessageBroker()
     * @see #createUpdateNotifier()
     */
    protected IsisTransaction createTransaction(final MessageBroker messageBroker, final UpdateNotifier updateNotifier) {
        ensureThatArg(messageBroker, is(not(nullValue())));
        ensureThatArg(updateNotifier, is(not(nullValue())));

        return new IsisTransaction(this, messageBroker, updateNotifier, getTransactionalResource(), auditingService, publishingService);
    }
    

    // //////////////////////////////////////////////////////
    // start, flush, abort, end
    // //////////////////////////////////////////////////////

    public synchronized void startTransaction() {

        boolean noneInProgress = false;
        if (getTransaction() == null || getTransaction().getState().isComplete()) {
            noneInProgress = true;

            createTransaction();
            transactionLevel = 0;
            transactionalResource.startTransaction();
        }

        transactionLevel++;

        if (LOG.isDebugEnabled()) {
            LOG.debug("startTransaction: level " + (transactionLevel - 1) + "->" + (transactionLevel) + (noneInProgress ? " (no transaction in progress or was previously completed; transaction created)" : ""));
        }
    }

    public synchronized boolean flushTransaction() {

        if (LOG.isDebugEnabled()) {
            LOG.debug("flushTransaction");
        }

        if (getTransaction() != null) {
            objectPersistor.objectChangedAllDirty();
            getTransaction().flush();
        }
        return false;
    }

    /**
     * Ends the transaction if nesting level is 0.
     */
    public synchronized void endTransaction() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("endTransaction: level " + (transactionLevel) + "->" + (transactionLevel - 1));
        }

        transactionLevel--;
        if (transactionLevel == 0) {

            //
            // TODO: granted, this is some fairly byzantine coding.  but I'm trying to account for different types
            // of object store implementations that could start throwing exceptions at any stage.
            // once the contract/API for the objectstore is better tied down, hopefully can simplify this...
            //
            
            List<IsisException> exceptions = this.getTransaction().getExceptionsIfAny();
            if(exceptions.isEmpty()) {
            
                if (LOG.isDebugEnabled()) {
                    LOG.debug("endTransaction: committing");
                }
                
                objectPersistor.objectChangedAllDirty();
                
                // just in case any additional exceptions were raised...
                exceptions = this.getTransaction().getExceptionsIfAny();
            }
            
            if(exceptions.isEmpty()) {
                getTransaction().commit();
                
                // in case any additional exceptions were raised...
                exceptions = this.getTransaction().getExceptionsIfAny();
            }
            
            if(exceptions.isEmpty()) {
                transactionalResource.endTransaction();
                
                // just in case any additional exceptions were raised...
                exceptions = this.getTransaction().getExceptionsIfAny();
            }
            
            if(!exceptions.isEmpty()) {
                
                if (LOG.isDebugEnabled()) {
                    LOG.debug("endTransaction: aborting instead, " + exceptions.size() + " exception(s) have been raised");
                }
                abortTransaction();
                
                // just in case any additional exceptions were raised...
                exceptions = this.getTransaction().getExceptionsIfAny();
                
                throw exceptionToThrowFrom(exceptions);
            }
            
        } else if (transactionLevel < 0) {
            LOG.error("endTransaction: transactionLevel=" + transactionLevel);
            transactionLevel = 0;
            throw new IllegalStateException(" no transaction running to end (transactionLevel < 0)");
        }
    }


    private IsisException exceptionToThrowFrom(List<IsisException> exceptions) {
        if(exceptions.size() == 1) {
            return exceptions.get(0);
        } 
        final StringBuilder buf = new StringBuilder();
        for (IsisException ope : exceptions) {
            buf.append(ope.getMessage()).append("\n");
        }
        return new IsisException(buf.toString());
    }
    

    public synchronized void abortTransaction() {
        if (getTransaction() != null) {
            getTransaction().abort();
            transactionLevel = 0;
            transactionalResource.abortTransaction();
        }
    }

    public void addCommand(final PersistenceCommand command) {
        getTransaction().addCommand(command);
    }

    
    // ///////////////////////////////////////////
    // Publishing service
    // ///////////////////////////////////////////

    public PublishingServiceWithCanonicalizers getPublishingServiceIfAny(ServicesInjectorSpi servicesInjectorSpi) {
        final PublishingService publishingService = servicesInjectorSpi.lookupService(PublishingService.class);
        if(publishingService == null) {
            return null;
        }
        
        PublishedObject.EventCanonicalizer objectEventCanonicalizer = servicesInjectorSpi.lookupService(PublishedObject.EventCanonicalizer.class);
        if(objectEventCanonicalizer == null) {
            objectEventCanonicalizer = newDefaultObjectEventCanonicalizer();
        }
        
        PublishedAction.EventCanonicalizer actionEventCanonicalizer = servicesInjectorSpi.lookupService(PublishedAction.EventCanonicalizer.class);
        if(actionEventCanonicalizer == null) {
            actionEventCanonicalizer = newDefaultActionEventCanonicalizer();
        }
        
        return new PublishingServiceWithCanonicalizers(publishingService, objectEventCanonicalizer, actionEventCanonicalizer);
    }


    protected PublishedObject.EventCanonicalizer newDefaultObjectEventCanonicalizer() {
        return new PublishedObject.EventCanonicalizer() {
            @Override
            public CanonicalEvent canonicalizeObject(final Object changedObject) {
                return new CanonicalEvent.Default("CHANGED_OBJECT\n    "+oidStrFor(changedObject));
            }
        };
    }

    protected PublishedAction.EventCanonicalizer newDefaultActionEventCanonicalizer() {
        return new PublishedAction.EventCanonicalizer() {

            @Override
            public CanonicalEvent canonicalizeAction(Object invokedObject, Identifier identifier, List<Object> args, Object actionResult) {
                final StringBuilder buf = new StringBuilder();
                buf.append("ACTION\n").append(identifier.toString());
                buf.append("\n    target=").append(oidStrFor(invokedObject));
                buf.append("\n      args=[");
                for (Object arg : args) {
                    buf.append("\n           ").append(oidStrFor(arg));
                }
                buf.append("\n      ]");
                buf.append("\n    result=").append(actionResult != null ? oidStrFor(actionResult) : "void");
                return new CanonicalEvent.Default(buf.toString()) ;
            }
        };
    }

    private static String oidStrFor(final Object changedObject) {
        final ObjectAdapter adapter = IsisContext.getPersistenceSession().getAdapterManager().adapterFor(changedObject);
        return adapter.getOid().enString(IsisContext.getOidMarshaller());
    }


    
    // //////////////////////////////////////////////////////////////
    // Hooks
    // //////////////////////////////////////////////////////////////



    
    /**
     * Overridable hook, used in
     * {@link #createTransaction(MessageBroker, UpdateNotifier)
     * 
     * <p> Called when a new {@link IsisTransaction} is created.
     */
    protected MessageBroker createMessageBroker() {
        return new MessageBrokerDefault();
    }

    /**
     * Overridable hook, used in
     * {@link #createTransaction(MessageBroker, UpdateNotifier)
     * 
     * <p> Called when a new {@link IsisTransaction} is created.
     */
    protected UpdateNotifier createUpdateNotifier() {
        return new UpdateNotifierDefault();
    }

    // ////////////////////////////////////////////////////////////////
    // helpers
    // ////////////////////////////////////////////////////////////////

    protected void ensureTransactionInProgress() {
        ensureThatState(getTransaction() != null && !getTransaction().getState().isComplete(), is(true), "No transaction in progress");
    }

    protected void ensureTransactionNotInProgress() {
        ensureThatState(getTransaction() != null && !getTransaction().getState().isComplete(), is(false), "Transaction in progress");
    }


    // //////////////////////////////////////////////////////
    // debugging
    // //////////////////////////////////////////////////////

    public void debugData(final DebugBuilder debug) {
        debug.appendln("Transaction", getTransaction());
    }

    // ////////////////////////////////////////////////////////////////
    // Dependencies (injected)
    // ////////////////////////////////////////////////////////////////

    /**
     * The owning {@link IsisSession}.
     * 
     * <p>
     * Will be non-<tt>null</tt> when {@link #open() open}ed, but <tt>null</tt>
     * if {@link #close() close}d .
     */
    public IsisSession getSession() {
        return session;
    }

    /**
     * Should be injected prior to {@link #open() opening}
     */
    public void setSession(final IsisSession session) {
        this.session = session;
    }


    /**
     * Called back by {@link IsisTransaction}.
     */
    protected AuthenticationSession getAuthenticationSession() {
        return IsisContext.getAuthenticationSession();
    }



    
}
