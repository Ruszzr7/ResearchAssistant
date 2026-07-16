package com.research.assistant.service.translation;

import org.springframework.http.HttpStatus;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Protects formulas and scholarly identifiers with DeepL XML ignore tags. */
final class AcademicTextProtector {

    private static final Pattern PROTECTED = Pattern.compile(
            "(?s)```.*?```"
                    + "|`[^`\\r\\n]+`"
                    + "|\\$\\$.*?\\$\\$"
                    + "|(?<!\\$)\\$(?!\\$).*?(?<!\\$)\\$(?!\\$)"
                    + "|\\\\\\[.*?\\\\\\]"
                    + "|\\\\\\(.*?\\\\\\)"
                    + "|\\\\begin\\{[^}]+}.*?\\\\end\\{[^}]+}"
                    + "|https?://[^\\s<>]+"
                    + "|10\\.\\d{4,9}/[^\\s<>]+"
                    + "|\\[(?:\\d+(?:\\s*[-–,]\\s*\\d+)*)]"
                    + "|(?<![\\p{L}\\p{N}_])[-+]?\\d+(?:[.,]\\d+)*(?:\\s?(?:%|dB|ms|Hz|kHz|MHz|GHz|mW|W|bit/s|bps))?(?![\\p{L}\\p{N}_])"
                    + "|\\b[A-Z][A-Z0-9-]{1,}\\b");

    String protect(String text) {
        String source = text == null ? "" : text;
        Matcher matcher = PROTECTED.matcher(source);
        StringBuilder xml = new StringBuilder("<ra>");
        int cursor = 0;
        while (matcher.find()) {
            xml.append(escapeXml(source.substring(cursor, matcher.start())));
            xml.append("<keep>").append(escapeXml(matcher.group())).append("</keep>");
            cursor = matcher.end();
        }
        xml.append(escapeXml(source.substring(cursor))).append("</ra>");
        return xml.toString();
    }

    String restore(String translatedXml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var document = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(translatedXml)));
            if (!"ra".equals(document.getDocumentElement().getTagName())) {
                throw new IllegalArgumentException("unexpected translation root");
            }
            return document.getDocumentElement().getTextContent();
        } catch (Exception e) {
            throw new TranslationException("INVALID_PROVIDER_RESPONSE",
                    "翻译服务返回了无法解析的内容，请重试",
                    HttpStatus.BAD_GATEWAY, true);
        }
    }

    private String escapeXml(String value) {
        StringBuilder cleaned = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (codePoint == '\t' || codePoint == '\n' || codePoint == '\r'
                    || codePoint >= 0x20 && codePoint <= 0xD7FF
                    || codePoint >= 0xE000 && codePoint <= 0xFFFD
                    || codePoint >= 0x10000 && codePoint <= 0x10FFFF) {
                cleaned.appendCodePoint(codePoint);
            } else {
                cleaned.append(' ');
            }
        });
        return cleaned.toString().replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
