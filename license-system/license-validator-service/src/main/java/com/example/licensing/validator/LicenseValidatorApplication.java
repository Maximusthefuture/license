package com.example.licensing.validator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * License validator service - runs in the customer's cluster next to the product.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LicenseValidatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(LicenseValidatorApplication.class, args);
    }
}
