package com.moveinsync.rostersync;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StaticResourcesIT extends AbstractSecurityIntegrationTest {
    @Test void htmlAndHashedAssetsArePublicWithDifferentCachePolicies() throws Exception {
        var html=request("GET","/index.html",null,null);
        assertThat(html.statusCode()).isEqualTo(200);
        assertThat(html.headers().firstValue("Cache-Control").orElseThrow()).isEqualTo("no-cache");
        var root=request("GET","/",null,null);
        assertThat(root.statusCode()).isEqualTo(200);
        var asset=request("GET","/assets/fixture-12345678.js",null,null);
        assertThat(asset.statusCode()).isEqualTo(200);
        assertThat(asset.headers().firstValue("Cache-Control").orElseThrow()).contains("max-age=31536000","immutable");
        assertThat(asset.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("script-src 'self'","font-src 'self'","frame-ancestors 'none'");
        assertThat(asset.headers().firstValue("Referrer-Policy").orElseThrow()).isEqualTo("no-referrer");
        assertThat(asset.headers().firstValue("X-Content-Type-Options").orElseThrow()).isEqualTo("nosniff");
    }
    @Test void missingResourcesAreProblemDetailsAndApisRemainProtected() throws Exception {
        problem(request("GET","/assets/missing.js",null,null),404,"NOT_FOUND");
        problem(request("GET","/runs/unknown",token("acme.operator"),null),404,"NOT_FOUND");
        problem(request("GET","/api/v1/reconciliation/runs",null,null),401,"UNAUTHENTICATED");
        problem(request("POST","/index.html",null,"{}"),401,"UNAUTHENTICATED");
    }
}
