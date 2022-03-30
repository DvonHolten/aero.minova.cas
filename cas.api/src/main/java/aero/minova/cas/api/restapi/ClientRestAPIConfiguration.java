package aero.minova.cas.api.restapi;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class ClientRestAPIConfiguration {

	@Bean
	public RestTemplate restTemplate(RestTemplateBuilder builder) {
		// Alle Konfigurationen für das RestTemplate hier einfügen.
		return builder.build();
	}

}