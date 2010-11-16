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


package org.apache.isis.remoting.protocol.internal;

import org.apache.isis.applib.query.Query;
import org.apache.isis.core.metamodel.services.container.query.QueryCardinality;
import org.apache.isis.core.metamodel.spec.ObjectSpecification;
import org.apache.isis.core.runtime.persistence.query.PersistenceQuery;
import org.apache.isis.core.runtime.persistence.query.PersistenceQueryFindByTitle;
import org.apache.isis.core.runtime.persistence.query.PersistenceQueryFindUsingApplibQuerySerializable;
import org.apache.isis.remoting.data.query.PersistenceQueryData;
import org.apache.isis.remoting.data.query.PersistenceQueryFindUsingApplibQuerySerializableData;

public class PersistenceQueryFindUsingApplibQuerySerializableEncoder extends PersistenceQueryEncoderAbstract {

    public Class<?> getPersistenceQueryClass() {
        return PersistenceQueryFindByTitle.class;
    }
    
    public PersistenceQueryData encode(
    		final PersistenceQuery persistenceQuery) {
        PersistenceQueryFindUsingApplibQuerySerializable query = 
        	downcast(persistenceQuery);
		return new PersistenceQueryFindUsingApplibQuerySerializableData(
        		query.getSpecification(), 
        		query.getApplibQuery(), 
        		query.getCardinality());
    }

    @Override
    protected PersistenceQuery doDecode(
            final ObjectSpecification specification,
            final PersistenceQueryData persistenceQueryData) {
        PersistenceQueryFindUsingApplibQuerySerializableData data = downcast(persistenceQueryData);
		final Query query = (Query) data.getApplibQuerySerializable();
		final QueryCardinality cardinality = (QueryCardinality) data.getCardinality();
        return new PersistenceQueryFindUsingApplibQuerySerializable(specification, query, cardinality);
    }

	private PersistenceQueryFindUsingApplibQuerySerializable downcast(
			final PersistenceQuery persistenceQuery) {
		return (PersistenceQueryFindUsingApplibQuerySerializable) persistenceQuery;
	}

	private PersistenceQueryFindUsingApplibQuerySerializableData downcast(
			final PersistenceQueryData persistenceQueryData) {
		return (PersistenceQueryFindUsingApplibQuerySerializableData) persistenceQueryData;
	}

}

