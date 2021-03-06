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

package org.apache.oodt.cas.filemgr.catalog;

//JDK imports

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Hits;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.RangeQuery;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.WildcardQuery;
import org.apache.oodt.cas.filemgr.structs.BooleanQueryCriteria;
import org.apache.oodt.cas.filemgr.structs.Element;
import org.apache.oodt.cas.filemgr.structs.Product;
import org.apache.oodt.cas.filemgr.structs.ProductPage;
import org.apache.oodt.cas.filemgr.structs.ProductType;
import org.apache.oodt.cas.filemgr.structs.Query;
import org.apache.oodt.cas.filemgr.structs.QueryCriteria;
import org.apache.oodt.cas.filemgr.structs.RangeQueryCriteria;
import org.apache.oodt.cas.filemgr.structs.Reference;
import org.apache.oodt.cas.filemgr.structs.TermQueryCriteria;
import org.apache.oodt.cas.filemgr.structs.exceptions.CatalogException;
import org.apache.oodt.cas.filemgr.structs.exceptions.ValidationLayerException;
import org.apache.oodt.cas.filemgr.validation.ValidationLayer;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.commons.pagination.PaginationUtils;

import org.safehaus.uuid.UUID;
import org.safehaus.uuid.UUIDGenerator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

//Lucene imports
//OODT imports
//JUG imports

/**
 * @author mattmann
 * @author bfoster
 * @author luca
 * @version $Revision$
 * 
 * <p>
 * An implementation of a File {@link Catalog} using Apache's popular <a
 * href="http://lucene.apache.org">Lucene</a> text indexing engine as a
 * backend.
 * </p>
 * 
 */
public class LuceneCatalog implements Catalog {

    /* the path to the index directory for this catalog */
    private String indexFilePath = null;

    /* validation layer */
    private ValidationLayer valLayer = null;

    /*
     * temporary Cache of product/metadata/reference information before it is
     * written to the index
     */
    private static ConcurrentHashMap<String, CompleteProduct> CATALOG_CACHE = new ConcurrentHashMap<String, CompleteProduct>();

    /* our product ID generator */
    private static UUIDGenerator generator = UUIDGenerator.getInstance();

    /* our log stream */
    private static final Logger LOG = Logger.getLogger(LuceneCatalog.class.getName());

    /* page size for pagination */
    private int pageSize = -1;

    /* write lock timeout for writing to the index */
    private long writeLockTimeout = -1L;

    /* commit lock timeout for writing/reading to the index */
    private long commitLockTimeout = -1L;

    /* lucene index merge factor */
    private int mergeFactor = -1;

