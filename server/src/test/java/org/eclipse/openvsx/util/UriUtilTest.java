/********************************************************************************
 * Copyright (c) 2020 TypeFox and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.openvsx.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import javax.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class UriUtilTest {

    @Mock
    private HttpServletRequest request;

    private AutoCloseable closeable;
    
    @BeforeEach
    public void openMocks() {
     closeable = MockitoAnnotations.openMocks(this);
    }
    
    @AfterEach
    public void releaseMocks() throws Exception {
        closeable.close();
    }

    @Test
    public void testApiUrl() throws Exception {
        var baseUrl = "http://localhost/";
        assertThat(UrlUtil.createApiUrl(baseUrl, "api", "foo", "b\ta/r"))
                .isEqualTo("http://localhost/api/foo/b%09a%2Fr");
    }

    @Test
    public void testQuery() throws Exception {
        var url = "http://localhost/api/foo";
        assertThat(UrlUtil.addQuery(url, "a", "1", "b", null, "c", "b\ta/r"))
                .isEqualTo("http://localhost/api/foo?a=1&c=b%09a%2Fr");
    }

    // Check base URL is localhost:8080 if there is no XForwarded headers
    @Test
    public void testWithoutXForwarded() throws Exception {
        doReturn("http").when(request).getScheme();
        doReturn("localhost").when(request).getServerName();
        doReturn(8080).when(request).getServerPort();
        doReturn("/").when(request).getContextPath();
        assertThat(UrlUtil.getBaseUrl(request)).isEqualTo("http://localhost:8080/");
    }    

    // Check base URL is using XForwarded headers
    @Test
    public void testWithXForwarded() throws Exception {
        // basic request
        doReturn("http").when(request).getScheme();
        doReturn("localhost").when(request).getServerName();
        doReturn(8080).when(request).getServerPort();
        doReturn("/").when(request).getContextPath();

        // XForwarded content
        doReturn("https").when(request).getHeader("X-Forwarded-Proto");
        doReturn("open-vsx.org").when(request).getHeader("X-Forwarded-Host");
        doReturn("/openvsx").when(request).getHeader("X-Forwarded-Prefix");
        assertThat(UrlUtil.getBaseUrl(request)).isEqualTo("https://open-vsx.org/openvsx/");
    }    

}
