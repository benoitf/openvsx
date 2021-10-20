/********************************************************************************
 * Copyright (c) 2020 TypeFox and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.openvsx.storage;

import static org.eclipse.openvsx.entities.FileResource.*;

import java.net.URI;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.transaction.Transactional;

import com.google.common.base.Strings;

import org.eclipse.openvsx.entities.ExtensionVersion;
import org.eclipse.openvsx.entities.FileResource;
import org.eclipse.openvsx.repositories.RepositoryService;
import org.eclipse.openvsx.search.SearchUtilService;
import org.eclipse.openvsx.util.UrlUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Provides utility around storing file resources and acts as a composite storage
 * service around the actually configured external services.
 */
@Component
public class StorageUtilService implements IStorageService {

    @Autowired
    RepositoryService repositories;

    @Autowired
    SearchUtilService search;

    @Autowired
    GoogleCloudStorageService googleStorage;

    @Autowired
    AzureBlobStorageService azureStorage;

    /** Determines which external storage service to use in case multiple services are configured. */
    @Value("${ovsx.storage.primary-service:}")
    String primaryService;

    /** Determines which resource types are stored externally. Default: all resources ({@code *}) */
    @Value("${ovsx.storage.external-resource-types:*}")
    String[] externalResourceTypes;

    public boolean shouldStoreExternally(FileResource resource) {
        if (!isEnabled()) {
            return false;
        }
        if (externalResourceTypes.length == 1 && "*".equals(externalResourceTypes[0])) {
            return true;
        }
        return Arrays.asList(externalResourceTypes).contains(resource.getType());
    }

    @Override
    public boolean isEnabled() {
        return googleStorage.isEnabled() || azureStorage.isEnabled();
    }

    public String getActiveStorageType() {
        var storageTypes = new ArrayList<String>(2);
        if (googleStorage.isEnabled())
            storageTypes.add(STORAGE_GOOGLE);
        if (azureStorage.isEnabled())
            storageTypes.add(STORAGE_AZURE);
        if (!Strings.isNullOrEmpty(primaryService)) {
            if (!storageTypes.contains(primaryService))
                throw new RuntimeException("The selected primary storage service is not available.");
            return primaryService;
        }
        if (storageTypes.isEmpty())
            return STORAGE_DB;
        if (storageTypes.size() == 1)
            return storageTypes.get(0);
        throw new RuntimeException("Multiple external storage services are available. Please select a primary service.");
    }

    @Override
    public void uploadFile(FileResource resource) {
        switch (getActiveStorageType()) {
            case STORAGE_GOOGLE:
                googleStorage.uploadFile(resource);
                break;
            case STORAGE_AZURE:
                azureStorage.uploadFile(resource);
                break;
            default:
                throw new RuntimeException("External storage is not available.");
        }
    }

    @Override
    public void removeFile(FileResource resource) {
        switch (resource.getStorageType()) {
            case STORAGE_GOOGLE:
                googleStorage.removeFile(resource);
                break;
            case STORAGE_AZURE:
                azureStorage.removeFile(resource);
                break;
        }
    }

    @Override
    public URI getLocation(FileResource resource) {
        switch (resource.getStorageType()) {
            case STORAGE_GOOGLE:
                return googleStorage.getLocation(resource);
            case STORAGE_AZURE:
                return azureStorage.getLocation(resource);
            case STORAGE_DB:
                return URI.create(getFileUrl(resource.getName(), resource.getExtension(), UrlUtil.getBaseUrl()));
            default:
                return null;
        }
    }

    private String getFileUrl(String name, ExtensionVersion extVersion, String serverUrl) {
        var extension = extVersion.getExtension();
        var namespace = extension.getNamespace();
        return UrlUtil.createApiUrl(serverUrl, "api", namespace.getName(), extension.getName(), extVersion.getVersion(),
                "file", name);
    }

    /**
     * Adds URLs for the given file types to a map to be used in JSON response data.
     */
    public void addFileUrls(ExtensionVersion extVersion, String serverUrl, Map<String, String> type2Url, String... types) {
        var extension = extVersion.getExtension();
        var namespace = extension.getNamespace();
        var versionUrl = UrlUtil.createApiUrl(serverUrl, "api", namespace.getName(), extension.getName(), extVersion.getVersion());
        var resources = repositories.findFilesByType(extVersion, Arrays.asList(types));
        for (var resource : resources) {
            var fileUrl = UrlUtil.createApiUrl(versionUrl, "file", resource.getName());
            type2Url.put(resource.getType(), fileUrl);
        }
    }

    public Map<String, String> getWebResourceUrls(ExtensionVersion extVersion, String serverUrl) {
        var name2Url = new HashMap<String, String>();
        var extension = extVersion.getExtension();
        var namespace = extension.getNamespace();
        var versionUrl = UrlUtil.createApiUrl(serverUrl, "api", namespace.getName(), extension.getName(), extVersion.getVersion());
        var resources = repositories.findFilesByType(extVersion, Arrays.asList(WEB_RESOURCE));
        if (resources != null) {
            for (var resource : resources) {
                var fileUrl = UrlUtil.createApiUrl(versionUrl, "file", resource.getName());
                name2Url.put(resource.getName(), fileUrl);
            }
        }
        return name2Url;
    }

    /**
     * Register a package file download by increasing its download count.
     */
    @Transactional
    public void increaseDownloadCount(ExtensionVersion extVersion) {
        var extension = extVersion.getExtension();
        extension.setDownloadCount(extension.getDownloadCount() + 1);
        search.updateSearchEntry(extension);
    }

    public HttpHeaders getFileResponseHeaders(String fileName) {
        var headers = new HttpHeaders();
        headers.setContentType(getFileType(fileName));
        if (fileName.endsWith(".vsix")) {
            headers.add("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        } else {
            headers.setCacheControl(getCacheControl(fileName));
        }
        return headers;
    }

    public MediaType getFileType(String fileName) {
        if (fileName.endsWith(".vsix"))
            return MediaType.APPLICATION_OCTET_STREAM;
        if (fileName.endsWith(".json"))
            return MediaType.APPLICATION_JSON;
        var contentType = URLConnection.guessContentTypeFromName(fileName);
        if (contentType != null)
            return MediaType.parseMediaType(contentType);
        return MediaType.TEXT_PLAIN;
    }

    public CacheControl getCacheControl(String fileName) {
        // Files are requested with a version string in the URL, so their content cannot change
        return CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic();
    }
    
}