    /**
     * 
     * @param idxFilePath
     *            A file path pointing to the lucene index directory for this
     *            catalog.
     * @param vLayer
     *            The validation layer to be used for this catalog.
     * @param pgSize
     *            The size of pages to be used when doing pagination of the
     *            catalog.
     * 
     * @param commitTimeout
     *            The amount of time (in seconds) that should be flowed down to
     *            the Lucene IndexReader and IndexWriters for their commit lock
     *            timeout property.
     * 
     * @param writeTimeout
     *            The amount of time (in seconds) that should be flowed down to
     *            the Lucene IndexWriters for their commit lock timeout
     *            property.
     * 
     * @param mergeFactor
     *            The merge factor to use when writing to the index.
     */
    public LuceneCatalog(String idxFilePath, ValidationLayer vLayer,
            int pgSize, long commitTimeout, long writeTimeout, int mergeFactor) {
        this.indexFilePath = idxFilePath;
        this.valLayer = vLayer;
        this.pageSize = pgSize;
        this.writeLockTimeout = writeTimeout;
        this.commitLockTimeout = commitTimeout;
        this.mergeFactor = mergeFactor;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#addMetadata(org.apache.oodt.cas.metadata.Metadata,
     *      org.apache.oodt.cas.filemgr.structs.Product)
     */
    public synchronized void addMetadata(Metadata m, Product product)
            throws CatalogException {
        if(product.getProductId()!=null && CATALOG_CACHE.containsKey(product.getProductId())) {
            CompleteProduct p = CATALOG_CACHE.get(product.getProductId());

                p.setMetadata(m);
                if (hasMetadataAndRefs(p)) {
                    LOG.log(Level.FINE,
                        "metadata and references present for product: ["
                        + product.getProductId() + "]");
                    addCompleteProductToIndex(p);
                    // now remove its entry from the cache
                    CATALOG_CACHE.remove(product.getProductId());
            }
        }
        else{
                // move product from index to cache
                // it will be moved back after metadata is added
                CompleteProduct p = getCompleteProductById(product.getProductId(), true, true);
                LOG.log(Level.FINE, "Product not found in local cache, retrieved from index");
                removeProduct(product);

        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#removeMetadata(org.apache.oodt.cas.metadata.Metadata,
     *      org.apache.oodt.cas.filemgr.structs.Product)
     */
    public synchronized void removeMetadata(Metadata m, Product product)
            throws CatalogException {
        CompleteProduct p=null;

        if(product.getProductId()!=null && CATALOG_CACHE.containsKey(product.getProductId())) {
             p = CATALOG_CACHE.get(product.getProductId());
        }
        else{
            String prodId = product.getProductId();
            p = getCompleteProductById(prodId, true, true);
            removeProductDocument(product);
        }




        Metadata currMet = p.getMetadata();
        List<String> metadataTypes = new ArrayList<String>();

        if (valLayer!=null) {
	        try {
	        		// remove metadata elements specified by validation layer
	        		for (Element element : valLayer.getElements(product.getProductType())) {
	        			metadataTypes.add(element.getElementName());
	        		}
	        } catch (ValidationLayerException e) {
	            LOG.log(Level.SEVERE, e.getMessage());
	            throw new CatalogException(
	                    "ValidationLayerException when trying to obtain element list for product type: "
	                            + product.getProductType().getName()
	                            + ": Message: " + e.getMessage(), e);
	        }
        } else {
        	// remove all metadata
        	metadataTypes = currMet.getAllKeys();
        }

        for (String name : metadataTypes) {
            currMet.removeMetadata(name);
        }

        p.setMetadata(currMet);

        if (hasMetadataAndRefs(p)) {
            LOG.log(Level.FINE,
                    "metadata and references present for product: ["
                            + product.getProductId() + "]");
            addCompleteProductToIndex(p);
            // now remove its entry from the cache
            CATALOG_CACHE.remove(product.getProductId());
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#addProduct(org.apache.oodt.cas.filemgr.structs.Product)
     */
    public synchronized void addProduct(Product product)
            throws CatalogException {
        if(product.getProductId()!=null && CATALOG_CACHE.containsKey(product.getProductId())) {
            throw new CatalogException(
                "Attempt to add a product that already existed: product: ["
                + product.getProductName() + "]");





        } else {
            // haven't cached this product yet, so let's cache it
            CompleteProduct completeProduct = new CompleteProduct();

            // NOTE: reuse existing ID if possible
            if (product.getProductId() == null) {
                synchronized (completeProduct) {
                    // now generate a unique ID for the product
                    UUID prodUUID = generator.generateTimeBasedUUID();
                    product.setProductId(prodUUID.toString());
                }
            }

            completeProduct.setProduct(product);
            CATALOG_CACHE.put(product.getProductId(), completeProduct);

        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#modifyProduct(org.apache.oodt.cas.filemgr.structs.Product)
     */
    public synchronized void modifyProduct(Product product)
            throws CatalogException {
        if (product.getProductId()!=null && CATALOG_CACHE.containsKey(product.getProductId())) {
            LOG.log(Level.FINE, "Modifying product: [" + product.getProductId()
                    + "]: found product in cache!");
            CompleteProduct cp = CATALOG_CACHE.get(product
                    .getProductId());
            cp.setProduct(product);
        } else {
            // need to grab the metadata for the existing product, and make sure
            // we don't lose it
            Metadata metadata = getMetadata(product);

            CompleteProduct completeProduct = new CompleteProduct();
            completeProduct.setMetadata(metadata);

            // now remove the product's document from the catalog
            removeProductDocument(product);

            // now add it back
            completeProduct.setProduct(product);
            addCompleteProductToIndex(completeProduct);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#removeProduct(org.apache.oodt.cas.filemgr.structs.Product)
     */
    public synchronized void removeProduct(Product product)
            throws CatalogException {
        removeProductDocument(product);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#setProductTransferStatus(org.apache.oodt.cas.filemgr.structs.Product)
     */
    public synchronized void setProductTransferStatus(Product product)
            throws CatalogException {
        LOG.log(Level.FINE,
                "LuceneCatalog: seting product transfer status to: ["
                        + product.getTransferStatus() + "] for " + "product: ["
                        + product.getProductId() + "]");
        modifyProduct(product);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#addProductReferences(org.apache.oodt.cas.filemgr.structs.Product)
     */
    public synchronized void addProductReferences(Product product)
            throws CatalogException {
        if(product.getProductId()!=null && CATALOG_CACHE.containsKey(product.getProductId())) {
            CompleteProduct p = CATALOG_CACHE.get(product
                .getProductId());
            p.getProduct().setProductReferences(product.getProductReferences());
                if (hasMetadataAndRefs(p)) {
                    LOG.log(Level.FINE,
                        "metadata and references present for product: ["
                        + product.getProductId() + "]");
                    addCompleteProductToIndex(p);
                    // now remove its entry from the cache
                    CATALOG_CACHE.remove(product.getProductId());
                }

        }
        else{
                // move product from index to cache
                // it will be moved back after metadata is added
                CompleteProduct p = getCompleteProductById(product.getProductId(), true, true);
                LOG.log(Level.FINE, "Product not found in local cache, retrieved from index");
                removeProduct(product);

        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#getProductById(java.lang.String)
     */
    public Product getProductById(String productId) throws CatalogException {
        CompleteProduct prod = getCompleteProductById(productId, false);
        return prod.getProduct();
    }

    private Product getProductById(String productId, boolean getRefs)
            throws CatalogException {
        CompleteProduct prod = getCompleteProductById(productId, getRefs);
        return prod.getProduct();
    }

    private CompleteProduct getCompleteProductById(String productId)
            throws CatalogException {
        return getCompleteProductById(productId, false);
    }

    private CompleteProduct getCompleteProductById(String productId,
            boolean getRefs) throws CatalogException {
        return getCompleteProductById(productId, getRefs, false);
    }

    private CompleteProduct getCompleteProductById(String productId,
            boolean getRefs, boolean getMet) throws CatalogException {
        IndexSearcher searcher = null;
        try {
            searcher = new IndexSearcher(indexFilePath);
            Term productIdTerm = new Term("product_id", productId);
            org.apache.lucene.search.Query query = new TermQuery(productIdTerm);
            Hits hits = searcher.search(query);

            // should be exactly 1 hit
            if (hits.length() == 0) {
            	throw new CatalogException("Product: [" + productId + "] NOT found in the catalog!");
            } else if (hits.length() > 1) {
                throw new CatalogException("Product: [" + productId+ "] is not unique in the catalog!");
            }

            Document productDoc = hits.doc(0);
            return toCompleteProduct(productDoc, getRefs,
                    getMet);
        } catch (IOException e) {
            LOG.log(Level.WARNING,
                    "IOException when opening index directory: ["
                            + indexFilePath + "] for search: Message: "
                            + e.getMessage());
            throw new CatalogException(e.getMessage(), e);
        } finally {
            if (searcher != null) {
                try {
                    searcher.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#getProductByName(java.lang.String)
     */
    public Product getProductByName(String productName) throws CatalogException {
        return getProductByName(productName, false);
    }

    private Product getProductByName(String productName, boolean getRefs)
            throws CatalogException {
        IndexSearcher searcher = null;
        try {
            searcher = new IndexSearcher(indexFilePath);
            Term productIdTerm = new Term("product_name", productName);
            org.apache.lucene.search.Query query = new TermQuery(productIdTerm);
            Sort sort = new Sort(new SortField("CAS.ProductReceivedTime",
                    SortField.STRING, true));
            Hits hits = searcher.search(query, sort);

            // should be > 0 hits
            if (hits.length() > 0) {
                // just get the first hit back
                Document productDoc = hits.doc(0);
                CompleteProduct prod = toCompleteProduct(productDoc, getRefs,
                        false);
                return prod.getProduct();
            } else {
                LOG.log(Level.FINEST, "Request for product by name: ["
                        + productName + "] returned no results");
                return null;
            }

        } catch (IOException e) {
            LOG.log(Level.WARNING,
                    "IOException when opening index directory: ["
                            + indexFilePath + "] for search: Message: "
                            + e.getMessage());
            throw new CatalogException(e.getMessage(), e);
        } finally {
            if (searcher != null) {
                try {
                    searcher.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#getProductReferences(org.apache.oodt.cas.filemgr.structs.Product)
     */
    public List<Reference> getProductReferences(Product product) throws CatalogException {
        Product prod = getProductById(product.getProductId(), true);
        if (prod != null) {
            return prod.getProductReferences();
        } else {
            return null;
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#getProducts()
     */
    public List<Product> getProducts() throws CatalogException {
        return getProducts(false);
    }

    private List<Product> getProducts(boolean getRefs) throws CatalogException {
        IndexSearcher searcher = null;
        List<Product> products = null;

        try {
            searcher = new IndexSearcher(indexFilePath);
            Term productIdTerm = new Term("myfield", "myvalue");
            org.apache.lucene.search.Query query = new TermQuery(productIdTerm);
            Sort sort = new Sort(new SortField("CAS.ProductReceivedTime",
                    SortField.STRING, true));
            Hits hits = searcher.search(query, sort);

            // should be > 0 hits
            if (hits.length() > 0) {
                products = new Vector<Product>(hits.length());
                for (int i = 0; i < hits.length(); i++) {
                    Document productDoc = hits.doc(i);
                    CompleteProduct prod = toCompleteProduct(productDoc,
                            getRefs, false);
                    products.add(prod.getProduct());
                }
            } else {
                LOG.log(Level.FINEST,
                        "Request for products returned no results");
                return null;
            }

        } catch (IOException e) {
            LOG.log(Level.WARNING,
                    "IOException when opening index directory: ["
                            + indexFilePath + "] for search: Message: "
                            + e.getMessage());
            throw new CatalogException(e.getMessage(), e);
        } finally {
            if (searcher != null) {
                try {
                    searcher.close();
                } catch (Exception ignore) {
                }
            }
        }

        return products;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#getProductsByProductType(org.apache.oodt.cas.filemgr.structs.ProductType)
     */
    public List<Product> getProductsByProductType(ProductType type)
            throws CatalogException {
        return getProductsByProductType(type, false);
    }

    private List<Product> getProductsByProductType(ProductType type, boolean getRefs)
            throws CatalogException {
        IndexSearcher searcher = null;
        List<Product> products = null;

        try {
            searcher = new IndexSearcher(indexFilePath);
            Term productIdTerm = new Term("product_type_id", type
                    .getProductTypeId());
            org.apache.lucene.search.Query query = new TermQuery(productIdTerm);
            Sort sort = new Sort(new SortField("CAS.ProductReceivedTime",
                    SortField.STRING, true));
            Hits hits = searcher.search(query, sort);

            // should be > 0 hits
            if (hits.length() > 0) {
                products = new Vector<Product>(hits.length());
                for (int i = 0; i < hits.length(); i++) {
                    Document productDoc = hits.doc(i);
                    CompleteProduct prod = toCompleteProduct(productDoc,
                            getRefs, false);
                    products.add(prod.getProduct());
                }
            } else {
                LOG.log(Level.FINEST, "Request for products by type: ["
                        + type.getProductTypeId() + "] returned no results");
                return null;
            }

        } catch (IOException e) {
            LOG.log(Level.WARNING,
                    "IOException when opening index directory: ["
                            + indexFilePath + "] for search: Message: "
                            + e.getMessage());
            throw new CatalogException(e.getMessage(), e);
        } finally {
            if (searcher != null) {
                try {
                    searcher.close();
                } catch (Exception ignore) {
                }
            }
        }

        return products;
    }

    public Metadata getMetadata(Product product) throws CatalogException {
        IndexSearcher searcher = null;
        try {
            searcher = new IndexSearcher(indexFilePath);
            Term productIdTerm = new Term("product_id", product.getProductId());
            org.apache.lucene.search.Query query = new TermQuery(productIdTerm);
            Hits hits = searcher.search(query);

            // should be exactly 1 hit
            if (hits.length() != 1) {
                throw new CatalogException("Product: ["
                        + product.getProductId()
                        + "] is not unique in the catalog! Num Hits: ["
                        + hits.length() + "]");
            }

            Document productDoc = hits.doc(0);
            CompleteProduct prod = toCompleteProduct(productDoc, false, true);
            return prod.getMetadata();
        } catch (IOException e) {
            LOG.log(Level.WARNING,
                    "IOException when opening index directory: ["
                            + indexFilePath + "] for search: Message: "
                            + e.getMessage());
            throw new CatalogException(e.getMessage(), e);
        } finally {
            if (searcher != null) {
                try {
                    searcher.close();
                } catch (Exception ignore) {
                }
            }
        }
    }
    
    public Metadata getReducedMetadata(Product product, List<String> elements) throws CatalogException {
        Metadata fullMetadata = getMetadata(product);
        Metadata reducedMetadata = new Metadata();
        for (String element : elements) {
            if (fullMetadata.containsKey(element)) {
                reducedMetadata.replaceMetadata(element, fullMetadata.getAllMetadata(element));
            }
        }
        return reducedMetadata;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#query(org.apache.oodt.cas.filemgr.structs.Query,
     *      org.apache.oodt.cas.filemgr.structs.ProductType)
     */
    public List<String> query(Query query, ProductType type) throws CatalogException {
        // paginate products returns full products, but the query method
        // is expected to return product ids
        List<Product> fullProducts = paginateQuery(query, type, -1, null);
        List<String> productIds = null;

        if (fullProducts != null && fullProducts.size() > 0) {
            productIds = new Vector<String>(fullProducts.size());

            for (Product p : fullProducts) {
                productIds.add(p.getProductId());
            }
        }

        return productIds;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#getTopNProducts(int)
     */
    public List<Product> getTopNProducts(int n) throws CatalogException {
        List<Product> products = null;
        IndexSearcher searcher = null;

        try {
            searcher = new IndexSearcher(indexFilePath);

            // construct a Boolean query here
            BooleanQuery booleanQuery = new BooleanQuery();
            TermQuery tq = new TermQuery(new Term("myfield", "myvalue"));
            booleanQuery.add(tq, BooleanClause.Occur.MUST);

            Sort sort = new Sort(new SortField("CAS.ProductReceivedTime",
                    SortField.STRING, true));
            LOG.log(Level.FINE, "Querying LuceneCatalog: q: [" + booleanQuery
                    + "]");
            Hits hits = searcher.search(booleanQuery, sort);
            if (hits.length() > 0) {
                products = new Vector<Product>(n);
                int i = 0;
                while (products.size() < Math.min(n, hits.length())) {
                    Document productDoc = hits.doc(i);
                    CompleteProduct prod = toCompleteProduct(productDoc, false,
                            false);
                    products.add(prod.getProduct());
                    i++;
                }
            } else {
                LOG.log(Level.WARNING, "Top N query produced no products!");
            }

        } catch (IOException e) {
            LOG.log(Level.WARNING,
                    "IOException when opening index directory: ["
                            + indexFilePath + "] for search: Message: "
                            + e.getMessage());
            throw new CatalogException(e.getMessage(), e);
        } finally {
            if (searcher != null) {
                try {
                    searcher.close();
                } catch (Exception ignore) {
                }
            }
        }

        return products;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#getTopNProducts(int,
     *      org.apache.oodt.cas.filemgr.structs.ProductType)
     */
    public List<Product> getTopNProducts(int n, ProductType type)
            throws CatalogException {
        int numPages = 1;
        if (n > this.pageSize) {
            numPages = n / this.pageSize + (n % this.pageSize == 0 ? 0 : 1);
        }

        List<Product> products = new Vector<Product>(n);
        Query query = new Query();

        for (int pageNum = 1; pageNum < numPages + 1; pageNum++) {
            List<Product> pageProducts = paginateQuery(query, type, pageNum, null);
                products.addAll(pageProducts);
        }

        if(n<=products.size()) {
         return products.subList(0, n);
        }

        return products;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#getValidationLayer()
     */
    public ValidationLayer getValidationLayer() {
        return valLayer;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#getNumProducts(org.apache.oodt.cas.filemgr.structs.ProductType)
     */
    public int getNumProducts(ProductType type) throws CatalogException {
        Query query = new Query();
        return getNumHits(query, type);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.util.Pagination#getFirstPage(org.apache.oodt.cas.filemgr.structs.ProductType)
     */
    public ProductPage getFirstPage(ProductType type) {
        ProductPage firstPage = new ProductPage();
        List<Product> products;
        Query query = new Query();
        
        // now construct the page
        firstPage.setPageNum(1);
        firstPage.setPageSize(pageSize);
        try {
          products = paginateQuery(query, type, 1, firstPage);
        } catch (CatalogException e) {
            LOG.log(Level.WARNING,
                    "CatalogException getting first page for product type: ["
                            + type.getProductTypeId()
                            + "] from catalog: Message: " + e.getMessage());
            return null;
        }
        // There are no products and thus no first page
        if (products == null || (products.size() == 0)) {
        		return null;
        }

        firstPage.setPageProducts(products);

        return firstPage;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.util.Pagination#getLastProductPage(org.apache.oodt.cas.filemgr.structs.ProductType)
     */
    public ProductPage getLastProductPage(ProductType type) {
        ProductPage lastPage = new ProductPage();
        ProductPage firstPage = getFirstPage(type);
        List<Product> products;
        Query query = new Query();
        
        // now construct the page
        lastPage.setPageNum(firstPage.getTotalPages());
        lastPage.setPageSize(pageSize);
        try {
            products = paginateQuery(query, type, firstPage.getTotalPages(), lastPage);
        } catch (CatalogException e) {
          	LOG.log(Level.WARNING,
                  "CatalogException getting last page for product type: ["
                          + type.getProductTypeId()
                          + "] from catalog: Message: " + e.getMessage());
          	return null;
        }
        // There are no products thus there is no last page
        if (products == null || (products.size() == 0)) {
        	  return null;
        }
        lastPage.setPageProducts(products);

        return lastPage;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.util.Pagination#getNextPage(org.apache.oodt.cas.filemgr.structs.ProductType,
     *      org.apache.oodt.cas.filemgr.structs.ProductPage)
     */
    public ProductPage getNextPage(ProductType type, ProductPage currentPage) {
        if(type==null){
            return null;
        }
        if (currentPage == null) {
            return getFirstPage(type);
        }

        if (currentPage.isLastPage()) {
            return currentPage;
        }

        List<Product> products;
        ProductPage nextPage = new ProductPage();
        Query query = new Query();

        // now construct the page
        nextPage.setPageNum(currentPage.getPageNum() + 1);
        nextPage.setPageSize(pageSize);
        try {
            products = paginateQuery(query, type, currentPage.getPageNum() + 1, nextPage);
        } catch (CatalogException e) {
            LOG.log(Level.WARNING,
                  "CatalogException getting next page for product type: ["
                          + type.getProductTypeId()
                          + "] from catalog: Message: " + e.getMessage());
            return null;
        }
        // There are no products and thus no next page
        if (products == null || (products.size() == 0)) {
        	  return null;
        }
        nextPage.setPageProducts(products);

        return nextPage;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.util.Pagination#getPrevPage(org.apache.oodt.cas.filemgr.structs.ProductType,
     *      org.apache.oodt.cas.filemgr.structs.ProductPage)
     */
    public ProductPage getPrevPage(ProductType type, ProductPage currentPage) {
        if(type==null){
            return null;
        }

        if (currentPage == null) {
            return getFirstPage(type);
        }

        if (currentPage.isFirstPage()) {
            return currentPage;
        }
        List<Product> products;

        Query query = new Query();

        // now construct the page
        ProductPage prevPage = new ProductPage();
        prevPage.setPageNum(currentPage.getPageNum() - 1);
        prevPage.setPageSize(pageSize);
        try {
            products = paginateQuery(query, type, currentPage.getPageNum() - 1, prevPage);
        } catch (CatalogException e) {
            LOG.log(Level.WARNING,
                    "CatalogException getting prev page for product type: ["
                            + type.getProductTypeId()
                            + "] from catalog: Message: " + e.getMessage());
            return null;
        }
        
        // There are no products and thus no pages
        if (products == null || (products.size() == 0)) {
        	  return null;
        }
        prevPage.setPageProducts(products);

        return prevPage;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.oodt.cas.filemgr.catalog.Catalog#pagedQuery(org.apache.oodt.cas.filemgr.structs.Query,
     *      org.apache.oodt.cas.filemgr.structs.ProductType, int)
     */
    public ProductPage pagedQuery(Query query, ProductType type, int pageNum)
            throws CatalogException {
        try {
            ProductPage retPage = new ProductPage();
            retPage.setPageNum(pageNum);
            retPage.setPageSize(pageSize);
            retPage.setPageProducts(paginateQuery(query, type, pageNum, retPage));
            return retPage;
        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage());
            LOG.log(Level.WARNING,
                    "CatalogException when doing paged product query: Message: "
                            + e.getMessage());
            throw new CatalogException(e.getMessage(), e);
        }

    }

    private synchronized void removeProductDocument(Product product)
            throws CatalogException {
        IndexReader reader = null;

        try {
            reader = IndexReader.open(indexFilePath);
            LOG.log(Level.FINE,
                    "LuceneCatalog: remove document from index for product: ["
                            + product.getProductId() + "]");
            reader.deleteDocuments(new Term("product_id", product
                    .getProductId()));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Exception removing product: ["
                    + product.getProductName() + "] from index: Message: "
                    + e.getMessage());
            throw new CatalogException(e.getMessage(), e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignore) {
                }

            }

        }
    }

    private synchronized void addCompleteProductToIndex(CompleteProduct cp)
            throws CatalogException {
        IndexWriter writer = null;

        File indexDir = new File(indexFilePath);

        boolean createIndex;

        createIndex = !(indexDir.exists() && indexDir.isDirectory());

        try {
            writer = new IndexWriter(indexFilePath, new StandardAnalyzer(),
                    createIndex);
            writer.setCommitLockTimeout(this.commitLockTimeout * 1000);
            writer.setWriteLockTimeout(this.writeLockTimeout * 1000);
            writer.setMergeFactor(this.mergeFactor);

            Document doc = toDoc(cp.getProduct(), cp.getMetadata());
            writer.addDocument(doc);
            // take this out for now
            // TODO: determine a better way to optimize the index
            // writer.optimize();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Unable to index product: ["
                    + cp.getProduct().getProductName() + "]: Message: "
                    + e.getMessage(), e);
            throw new CatalogException("Unable to index product: ["
                    + cp.getProduct().getProductName() + "]: Message: "
                    + e.getMessage(), e);
        } finally {
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (Exception ignore) {
            }
        }

    }

    private CompleteProduct toCompleteProduct(Document doc) {
        return toCompleteProduct(doc, true, true);
    }

    private CompleteProduct toCompleteProduct(Document doc, boolean getRefs,
            boolean getMetadata) {
        Product product = new Product();
        Metadata metadata = new Metadata();
        CompleteProduct completeProduct = new CompleteProduct();

        product.setProductId(doc.get("product_id"));
        product.setProductName(doc.get("product_name"));
        product.setProductStructure(doc.get("product_structure"));
        product.setTransferStatus(doc.get("product_transfer_status"));

        ProductType type = new ProductType();
        type.setDescription(doc.get("product_type_desc"));
        type.setProductTypeId(doc.get("product_type_id"));
        type.setName(doc.get("product_type_name"));
        type.setProductRepositoryPath(doc.get("product_type_repoPath"));
        type.setVersioner(doc.get("product_type_versioner"));
        product.setProductType(type);

        if (getMetadata) {
            List<String> names = new ArrayList<String>();

            if (valLayer!=null) {
            	// only add metadata elements specified by validation layer
	            try {
	                for (Element element : valLayer.getElements(type)) {
	                	names.add(element.getElementName());
	                }
	            } catch (ValidationLayerException e) {
	                LOG.log(Level.WARNING,
	                        "Unable to obtain metadata for product: ["
	                                + product.getProductName() + "]: Message: "
	                                + e.getMessage());
	            }
            } else {
            	// add all metadata elements found in document
            	Enumeration<Field> fields = doc.fields();
            	while (fields.hasMoreElements()) {
            		Field field = fields.nextElement();
            		if (!names.contains(field.name())) {
            				names.add(field.name());
            		}
            	}
            	
            }

            // loop over field names to add to metadata
            for (String name : names) {
            		if (metadata.getAllMetadata(name)==null || metadata.getAllMetadata(name).size()==0) {
	                String[] elemValues = doc.getValues(name);
	                	
	                if (elemValues != null && elemValues.length > 0) {
                        for (String elemValue : elemValues) {
                            metadata.addMetadata(name, elemValue);
                        }
	                }
            		}
            }

            completeProduct.setMetadata(metadata);
        }

        if (getRefs) {
            // now add the references
            String[] origRefs = doc.getValues("reference_orig");
            String[] dataStoreRefs = doc.getValues("reference_data_store");
            String[] refLengths = doc.getValues("reference_fileSize");
            String[] refMimeTypes = doc.getValues("reference_mimeType");

            if ((origRefs.length == dataStoreRefs.length)
                    && (origRefs.length == refLengths.length)) {
                List<Reference> references = new Vector<Reference>();
                for (int i = 0; i < origRefs.length; i++) {
                    Reference r = new Reference();
                    r.setOrigReference(origRefs[i]);
                    r.setDataStoreReference(dataStoreRefs[i]);
                    r.setFileSize((Long.parseLong(refLengths[i])));
                    if (refMimeTypes != null) {
                        r.setMimeType(refMimeTypes[i]);
                    }
                    references.add(r);
                }

                product.setProductReferences(references);
            } else {
                LOG.log(Level.WARNING, "Number of original refs: ["
                        + origRefs.length + "] for product: ["
                        + product.getProductName()
                        + "] not equivalent to number of data store refs: ["
                        + dataStoreRefs.length
                        + "]: Skipping product references");
            }
        }

        completeProduct.setProduct(product);
        return completeProduct;
    }

    private Document toDoc(Product product, Metadata metadata) {
        Document doc = new Document();

        // add the product information
        doc.add(new Field("product_id", product.getProductId(),
                Field.Store.YES, Field.Index.UN_TOKENIZED));
        doc.add(new Field("product_name", product.getProductName(),
                Field.Store.YES, Field.Index.UN_TOKENIZED));
        doc.add(new Field("product_structure", product.getProductStructure(),
                Field.Store.YES, Field.Index.UN_TOKENIZED));
        doc
                .add(new Field("product_transfer_status", product
                        .getTransferStatus(), Field.Store.YES,
                        Field.Index.UN_TOKENIZED));

        // product type
        doc
                .add(new Field("product_type_id", product.getProductType()
                        .getProductTypeId(), Field.Store.YES,
                        Field.Index.UN_TOKENIZED));
        doc.add(new Field("product_type_name", product.getProductType()
                .getName(), Field.Store.YES, Field.Index.UN_TOKENIZED));
        doc.add(new Field("product_type_desc", product.getProductType()
                .getDescription() != null ? product.getProductType()
                .getDescription() : "", Field.Store.YES, Field.Index.NO));
        doc.add(new Field("product_type_repoPath", product.getProductType()
                .getProductRepositoryPath() != null ? product.getProductType()
                .getProductRepositoryPath() : "", Field.Store.YES,
                Field.Index.NO));
        doc.add(new Field("product_type_versioner", product.getProductType()
                .getVersioner() != null ? product.getProductType()
                .getVersioner() : "", Field.Store.YES, Field.Index.NO));
        
        // write metadata fields to the Lucene document
        List<String> keys = new ArrayList<String>();
        // validation layer: add only specifically configured keys
        if (valLayer!=null) {
        	List<Element> elements = quietGetElements(product.getProductType());
            for (Element element : elements) {
                String key = element.getElementName();
                keys.add(key);
            }
        // no validation layer: add all keys that are NOT already in doc
        // (otherwise some keys such as the product_* keys are duplicated)
        } else {
        	for (String key : metadata.getAllKeys()) {
        		if (doc.getField(key)==null) {
        				keys.add(key);
        		}
        	}
        }


        for (String key : keys) {
          List<String> values = metadata.getAllMetadata(key);

            if (values == null) {
                LOG
                        .log(
                                Level.WARNING,
                                "No Metadata specified for product ["
                                        + product.getProductName()
                                        + "] for required field ["
                                        + key
                                        + "]: Attempting to continue processing metadata");
                continue;
            }

            for (String val : values) {
                doc.add(new Field(key, val, Field.Store.YES,
                    Field.Index.UN_TOKENIZED));
            }
        }

        // add the product references
        for (Reference r : product.getProductReferences()) {
            doc.add(new Field("reference_orig", r.getOrigReference(),
                Field.Store.YES, Field.Index.NO));
            doc
                .add(new Field("reference_data_store", r
                    .getDataStoreReference(), Field.Store.YES,
                    Field.Index.NO));
            doc.add(new Field("reference_fileSize", String.valueOf(r
                .getFileSize()), Field.Store.YES, Field.Index.NO));
            doc.add(new Field("reference_mimeType", r.getMimeType() != null ? r
                .getMimeType().getName() : "", Field.Store.YES,
                Field.Index.UN_TOKENIZED));
        }

        // add special field for all products
        // then can use that field to retrieve back all products
        doc.add(new Field("myfield", "myvalue", Field.Store.NO,
                Field.Index.TOKENIZED));

        return doc;
    }

    private boolean hasMetadataAndRefs(CompleteProduct cp) {
        if (cp.getMetadata() != null && cp.getProduct() != null) {
            if (cp.getReferences() != null && cp.getReferences().size() > 0) {
                // make sure there is a data store ref for each of the refs
                for (Reference r : cp.getReferences()) {
                    if (r.getDataStoreReference() == null || (r.getDataStoreReference().equals(""))) {
                        return false;
                    }
                }

                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }

    }

    private int getNumHits(Query query, ProductType type)
            throws CatalogException {
        IndexSearcher searcher = null;

        int numHits = -1;

        try {
            searcher = new IndexSearcher(indexFilePath);

            // construct a Boolean query here
            BooleanQuery booleanQuery = new BooleanQuery();

            // add the product type as the first clause
            TermQuery prodTypeTermQuery = new TermQuery(new Term(
                    "product_type_id", type.getProductTypeId()));
            booleanQuery.add(prodTypeTermQuery, BooleanClause.Occur.MUST);

            //convert filemgr query into a lucene query
            for (QueryCriteria queryCriteria : query.getCriteria()) {
                booleanQuery.add(this.getQuery(queryCriteria), BooleanClause.Occur.MUST);
            }

            LOG.log(Level.FINE, "Querying LuceneCatalog: q: [" + booleanQuery
                    + "]");
            Hits hits = searcher.search(booleanQuery);
            numHits = hits.length();
        } catch (IOException e) {
            LOG.log(Level.WARNING,
                    "IOException when opening index directory: ["
                            + indexFilePath + "] for search: Message: "
                            + e.getMessage());
            throw new CatalogException(e.getMessage());
        } finally {
            if (searcher != null) {
                try {
                    searcher.close();
                } catch (Exception ignore) {
                }
            }
        }

        return numHits;
    }

    private List<Product> paginateQuery(Query query, ProductType type, int pageNum, ProductPage page)
            throws CatalogException {
        List<Product> products = null;
        IndexSearcher searcher = null;

        boolean doSkip = true;

        if (pageNum == -1) {
            doSkip = false;
        }

        try {
            searcher = new IndexSearcher(indexFilePath);

            // construct a Boolean query here
            BooleanQuery booleanQuery = new BooleanQuery();

            // add the product type as the first clause
            TermQuery prodTypeTermQuery = new TermQuery(new Term(
                    "product_type_id", type.getProductTypeId()));
            booleanQuery.add(prodTypeTermQuery, BooleanClause.Occur.MUST);
            
            //convert filemgr query into a lucene query
            for (QueryCriteria queryCriteria : query.getCriteria()) {
                booleanQuery.add(this.getQuery(queryCriteria), BooleanClause.Occur.MUST);
            }
            
            Sort sort = new Sort(new SortField("CAS.ProductReceivedTime",
                    SortField.STRING, true));
            LOG.log(Level.FINE, "Querying LuceneCatalog: q: [" + booleanQuery
                    + "]");
            Hits hits = searcher.search(booleanQuery, sort);
            
            // Calculate page size and set it while we have the results
            if (page != null) {
            	page.setTotalPages(PaginationUtils.getTotalPage(hits.length(), pageSize));
            }
            
            if (hits.length() > 0) {

                int startNum = (pageNum - 1) * pageSize;
                if (doSkip) {
                    if (startNum > hits.length()) {
                        startNum = 0;
                    }

                    products = new Vector<Product>(pageSize);

                    for (int i = startNum; i < Math.min(hits.length(),
                            (startNum + pageSize)); i++) {
                        Document productDoc = hits.doc(i);
                        CompleteProduct prod = toCompleteProduct(productDoc,
                                false, false);
                        products.add(prod.getProduct());
                    }
                } else {
                    products = new Vector<Product>(hits.length());
                    for (int i = 0; i < hits.length(); i++) {
                        Document productDoc = hits.doc(i);
                        CompleteProduct prod = toCompleteProduct(productDoc,
                                false, false);
                        products.add(prod.getProduct());
                    }
                }
            } else {
                LOG.log(Level.WARNING, "Query: [" + query
                        + "] for Product Type: [" + type.getProductTypeId()
                        + "] returned no results");
            }

        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage());
            LOG.log(Level.WARNING,
                    "IOException when opening index directory: ["
                            + indexFilePath + "] for search: Message: "
                            + e.getMessage());
            throw new CatalogException(e.getMessage());
        } finally {
            if (searcher != null) {
                try {
                    searcher.close();
                } catch (Exception ignore) {
                }
            }
        }

        return products;
    }

    private org.apache.lucene.search.Query getQuery(QueryCriteria queryCriteria) throws CatalogException {
        if (queryCriteria instanceof BooleanQueryCriteria) {
            BooleanQuery booleanQuery = new BooleanQuery();
            BooleanClause.Occur occur;
            switch (((BooleanQueryCriteria) queryCriteria).getOperator()) {
            case BooleanQueryCriteria.AND:
                occur = BooleanClause.Occur.MUST;
                break;
            case BooleanQueryCriteria.OR:
                occur = BooleanClause.Occur.SHOULD;
                break;
            case BooleanQueryCriteria.NOT:
                occur = BooleanClause.Occur.MUST_NOT;
                booleanQuery.add(new WildcardQuery(new Term(((BooleanQueryCriteria) queryCriteria)
                        .getTerms().get(0).getElementName(), "*")), BooleanClause.Occur.SHOULD);
                break;
            default:
                throw new CatalogException("Invalid BooleanQueryCriteria opertor [" 
                        + ((BooleanQueryCriteria) queryCriteria).getOperator() + "]");
            }
            for (QueryCriteria qc : ((BooleanQueryCriteria) queryCriteria).getTerms()) {
                booleanQuery.add(this.getQuery(qc), occur);
            }

            return booleanQuery;
        } else if (queryCriteria instanceof TermQueryCriteria) {
            String val = ((TermQueryCriteria) queryCriteria).getValue();
            return new TermQuery(new Term(queryCriteria.getElementName(), val));
        } else if (queryCriteria instanceof RangeQueryCriteria) {
            String startVal = ((RangeQueryCriteria) queryCriteria).getStartValue();
            String endVal = ((RangeQueryCriteria) queryCriteria).getEndValue();
            boolean inclusive = ((RangeQueryCriteria) queryCriteria).getInclusive();
            Term startTerm = null, endTerm = null;
            if (!startVal.equals("")) {
                startTerm = new Term(queryCriteria.getElementName(), startVal);
            }

            if (!endVal.equals("")) {
                endTerm = new Term(queryCriteria.getElementName(), endVal);
            }

            return new RangeQuery(startTerm, endTerm, inclusive);
        }else {
            throw new CatalogException("Invalid QueryCriteria ["
                    + queryCriteria.getClass().getCanonicalName() + "]");
        }
    }
    
    private List<Element> quietGetElements(ProductType type) {
        List<Element> elementList = new Vector<Element>();

        try {
            elementList = valLayer.getElements(type);
        } catch (Exception e) {
            LOG.log(Level.WARNING,
                    "Exception obtaining elements for product type: ["
                            + type.getName() + "]: Message: " + e.getMessage());
        }

        return elementList;
    }

    private class CompleteProduct {
        private Metadata metadata = null;

        private Product product = null;

        public CompleteProduct(Metadata met, List<Reference> refs, Product p) {
            this.metadata = met;
            this.product = p;
            this.product.setProductReferences(refs);
        }

        public CompleteProduct() {
        }

        /**
         * @return Returns the metadata.
         */
        public Metadata getMetadata() {
            return metadata;
        }

        /**
         * @param metadata
         *            The metadata to set.
         */
        public void setMetadata(Metadata metadata) {
            this.metadata = metadata;
        }

        /**
         * @return Returns the product.
         */
        public Product getProduct() {
            return product;
        }

        /**
         * @param product
         *            The product to set.
         */
        public void setProduct(Product product) {
            this.product = product;
        }

        /**
         * @return Returns the references.
         */
        public List<Reference> getReferences() {
            return product.getProductReferences();
        }

        /**
         * @param references
         *            The references to set.
         */
        public void setReferences(List<Reference> references) {
            this.product.setProductReferences(references);
        }

    }

}
