package org.springframework.samples.petclinic.pettypes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.samples.petclinic.owner.PetType;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.regions.Region;

import java.util.List;
import java.util.Optional;

/**
 * Perform some initializing of the supported pet types on startup by downloading them
 * from S3, if enabled
 */
@Component
public class InitPetTypes implements InitializingBean {

	private static final Logger logger = LoggerFactory.getLogger(InitPetTypes.class);

	private final PetTypesRepository petTypesRepository;

	private final KmsClient kmsClient;

	private final S3Client s3Client;

	@Value("${app.init.pet-types.bucket:spring-petclinic-init-demo1}")
	private String petTypesBucket;

	@Value("${app.init.pet-types.key:petclinic-pettypes.txt}")
	private String petTypesInitObjectKey;

	@Value("${app.init.pet-types.kms-encrypted:false}")
	private Boolean petTypesInitObjectKmsEncrypted;

	@Value("${app.init.pet-types.kms-key-alias:alias/spring-petclinic-init-demo1}")
	private String petTypesKmsKeyAlias;

	InitPetTypes(PetTypesRepository petTypesRepository, Optional<AwsCredentialsProvider> awsCredentialsProvider,
			Optional<S3Client> s3Client) {
		this.petTypesRepository = petTypesRepository;
		this.s3Client = s3Client.orElse(null);

		AwsCredentialsProvider credentialsProvider = awsCredentialsProvider.orElse(null);
		KmsClient kms = null;
		if (credentialsProvider != null) {
			try {
				// Derive region from env (AWS_REGION / AWS_DEFAULT_REGION) or fallback to
				// a default.
				String regionEnv = System.getenv("AWS_REGION");
				if (regionEnv == null || regionEnv.isBlank()) {
					regionEnv = System.getenv("AWS_DEFAULT_REGION");
				}
				Region region = Region.of(regionEnv != null && !regionEnv.isBlank() ? regionEnv : "ap-south-1");
				kms = KmsClient.builder().credentialsProvider(credentialsProvider).region(region).build();
			}
			catch (Exception ex) {
				logger.warn("Disabling KMS integration (failed to create client): {}", ex.getMessage());
			}
		}
		this.kmsClient = kms; // may be null if not configured
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		if (s3Client == null) {
			logger.info("No S3Client configured, skipping loading pettypes.");
			return;
		}

		logger.info("Loading Pet types from bucket '{}' key '{}'", petTypesBucket, petTypesInitObjectKey);
		String fileContents;
		try {
			fileContents = s3Client.getObjectAsBytes(b -> b.bucket(petTypesBucket).key(petTypesInitObjectKey))
				.asUtf8String();
		}
		catch (Exception ex) {
			logger.warn("Failed retrieving pet types from S3 (skipping initialization): {}", ex.getMessage());
			return; // do not fail app startup
		}

		// if kms encrypted, attempt to decrypt it
		if (petTypesInitObjectKmsEncrypted) {
			if (kmsClient == null) {
				logger.warn("KMS decryption requested but no KmsClient available; skipping decryption");
			}
			else {
				try {
					logger.info("Decrypting pet types using KMS key alias '{}'", petTypesKmsKeyAlias);
					SdkBytes encryptedData = SdkBytes.fromUtf8String(fileContents);
					DecryptResponse decryptResponse = kmsClient
						.decrypt(b -> b.ciphertextBlob(encryptedData).keyId(petTypesKmsKeyAlias));
					fileContents = decryptResponse.plaintext().asUtf8String();
				}
				catch (Exception ex) {
					logger.warn("Failed KMS decrypt (continuing with original contents): {}", ex.getMessage());
				}
			}
		}

		List<PetType> foundTypes = fileContents.lines().map(s -> {
			PetType type = new PetType();
			type.setName(s);
			return type;
		}).toList();
		logger.info("Found " + foundTypes.size() + " pet types");

		// load the found types into the database
		if (!foundTypes.isEmpty()) {
			try {
				petTypesRepository.saveAllAndFlush(foundTypes);
				logger.info("Saved {} pet types", foundTypes.size());
				try {
					logger.info("Deleting Pet types file from S3 bucket '{}'", petTypesBucket);
					s3Client.deleteObject(builder -> builder.bucket(petTypesBucket).key(petTypesInitObjectKey));
				}
				catch (Exception ex) {
					logger.warn("Failed deleting pet types init object: {}", ex.getMessage());
				}
			}
			catch (Exception ex) {
				logger.warn("Failed persisting pet types to repository: {}", ex.getMessage());
			}
		}
	}

}
