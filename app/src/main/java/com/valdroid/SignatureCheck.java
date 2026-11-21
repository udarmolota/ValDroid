package com.valdroid;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Whether the installed APK is signed with the author's release key. The fingerprint is built in, so
 * the check works offline at every start; it is the same key for all her launchers and is printed in
 * README.md. Repacks from APK sites are re-signed with the site's own key (seen: CN=APKVISION.ORG).
 */
public final class SignatureCheck {

    /** SHA-256 of the release signing certificate (README.md: F8:5A:43:7A:...:D2:C6). */
    static final String RELEASE_CERT_SHA256 =
            "f85a437a5090fb84b9eb2a4cbff5abc07e4d3ba2d5c4be0259636f3a624ed2c6";

    private SignatureCheck() {}

    /** One signing certificate of the installed APK. */
    public static final class Signer {
        public final String sha256;    // lowercase hex
        public final String subject;   // X.500 name, "?" when it cannot be read

        Signer(String sha256, String subject) { this.sha256 = sha256; this.subject = subject; }

        public boolean isRelease() { return RELEASE_CERT_SHA256.equals(sha256); }
    }

    /** The certificates the installed APK is signed with; empty when they cannot be read. */
    public static List<Signer> signers(Context ctx) {
        List<Signer> out = new ArrayList<>();
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            for (android.content.pm.Signature s : pi.signingInfo.getApkContentsSigners()) {
                byte[] der = s.toByteArray();
                byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(der);
                StringBuilder hex = new StringBuilder(64);
                for (byte b : d) hex.append(String.format(Locale.US, "%02x", b));
                String subject;
                try {
                    subject = ((java.security.cert.X509Certificate) java.security.cert.CertificateFactory
                            .getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(der)))
                            .getSubjectX500Principal().getName();
                } catch (Throwable t) { subject = "?"; }
                out.add(new Signer(hex.toString(), subject));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /**
     * The foreign key the APK is signed with, or null when it carries the release key or the signers
     * cannot be read (then nothing is claimed either way).
     */
    @Nullable
    public static Signer foreign(Context ctx) {
        List<Signer> all = signers(ctx);
        if (all.isEmpty()) return null;
        for (Signer s : all) if (s.isRelease()) return null;
        return all.get(0);
    }
}
