/*
 * Copyright 2017-2018 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.micronaut.security.token.jwt.validator;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.EncryptedJWT;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.token.jwt.encryption.EncryptionConfiguration;
import io.micronaut.security.token.jwt.signature.SignatureConfiguration;
import io.micronaut.security.token.validator.TokenValidator;
import io.reactivex.Flowable;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @see <a href="https://connect2id.com/products/nimbus-jose-jwt/examples/validating-jwt-access-tokens">Validating JWT Access Tokens</a>
 *
 * @author Sergio del Amo
 * @since 1.0
 */
@Singleton
public class JwtTokenValidator implements TokenValidator {

    private static final Logger LOG = LoggerFactory.getLogger(JwtTokenValidator.class);

    protected final List<SignatureConfiguration> signatureConfigurations = new ArrayList<>();
    protected final List<EncryptionConfiguration> encryptionConfigurations = new ArrayList<>();
    protected final List<JwtClaimsValidator> jwtClaimsValidators = new ArrayList<>();

    /**
     * Constructor.
     *
     * @param signatureConfigurations List of Signature configurations which are used to attempt validation.
     * @param encryptionConfigurations List of Encryption configurations which are used to attempt validation.
     * @param jwtClaimsValidators JWT Claims validators.
     */
    @Inject
    public JwtTokenValidator(Collection<SignatureConfiguration> signatureConfigurations,
                             Collection<EncryptionConfiguration> encryptionConfigurations,
                             Collection<JwtClaimsValidator> jwtClaimsValidators) {
        this.signatureConfigurations.addAll(signatureConfigurations);
        this.encryptionConfigurations.addAll(encryptionConfigurations);
        this.jwtClaimsValidators.addAll(jwtClaimsValidators);
    }

    /**
     *
     * Deprecated Constructor.
     *
     * @deprecated Use {@link JwtTokenValidator#JwtTokenValidator(Collection, Collection, Collection)} instead.
     * @param signatureConfigurations List of Signature configurations which are used to attempt validation.
     * @param encryptionConfigurations List of Encryption configurations which are used to attempt validation.
     */
    @Deprecated
    public JwtTokenValidator(Collection<SignatureConfiguration> signatureConfigurations,
                             Collection<EncryptionConfiguration> encryptionConfigurations) {
        this(signatureConfigurations,
                encryptionConfigurations,
                Collections.singleton(new ExpirationJwtClaimsValidator()));
    }

    /**
     *
     * @param jwt a JWT Token
     * @return an Authentication if validation was successful or empty if not.
     * @throws ParseException it may throw a ParseException while retrieving the JWT claims
     */
    protected Publisher<Authentication> validatePlainJWT(JWT jwt) throws ParseException {
        if (signatureConfigurations.isEmpty()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("JWT is not signed and no signature configurations -> verified");
            }
        } else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("A non-signed JWT cannot be accepted as signature configurations have been defined");
            }
            return Flowable.empty();
        }
        if (!verifyClaims(jwt.getJWTClaimsSet())) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("JWT Claims verification failed: {}", jwt.getJWTClaimsSet().toString());
            }
            return Flowable.empty();
        }
        return createAuthentication(jwt);
    }

    /**
     *
     * Validates a Signed JWT.
     *
     * @param signedJWT a Signed JWT Token
     * @return an Authentication if validation was successful or empty if not.
     * @throws ParseException it may throw a ParseException while retrieving the JWT claims
     */
    protected  Publisher<Authentication> validateSignedJWT(SignedJWT signedJWT) throws ParseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("JWT is signed");
        }

        final JWSAlgorithm algorithm = signedJWT.getHeader().getAlgorithm();
        for (final SignatureConfiguration config : signatureConfigurations) {
            if (config.supports(algorithm)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Using signature configuration: {}", config.toString());
                }
                try {
                    if (config.verify(signedJWT)) {
                        if (verifyClaims(signedJWT.getJWTClaimsSet())) {
                            return createAuthentication(signedJWT);
                        } else {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("JWT Claims verification failed: {}", signedJWT.getJWTClaimsSet().toString());
                            }
                        }

                    } else {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("JWT Signature verification failed: {}", signedJWT.getParsedString());
                        }
                    }
                } catch (final JOSEException e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Verification fails with signature configuration: {}, passing to the next one", config);
                    }
                }
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("{}", config.supportedAlgorithmsMessage());
                }
            }
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("No signature algorithm found for JWT: {}", signedJWT.getParsedString());
        }
        return Flowable.empty();
    }

    /**
     *
     * @param jwtClaimsSet JWT Claims.
     * @return Whether the JWT claims pass every validation.
     */
    protected boolean verifyClaims(JWTClaimsSet jwtClaimsSet) {
        return this.jwtClaimsValidators.stream()
                .allMatch(jwtClaimsValidator -> jwtClaimsValidator.validate(jwtClaimsSet));
    }

    /**
     *
     * Validates a encrypted JWT.
     *
     * @param encryptedJWT a encrytped JWT Token
     * @param token the JWT token as String
     * @return an Authentication if validation was successful or empty if not.
     * @throws ParseException it may throw a ParseException while retrieving the JWT claims
     */
    protected Publisher<Authentication> validateEncryptedJWT(EncryptedJWT encryptedJWT, String token) throws ParseException  {
        if (LOG.isDebugEnabled()) {
            LOG.debug("JWT is encrypted");
        }

        final JWEHeader header = encryptedJWT.getHeader();
        final JWEAlgorithm algorithm = header.getAlgorithm();
        final EncryptionMethod method = header.getEncryptionMethod();
        for (final EncryptionConfiguration config : encryptionConfigurations) {
            if (config.supports(algorithm, method)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Using encryption configuration: {}", config.toString());
                }
                try {
                    config.decrypt(encryptedJWT);
                    SignedJWT signedJWT = encryptedJWT.getPayload().toSignedJWT();
                    if (signedJWT == null) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("encrypted JWT could couldn't be converted to a signed JWT.");
                        }
                        return Flowable.empty();
                    }
                    return validateSignedJWT(signedJWT);

                } catch (final JOSEException e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Decryption fails with encryption configuration: {}, passing to the next one", config.toString());
                    }
                }
            }
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("No encryption algorithm found for JWT: {}", token);
        }
        return Flowable.empty();
    }

    /**
     *
     * @param token The token string.
     * @return The authentication or empty if the validation fails.
     */
    @Override
    public Publisher<Authentication> validateToken(String token) {
        try {
            JWT jwt = JWTParser.parse(token);

            if (jwt instanceof PlainJWT) {
                return validatePlainJWT(jwt);

            } else if (jwt instanceof EncryptedJWT) {
                final EncryptedJWT encryptedJWT = (EncryptedJWT) jwt;
                return validateEncryptedJWT(encryptedJWT, token);

            } else if (jwt instanceof SignedJWT) {
                final SignedJWT signedJWT = (SignedJWT) jwt;
                return validateSignedJWT(signedJWT);
            }

        } catch (final ParseException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Cannot decrypt / verify JWT: {}", e.getMessage());
            }
        }

        return Flowable.empty();
    }

    /**
     *
     * @param jwt a JWT token
     * @return Publishes a {@link Authentication} based on the JWT.
     * @throws ParseException it may throw a ParseException while retrieving the JWT claims
     */
    protected Publisher<Authentication> createAuthentication(final JWT jwt) throws ParseException {
        final JWTClaimsSet claimSet = jwt.getJWTClaimsSet();
        return Flowable.just(new AuthenticationJWTClaimsSetAdapter(claimSet));
    }
}
