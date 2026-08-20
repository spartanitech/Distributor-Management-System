package com.spartan.dms.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

// BUG-M10 fix: this used to define the OpenAPI bean unconditionally, so
// Swagger UI / the OpenAPI docs were reachable in every environment
// including production. Gated to local/dev only, mirroring the same
// profile-gating approach used for DataInitializer (BUG-H8) -- outside
// those profiles this bean is never created, so springdoc has nothing to
// serve regardless of what the security rules for /swagger-ui/** allow.
// See also application.properties / application-local.properties.example
// for the springdoc.*.enabled property override that backs this up.
@Configuration
@Profile({"local", "dev"})
public class SwaggerConfig {

    @Bean
    public OpenAPI distributorManagementOpenAPI() {

        return new OpenAPI()
                .info(new Info()
                        .title("Distributor Management System API")
                        .description("REST API Documentation for Spartan Distributor Management System")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Spartan Technologies")
                                .email("support@spartan.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .externalDocs(new ExternalDocumentation()
                        .description("Project Documentation")
                        .url("https://spartan.com"));
    }
}