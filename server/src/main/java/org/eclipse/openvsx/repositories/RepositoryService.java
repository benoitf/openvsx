/********************************************************************************
 * Copyright (c) 2019 TypeFox and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.openvsx.repositories;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Streamable;
import org.springframework.stereotype.Component;
import org.eclipse.openvsx.entities.PersistedLog;

import java.time.LocalDateTime;
import java.util.Collection;

import org.eclipse.openvsx.entities.Extension;
import org.eclipse.openvsx.entities.ExtensionReview;
import org.eclipse.openvsx.entities.ExtensionVersion;
import org.eclipse.openvsx.entities.FileResource;
import org.eclipse.openvsx.entities.PersonalAccessToken;
import org.eclipse.openvsx.entities.Namespace;
import org.eclipse.openvsx.entities.NamespaceMembership;
import org.eclipse.openvsx.entities.UserData;

@Component
public class RepositoryService {

    @Autowired NamespaceRepository namespaceRepo;
    @Autowired ExtensionRepository extensionRepo;
    @Autowired ExtensionVersionRepository extensionVersionRepo;
    @Autowired FileResourceRepository fileResourceRepo;
    @Autowired ExtensionReviewRepository extensionReviewRepo;
    @Autowired UserDataRepository userDataRepo;
    @Autowired NamespaceMembershipRepository membershipRepo;
    @Autowired PersonalAccessTokenRepository tokenRepo;
    @Autowired PersistedLogRepository persistedLogRepo;

    public Namespace findNamespace(String name) {
        return namespaceRepo.findByNameIgnoreCase(name);
    }

    public Namespace findNamespaceByPublicId(String publicId) {
        return namespaceRepo.findByPublicId(publicId);
    }

    public Streamable<Namespace> findOrphanNamespaces() {
        return namespaceRepo.findOrphans();
    }

    public long countNamespaces() {
        return namespaceRepo.count();
    }

    public Extension findExtension(String name, Namespace namespace) {
        return extensionRepo.findByNameIgnoreCaseAndNamespace(name, namespace);
    }

    public Extension findExtension(String name, String namespace) {
        return extensionRepo.findByNameIgnoreCaseAndNamespaceNameIgnoreCase(name, namespace);
    }

    public Extension findExtensionByPublicId(String publicId) {
        return extensionRepo.findByPublicId(publicId);
    }

    public Streamable<Extension> findActiveExtensions(Namespace namespace) {
        return extensionRepo.findByNamespaceAndActiveTrueOrderByNameAsc(namespace);
    }

    public Streamable<Extension> findExtensions(Namespace namespace) {
        return extensionRepo.findByNamespace(namespace);
    }

    public Streamable<Extension> findExtensions(String name) {
        return extensionRepo.findByNameIgnoreCase(name);
    }

    public Streamable<Extension> findAllActiveExtensions() {
        return extensionRepo.findByActiveTrue();
    }

    public long countExtensions() {
        return extensionRepo.count();
    }

    public long countExtensions(String name, String namespace) {
        return extensionRepo.countByNameIgnoreCaseAndNamespaceNameIgnoreCase(name, namespace);
    }

    public int getMaxExtensionDownloadCount() {
        return extensionRepo.getMaxDownloadCount();
    }

    public ExtensionVersion findVersion(String version, Extension extension) {
        return extensionVersionRepo.findByVersionAndExtension(version, extension);
    }

    public ExtensionVersion findVersion(String version, String extensionName, String namespace) {
        return extensionVersionRepo.findByVersionAndExtensionNameIgnoreCaseAndExtensionNamespaceNameIgnoreCase(version, extensionName, namespace);
    }

    public Streamable<ExtensionVersion> findVersions(Extension extension) {
         return extensionVersionRepo.findByExtension(extension);
    }

    public Streamable<ExtensionVersion> findActiveVersions(Extension extension) {
         return extensionVersionRepo.findByExtensionAndActiveTrue(extension);
    }

    public Streamable<String> getVersionStrings(Extension extension) {
        return extensionVersionRepo.getVersionStrings(extension);
    }

    public Streamable<String> getActiveVersionStrings(Extension extension) {
        return extensionVersionRepo.getActiveVersionStrings(extension);
    }

    public Streamable<ExtensionVersion> findActiveVersions(Extension extension, boolean preview) {
         return extensionVersionRepo.findByExtensionAndPreviewAndActiveTrue(extension, preview);
    }

    public Streamable<ExtensionVersion> findBundledExtensionsReference(Extension extension) {
        return extensionVersionRepo.findByBundledExtensions(extensionId(extension));
    }

    public Streamable<ExtensionVersion> findDependenciesReference(Extension extension) {
        return extensionVersionRepo.findByDependencies(extensionId(extension));
    }

    private String extensionId(Extension extension) {
        return extension.getNamespace().getName() + "." + extension.getName();
    }

    public Streamable<ExtensionVersion> findVersionsByAccessToken(PersonalAccessToken publishedWith) {
        return extensionVersionRepo.findByPublishedWith(publishedWith);
    }

    public Streamable<ExtensionVersion> findVersionsByAccessToken(PersonalAccessToken publishedWith, boolean active) {
        return extensionVersionRepo.findByPublishedWithAndActive(publishedWith, active);
    }

    public LocalDateTime getOldestExtensionTimestamp() {
        return extensionVersionRepo.getOldestTimestamp();
    }

    public Streamable<FileResource> findFiles(ExtensionVersion extVersion) {
        return fileResourceRepo.findByExtension(extVersion);
    }

    public Streamable<FileResource> findFilesByStorageType(String storageType) {
        return fileResourceRepo.findByStorageType(storageType);
    }

    public FileResource findFileByName(ExtensionVersion extVersion, String name) {
        return fileResourceRepo.findByExtensionAndNameIgnoreCase(extVersion, name);
    }

    public FileResource findFileByTypeAndName(ExtensionVersion extVersion, String type, String name) {
        return fileResourceRepo.findByExtensionAndTypeAndNameIgnoreCase(extVersion, type, name);
    }

    public FileResource findFileByType(ExtensionVersion extVersion, String type) {
        return fileResourceRepo.findByExtensionAndType(extVersion, type);
    }

    public Streamable<FileResource> findFilesByType(ExtensionVersion extVersion, Collection<String> types) {
        return fileResourceRepo.findByExtensionAndTypeIn(extVersion, types);
    }

    public Streamable<ExtensionReview> findActiveReviews(Extension extension) {
        return extensionReviewRepo.findByExtensionAndActiveTrue(extension);
    }

    public Streamable<ExtensionReview> findAllReviews(Extension extension) {
        return extensionReviewRepo.findByExtension(extension);
    }

    public Streamable<ExtensionReview> findActiveReviews(Extension extension, UserData user) {
        return extensionReviewRepo.findByExtensionAndUserAndActiveTrue(extension, user);
    }

    public long countActiveReviews(Extension extension) {
        return extensionReviewRepo.countByExtensionAndActiveTrue(extension);
    }

    public UserData findUserByAuthId(String provider, String providerId) {
        return userDataRepo.findByProviderAndAuthId(provider, providerId);
    }

    public UserData findUserByLoginName(String provider, String loginName) {
        return userDataRepo.findByProviderAndLoginName(provider, loginName);
    }

    public Streamable<UserData> findUsersByLoginNameStartingWith(String loginNameStart) {
        return userDataRepo.findByLoginNameStartingWith(loginNameStart);
    }

    public Streamable<UserData> findAllUsers() {
        return userDataRepo.findAll();
    }

    public long countUsers() {
        return userDataRepo.count();
    }

    public NamespaceMembership findMembership(UserData user, Namespace namespace) {
        return membershipRepo.findByUserAndNamespace(user, namespace);
    }

    public long countMemberships(UserData user, Namespace namespace) {
        return membershipRepo.countByUserAndNamespace(user, namespace);
    }

    public Streamable<NamespaceMembership> findMemberships(Namespace namespace, String role) {
        return membershipRepo.findByNamespaceAndRoleIgnoreCase(namespace, role);
    }

    public long countMemberships(Namespace namespace, String role) {
        return membershipRepo.countByNamespaceAndRoleIgnoreCase(namespace, role);
    }

    public Streamable<NamespaceMembership> findMemberships(UserData user, String role) {
        return membershipRepo.findByUserAndRoleIgnoreCaseOrderByNamespaceName(user, role);
    }

    public Streamable<NamespaceMembership> findMemberships(Namespace namespace) {
        return membershipRepo.findByNamespace(namespace);
    }

    public Streamable<PersonalAccessToken> findAccessTokens(UserData user) {
        return tokenRepo.findByUser(user);
    }

    public PersonalAccessToken findAccessToken(String value) {
        return tokenRepo.findByValue(value);
    }

    public PersonalAccessToken findAccessToken(long id) {
        return tokenRepo.findById(id);
    }

    public Streamable<PersistedLog> findAllPersistedLogs() {
        return persistedLogRepo.findByOrderByTimestampAsc();
    }

    public Streamable<PersistedLog> findPersistedLogsAfter(LocalDateTime dateTime) {
        return persistedLogRepo.findByTimestampAfterOrderByTimestampAsc(dateTime);
    }

}