/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.idp.binding.services;



import static io.mosip.idp.core.util.Constants.UTC_DATETIME_PATTERN;
import static io.mosip.idp.core.util.ErrorConstants.AUTH_FAILED;
import static io.mosip.idp.core.util.ErrorConstants.DUPLICATE_PUBLIC_KEY;
import static io.mosip.idp.core.util.ErrorConstants.INVALID_AUTH_CHALLENGE;
import static io.mosip.idp.core.util.ErrorConstants.INVALID_INDIVIDUAL_ID;
import static io.mosip.idp.core.util.ErrorConstants.INVALID_PUBLIC_KEY;
import static io.mosip.idp.core.util.IdentityProviderUtil.ALGO_SHA_256;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

import org.jose4j.jwe.ContentEncryptionAlgorithmIdentifiers;
import org.jose4j.jwe.JsonWebEncryption;
import org.jose4j.jwe.KeyManagementAlgorithmIdentifiers;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.lang.JoseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.mosip.idp.binding.dto.BindingTransaction;
import io.mosip.idp.binding.entity.PublicKeyRegistry;
import io.mosip.idp.binding.repository.PublicKeyRegistryRepository;
import io.mosip.idp.core.dto.KycAuthRequest;
import io.mosip.idp.core.dto.KycAuthResult;
import io.mosip.idp.core.dto.WalletBindingRequest;
import io.mosip.idp.core.dto.WalletBindingResponse;
import io.mosip.idp.core.exception.IdPException;
import io.mosip.idp.core.exception.InvalidTransactionException;
import io.mosip.idp.core.exception.KycAuthException;
import io.mosip.idp.core.spi.AuthenticationWrapper;
import io.mosip.idp.core.spi.WalletBindingService;
import io.mosip.idp.core.util.IdentityProviderUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class WalletBindingServiceImpl implements WalletBindingService {

	@Autowired
	private CacheUtilService cacheUtilService;

	@Autowired
	private AuthenticationWrapper authenticationWrapper;

	@Autowired
	private PublicKeyRegistryRepository publicKeyRegistryRepository;

	@Value("${mosip.idp.binding.auth-partner-id}")
	private String authPartnerId;

	@Value("${mosip.idp.binding.auth-license-key}")
	private String apiKey;

	@Value("${mosip.idp.binding.public-key-expire-days}")
	private int expireDays;

	@Value("${mosip.idp.binding.issuer-id}")
	private String issuerId;

	@Value("${mosip.idp.binding.salt-length}")
	private int saltLength;

	@Override
	public void sendBindingOtp() throws IdPException {
		// TODO Auto-generated method stub

	}

	@Override
	public WalletBindingResponse bindWallet(WalletBindingRequest walletBindingRequest) throws IdPException {
		BindingTransaction bindingTransaction = cacheUtilService
				.getTransaction(walletBindingRequest.getTransactionId());
		if (bindingTransaction == null)
			throw new InvalidTransactionException();

		if (!bindingTransaction.getIndividualId().equals(walletBindingRequest.getIndividualId()))
			throw new IdPException(INVALID_INDIVIDUAL_ID);

		if (!bindingTransaction.getAuthChallengeType()
				.equals(walletBindingRequest.getChallengeList().get(0).getAuthFactorType()))
			throw new IdPException(INVALID_AUTH_CHALLENGE);

		log.info("Wallet Binding Request validated and sent for authentication");

		KycAuthResult kycAuthResult = authenticateIndividual(bindingTransaction, walletBindingRequest);

		log.info("Wallet Binding Request authentication is successful");

		PublicKeyRegistry publicKeyRegistry = storeData(walletBindingRequest,
				kycAuthResult.getPartnerSpecificUserToken());

		WalletBindingResponse walletBindingResponse = new WalletBindingResponse();
		walletBindingResponse.setTransactionId(walletBindingRequest.getTransactionId());
		walletBindingResponse.setExpireDateTime(
				publicKeyRegistry.getExpiredtimes().format(DateTimeFormatter.ofPattern(UTC_DATETIME_PATTERN)));
		// TODO need to call Keymanager publickey-JWE encryption
		walletBindingResponse.setEncryptedWalletBindingId(
				getEncryptedWalletBindingId(walletBindingRequest, publicKeyRegistry,
						publicKeyRegistry.getWalletBindingId()));

		return walletBindingResponse;

	}

	@Override
	public void validateBinding() throws IdPException {
		// TODO Auto-generated method stub

	}

	private PublicKeyRegistry storeData(WalletBindingRequest walletBindingRequest,
			String partnerSpecificUserToken) throws IdPException {
		PublicKeyRegistry publicKeyRegistry;
		String publicKey = IdentityProviderUtil.getJWKString(walletBindingRequest.getPublicKey());
		String publicKeyHash=IdentityProviderUtil.generateB64EncodedHash(ALGO_SHA_256,publicKey );
		LocalDateTime expiredtimes = calculateExpiresdtimes();
		Optional<PublicKeyRegistry> optionalPublicKeyRegistryForDuplicateCheck = publicKeyRegistryRepository
				.findByPublicKeyHashNotEqualToPsuToken(publicKeyHash, partnerSpecificUserToken);

		if (optionalPublicKeyRegistryForDuplicateCheck.isPresent())
			throw new IdPException(DUPLICATE_PUBLIC_KEY);

		Optional<PublicKeyRegistry> optionalPublicKeyRegistry = publicKeyRegistryRepository
				.findOneByPsuToken(partnerSpecificUserToken);

		if (optionalPublicKeyRegistry.isPresent()) {
			publicKeyRegistry = optionalPublicKeyRegistry.get();
			publicKeyRegistry.setPublicKey(publicKey);
			publicKeyRegistry.setPublicKeyHash(publicKeyHash);
			publicKeyRegistry.setExpiredtimes(expiredtimes);
			publicKeyRegistryRepository.updatePublicKeyRegistry(publicKey, publicKeyHash, expiredtimes,
					partnerSpecificUserToken);
		} else {

			publicKeyRegistry = new PublicKeyRegistry();
			publicKeyRegistry.setIdHash(
					IdentityProviderUtil.generateB64EncodedHash(ALGO_SHA_256, walletBindingRequest.getIndividualId()));
			publicKeyRegistry.setPsuToken(partnerSpecificUserToken);
			publicKeyRegistry.setPublicKey(publicKey);
			publicKeyRegistry.setExpiredtimes(expiredtimes);
			byte[] salt = IdentityProviderUtil.generateSalt(saltLength);

			String walletBindingId = IdentityProviderUtil
					.digestAsPlainTextWithSalt(partnerSpecificUserToken.getBytes(), salt);

			publicKeyRegistry.setWalletBindingId(walletBindingId);
			publicKeyRegistry.setPublicKeyHash(publicKeyHash);
			publicKeyRegistry.setCreatedtimes(LocalDateTime.now(ZoneId.of("UTC")));
			publicKeyRegistry = publicKeyRegistryRepository.save(publicKeyRegistry);
		}
		log.info("Saved PublicKeyRegistry details successfully");

		return publicKeyRegistry;
	}

	private LocalDateTime calculateExpiresdtimes() {
		LocalDateTime currentDateTime = LocalDateTime.now(ZoneId.of("UTC"));
		return currentDateTime.plusDays(expireDays);
	}

	private KycAuthResult authenticateIndividual(BindingTransaction bindingTransaction,
			WalletBindingRequest walletBindingRequest) throws IdPException {
		KycAuthResult kycAuthResult;
		try {
			kycAuthResult = authenticationWrapper.doKycAuth(authPartnerId, apiKey,
					new KycAuthRequest(bindingTransaction.getAuthTransactionId(),
							walletBindingRequest.getIndividualId(), walletBindingRequest.getChallengeList()));
		} catch (KycAuthException e) {
			log.error("KYC auth failed for transaction : {}", bindingTransaction.getAuthTransactionId(), e);
			throw new IdPException(e.getErrorCode());
		}
		if (kycAuthResult == null || (StringUtils.isEmpty(kycAuthResult.getKycToken())
				|| StringUtils.isEmpty(kycAuthResult.getPartnerSpecificUserToken()))) {
			log.error("** authenticationWrapper : {} returned empty tokens received **", authenticationWrapper);
			throw new IdPException(AUTH_FAILED);
		}
		return kycAuthResult;
	}

	private String getJWE(Map<String, Object> publicKey, String walletBindingId) throws JoseException {
		JsonWebEncryption jsonWebEncryption = new JsonWebEncryption();
		jsonWebEncryption.setAlgorithmHeaderValue(KeyManagementAlgorithmIdentifiers.RSA_OAEP_256);
		jsonWebEncryption.setEncryptionMethodHeaderParameter(ContentEncryptionAlgorithmIdentifiers.AES_256_GCM);
		jsonWebEncryption.setPayload(walletBindingId);
		jsonWebEncryption.setContentTypeHeaderValue("JWT");
		RsaJsonWebKey jsonWebKey = new RsaJsonWebKey(publicKey);
		jsonWebEncryption.setKey(jsonWebKey.getKey());
		jsonWebEncryption.setKeyIdHeaderValue(jsonWebKey.getKeyId());
		return jsonWebEncryption.getCompactSerialization();
	}

	private String getEncryptedWalletBindingId(WalletBindingRequest walletBindingRequest,
			PublicKeyRegistry publicKeyRegistry, String walletBindingId) throws IdPException {
		try {
			return getJWE(walletBindingRequest.getPublicKey(), walletBindingId);
		} catch (JoseException e) {
			log.error(INVALID_PUBLIC_KEY, e);
			throw new IdPException(INVALID_PUBLIC_KEY);
		}
	}
}
