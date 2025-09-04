package org.springframework.samples.petclinic.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.Optional;

@Configuration
public class AwsClientsConfig {

	private static final Logger log = LoggerFactory.getLogger(AwsClientsConfig.class);

	@Value("${AWS_REGION:#{null}}")
	private String awsRegion;

	@Bean
	public Optional<AwsCredentialsProvider> awsCredentialsProvider() {
		try {
			AwsCredentialsProvider provider = DefaultCredentialsProvider.create();
			provider.resolveCredentials(); // trigger validation
			return Optional.of(provider);
		}
		catch (Exception e) {
			log.info("No AWS credentials available; running without AWS integration");
			return Optional.empty();
		}
	}

	@Bean
	public Optional<S3Client> s3Client(Optional<AwsCredentialsProvider> providerOpt) {
		if (providerOpt.isEmpty()) {
			return Optional.empty();
		}
		try {
			Region region = awsRegion != null ? Region.of(awsRegion) : Region.of("ap-south-1");
			S3Client client = S3Client.builder().region(region).credentialsProvider(providerOpt.get()).build();
			return Optional.of(client);
		}
		catch (Exception e) {
			log.warn("Failed creating S3 client: {}", e.getMessage());
			return Optional.empty();
		}
	}

}
