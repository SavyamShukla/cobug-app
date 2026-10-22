package com.cobug.security;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collections;

@Service
public class GoogleTokenVerifierService {
    private static final Logger log = LoggerFactory.getLogger(GoogleTokenVerifierService.class);
    private final String clientId;
    public GoogleTokenVerifierService(@Value("${cobug.google.client-id:}") String clientId) { this.clientId=clientId; }
    public GoogleProfile verify(String credential) {
        log.info("[AUTH][GOOGLE] verifying ID token; clientIdConfigured={}", clientId != null && !clientId.isBlank());
        if(clientId==null||clientId.isBlank()) throw new IllegalArgumentException("Google login is not configured on this COBUG server.");
        try {
            GoogleIdTokenVerifier verifier=new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance()).setAudience(Collections.singletonList(clientId)).build();
            GoogleIdToken idToken=verifier.verify(credential);
            if(idToken==null) { log.error("[AUTH][GOOGLE] Google ID token verification returned null"); throw new IllegalArgumentException("Google sign-in could not be verified.");
            }
            GoogleIdToken.Payload p=idToken.getPayload();
            if(p.getEmail()==null || Boolean.FALSE.equals(p.getEmailVerified())) throw new IllegalArgumentException("Google did not provide a verified email address.");
            log.info("[AUTH][GOOGLE] verified Google profile email={} subject={}", p.getEmail(), p.getSubject());
            return new GoogleProfile(p.getSubject(),p.getEmail(),(String)p.get("name"));
        } catch(IllegalArgumentException e) { log.error("[AUTH][GOOGLE] verification rejected: {}", e.getMessage()); throw e; } catch(Exception e) { log.error("[AUTH][GOOGLE] verification exception", e); throw new IllegalArgumentException("Google sign-in could not be verified."); }
    }
    public record GoogleProfile(String subject,String email,String name) {}
}
