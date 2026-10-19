package com.example.licensing.generator;

import com.example.licensing.issuer.KeyGenTool;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.Arrays;

/**
 * License generator service.
 *
 * <pre>
 * java -jar license-generator.jar                                   run the service
 * java -jar license-generator.jar init-keys &lt;kid&gt; &lt;keystore.p12&gt;   create a signing key (on a secure
 *                                                                   workstation, not in the cluster)
 * java -jar license-generator.jar public-key &lt;keystore.p12&gt;         print its public key again
 * </pre>
 *
 * <p>The running service never creates a key. If it did "create one when
 * missing", a volume that failed to mount would silently produce a new key
 * that no product in the field trusts, and every license issued from then on
 * would be rejected by customers. Keys are created deliberately, outside the
 * cluster, and mounted into it; at startup the key is only loaded, and a
 * missing key stops the service with an explanation.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LicenseGeneratorApplication {

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("init-keys")) {
            String[] keyGenArgs = new String[args.length];
            keyGenArgs[0] = "generate";
            System.arraycopy(args, 1, keyGenArgs, 1, args.length - 1);
            KeyGenTool.main(keyGenArgs);
            return;
        }
        if (args.length > 0 && args[0].equals("public-key")) {
            KeyGenTool.main(Arrays.copyOf(args, args.length));
            return;
        }
        SpringApplication.run(LicenseGeneratorApplication.class, args);
    }
}
