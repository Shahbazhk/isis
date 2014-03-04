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

package org.apache.isis.core.progmodel.facets.object.audited.configuration;

import java.lang.reflect.Method;
import java.util.UUID;

import org.jmock.Expectations;
import org.jmock.auto.Mock;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import org.apache.isis.applib.annotation.ActionSemantics.Of;
import org.apache.isis.applib.annotation.Command;
import org.apache.isis.applib.services.HasTransactionId;
import org.apache.isis.core.commons.config.IsisConfiguration;
import org.apache.isis.core.metamodel.facetapi.Facet;
import org.apache.isis.core.metamodel.facets.FacetFactory.ProcessClassContext;
import org.apache.isis.core.metamodel.facets.FacetFactory.ProcessMethodContext;
import org.apache.isis.core.metamodel.facets.actions.command.CommandFacet;
import org.apache.isis.core.metamodel.facets.actions.semantics.ActionSemanticsFacetAbstract;
import org.apache.isis.core.metamodel.facets.object.audit.AuditableFacet;
import org.apache.isis.core.metamodel.facets.object.audit.annotation.AuditableFacetAuditedAnnotation;
import org.apache.isis.core.metamodel.facets.object.audit.configuration.AuditableFacetFromConfiguration;
import org.apache.isis.core.metamodel.facets.object.audit.configuration.AuditableFromConfigurationFacetFactory;
import org.apache.isis.core.progmodel.facets.AbstractFacetFactoryJUnit4TestCase;
import org.apache.isis.core.progmodel.facets.AbstractFacetFactoryTest;
import org.apache.isis.core.progmodel.facets.actions.command.CommandFacetAbstract;
import org.apache.isis.core.unittestsupport.jmocking.JUnitRuleMockery2;
import org.apache.isis.core.unittestsupport.jmocking.JUnitRuleMockery2.Mode;

public class AuditableFromConfigurationFacetFactoryTest extends AbstractFacetFactoryJUnit4TestCase {

    private AuditableFromConfigurationFacetFactory facetFactory;

    @Before
    public void setUp() throws Exception {
        facetFactory = new AuditableFromConfigurationFacetFactory();
        facetFactory.setConfiguration(mockConfiguration);
    }

    @After
    public void tearDown() throws Exception {
        facetFactory = null;
    }

    class Customer {
        public void someAction() {
        }
    }

    class SomeTransactionalId implements HasTransactionId {
        public void someAction() {
        }

        @Override
        public UUID getTransactionId() {
            return null;
        }

        @Override
        public void setTransactionId(UUID transactionId) {
        }
    }


    @Test
    public void ignoreHasTransactionId() {
        String configuredValue = "all";

        allowingConfigurationToReturn(configuredValue);

        facetFactory.process(new ProcessClassContext(Customer.class, null, mockMethodRemover, facetHolderImpl));

        final Facet facet = facetHolderImpl.getFacet(AuditableFacet.class);
        Assert.assertNotNull(facet);

        expectNoMethodsRemoved();
    }


    @Test
    public void configured_value_set_to_all() {
        allowingConfigurationToReturn("all");

        facetFactory.process(new ProcessClassContext(Customer.class, null, mockMethodRemover, facetHolderImpl));

        final Facet facet = facetHolderImpl.getFacet(AuditableFacet.class);
        Assert.assertTrue(facet instanceof AuditableFacetFromConfiguration);

        expectNoMethodsRemoved();
    }

    @Test
    public void configured_value_set_to_none() {
        allowingConfigurationToReturn("none");

        facetFactory.process(new ProcessClassContext(Customer.class, null, mockMethodRemover, facetHolderImpl));

        final Facet facet = facetHolderImpl.getFacet(CommandFacet.class);
        Assert.assertNull(facet);

        expectNoMethodsRemoved();
    }

    @Test
    public void configured_value_set_to_not_recognized() {
        allowingConfigurationToReturn("foobar");
        
        facetFactory.process(new ProcessClassContext(Customer.class, null, mockMethodRemover, facetHolderImpl));
        
        final Facet facet = facetHolderImpl.getFacet(CommandFacet.class);
        Assert.assertNull(facet);
        
        expectNoMethodsRemoved();
    }
    

    private void allowingConfigurationToReturn(final String value) {
        context.checking(new Expectations() {
            {
                allowing(mockConfiguration).getString("isis.services.audit.objects");
                will(returnValue(value));
            }
        });
    }


}
