/********************************************************************************
 * Copyright (c) 2020 TypeFox and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.openvsx.search;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;

import org.eclipse.openvsx.entities.Extension;
import org.eclipse.openvsx.entities.ExtensionVersion;
import org.eclipse.openvsx.entities.NamespaceMembership;
import org.eclipse.openvsx.repositories.RepositoryService;
import org.eclipse.openvsx.util.ErrorResultException;
import org.eclipse.openvsx.util.TimeUtil;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StopWatch;

@Component
public class SearchService {

    protected final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    protected final Logger logger = LoggerFactory.getLogger(SearchService.class);

    @Autowired
    RepositoryService repositories;

    @Autowired
    ElasticsearchOperations searchOperations;

    @Value("${ovsx.elasticsearch.enabled:true}")
    boolean enableSearch;
    @Value("${ovsx.elasticsearch.clear-on-start:false}")
    boolean clearOnStart;

    @Value("${ovsx.elasticsearch.relevance.rating:1.0}")
    double ratingRelevance;
    @Value("${ovsx.elasticsearch.relevance.downloads:1.0}")
    double downloadsRelevance;
    @Value("${ovsx.elasticsearch.relevance.timestamp:1.0}")
    double timestampRelevance;
    @Value("${ovsx.elasticsearch.relevance.unverified:0.5}")
    double unverifiedRelevance;

    public boolean isEnabled() {
        return enableSearch;
    }

    /**
     * Application start listener that initializes the search index. If the application property
     * {@code ovsx.elasticsearch.clear-on-start} is set to {@code true}, the index is cleared
     * and rebuilt from scratch. If the property is {@code false} and the search index does
     * not exist yet, it is created and initialized. Otherwise nothing happens.
     */
    @EventListener
    @Transactional(readOnly = true)
    public void initSearchIndex(ApplicationStartedEvent event) {
        if (!isEnabled() || !clearOnStart && searchOperations.indexOps(ExtensionSearch.class).exists()) {
            return;
        }
        var stopWatch = new StopWatch();
        stopWatch.start();
        updateSearchIndex(clearOnStart);
        stopWatch.stop();
        logger.info("Initialized search index in " + stopWatch.getTotalTimeMillis() + " ms");
    }

    /**
     * Task scheduled once per day to soft-update the search index. This is necessary
     * because the relevance of index entries might consider the extension publishing
     * timestamps in relation to the current time.
     */
    @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
    @Transactional(readOnly = true)
    public void updateSearchIndex() {
        if (!isEnabled() || Math.abs(timestampRelevance) < 0.01) {
            return;
        }
        var stopWatch = new StopWatch();
        stopWatch.start();
        updateSearchIndex(false);
        stopWatch.stop();
        logger.info("Updated search index in " + stopWatch.getTotalTimeMillis() + " ms");
    }

    /**
     * Updating the search index has two modes:
     * <em>soft</em> ({@code clear} is set to {@code false}) means the index is created
     * if it does not exist yet, and
     * <em>hard</em> ({@code clear} is set to {@code true}) means the index is deleted
     * and then recreated.
     * In any case, this method scans all extensions in the database and indexes their
     * relevant metadata.
     */
    public void updateSearchIndex(boolean clear) {
        var locked = false;
        try {
            var indexOps = searchOperations.indexOps(ExtensionSearch.class);
            if (clear) {
                // Hard mode: delete the index if it exists, then recreate it
                rwLock.writeLock().lock();
                locked = true;
                if (indexOps.exists()) {
                    indexOps.delete();
                }
                indexOps.create();
            } else if (!indexOps.exists()) {
                // Soft mode: the index is created only when it does not exist yet
                rwLock.writeLock().lock();
                locked = true;
                indexOps.create();
            }
            
            // Scan all extensions and create index queries
            var allExtensions = repositories.findAllActiveExtensions();
            if (allExtensions.isEmpty()) {
                return;
            }
            var stats = new SearchStats();
            var indexQueries = allExtensions.map(extension ->
                new IndexQueryBuilder()
                    .withObject(toSearchEntry(extension, stats))
                    .build()
            ).toList();

            if (!locked) {
                // The write lock has not been acquired upfront, so do it just before submitting the index queries
                rwLock.writeLock().lock();
                locked = true;
            }
            searchOperations.bulkIndex(indexQueries, indexOps.getIndexCoordinates());
        } finally {
            if (locked) {
                rwLock.writeLock().unlock();
            }
        }
    }

    public void updateSearchEntry(Extension extension) {
        if (!isEnabled()) {
            return;
        }
        try {
            rwLock.writeLock().lock();
            var stats = new SearchStats();
            var indexQuery = new IndexQueryBuilder()
                    .withObject(toSearchEntry(extension, stats))
                    .build();
            var indexOps = searchOperations.indexOps(ExtensionSearch.class);
            searchOperations.index(indexQuery, indexOps.getIndexCoordinates());
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void removeSearchEntry(Extension extension) {
        if (!isEnabled()) {
            return;
        }
        try {
            rwLock.writeLock().lock();
            var indexOps = searchOperations.indexOps(ExtensionSearch.class);
            searchOperations.delete(Long.toString(extension.getId()), indexOps.getIndexCoordinates());
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    protected ExtensionSearch toSearchEntry(Extension extension, SearchStats stats) {
        var entry = extension.toSearch();
        var ratingValue = 0.0;
        if (entry.averageRating != null) {
            var reviewCount = repositories.countActiveReviews(extension);
            // Reduce the rating relevance if there are only few reviews
            var countRelevance = saturate(reviewCount, 0.25);
            ratingValue = (entry.averageRating / 5.0) * countRelevance;
        }
        var downloadsValue = entry.downloadCount / stats.downloadRef;
        var timestamp = extension.getLatest().getTimestamp();
        var timestampValue = Duration.between(stats.oldest, timestamp).toSeconds() / stats.timestampRef;
        entry.relevance = ratingRelevance * limit(ratingValue)
                + downloadsRelevance * limit(downloadsValue)
                + timestampRelevance * limit(timestampValue);

        // Reduce the relevance value of unverified extensions
        if (!isVerified(extension.getLatest())) {
            entry.relevance *= unverifiedRelevance;
        }
    
        if (Double.isNaN(entry.relevance) || Double.isInfinite(entry.relevance)) {
            var message = "Invalid relevance for entry " + entry.namespace + "." + entry.name;
            try {
                message += " " + new ObjectMapper().writeValueAsString(stats);
            } catch (JsonProcessingException exc) {
                // Ignore exception
            }
            logger.error(message);
            entry.relevance = 0.0;
        }
        return entry;
    }

    private double limit(double value) {
        if (value < 0.0)
            return 0.0;
        else if (value > 1.0)
            return 1.0;
        else
            return value;
    }

    private double saturate(double value, double factor) {
        return 1 - 1.0 / (value * factor + 1);
    }

    private boolean isVerified(ExtensionVersion extVersion) {
        if (extVersion.getPublishedWith() == null)
            return false;
        var user = extVersion.getPublishedWith().getUser();
        var namespace = extVersion.getExtension().getNamespace();
        return repositories.countMemberships(namespace, NamespaceMembership.ROLE_OWNER) > 0
                && repositories.countMemberships(user, namespace) > 0;
    }

    public SearchHits<ExtensionSearch> search(Options options, Pageable pageRequest) {
        var queryBuilder = new NativeSearchQueryBuilder()
                .withPageable(pageRequest);
        if (!Strings.isNullOrEmpty(options.queryString)) {
            var boolQuery = QueryBuilders.boolQuery();

            boolQuery.should(QueryBuilders.termQuery("extensionId.keyword", options.queryString)).boost(10);

            // Fuzzy matching of search query in multiple fields
            var multiMatchQuery = QueryBuilders.multiMatchQuery(options.queryString)
                    .field("name").boost(5)
                    .field("displayName").boost(5)
                    .field("tags").boost(3)
                    .field("namespace").boost(2)
                    .field("description")
                    .fuzziness(Fuzziness.AUTO)
                    .prefixLength(2);
            boolQuery.should(multiMatchQuery).boost(5);

            // Prefix matching of search query in display name and namespace
            var prefixString = options.queryString.trim().toLowerCase();
            var namePrefixQuery = QueryBuilders.prefixQuery("displayName", prefixString);
            boolQuery.should(namePrefixQuery).boost(2);
            var namespacePrefixQuery = QueryBuilders.prefixQuery("namespace", prefixString);
            boolQuery.should(namespacePrefixQuery);

            queryBuilder.withQuery(boolQuery);
        }

        if (!Strings.isNullOrEmpty(options.category)) {
            // Filter by selected category
            queryBuilder.withFilter(QueryBuilders.matchPhraseQuery("categories", options.category));
        }

        // Sort search results according to 'sortOrder' and 'sortBy' options
        sortResults(queryBuilder, options.sortOrder, options.sortBy);
        
        try {
            rwLock.readLock().lock();
            var indexOps = searchOperations.indexOps(ExtensionSearch.class);
            return searchOperations.search(queryBuilder.build(), ExtensionSearch.class, indexOps.getIndexCoordinates());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    private void sortResults(NativeSearchQueryBuilder queryBuilder, String sortOrder, String sortBy) {
        if (!"asc".equalsIgnoreCase(sortOrder) && !"desc".equalsIgnoreCase(sortOrder)) {
            throw new ErrorResultException("sortOrder parameter must be either 'asc' or 'desc'.");
        }

        if ("relevance".equals(sortBy)) {
            queryBuilder.withSort(SortBuilders.scoreSort());
        }

        if ("relevance".equals(sortBy) || "averageRating".equals(sortBy)) {
            queryBuilder.withSort(
                    SortBuilders.fieldSort(sortBy).unmappedType("float").order(SortOrder.fromString(sortOrder)));
        } else if ("timestamp".equals(sortBy)) {
            queryBuilder.withSort(
                    SortBuilders.fieldSort(sortBy).unmappedType("long").order(SortOrder.fromString(sortOrder)));
        } else if ("downloadCount".equals(sortBy)) {
            queryBuilder.withSort(
                    SortBuilders.fieldSort(sortBy).unmappedType("integer").order(SortOrder.fromString(sortOrder)));
        } else {
            throw new ErrorResultException(
                    "sortBy parameter must be 'relevance', 'timestamp', 'averageRating' or 'downloadCount'");
        }
    }

    public static class Options {
        public final String queryString;
        public final String category;
        public final int requestedSize;
        public final int requestedOffset;
        public final String sortOrder;
        public final String sortBy;
        public final boolean includeAllVersions;

        public Options(String queryString, String category, int size, int offset, String sortOrder,
                String sortBy, boolean includeAllVersions) {
            this.queryString = queryString;
            this.category = category;
            this.requestedSize = size;
            this.requestedOffset = offset;
            this.sortOrder = sortOrder;
            this.sortBy = sortBy;
            this.includeAllVersions = includeAllVersions;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this)
                return true;
            if (!(obj instanceof Options))
                return false;
            var other = (Options) obj;
            if (!Objects.equals(this.queryString, other.queryString))
                return false;
            if (!Objects.equals(this.category, other.category))
                return false;
            if (this.requestedSize != other.requestedSize)
                return false;
            if (this.requestedOffset != other.requestedOffset)
                return false;
            if (!Objects.equals(this.sortOrder, other.sortOrder))
                return false;
            if (!Objects.equals(this.sortBy, other.sortBy))
                return false;
            if (this.includeAllVersions != other.includeAllVersions)
                return false;
            return true;
        }

        @Override
        public int hashCode() {
            return Objects.hash(queryString, category, requestedSize, requestedOffset, sortOrder, sortBy,
                    includeAllVersions);
        }
    }

    protected class SearchStats {
        protected final double downloadRef;
        protected final double timestampRef;
        protected final LocalDateTime oldest;

        public SearchStats() {
            var now = TimeUtil.getCurrentUTC();
            var maxDownloads = repositories.getMaxExtensionDownloadCount();
            var oldestTimestamp = repositories.getOldestExtensionTimestamp();
            this.downloadRef = maxDownloads * 1.5 + 100;
            this.oldest = oldestTimestamp == null ? now : oldestTimestamp;
            this.timestampRef = Duration.between(this.oldest, now).toSeconds() + 60;
        }
    }

}