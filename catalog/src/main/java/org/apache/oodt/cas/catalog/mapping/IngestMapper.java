/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.oodt.cas.catalog.mapping;

//JDK imports
import java.util.Set;

//OODT imports
import org.apache.oodt.cas.catalog.exception.CatalogRepositoryException;
import org.apache.oodt.cas.catalog.page.CatalogReceipt;
import org.apache.oodt.cas.catalog.page.IndexPager;
import org.apache.oodt.cas.catalog.struct.TransactionId;
import org.apache.oodt.cas.catalog.struct.TransactionIdFactory;

/**
 * @author bfoster
 * @version $Revision$
 *
 * <p>
 * An Interface Ingest Mapper that indexes transaction ids to catalog transaction ids and catlaog ids
 * <p>
 */
public interface IngestMapper {

	/**
	 * Maps the three arguments to each other so any of the others can be
	 * retrieved via one of the other arguments
	 * 
	 * @param catalogServiceTransactionId
	 *            TransactionId generated by the CatalogService
	 * @throws CatalogRepositoryException
	 *             Any error
	 */
	void storeTransactionIdMapping(
		TransactionId<?> catalogServiceTransactionId, TransactionIdFactory catalogServiceTransactionIdFactory,
		CatalogReceipt catalogReceipt, TransactionIdFactory catalogTransactionIdFactory)
			throws CatalogRepositoryException;

	Set<TransactionId<?>> getPageOfCatalogTransactionIds(IndexPager indexPager,
														 String catalogId) throws CatalogRepositoryException;
	
	void deleteTransactionIdMapping(TransactionId<?> catalogTransactionId,
									String catalogId) throws CatalogRepositoryException;
	
	void deleteAllMappingsForCatalogServiceTransactionId(
		TransactionId<?> catalogServiceTransactionId)
			throws CatalogRepositoryException;
	
	void deleteAllMappingsForCatalog(String catalogId)
			throws CatalogRepositoryException;
	
	/**
	 * Verify if the given CatalogService TransactionId has been stored in a
	 * mapping {@link storeTransactionIdMapping(String, TransactionId<?>,
	 * TransactionId<?>)}
	 * 
	 * @param catalogServiceTransactionId
	 *            The CatalogService TransactionId in question
	 * @return True is the catalogServiceTransactionId has been used in a used in a mapping
	 * @throws CatalogRepositoryException
	 *             Any error
	 */
	boolean hasCatalogServiceTransactionId(
		TransactionId<?> catalogServiceTransactionId)
			throws CatalogRepositoryException;

	/**
	 * Get the CatalogService TransactionId that was mapped to the given Catalog
	 * URN and Catalog TransactionId
	 * 
	 * @param catalogTransactionId
	 *            A Catalog TransactionId
	 * @param catalogId
	 *            A unique Catalog ID when the Catalog TransactionId was used
	 * @return The CatalogService TransactionId which was mapped to the given
	 *         Catalog TransactionId and Catalog ID
	 * @throws CatalogRepositoryException
	 *             Any Error
	 */
	TransactionId<?> getCatalogServiceTransactionId(
		TransactionId<?> catalogTransactionId, String catalogId)
			throws CatalogRepositoryException;

	/**
	 * Get the Catalog TransactionId that was mapped to the given Catalog ID
	 * and CatalogService TransactionId
	 * 
	 * @param catalogServiceTransactionId
	 *            A Catalog TransactionId
	 * @param catalogId
	 *            A unique Catalog ID when the Catalog TransactionId was used
	 * @return The CatalogService TransactionId which was mapped to the given
	 *         Catalog TransactionId and Catalog ID
	 * @throws CatalogRepositoryException
	 *             Any Error
	 */
	TransactionId<?> getCatalogTransactionId(
		TransactionId<?> catalogServiceTransactionId, String catalogId)
			throws CatalogRepositoryException;

	/**
	 * Get all the Catalog URNs for which the given TransactionId was mapped
	 * 
	 * @param catalogServiceTransactionId
	 *            A CatalogService TransactionId
	 * @return Catalog IDs for which the given TransactionId was mapped
	 * @throws CatalogRepositoryException
	 *             Any Error
	 */
	Set<String> getCatalogIds(
		TransactionId<?> catalogServiceTransactionId)
			throws CatalogRepositoryException;
	
	/**
	 * 
	 * @param catalogServiceTransactionId
	 * @param catalogId
	 * @return
	 * @throws CatalogRepositoryException
	 */
	CatalogReceipt getCatalogReceipt(
		TransactionId<?> catalogServiceTransactionId, String catalogId)
			throws CatalogRepositoryException;
	
}
