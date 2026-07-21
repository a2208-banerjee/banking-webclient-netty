package com.banking.payment.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "mtls")
public class MtlsProperties {
    private Keystore keystore   = new Keystore();
    private Keystore truststore = new Keystore();
    private boolean disableHostnameVerification = false;

    @Data
    public static class Keystore {
        private String path;
        private String password;
        private String type = "PKCS12";
    }
}
