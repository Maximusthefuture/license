package com.example.licensing.validator;

import com.example.licensing.client.ClusterIdPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code license.validator.*} settings. Defaults are in application.yaml.
 *
 * @param productId       must match the product id the generator puts into licenses
 * @param productVersion  informational, shown to the operator issuing the license
 * @param clusterIdPolicy STRICT: a license bound to a cluster only works in that cluster
 * @param cacheTtl        how long a verified license is reused before re-reading the database
 * @param expiringWarning how long before the end date the status turns EXPIRING
 * @param grace           how long after the end date the status stays GRACE (product still works)
 * @param clockTolerance  how far the system clock may be behind the last known time before
 *                        it is reported as turned back (validity is always counted from the later time)
 */
@ConfigurationProperties("license.validator")
public record ValidatorProperties(String productId,
                                  String productVersion,
                                  ClusterIdPolicy clusterIdPolicy,
                                  Duration cacheTtl,
                                  Duration expiringWarning,
                                  Duration grace,
                                  Duration clockTolerance) {
}
