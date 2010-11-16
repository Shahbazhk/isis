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


package org.apache.isis.extensions.html.action.view.util;

import org.apache.isis.core.metamodel.adapter.ObjectAdapter;
import org.apache.isis.core.metamodel.authentication.AuthenticationSession;
import org.apache.isis.core.metamodel.facets.collections.modify.CollectionFacet;
import org.apache.isis.core.metamodel.facets.propparam.multiline.MultiLineFacet;
import org.apache.isis.core.metamodel.spec.ObjectSpecification;
import org.apache.isis.core.metamodel.spec.feature.ObjectAssociation;
import org.apache.isis.core.metamodel.spec.feature.OneToManyAssociation;
import org.apache.isis.core.metamodel.util.CollectionFacetUtils;
import org.apache.isis.core.runtime.context.IsisContext;
import org.apache.isis.core.runtime.persistence.PersistenceSession;
import org.apache.isis.extensions.html.component.ComponentFactory;
import org.apache.isis.extensions.html.component.Table;
import org.apache.isis.extensions.html.context.Context;

import static org.apache.isis.core.metamodel.spec.feature.ObjectAssociationFilters.PROPERTIES;
import static org.apache.isis.core.metamodel.spec.feature.ObjectAssociationFilters.STATICALLY_VISIBLE_ASSOCIATIONS;



public class TableUtil {

    public static Table createTable(
            final Context context,
            final String id,
            final ObjectAdapter object,
            final OneToManyAssociation collectionField) {

        final ObjectAdapter collection = collectionField.get(object);
        final String name = collectionField.getName();
        final ObjectSpecification type = collectionField.getSpecification();

        final String summary = "Table showing elements of " + name + " field";
        return createTable(context, collectionField != null, collection, summary, type);
    }

    public static Table createTable(
            final Context context,
            final boolean addSelector,
            final ObjectAdapter collection,
            final String summary,
            final ObjectSpecification elementType) {

    	final CollectionFacet facet = CollectionFacetUtils.getCollectionFacetFromSpec(collection);
        final ObjectAssociation[] columnAssociations = 
        	elementType.getAssociations(STATICALLY_VISIBLE_ASSOCIATIONS.and(PROPERTIES));
        
        int len = columnAssociations.length;

        ComponentFactory factory = context.getComponentFactory();
        final Table table = factory.createTable(len, addSelector);
        table.setSummary(summary);

        for (ObjectAssociation columnAssociation : columnAssociations) {
        	table.addColumnHeader(columnAssociation.getName());
		}

        for(ObjectAdapter rowAdapter: facet.iterable(collection)) {
		    getPersistenceSession().resolveImmediately(rowAdapter);
		    final String elementId = context.mapObject(rowAdapter);
		    table.addRowHeader(factory.createObjectIcon(rowAdapter, elementId, "icon"));
		    
	        for (ObjectAssociation columnAssociation : columnAssociations) {
		        final ObjectAdapter columnAdapter = columnAssociation.get(rowAdapter);
		
		        ObjectSpecification columnSpec = columnAssociation.getSpecification();
				if (!columnAssociation.isVisible(getAuthenticationSession(), rowAdapter).isAllowed()) {
		            table.addEmptyCell();
		        } else if (columnSpec.isParseable()) {
		            final MultiLineFacet multiline = columnSpec.getFacet(MultiLineFacet.class);
		            final boolean shouldTruncate = multiline != null && multiline.numberOfLines() > 1;
		            final String titleString = columnAdapter != null ? columnAdapter.titleString() : "";
		            table.addCell(titleString, shouldTruncate);
		        } else if (columnAdapter == null) {
		            table.addEmptyCell();
		        } else {
		            getPersistenceSession().resolveImmediately(columnAdapter);	            
		            final String objectId = context.mapObject(columnAdapter);
                    table.addCell(factory.createObjectIcon(columnAssociation, columnAdapter, objectId, "icon"));
                }
		    }
		    /*
		     * if (addSelector) { table.addCell(context.getFactory().createRemoveOption(id, elementId,
		     * collectionField.getId())); }
		     */
		    // TODO add selection box
		    // table.addCell();
		    /*
		     * if (collectionField != null) { if (collectionField.isValidToRemove(object,
		     * element).isAllowed()) { table.addCell(context.getFactory().createRemoveOption(id, elementId,
		     * collectionField.getId())); } else { table.addEmptyCell(); } }
		     */
		
		}
        return table;
    }

    
    //////////////////////////////////////////////////////////////////////////////////
    // Dependencies (from context)
    //////////////////////////////////////////////////////////////////////////////////
    
	private static PersistenceSession getPersistenceSession() {
		return IsisContext.getPersistenceSession();
	}

	private static AuthenticationSession getAuthenticationSession() {
		return IsisContext.getAuthenticationSession();
	}

}

