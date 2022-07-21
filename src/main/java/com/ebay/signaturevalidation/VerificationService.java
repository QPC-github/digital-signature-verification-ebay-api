package com.ebay.signaturevalidation;


import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.EncryptedJWT;
import com.nimbusds.jwt.JWTClaimsSet;
import org.bouncycastle.crypto.Signer;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.crypto.signers.RSADigestSigner;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.util.encoders.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.text.ParseException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class VerificationService {

    private final KeypairService keypairService;

    private final Logger logger = LoggerFactory.getLogger(VerificationService.class.getName());

    private final Pattern signatureInputPattern = Pattern.compile(".*=(\\((.+)\\);created=(\\d+)(;keyid=.+)?)");
    private final Pattern signaturePattern = Pattern.compile(".*=:(.+):");
    private final Pattern contentDigestPattern = Pattern.compile("(.*)=:(.+):");


    public VerificationService(KeypairService keypairService) {
        this.keypairService = keypairService;
    }


    public boolean verifyMessage(String body, Map<String, String> headers, URI uri, String method) {
        try {
            String base = calculateBase(headers, uri, method);
            PublicKey publicKey = verifyJWT(headers);
            verifyDigestHeader(body, headers);
            verifySignature(publicKey, base, headers);
            logger.info("Message signature verified");
        } catch (Exception ex) {
            ex.printStackTrace();
            logger.error("Message signature invalid");
            return false;
        }

        return true;
    }

    private PublicKey verifyJWT(Map<String, String> headers) throws SignatureException, ParseException, JOSEException, NoSuchAlgorithmException, InvalidKeySpecException {
        String jwtString = headers.get("signature-key");

        if (jwtString == null) {
            throw new SignatureException("No JWT found");
        }

        EncryptedJWT jwe = keypairService.decryptJWE(jwtString);

        JWTClaimsSet jwtClaimsSet = jwe.getJWTClaimsSet();

        // TODO: additional validation of expiration, appID etc

        byte[] keyBytes = Base64.decode((String) jwtClaimsSet.getClaim("pkey"));
        KeyFactory keyFactory = KeyFactory.getInstance(keypairService.getAlgorithm());
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        PublicKey publicKey = keyFactory.generatePublic(keySpec);

        return publicKey;
    }

    private void verifyDigestHeader(String body, Map<String, String> headers) throws NoSuchAlgorithmException, SignatureException {
        String contentDigestHeader = headers.get("content-digest");
        Matcher contentDigestMatcher = contentDigestPattern.matcher(contentDigestHeader);
        if (!contentDigestMatcher.find()) {
            throw new SignatureException("Content-digest header invalid");
        }
        String cipher = contentDigestMatcher.group(1);
        String digest = contentDigestMatcher.group(2);

        if (!cipher.equals("sha-256") && !cipher.equals("sha-512")) {
            throw new SignatureException("Invalid cipher " + cipher);
        }

        MessageDigest messageDigest = MessageDigest.getInstance(cipher.toUpperCase());
        String newDigest = new String(Base64.encode(messageDigest.digest(body.getBytes(StandardCharsets.UTF_8))));

        if (!newDigest.equals(digest)) {
            throw new SignatureException("Body signature invalid");
        }
    }

    private void verifySignature(PublicKey publicKey, String base, Map<String, String> headers) throws IOException, SignatureException {
        String signatureHeader = headers.get("signature");
        Matcher signatureMatcher = signaturePattern.matcher(signatureHeader);
        if (!signatureMatcher.find()) {
            throw new SignatureException("Signature header invalid");
        }
        String signature = signatureMatcher.group(1);
        byte[] signatureBytes = Base64.decode(signature);

        Signer signer;
        if (keypairService.getAlgorithm().equals("RSA")) {
            signer = new RSADigestSigner(new SHA256Digest());
        } else {
            signer = new Ed25519Signer();
        }
        AsymmetricKeyParameter publicKeyParameters =  PublicKeyFactory.createKey(publicKey.getEncoded());
        signer.init(false, publicKeyParameters);
        byte[] baseBytes = base.getBytes(StandardCharsets.UTF_8);
        signer.update(baseBytes, 0, baseBytes.length);
        boolean verified = signer.verifySignature(signatureBytes);

        if (!verified) {
            throw new SignatureException("Signature invalid");
        }

    }

    private String calculateBase(Map<String, String> headers, URI uri, String method) throws SignatureException {
        String signatureInputHeader = headers.get("signature-input");
        Matcher signatureInputMatcher = signatureInputPattern.matcher(signatureInputHeader);
        if (!signatureInputMatcher.find()) {
            throw new SignatureException("Invalid signature-input");
        }
        String signatureInput = signatureInputMatcher.group(2).replaceAll("\"", "");
        List<String> signatureParams = List.of(signatureInput.split(" "));


        StringBuilder buf = new StringBuilder();

        for (String header : signatureParams) {
            buf.append("\"");
            buf.append(header.toLowerCase());
            buf.append("\": ");

            if (header.startsWith("@")) {
                switch (header.toLowerCase()) {
                    case "@method":
                        buf.append(method);
                        break;
                    case "@authority":
                        buf.append(uri.getAuthority());
                        break;
                    case "@target-uri":
                        buf.append(uri.toString());
                        break;
                    case "@scheme":
                        buf.append(uri.getScheme());
                        break;
//                    case "@request-target":
//                        // TBD
//                        break;
                    case "@path":
                        buf.append(uri.getPath());
                        break;
                    case "@query":
                        buf.append(uri.getQuery());
                        break;
//                    case "@query-param":
//                        // TBD
//                        break;
//                    case "@status":
//                        // TBD
//                        break;
                    default:
                        throw new SignatureException("Unknown pseudo header " + header);
                }
            } else {
                if (!headers.containsKey(header)) {
                    throw new SignatureException("Header " + header + " not included in message");
                }

                buf.append(headers.get(header));
            }

            buf.append("\n");
        }

        buf.append("\"@signature-params\": ");
        buf.append(signatureInputMatcher.group(1));

//
        return buf.toString();
    }



    @PostConstruct
    private void postConstruct() throws IOException {
    }
}