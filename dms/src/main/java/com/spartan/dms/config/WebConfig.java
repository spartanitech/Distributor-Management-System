package com.spartan.dms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Exposes the on-disk uploads/ directory (where FileUploadUtil writes
 * uploaded files) as static content at /uploads/**.
 *
 * Without this handler, files saved by FileUploadUtil are stored correctly
 * on disk but are completely unreachable over HTTP — any <img src> or
 * download link pointing at them returns 404.
 *
 * uploads/paymentproofs/** is deliberately excluded (see the resolver
 * below): those are financial documents scoped to one payment's parties,
 * and this handler has no way to check who's asking — SecurityConfig only
 * requires *some* valid login for /uploads/**, not the right one, so
 * anyone authenticated who obtained a proof's URL could otherwise view
 * another party's payment proof. Proof files are served instead through
 * PaymentProofController's GET /api/v1/payment-proofs/file/{proofId},
 * which runs PaymentProofService.assertProofAccess() first. If a
 * non-payment-proof upload feature is added later, give it its own
 * subdirectory here rather than reusing paymentproofs/.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    // Bug fix: this used to be Paths.get("uploads") -- relative to whatever
    // folder the JVM happened to be started from, and out of sync the
    // moment that changed. Now reads the same app.upload-dir property
    // FileUploadUtil writes to, so this handler always serves from wherever
    // files were actually saved.
    // Same defensive default as FileUploadUtil.uploadRoot -- see the comment
    // there. Keeps the app bootable even if target/classes/application.properties
    // is a stale build missing the app.upload-dir line.
    @Value("${app.upload-dir:${user.home}/brisk-dms-uploads}")
    private String uploadRoot;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path uploadDir = Paths.get(uploadRoot).toAbsolutePath().normalize();

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadDir + "/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, org.springframework.core.io.Resource location) throws java.io.IOException {
                        if (resourcePath.startsWith("paymentproofs/")) {
                            return null; // 404 -- must go through the access-controlled endpoint
                        }
                        return super.getResource(resourcePath, location);
                    }
                });
    }
}
