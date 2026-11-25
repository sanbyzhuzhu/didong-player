package com.dongting.player;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** Offline text-only import. No browser, remote resources, Office engine or native library. */
final class BookTextParser {
    private static final int MAX_PART = 8 * 1024 * 1024;
    private static final int MAX_ARCHIVE = 128 * 1024 * 1024;
    private static final Pattern ATTR = Pattern.compile("([\\w:.-]+)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+)))?");
    private final Function<byte[], String> decode;
    private final Function<String, String> entities;

    BookTextParser(Function<byte[], String> decode, Function<String, String> entities) {
        this.decode = decode;
        this.entities = entities;
    }

    static String format(String name, String mime) {
        String lower = (name == null ? "" : name).toLowerCase(Locale.ROOT);
        if (lower.endsWith(".epub") || "application/epub+zip".equals(mime)) return "EPUB";
        if (lower.endsWith(".docx") || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mime)) return "DOCX";
        if (lower.endsWith(".md") || lower.endsWith(".markdown") || "text/markdown".equals(mime)) return "Markdown";
        if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml")
                || "text/html".equals(mime) || "application/xhtml+xml".equals(mime)) return "HTML";
        if (lower.endsWith(".json") || "application/json".equals(mime)) return "JSON";
        return "TXT";
    }

    static boolean supportedName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).matches(".*\\.(txt|json|epub|md|markdown|html|htm|xhtml|docx)$");
    }

    ReadableBook parse(InputStream input, String name, String mime, boolean legacy) throws IOException {
        String format = format(name, mime);
        if ("EPUB".equals(format)) return epub(readArchive(input), legacy);
        if ("DOCX".equals(format)) return docx(readArchive(input));
        byte[] bytes = readLimited(input, 32 * 1024 * 1024);
        String raw = "HTML".equals(format) ? decodeMarkup(bytes) : decode.apply(bytes);
        if ("HTML".equals(format)) {
            HtmlText html = html(raw);
            return book(html.sink.finish(), html.headings, Collections.emptyList(), format, "");
        }
        if ("Markdown".equals(format)) return markdown(raw);
        return book(normalize(raw), Collections.emptyList(), Collections.emptyList(), format, "");
    }

    private ReadableBook book(String text, List<ReadableBook.Chapter> chapters, List<String> warnings,
                              String format, String legacy) throws IOException {
        if (text.trim().isEmpty()) throw new IOException("没有可朗读的正文（图片、扫描件或加密内容无法提取）");
        return new ReadableBook(text, chapters, warnings, format, legacy);
    }

    private Map<String, byte[]> readArchive(InputStream input) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        int total = 0;
        int keptTotal = 0;
        int count = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > 10000) throw new IOException("电子书包含过多文件");
                if (entry.isDirectory()) continue;
                String path = resolve("", entry.getName());
                if (path == null || path.isEmpty()) throw new IOException("电子书包含无效的内部路径");
                String lower = path.toLowerCase(Locale.ROOT);
                boolean keep = lower.matches(".*\\.(xml|rels|opf|ncx|xhtml|html|htm|txt)$");
                ByteArrayOutputStream output = keep ? new ByteArrayOutputStream() : null;
                int part = 0;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    total += read;
                    part += read;
                    if (keep) keptTotal += read;
                    if (total > MAX_ARCHIVE || (keep && part > MAX_PART)) throw new IOException("电子书解压后的内容过大，请拆分文件后导入");
                    if (keptTotal > 32 * 1024 * 1024) throw new IOException("电子书正文及目录过大，请拆分后导入");
                    if (keep) output.write(buffer, 0, read);
                }
                if (keep && entries.put(path, output.toByteArray()) != null) throw new IOException("电子书内部文件路径重复");
            }
        }
        if (count == 0) throw new IOException("无法读取电子书压缩结构；不支持加密文档或旧版 DOC");
        return entries;
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (out.size() + read > limit) throw new IOException("文本内容过大，请拆分后导入");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private Document xml(byte[] bytes) throws IOException {
        if (bytes == null) throw new IOException("电子书缺少必要的目录或正文文件");
        // External identifiers in EPUB 2 NCX are common; resolve them to nothing.
        // Entity declarations themselves are forbidden to prevent expansion attacks.
        if (new String(bytes, StandardCharsets.ISO_8859_1).replace("\u0000", "").contains("<!ENTITY"))
            throw new IOException("不支持包含自定义 XML 实体的文档");
        // XHTML navigation often uses named HTML entities supplied by its DTD.
        // Resolve those locally to numeric references, without fetching the DTD.
        String source = decodeMarkup(bytes);
        Matcher entity = Pattern.compile("&(?!(?:amp|lt|gt|quot|apos);)([A-Za-z][A-Za-z0-9]+);").matcher(source);
        StringBuffer normalized = new StringBuffer();
        boolean changed = false;
        while (entity.find()) {
            String decoded = entities.apply(entity.group());
            if (decoded.equals(entity.group())) continue;
            StringBuilder references = new StringBuilder();
            decoded.codePoints().forEach(point -> references.append("&#").append(point).append(';'));
            entity.appendReplacement(normalized, Matcher.quoteReplacement(references.toString()));
            changed = true;
        }
        if (changed) {
            entity.appendTail(normalized);
            String updated = normalized.toString().replaceFirst("(?is)^\\s*<\\?xml[^?]*\\?>", "");
            bytes = updated.getBytes(StandardCharsets.UTF_8);
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            for (String feature : new String[]{"http://xml.org/sax/features/external-general-entities",
                    "http://xml.org/sax/features/external-parameter-entities",
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd"}) {
                try { factory.setFeature(feature, false); } catch (Exception ignored) { }
            }
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (Exception error) {
            throw new IOException("电子书 XML 结构无法解析", error);
        }
    }

    private static List<Element> elements(Node parent, String local) {
        List<Element> result = new ArrayList<>();
        collect(parent, local, result);
        return result;
    }

    private static void collect(Node parent, String local, List<Element> result) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                if (local.equals(local(child))) result.add((Element) child);
                collect(child, local, result);
            }
        }
    }

    private static String local(Node node) {
        String name = node.getLocalName();
        return name == null ? node.getNodeName().replaceFirst("^.*:", "") : name;
    }

    private static Element first(Node parent, String local) {
        List<Element> found = elements(parent, local);
        return found.isEmpty() ? null : found.get(0);
    }

    private static String attr(Element element, String name) {
        if (element.hasAttribute(name)) return element.getAttribute(name);
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Node attribute = element.getAttributes().item(i);
            if (name.equals(local(attribute))) return attribute.getNodeValue();
        }
        return "";
    }

    private static boolean token(String value, String wanted) {
        for (String part : value.trim().split("\\s+")) if (part.equals(wanted)) return true;
        return false;
    }

    private static String percent(String value) {
        try { return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8"); }
        catch (Exception ignored) { return value; }
    }

    static String resolve(String base, String href) {
        if (href == null || href.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*") || href.startsWith("//")) return null;
        String path = percent(href.split("#", 2)[0].split("\\?", 2)[0]).replace('\\', '/');
        if (path.isEmpty()) return base;
        String combined = path.startsWith("/") ? path.substring(1)
                : (base.contains("/") ? base.substring(0, base.lastIndexOf('/') + 1) : "") + path;
        List<String> parts = new ArrayList<>();
        for (String part : combined.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (parts.isEmpty()) return null;
                parts.remove(parts.size() - 1);
            } else parts.add(part);
        }
        return String.join("/", parts);
    }

    private static String fragment(String href) {
        int hash = href.indexOf('#');
        return hash < 0 ? "" : percent(href.substring(hash + 1));
    }

    private ReadableBook epub(Map<String, byte[]> entries, boolean includeLegacy) throws IOException {
        Document container = xml(entries.get("META-INF/container.xml"));
        Element rootfile = null;
        for (Element file : elements(container, "rootfile")) {
            if (rootfile == null) rootfile = file;
            if ("application/oebps-package+xml".equals(attr(file, "media-type"))) { rootfile = file; break; }
        }
        if (rootfile == null) throw new IOException("EPUB 缺少 package 入口");
        String packagePath = resolve("", attr(rootfile, "full-path"));
        Document opf = xml(entries.get(packagePath));
        Map<String, Element> manifest = new LinkedHashMap<>();
        for (Element item : elements(opf, "item")) manifest.put(attr(item, "id"), item);
        Element spine = first(opf, "spine");
        if (spine == null) throw new IOException("EPUB 缺少正文阅读顺序 spine");
        Set<String> encrypted = new HashSet<>();
        if (entries.containsKey("META-INF/encryption.xml")) {
            for (Element ref : elements(xml(entries.get("META-INF/encryption.xml")), "CipherReference"))
                encrypted.add(resolve("", attr(ref, "URI")));
        }
        StringBuilder text = new StringBuilder();
        Map<String, Integer> positions = new HashMap<>();
        List<ReadableBook.Chapter> headings = new ArrayList<>();
        List<ReadableBook.Chapter> sections = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element ref : elements(spine, "itemref")) {
            if ("no".equalsIgnoreCase(attr(ref, "linear"))) continue;
            Element item = manifest.get(attr(ref, "idref"));
            if (item == null) throw new IOException("EPUB 阅读顺序引用了缺失的正文条目");
            String path = resolve(packagePath, attr(item, "href"));
            if (path == null) throw new IOException("EPUB 正文包含不支持的外部路径");
            if (token(attr(item, "properties"), "nav") || !seen.add(path)) continue;
            if (encrypted.contains(path)) throw new IOException("本书正文已加密，无法进行纯文字朗读");
            String type = attr(item, "media-type");
            if (!("application/xhtml+xml".equals(type) || "text/html".equals(type) || "text/plain".equals(type))) {
                warnings.add("跳过非文字正文：" + path);
                continue;
            }
            if (!entries.containsKey(path)) throw new IOException("EPUB 正文文件缺失：" + path);
            HtmlText section = "text/plain".equals(type) ? plainHtml(decode.apply(entries.get(path))) : html(decodeMarkup(entries.get(path)));
            String content = section.sink.finish();
            if (content.isEmpty()) continue;
            if (text.length() > 0) text.append("\n\n");
            int base = text.length();
            positions.put(path, base);
            for (Map.Entry<String, Integer> anchor : section.anchors.entrySet())
                positions.put(path + "#" + anchor.getKey(), base + Math.min(anchor.getValue(), content.length()));
            for (ReadableBook.Chapter chapter : section.headings)
                headings.add(new ReadableBook.Chapter(chapter.title, base + chapter.offset, chapter.depth));
            String label = section.headings.isEmpty() ? path.substring(path.lastIndexOf('/') + 1) : section.headings.get(0).title;
            sections.add(new ReadableBook.Chapter(label, base, 0));
            text.append(content);
        }
        List<ReadableBook.Chapter> chapters = new ArrayList<>();
        for (Element item : manifest.values()) {
            if (!token(attr(item, "properties"), "nav")) continue;
            String path = resolve(packagePath, attr(item, "href"));
            try {
                Document navigation = xml(entries.get(path));
                for (Element nav : elements(navigation, "nav")) {
                    if (!token(attr(nav, "type"), "toc")) continue;
                    for (Element link : elements(nav, "a"))
                        addChapter(chapters, positions, path, attr(link, "href"), link.getTextContent(), depth(link, nav, "ol") - 1);
                }
            } catch (IOException error) { warnings.add("EPUB 导航文件无法解析，尝试 NCX 或正文标题"); }
            if (!chapters.isEmpty()) break;
        }
        if (chapters.isEmpty()) {
            Element ncx = manifest.get(attr(spine, "toc"));
            if (ncx == null) for (Element item : manifest.values()) {
                if ("application/x-dtbncx+xml".equals(attr(item, "media-type"))) { ncx = item; break; }
            }
            if (ncx != null) {
                String path = resolve(packagePath, attr(ncx, "href"));
                try {
                    Document navigation = xml(entries.get(path));
                    for (Element point : elements(navigation, "navPoint")) {
                        Element content = first(point, "content");
                        Element label = first(point, "navLabel");
                        if (content != null && label != null)
                            addChapter(chapters, positions, path, attr(content, "src"), label.getTextContent(), depth(point, navigation, "navPoint"));
                    }
                } catch (IOException error) { warnings.add("EPUB NCX 目录无法解析，使用正文标题"); }
            }
        }
        if (chapters.isEmpty()) chapters.addAll(headings.isEmpty() ? sections : headings);
        String legacy = "";
        if (includeLegacy) {
            StringBuilder old = new StringBuilder();
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                if (entry.getKey().toLowerCase(Locale.ROOT).matches(".*\\.(xhtml|html|htm|txt)$"))
                    old.append('\n').append(legacyHtml(decode.apply(entry.getValue())));
            }
            legacy = old.toString().trim();
        }
        return book(text.toString(), chapters, warnings, "EPUB", legacy);
    }

    private static int depth(Node node, Node stop, String tag) {
        int depth = 0;
        for (Node parent = node.getParentNode(); parent != null && parent != stop; parent = parent.getParentNode())
            if (tag.equals(local(parent))) depth++;
        return depth;
    }

    private static void addChapter(List<ReadableBook.Chapter> chapters, Map<String, Integer> positions,
                                   String base, String href, String title, int depth) {
        String path = resolve(base, href);
        if (path == null) return;
        String fragment = fragment(href);
        Integer offset = positions.get(path + (fragment.isEmpty() ? "" : "#" + fragment));
        // Invalid fragment links must not silently jump to the beginning of the file.
        if (offset == null) return;
        String label = title.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        if (label.isEmpty()) return;
        for (ReadableBook.Chapter existing : chapters) if (existing.offset == offset && existing.title.equals(label)) return;
        chapters.add(new ReadableBook.Chapter(label, offset, depth));
    }

    private static final class Sink {
        final StringBuilder text = new StringBuilder();
        void line() {
            while (text.length() > 0 && text.charAt(text.length() - 1) == ' ') text.setLength(text.length() - 1);
            if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') text.append('\n');
        }
        void add(String value) {
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (Character.isWhitespace(ch) || ch == '\u00a0') {
                    if (text.length() > 0 && text.charAt(text.length() - 1) != ' ' && text.charAt(text.length() - 1) != '\n') text.append(' ');
                } else if (ch != '\u00ad' && ch != '\ufeff') text.append(ch);
            }
        }
        String finish() { return text.toString().trim(); }
    }

    private static final class HtmlText {
        final Sink sink = new Sink();
        final Map<String, Integer> anchors = new HashMap<>();
        final List<ReadableBook.Chapter> headings = new ArrayList<>();
    }

    private HtmlText plainHtml(String text) {
        HtmlText result = new HtmlText();
        result.sink.text.append(normalize(text));
        return result;
    }

    private HtmlText html(String html) {
        HtmlText result = new HtmlText();
        int cursor = 0;
        int headingStart = -1;
        int headingDepth = 0;
        while (cursor < html.length()) {
            if (html.startsWith("<!--", cursor)) {
                int end = html.indexOf("-->", cursor + 4);
                cursor = end < 0 ? html.length() : end + 3;
                continue;
            }
            if (html.charAt(cursor) != '<') {
                int end = html.indexOf('<', cursor);
                if (end < 0) end = html.length();
                result.sink.add(entities.apply(html.substring(cursor, end)));
                cursor = end;
                continue;
            }
            if (cursor + 1 < html.length() && !Character.isLetter(html.charAt(cursor + 1))
                    && "/!?".indexOf(html.charAt(cursor + 1)) < 0) {
                result.sink.add("<");
                cursor++;
                continue;
            }
            int end = tagEnd(html, cursor + 1);
            if (end < 0) { result.sink.add(html.substring(cursor)); break; }
            String tag = html.substring(cursor + 1, end).trim();
            boolean closing = tag.startsWith("/");
            if (closing) tag = tag.substring(1).trim();
            Matcher name = Pattern.compile("^([a-zA-Z][\\w:-]*)").matcher(tag);
            cursor = end + 1;
            if (!name.find()) continue;
            String tagName = name.group(1).toLowerCase(Locale.ROOT).replaceFirst("^.*:", "");
            Map<String, String> attrs = new HashMap<>();
            Matcher attributes = ATTR.matcher(tag.substring(name.end()));
            while (attributes.find()) {
                String value = attributes.group(2) != null ? attributes.group(2)
                        : attributes.group(3) != null ? attributes.group(3) : attributes.group(4);
                attrs.put(attributes.group(1).toLowerCase(Locale.ROOT), value == null ? "" : entities.apply(value));
            }
            if (!closing && ("head".equals(tagName) || "script".equals(tagName) || "style".equals(tagName)
                    || "template".equals(tagName) || "noscript".equals(tagName))) {
                Matcher close = Pattern.compile("(?i)</(?:\\w+:)?" + Pattern.quote(tagName) + "\\s*>").matcher(html);
                cursor = close.find(cursor) ? close.end() : html.length();
                continue;
            }
            if (!closing && (attrs.containsKey("hidden") || "true".equalsIgnoreCase(attrs.get("aria-hidden")))) {
                if (!tagName.matches("img|br|hr|input|meta|link|source|wbr") && !tag.endsWith("/")) {
                    Matcher nested = Pattern.compile("(?is)<(/?)(?:\\w+:)?" + Pattern.quote(tagName) + "\\b[^>]*>").matcher(html);
                    int remaining = 1;
                    int next = cursor;
                    while (nested.find(next)) {
                        next = nested.end();
                        remaining += nested.group(1).isEmpty() && !nested.group().endsWith("/>") ? 1 : -1;
                        if (remaining == 0) break;
                    }
                    cursor = remaining == 0 ? next : html.length();
                }
                continue;
            }
            boolean block = tagName.matches("h[1-6]|p|div|section|article|li|ul|ol|blockquote|pre|tr|table|header|footer|br|hr");
            if (block) result.sink.line();
            if (!closing) {
                if (attrs.containsKey("id")) result.anchors.putIfAbsent(attrs.get("id"), result.sink.text.length());
                else if (attrs.containsKey("xml:id")) result.anchors.putIfAbsent(attrs.get("xml:id"), result.sink.text.length());
                if ("a".equals(tagName) && attrs.containsKey("name")) result.anchors.putIfAbsent(attrs.get("name"), result.sink.text.length());
            }
            if (tagName.matches("h[1-6]")) {
                if (!closing) {
                    headingStart = result.sink.text.length();
                    headingDepth = tagName.charAt(1) - '1';
                } else if (headingStart >= 0) {
                    String title = result.sink.text.substring(headingStart).trim();
                    if (!title.isEmpty()) result.headings.add(new ReadableBook.Chapter(title, headingStart, headingDepth));
                    headingStart = -1;
                }
            }
            if ("td".equals(tagName) || "th".equals(tagName)) result.sink.add(" ");
            if (!closing && "img".equals(tagName) && attrs.containsKey("alt")) result.sink.add(attrs.get("alt"));
        }
        return result;
    }

    private static int tagEnd(String text, int start) {
        char quote = 0;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quote != 0) { if (ch == quote) quote = 0; }
            else if (ch == '\'' || ch == '"') quote = ch;
            else if (ch == '>') return i;
        }
        return -1;
    }

    private ReadableBook markdown(String raw) throws IOException {
        String[] lines = normalize(raw).split("\n", -1);
        Sink sink = new Sink();
        List<ReadableBook.Chapter> headings = new ArrayList<>();
        boolean code = false;
        String fence = "";
        int start = 0;
        if (lines.length > 0 && lines[0].equals("---")) {
            for (int i = 1; i < lines.length; i++) if (lines[i].matches("---|\\.\\.\\.")) { start = i + 1; break; }
        }
        for (int i = start; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("```") || line.startsWith("~~~")) {
                String marker = line.substring(0, 3);
                if (!code) { code = true; fence = marker; }
                else if (marker.equals(fence)) code = false;
                sink.line();
                continue;
            }
            if (!code && (line.matches("(?:[-*_]\\s*){3,}") || line.matches("\\[[^]]+]:\\s*\\S+.*")
                    || line.matches("\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)+\\|?"))) continue;
            int level = -1;
            Matcher heading = Pattern.compile("^(#{1,6})\\s+(.+?)(?:\\s+#+)?$").matcher(line);
            if (!code && heading.matches()) { level = heading.group(1).length() - 1; line = heading.group(2); }
            else if (!code && i + 1 < lines.length && !line.isEmpty() && lines[i + 1].trim().matches("=+|-+")) {
                level = lines[++i].trim().startsWith("=") ? 0 : 1;
            }
            if (!code) {
                line = line.replaceFirst("^(?:>\\s*)+", "").replaceFirst("^(?:[-+*]|\\d+[.)])\\s+", "")
                        .replaceFirst("^\\[[ xX]]\\s*", "")
                        .replaceAll("!?\\[([^]]*)]\\([^)]*\\)", "$1")
                        .replaceAll("\\[([^]]+)]\\[[^]]*]", "$1")
                        .replaceAll("`+([^`]+)`+", "$1")
                        .replaceAll("\\*{1,3}([^*]+)\\*{1,3}", "$1")
                        .replaceAll("(?<!\\w)_{1,3}([^_]+)_{1,3}(?!\\w)", "$1")
                        .replaceAll("~~([^~]+)~~", "$1")
                        .replaceAll("\\\\([\\\\`*{}\\[\\]()#+.!_>~-])", "$1");
                line = html(line).sink.finish();
            }
            sink.line();
            int offset = sink.text.length();
            sink.add(line);
            if (level >= 0 && !line.isEmpty()) headings.add(new ReadableBook.Chapter(line, offset, level));
            sink.line();
        }
        return book(sink.finish(), headings, Collections.emptyList(), "Markdown", "");
    }

    private ReadableBook docx(Map<String, byte[]> entries) throws IOException {
        String documentPath = "word/document.xml";
        if (entries.containsKey("_rels/.rels")) {
            for (Element relationship : elements(xml(entries.get("_rels/.rels")), "Relationship")) {
                if (attr(relationship, "Type").endsWith("/officeDocument")) {
                    if ("External".equals(attr(relationship, "TargetMode"))) throw new IOException("不支持外部 Word 正文");
                    documentPath = resolve("", attr(relationship, "Target"));
                    break;
                }
            }
        }
        Document document = xml(entries.get(documentPath));
        Element body = first(document, "body");
        if (body == null) throw new IOException("DOCX 缺少正文 body");
        Map<String, Integer> levels = new HashMap<>();
        Map<String, String> bases = new HashMap<>();
        String stylesPath = resolve(documentPath, "styles.xml");
        String relationshipsPath = (documentPath.contains("/") ? documentPath.substring(0, documentPath.lastIndexOf('/') + 1) : "")
                + "_rels/" + documentPath.substring(documentPath.lastIndexOf('/') + 1) + ".rels";
        if (entries.containsKey(relationshipsPath)) {
            for (Element rel : elements(xml(entries.get(relationshipsPath)), "Relationship"))
                if (attr(rel, "Type").endsWith("/styles") && !"External".equals(attr(rel, "TargetMode")))
                    stylesPath = resolve(documentPath, attr(rel, "Target"));
        }
        if (entries.containsKey(stylesPath)) {
            for (Element style : elements(xml(entries.get(stylesPath)), "style")) {
                String id = attr(style, "styleId");
                Element outline = first(style, "outlineLvl");
                Element name = first(style, "name");
                int level = outline == null ? headingLevel(id + " " + (name == null ? "" : attr(name, "val"))) : integer(attr(outline, "val"), -1);
                if (level >= 0 && level < 9) levels.put(id, level);
                Element basedOn = first(style, "basedOn");
                if (basedOn != null) bases.put(id, attr(basedOn, "val"));
            }
        }
        Sink sink = new Sink();
        List<ReadableBook.Chapter> chapters = new ArrayList<>();
        for (Element paragraph : elements(body, "p")) {
            if (inside(paragraph, body, "del") || inside(paragraph, body, "moveFrom")) continue;
            StringBuilder content = new StringBuilder();
            wordText(paragraph, content);
            String value = content.toString().trim();
            if (value.isEmpty()) continue;
            sink.line();
            int offset = sink.text.length();
            for (String line : value.split("\n", -1)) { sink.add(line); sink.line(); }
            int end = sink.finish().length();
            Element properties = null;
            for (int i = 0; i < paragraph.getChildNodes().getLength(); i++) {
                Node child = paragraph.getChildNodes().item(i);
                if (child instanceof Element && "pPr".equals(local(child))) { properties = (Element) child; break; }
            }
            int level = -1;
            if (properties != null) {
                Element outline = first(properties, "outlineLvl");
                Element style = first(properties, "pStyle");
                if (outline != null) level = integer(attr(outline, "val"), -1);
                else if (style != null) {
                    String id = attr(style, "val");
                    Set<String> visited = new HashSet<>();
                    while (id != null && visited.add(id)) {
                        level = levels.containsKey(id) ? levels.get(id) : headingLevel(id);
                        if (level >= 0) break;
                        id = bases.get(id);
                    }
                }
            }
            if (level >= 0 && level < 9) chapters.add(new ReadableBook.Chapter(sink.text.substring(offset, end), offset, level));
        }
        return book(sink.finish(), chapters, Collections.emptyList(), "DOCX", "");
    }

    private static boolean inside(Node node, Node stop, String name) {
        for (Node parent = node.getParentNode(); parent != null && parent != stop; parent = parent.getParentNode())
            if (name.equals(local(parent))) return true;
        return false;
    }

    private static void wordText(Node node, StringBuilder output) {
        String name = local(node);
        if ("del".equals(name) || "moveFrom".equals(name) || "instrText".equals(name)
                || "drawing".equals(name) || "pict".equals(name)) return;
        if ("t".equals(name)) { output.append(node.getTextContent()); return; }
        if ("tab".equals(name)) { output.append(' '); return; }
        if ("br".equals(name) || "cr".equals(name)) { output.append('\n'); return; }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) wordText(children.item(i), output);
    }

    private static int headingLevel(String style) {
        Matcher matcher = Pattern.compile("(?i)(?:heading|标题)\\s*([1-9])").matcher(style);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) - 1 : -1;
    }

    private static int integer(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; }
    }

    private static String normalize(String text) { return text.replace("\r\n", "\n").replace('\r', '\n').replace("\ufeff", "").trim(); }

    private String decodeMarkup(byte[] bytes) {
        if (bytes.length >= 2 && ((bytes[0] & 255) == 0xff || (bytes[0] & 255) == 0xfe)
                || bytes.length >= 3 && (bytes[0] & 255) == 0xef && (bytes[1] & 255) == 0xbb && (bytes[2] & 255) == 0xbf)
            return decode.apply(bytes);
        String header = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1);
        Matcher charset = Pattern.compile("(?i)(?:charset\\s*=\\s*[\"']?\\s*|encoding\\s*=\\s*[\"'])([\\w-]+)").matcher(header);
        if (charset.find()) {
            try { return new String(bytes, Charset.forName(charset.group(1))); } catch (IllegalArgumentException ignored) { }
        }
        return decode.apply(bytes);
    }

    private static String legacyHtml(String html) {
        return html.replaceAll("(?is)<script.*?</script>", " ").replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>|</h[1-6]>|</div>|</li>", "\n")
                .replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }
}
