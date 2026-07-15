package com.research.assistant.service.pdf.layout;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Stable content fingerprint used as the layout artifact cache key. */
public final class PdfDocumentFingerprint {

    private PdfDocumentFingerprint() {
    }

    public static String sha256(File file) {
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("PDF 文件不存在");
        }
        try (InputStream input = Files.newInputStream(file.toPath())) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("无法计算 PDF 指纹", e);
        }
    }
}
