package com.research.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * arXiv API 查询器 —— 搜索论文、获取元数据、下载 LaTeX 源码。
 * <p>
 * arXiv API 免费公开，无需 API Key，但有速率限制（单次请求间隔建议 ≥3s）。
 * 文档: https://info.arxiv.org/help/api/
 */
@Component
public class ArxivFetcher {

    private static final Logger log = LoggerFactory.getLogger(ArxivFetcher.class);

    private static final String ARXIV_API_BASE = "https://export.arxiv.org/api/query";
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ArxivFetcher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 按关键词搜索 arXiv 论文。
     *
     * @param query      搜索关键词
     * @param maxResults 最大返回数 (1-100)
     * @return 论文列表
     */
    public List<Map<String, Object>> search(String query, int maxResults) throws Exception {
        String url = ARXIV_API_BASE
                + "?search_query=all:" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&start=0&max_results=" + Math.min(maxResults, 100)
                + "&sortBy=relevance&sortOrder=descending";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("arXiv API 返回 HTTP " + response.statusCode());
        }

        // arXiv API 返回 Atom XML，需要 XML 解析
        // 这里使用简单的字符串解析提取关键字段（避免引入 XML 依赖）
        return parseArxivAtom(response.body());
    }

    /**
     * 根据 arXiv ID 获取论文元数据。
     *
     * @param arxivId arXiv ID，如 "2301.12345"
     */
    public Map<String, String> getMetadata(String arxivId) throws Exception {
        String url = ARXIV_API_BASE
                + "?id_list=" + URLEncoder.encode(arxivId, StandardCharsets.UTF_8)
                + "&max_results=1";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String xml = response.body();

        Map<String, String> meta = new java.util.HashMap<>();
        meta.put("arxiv_id", arxivId);
        meta.put("title", extractTag(xml, "title"));
        meta.put("authors", extractAllTags(xml, "author", "name"));
        meta.put("summary", extractTag(xml, "summary"));
        meta.put("published", extractTag(xml, "published"));
        meta.put("source_url", "https://arxiv.org/pdf/" + arxivId);

        return meta;
    }

    /**
     * 检查 arXiv 是否提供 LaTeX 源码。
     * <p>
     * arXiv 源码下载地址: https://arxiv.org/e-print/{arxivId}
     */
    public boolean hasLatexSource(String arxivId) {
        try {
            String url = "https://arxiv.org/e-print/" + arxivId;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 下载 arXiv PDF 到指定目录。
     *
     * @param arxivId arXiv ID，如 "2301.12345"
     * @param saveDir 保存目录（绝对路径）
     * @return 保存的文件名（相对路径），失败返回 null
     */
    public String downloadPdf(String arxivId, String saveDir) {
        // 使用无后缀的 canonical URL，避免 arXiv 返回 301 重定向
        String pdfUrl = "https://arxiv.org/pdf/" + arxivId;
        String fileName = arxivId + ".pdf";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(pdfUrl))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.warn("arXiv PDF 下载失败 {}: HTTP {}", arxivId, response.statusCode());
                return null;
            }
            java.io.File dir = new java.io.File(saveDir);
            if (!dir.exists()) dir.mkdirs();
            java.io.File outFile = new java.io.File(dir, fileName);
            java.nio.file.Files.write(outFile.toPath(), response.body());
            log.info("arXiv PDF 下载成功: {} → {}", arxivId, fileName);
            return fileName;
        } catch (Exception e) {
            log.warn("arXiv PDF 下载异常 {}: {}", arxivId, e.getMessage());
            return null;
        }
    }

    // ========== 简易 Atom XML 解析（避免引入额外依赖） ==========

    private List<Map<String, Object>> parseArxivAtom(String xml) {
        List<Map<String, Object>> results = new ArrayList<>();
        String[] entries = xml.split("<entry>");
        // 第一个 entry 之前是 feed header，跳过
        for (int i = 1; i < entries.length; i++) {
            String entry = entries[i].split("</entry>")[0];
            Map<String, Object> paper = new java.util.HashMap<>();
            paper.put("title", cleanXml(extractTag(entry, "title")));
            paper.put("authors", extractAllTags(entry, "author", "name"));
            paper.put("summary", cleanXml(extractTag(entry, "summary")));
            paper.put("published", extractTag(entry, "published"));
            paper.put("arxivId", extractArxivId(entry));
            paper.put("pdfUrl", "https://arxiv.org/pdf/" + extractArxivId(entry));
            results.add(paper);
        }
        return results;
    }

    private String extractTag(String xml, String tag) {
        String open = "<" + tag;
        String close = "</" + tag + ">";
        int start = xml.indexOf(open);
        if (start < 0) return "";
        int contentStart = xml.indexOf('>', start) + 1;
        int end = xml.indexOf(close, contentStart);
        if (end < 0) return "";
        return xml.substring(contentStart, end).trim();
    }

    private String extractAllTags(String xml, String tag, String subTag) {
        List<String> values = new ArrayList<>();
        String open = "<" + tag;
        String close = "</" + tag + ">";
        int pos = 0;
        while ((pos = xml.indexOf(open, pos)) >= 0) {
            int contentStart = xml.indexOf('>', pos) + 1;
            int end = xml.indexOf(close, contentStart);
            if (end < 0) break;
            String block = xml.substring(contentStart, end);
            if (subTag != null) {
                String sub = extractTag(block, subTag);
                values.add(cleanXml(sub));
            }
            pos = end + close.length();
        }
        return String.join(", ", values);
    }

    private String extractArxivId(String entry) {
        // 尝试从 <id> 标签提取
        String id = extractTag(entry, "id");
        // ID 格式: http://arxiv.org/abs/2301.12345v2
        int lastSlash = id.lastIndexOf('/');
        if (lastSlash >= 0) {
            String aid = id.substring(lastSlash + 1);
            // 去掉版本号 v1/v2
            return aid.replaceAll("v\\d+$", "");
        }
        return id;
    }

    private String cleanXml(String s) {
        return s.replaceAll("<[^>]+>", "")       // 移除 XML 标签
                .replaceAll("\\s+", " ")          // 合并空白
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .trim();
    }
}
