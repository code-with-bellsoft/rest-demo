package dev.cyberjar.medflow.ui.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class ApiClientConfig {

    @Bean
    RestClient medFlowRestClient(RestClient.Builder builder,
                                 @Value("${medflow.api.base-url}") URI baseUrl) {
        if (baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException(
                    "medflow.api.base-url must be a base URL without credentials, query or fragment");
        }
        var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return builder.baseUrl(baseUrl.toString()).requestFactory(factory).build();
    }
}
