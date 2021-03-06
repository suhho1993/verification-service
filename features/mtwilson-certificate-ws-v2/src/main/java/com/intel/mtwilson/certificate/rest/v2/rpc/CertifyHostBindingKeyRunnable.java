/*
 * Copyright (C) 2019 Intel Corporation
 * SPDX-License-Identifier: BSD-3-Clause
 */
package com.intel.mtwilson.certificate.rest.v2.rpc;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.intel.dcsg.cpg.crypto.RsaUtil;
import com.intel.dcsg.cpg.x509.X509Builder;
import com.intel.dcsg.cpg.x509.X509Util;
import com.intel.mtwilson.My;
import com.intel.mtwilson.launcher.ws.ext.RPC;
import com.intel.mtwilson.repository.RepositoryCreateException;
import com.intel.mtwilson.util.tpm20.CertifyKey20;
import gov.niarl.his.privacyca.TpmCertifyKey20;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import gov.niarl.his.privacyca.TpmUtils;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.IOUtils;

/**
 *
 * @author ssbangal
 */
@RPC("certify-host-binding-key")
@JacksonXmlRootElement(localName = "certify_host_binding_key")
public class CertifyHostBindingKeyRunnable implements Runnable {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CertifyHostBindingKeyRunnable.class);
    
    private byte[] publicKeyModulus;
    private byte[] tpmCertifyKey;
    private byte[] tpmCertifyKeySignature;
    private byte[] aikDerCertificate;
    private short encryptionScheme; // TPM Encryption Scheme
    private byte[] bindingKeyDerCertificate;
    private byte[] nameDigest;
    private String tpmVersion;
    private String operatingSystem;
    
    public byte[] getNameDigest() {
        return nameDigest;
    }

    public void setNameDigest(byte[] nameDigest) {
        this.nameDigest = nameDigest;
    }
    public byte[] getPublicKeyModulus() {
        return publicKeyModulus;
    }

    public void setPublicKeyModulus(byte[] publicKeyModulus) {
        this.publicKeyModulus = publicKeyModulus;
    }

    public byte[] getTpmCertifyKey() {
        return tpmCertifyKey;
    }

    public void setTpmCertifyKey(byte[] tpmCertifyKey) {
        this.tpmCertifyKey = tpmCertifyKey;
    }

    public byte[] getTpmCertifyKeySignature() {
        return tpmCertifyKeySignature;
    }

    public void setTpmCertifyKeySignature(byte[] tpmCertifyKeySignature) {
        this.tpmCertifyKeySignature = tpmCertifyKeySignature;
    }

    public byte[] getAikDerCertificate() {
        return aikDerCertificate;
    }

    public void setAikDerCertificate(byte[] aikDerCertificate) {
        this.aikDerCertificate = aikDerCertificate;
    }

    public short getEncryptionScheme() {
        return encryptionScheme;
    }

    public void setEncryptionScheme(short encryptionScheme) {
        this.encryptionScheme = encryptionScheme;
    }
    
    public byte[] getBindingKeyDerCertificate() {
        return bindingKeyDerCertificate;
    }

    public void setBindingKeyDerCertificate(byte[] bindingKeyDerCertificate) {
        this.bindingKeyDerCertificate = bindingKeyDerCertificate;
    }
    
    public String getOperatingSystem() {
        return operatingSystem;
    }

    public void setOperatingSystem(String os) {
        operatingSystem = os;
    }
    
    public String getTpmVersion() {
        return tpmVersion;
    }

    public void setTpmVersion(String newVersion) {
        tpmVersion = newVersion;
    }

    @Override
    @RequiresPermissions({"host_signing_key_certificates:create"})
    public void run() {
        try {            
            //ToDo: Need to verify nameDigest it only works on 2.0
            log.info("operatingSystem received: {}",operatingSystem);
            if (publicKeyModulus != null && tpmCertifyKey != null && tpmCertifyKeySignature != null && aikDerCertificate != null) {
                //If it's Linux TPM 2.0 we must receive the nameDigest, otherwise thrown exception
                if(tpmVersion != null && tpmVersion.equals("2.0") && operatingSystem.equals("Linux") && nameDigest == null){
                    throw new Exception("Invalid input specified or input value missing.");
                }

                log.debug("Starting to verify the Binding key TCG certificate and generate the MTW certified certificate.");
                
                if (tpmVersion != null && tpmVersion.equals("2.0")) {
                    // Verify the encryption scheme, key flags etc
                    if (operatingSystem.equals("Linux")){
                        if (!CertifyKey20.isBindingKey(new TpmCertifyKey20(tpmCertifyKey))) {
                            throw new Exception("Not a valid binding key");
                        }
                    }
                } else {
                    throw new Exception("Invalid TPM version detected...");
                }

                    X509Certificate decodedAikDerCertificate = X509Util.decodeDerCertificate(aikDerCertificate);
                    log.debug("AIK Certificate {}", decodedAikDerCertificate.getIssuerX500Principal().getName());

                    // Need to verify if the AIK is signed by the trusted Privacy CA, which would also ensure that the EK is verified.
                    byte[] privacyCAPemBytes;
                    try (FileInputStream privacyCAPemFile = new FileInputStream(My.configuration().getPrivacyCaIdentityCacertsFile())) {
                        privacyCAPemBytes = IOUtils.toByteArray(privacyCAPemFile);
                    }

                    X509Certificate privacyCACert = X509Util.decodePemCertificate(new String(privacyCAPemBytes));
                    log.debug("Privacy CA Certificate {}", privacyCACert.getIssuerX500Principal().getName());

                    if (!isAikCertifiedByPrivacyCA(decodedAikDerCertificate, privacyCACert)) {
                        throw new CertificateException("The specified AIK certificate is not trusted.");
                    }
                    ////////////////////////
                    boolean validatePublicKeyDigest;
                    if (tpmVersion.equals("2.0") && operatingSystem.equals("Linux")) {
                        
                        if (!CertifyKey20.isCertifiedKeySignatureValid(tpmCertifyKey, tpmCertifyKeySignature, decodedAikDerCertificate.getPublicKey())) {
                            throw new CertificateException("The signature specified for the certify key does not match.");
                        }
                        //In TPM 2.0 we validate TPM name given to each key
                        validatePublicKeyDigest = CertifyKey20.validatePublicKey(publicKeyModulus, tpmCertifyKey);
                        if (!validatePublicKeyDigest) {
                            throw new Exception("Binding Public Key digest does not match digest in the TCG binding certificate");
                        }
                    }else if( tpmVersion.equals("2.0") && operatingSystem.equals("Windows")){
                        if (!CertifyKey20.isCertifiedKeySignatureValidWin(tpmCertifyKey, tpmCertifyKeySignature, decodedAikDerCertificate.getPublicKey())) {
                            throw new CertificateException("The signature specified for the certifiy key does not match.");
                        }                    
                    }else{
                        throw new Exception("Invalid TPM and Operating System versions detected...");
                    }

                    // Generate the TCG standard exponent to create the RSApublic key from the modulus specified.
                    byte[] pubExp = new byte[3];
                    pubExp[0] = (byte) (0x01 & 0xff);
                    pubExp[1] = (byte) (0x00);
                    pubExp[2] = (byte) (0x01 & 0xff);
                    
                    //Set the publicKeyModules. for tpm1.2, trustagent sends the public key modulus                    
                    byte[] publicKeyModulusRSA = publicKeyModulus;
                    if (tpmVersion != null && tpmVersion.equals("2.0") && operatingSystem.equals("Linux")) { // for tpm2.0 on Linux, trustagent sent the tpm2b_public structure, we need to extract the public modulus portion
                        log.debug("received tpm2 binding key pub key modulus size: {}", publicKeyModulus.length);
                        if (publicKeyModulus.length < 256) {
                            throw new Exception("received tpm binding key pub modulus is less than 256 (expected is tpm2b_public structure)");
                        }
                        publicKeyModulusRSA = Arrays.copyOfRange(publicKeyModulus, publicKeyModulus.length-256, publicKeyModulus.length);
                    }
                    RSAPublicKey pubBk = TpmUtils.makePubKey(publicKeyModulusRSA, pubExp);

                    if (pubBk != null) {
                        log.debug("Successfully created the public key from the modulus specified");
                    } else {
                        throw new Exception("Error during the creation of the public key from the modulus and exponent");
                    }

                    // first load the privacy ca key                
                    log.debug("PrivacyCA.p12: {}", My.configuration().getPrivacyCaIdentityP12().getAbsolutePath());
                    RSAPrivateKey cakey = TpmUtils.privKeyFromP12(My.configuration().getPrivacyCaIdentityP12().getAbsolutePath(), My.configuration().getPrivacyCaIdentityPassword());
                    X509Certificate cacert = TpmUtils.certFromP12(My.configuration().getPrivacyCaIdentityP12().getAbsolutePath(), My.configuration().getPrivacyCaIdentityPassword());
                    //Read encryption scheme used in binding key
                    ByteBuffer byteBuffer = ByteBuffer.allocate(2);
                    byteBuffer.putShort(encryptionScheme);


                    X509Builder caBuilder = X509Builder.factory();
                    //ToDo: Add encryption Scheme in certificate attribute
                    X509Certificate bkCert = caBuilder
                            .commonName("CN=Binding_Key_Certificate")
                            .subjectPublicKey(pubBk)
                            .expires(RsaUtil.DEFAULT_RSA_KEY_EXPIRES_DAYS, TimeUnit.DAYS)
                            .issuerPrivateKey(cakey)
                            .issuerName(cacert)
                            .keyUsageKeyEncipherment()
                            //                        .keyUsageDataEncipherment()
                            .extKeyUsageIsCritical()
                            .randomSerial()
                            .noncriticalExtension(CertifyKey20.TCG_STRUCTURE_CERTIFY_INFO_OID, tpmCertifyKey)
                            .noncriticalExtension(CertifyKey20.TCG_STRUCTURE_CERTIFY_INFO_SIGNATURE_OID, tpmCertifyKeySignature)
                            .noncriticalExtension(CertifyKey20.TCG_STRUCTURE_CERTIFY_INFO_ENC_SCHEME_OID, byteBuffer.array())
                            .build();

                    if (bkCert != null) {
                        bindingKeyDerCertificate = X509Util.encodeDerCertificate(bkCert);
                    } else {
                        throw new Exception("Error during creation of the MTW signed binding key certificate");
                    }

                    log.debug("Successfully created the MTW signed PEM certificate for binding key: {}.", X509Util.encodePemCertificate(bkCert));

                } else {
                    throw new Exception("Invalid input specified or input value missing.");
                }
        } catch (Exception ex) {
            log.error("Error during MTW signed binding key certificate.", ex);
            throw new RepositoryCreateException();
        }
    }
    
    /**
     * Verifies if the specified AIK certificate is issued by Privacy CA and is valid.
     * @param aikCert
     * @param privacyCACert
     * @return
     * @throws CertificateException
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeyException
     * @throws NoSuchProviderException
     * @throws SignatureException 
     */
    protected static boolean isAikCertifiedByPrivacyCA(X509Certificate aikCert, X509Certificate privacyCACert) 
            throws CertificateException, NoSuchAlgorithmException, InvalidKeyException, NoSuchProviderException, SignatureException {
        try {
            
            log.debug("Verifying the AIK cert with public key {} with the Privacy CA {}.", TpmUtils.byteArrayToHexString(aikCert.getPublicKey().getEncoded()),
                    TpmUtils.byteArrayToHexString(privacyCACert.getPublicKey().getEncoded()));
            
            if (aikCert != null && privacyCACert != null) {
                aikCert.verify(privacyCACert.getPublicKey());
                log.debug("Successfully verified the AIK signature against the Privacy CA");
                return true;
            }
            log.debug("Error verifying the AIK signature against the Privacy CA");
            return false;
            
        } catch (CertificateException | NoSuchAlgorithmException | InvalidKeyException | NoSuchProviderException | SignatureException ex) {
            log.error("Error during signature verification. {}", ex.getMessage());
            throw ex;
        }
    }
    

}
