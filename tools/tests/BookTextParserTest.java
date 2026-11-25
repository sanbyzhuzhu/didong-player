package com.dongting.player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class BookTextParserTest {
    private static final BookTextParser PARSER = new BookTextParserTest().parser();
    private BookTextParser parser() {
        return new BookTextParser(bytes -> new String(bytes, StandardCharsets.UTF_8),
                text -> text.replace("&amp;", "&").replace("&nbsp;", " ").replace("&#x4E2D;", "中")
                        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\""));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static byte[] zip(Map<String, String> files) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
    private static ReadableBook parse(String name, byte[] bytes) throws Exception {
        return PARSER.parse(new ByteArrayInputStream(bytes), name, "", true);
    }
    private static void fails(String name, byte[] bytes) throws Exception {
        try { parse(name, bytes); throw new AssertionError("Expected import failure for " + name); }
        catch (IOException expected) { }
    }

    static LinkedHashMap<String, String> epub(boolean ncx) {
        LinkedHashMap<String, String> files = new LinkedHashMap<>();
        // Intentionally put chapter 2 before chapter 1 in ZIP order.
        files.put("OEBPS/Text/ch2.xhtml", "<html><head><title>不能朗读的网页标题</title></head><body><h1 id='two'>第二章</h1><p>第二章正文。继续第二章。</p></body></html>");
        files.put("OEBPS/Text/ch 1.xhtml", "<html><body><h1 id='one'>第一章</h1><p>第一章正文。继续第一章。</p><h2 id='sub'>第一章的小节</h2><p>小节正文。</p></body></html>");
        files.put("META-INF/container.xml", "<?xml version='1.0'?><container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OEBPS/package.opf' media-type='application/oebps-package+xml'/></rootfiles></container>");
        files.put("OEBPS/package.opf", "<package xmlns='http://www.idpf.org/2007/opf'><manifest>"
                + "<item id='one' href='Text/ch%201.xhtml' media-type='application/xhtml+xml'/>"
                + "<item id='two' href='Text/ch2.xhtml' media-type='application/xhtml+xml'/>"
                + "<item id='ignored' href='extra.xhtml' media-type='application/xhtml+xml'/>"
                + (ncx ? "<item id='nav' href='Nav/toc.ncx' media-type='application/x-dtbncx+xml'/>"
                : "<item id='nav' href='Nav/toc.xhtml' media-type='application/xhtml+xml' properties='nav'/>" )
                + "</manifest><spine toc='nav'><itemref idref='one'/><itemref idref='ignored' linear='no'/><itemref idref='two'/></spine></package>");
        files.put("OEBPS/extra.xhtml", "<p>不能朗读的非线性附录。</p>");
        if (ncx) files.put("OEBPS/Nav/toc.ncx", "<?xml version='1.0'?><!DOCTYPE ncx PUBLIC '-//NISO//DTD ncx 2005-1//EN' 'http://invalid.example/ncx.dtd'><ncx xmlns='http://www.daisy.org/z3986/2005/ncx/'><navMap>"
                + "<navPoint id='one'><navLabel><text>目录第一章</text></navLabel><content src='../Text/ch%201.xhtml#one'/>"
                + "<navPoint id='sub'><navLabel><text>目录小节</text></navLabel><content src='../Text/ch%201.xhtml#sub'/></navPoint></navPoint>"
                + "<navPoint id='two'><navLabel><text>目录第二章</text></navLabel><content src='../Text/ch2.xhtml#two'/></navPoint></navMap></ncx>");
        else files.put("OEBPS/Nav/toc.xhtml", "<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><body>"
                + "<nav epub:type='landmarks'><a href='../Text/ch2.xhtml'>不应该采用的目录</a></nav>"
                + "<nav epub:type='toc'><ol><li><a href='../Text/ch%201.xhtml#one'>目录第一章</a><ol><li><a href='../Text/ch%201.xhtml#sub'>目录小节</a></li></ol></li>"
                + "<li><a href='../Text/ch2.xhtml#two'>目录第二章</a></li>"
                + "<li><a href='https://example.com'>不应该访问网络</a></li><li><a href='../Text/ch2.xhtml#missing'>不存在的锚点</a></li></ol></nav></body></html>");
        return files;
    }

    static Map<String, String> docx() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("_rels/.rels", "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='main' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument' Target='word/document.xml'/></Relationships>");
        files.put("word/styles.xml", "<w:styles xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'>"
                + "<w:style w:styleId='base'><w:name w:val='Heading 2'/></w:style>"
                + "<w:style w:styleId='CustomChapter'><w:basedOn w:val='base'/></w:style></w:styles>");
        files.put("word/document.xml", "<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body>"
                + "<w:p><w:pPr><w:pStyle w:val='CustomChapter'/></w:pPr><w:r><w:t>Word 第一章</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t xml:space='preserve'>这里是 </w:t></w:r><w:hyperlink><w:r><w:t>正文</w:t></w:r></w:hyperlink><w:r><w:tab/><w:t>内容。</w:t><w:br/><w:t>下一行。</w:t></w:r>"
                + "<w:del><w:r><w:delText>不能读的删除文字</w:delText></w:r></w:del><w:r><w:instrText>不能读的域代码</w:instrText></w:r></w:p>"
                + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>表格甲</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>表格乙</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>");
        return files;
    }

    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of("build/book-fixtures");
        Files.createDirectories(fixtures);
        for (boolean ncx : new boolean[]{false, true}) {
            byte[] bytes = zip(epub(ncx));
            Files.write(fixtures.resolve(ncx ? "epub2.epub" : "epub3.epub"), bytes);
            ReadableBook book = parse("book.epub", bytes);
            check(book.text.startsWith("第一章\n第一章正文。"), "Spine must override ZIP order: " + book.text);
            check(book.text.indexOf("第二章") > book.text.indexOf("小节正文"), "Correct chapter order");
            check(!book.text.contains("不能朗读") && !book.text.contains("不应该"), "Exclude non-linear and navigation content");
            check(book.chapters.size() == 3, "Complete toc, excluding broken/external anchors: " + book.chapters.size());
            check(book.chapters.get(0).title.equals("目录第一章"), "Use explicit TOC title");
            check(book.chapters.get(1).depth == 1, "Nested TOC level");
            check(book.text.substring(book.chapters.get(1).offset).startsWith("第一章的小节"), "Fragment anchor offset");
            TtsText.Position pos = TtsText.positionForOffset(book.text, TtsText.splitChunks(book.text), book.chapters.get(1).offset);
            check(pos.chunk == 0 && pos.offset > 0, "Anchor within same TTS paragraph");
            check(TtsText.splitChunks(book.text).get(pos.chunk).substring(pos.offset).startsWith("第一章的小节"), "Accurate spoken target");
            List<String> oldChunks = TtsText.splitChunks(book.legacyText);
            int oldOffset = oldChunks.get(0).indexOf("第二章正文");
            TtsText.Position mapped = BookProgress.remap(book.legacyText, book.text, 0, oldOffset);
            check(mapped != null && TtsText.splitChunks(book.text).get(mapped.chunk).substring(mapped.offset).startsWith("第二章正文"), "Legacy progress migration");
        }
        Map<String, String> missingToc = epub(false);
        missingToc.remove("OEBPS/Nav/toc.xhtml");
        check(parse("fallback.epub", zip(missingToc)).chapters.get(0).title.equals("第一章"), "Fallback to document headings");
        Map<String, String> namedEntity = epub(false);
        namedEntity.put("OEBPS/Nav/toc.xhtml", namedEntity.get("OEBPS/Nav/toc.xhtml").replace("目录第一章", "目录&nbsp;第一章"));
        check(parse("entities.epub", zip(namedEntity)).chapters.get(0).title.equals("目录 第一章"), "Local named entities in EPUB navigation without loading DTD");
        Map<String, String> missing = epub(false);
        missing.remove("OEBPS/Text/ch 1.xhtml");
        fails("missing.epub", zip(missing));
        Map<String, String> encrypted = epub(false);
        encrypted.put("META-INF/encryption.xml", "<encryption><EncryptedData><CipherData><CipherReference URI='OEBPS/Text/ch%201.xhtml'/></CipherData></EncryptedData></encryption>");
        fails("encrypted.epub", zip(encrypted));
        Map<String, String> traversal = epub(false);
        traversal.put("../outside.txt", "bad");
        fails("traversal.epub", zip(traversal));
        Map<String, String> xxe = epub(false);
        xxe.put("META-INF/container.xml", "<!DOCTYPE container [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><container>&x;</container>");
        fails("xxe.epub", zip(xxe));
        ReadableBook word = parse("sample.docx", zip(docx()));
        Files.write(fixtures.resolve("sample.docx"), zip(docx()));
        check(word.text.contains("这里是 正文 内容。\n下一行。"), "Word runs, links, tabs and breaks: " + word.text);
        check(word.text.contains("表格甲\n表格乙") && !word.text.contains("不能读"), "Word tables and deleted/field text");
        check(word.chapters.size() == 1 && word.chapters.get(0).depth == 1, "Inherited Word heading style");
        String md = "---\ntitle: 隐藏元数据\n---\n# Markdown 第一章\n这是**重点**，看[正文](https://example.com)。\n## 小节\n- [x] 完成。\n```java\n代码内容\n```\n";
        Files.writeString(fixtures.resolve("sample.md"), md, StandardCharsets.UTF_8);
        ReadableBook markdown = parse("sample.md", md.getBytes(StandardCharsets.UTF_8));
        check(markdown.text.contains("这是重点，看正文。"), "Remove Markdown syntax");
        check(markdown.chapters.size() == 2 && !markdown.text.contains("隐藏元数据") && !markdown.text.contains("```"), "Markdown headings and metadata");
        String html = "<html><head><style>隐藏样式</style><title>隐藏标题</title></head><body><h1 id='c1'>HTML 第一章</h1><p>这里<b>没有</b>多余空格 &amp; &#x4E2D;文。</p><div hidden><div>隐藏内容</div>隐藏尾部</div><script>不读脚本</script><h2>小节</h2><p>2 < 3，正常。</p></body></html>";
        Files.writeString(fixtures.resolve("sample.html"), html, StandardCharsets.UTF_8);
        ReadableBook page = parse("sample.html", html.getBytes(StandardCharsets.UTF_8));
        check(page.text.contains("这里没有多余空格 & 中文。") && page.text.contains("2 < 3，正常。"), "HTML inline tags, entities and malformed less-than: " + page.text);
        check(!page.text.contains("隐藏") && !page.text.contains("脚本") && page.chapters.size() == 2, "HTML excludes head/scripts/hidden nodes");
        ReadableBook latin = parse("latin.html", "<meta charset='windows-1252'><p>café</p>".getBytes(java.nio.charset.Charset.forName("windows-1252")));
        check(latin.text.equals("café"), "Honor HTML charset declaration");
        fails("empty.docx", "not a docx".getBytes(StandardCharsets.UTF_8));
        check(BookTextParser.supportedName("BOOK.DOCX") && BookTextParser.supportedName("test.markdown"), "Import filter");
        check(BookTextParser.resolve("OPS/ch.xhtml", "../../escape.xhtml") == null, "No archive root traversal");
        check(BookTextParser.resolve("OPS/ch.xhtml", "https://example.com/a") == null, "No external resources");
        System.out.println("PASS: EPUB 2/3 spine/nav/NCX/anchors/fallback/migration; DOCX; Markdown; HTML; malformed/encrypted/traversal/XXE rejection");
    }
}
