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


package org.apache.isis.runtime.objectstore.inmemory;

import org.apache.isis.core.metamodel.config.IsisConfiguration;
import org.apache.isis.core.runtime.persistence.PersistenceSessionFactory;
import org.apache.isis.core.runtime.persistence.adapterfactory.AdapterFactory;
import org.apache.isis.core.runtime.persistence.adaptermanager.AdapterManager;
import org.apache.isis.core.runtime.persistence.objectstore.ObjectStore;
import org.apache.isis.core.runtime.persistence.objectstore.ObjectStorePersistenceMechanismInstallerAbstract;
import org.apache.isis.core.runtime.system.DeploymentType;


/**
 * Installs the in-memory object store.
 */
public class InMemoryPersistenceMechanismInstaller extends ObjectStorePersistenceMechanismInstallerAbstract  {
    
    

    public InMemoryPersistenceMechanismInstaller() {
		super("in-memory");
	}

    
    /////////////////////////////////////////////////////////////////
    // createPersistenceSessionFactory
    /////////////////////////////////////////////////////////////////

    public PersistenceSessionFactory createPersistenceSessionFactory(final DeploymentType deploymentType) {
        return new InMemoryPersistenceSessionFactory(deploymentType, this);
    }

    
    /////////////////////////////////////////////////////////////////
    // Hook methods
    /////////////////////////////////////////////////////////////////

    /**
     * Hook method to return {@link ObjectStore}. 
     */
    protected ObjectStore createObjectStore(
            final IsisConfiguration configuration, 
            final AdapterFactory adapterFactory, 
            final AdapterManager adapterManager) {
        return new InMemoryObjectStore();
    }





}
