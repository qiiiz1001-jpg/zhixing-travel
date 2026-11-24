package cn.zhixing.travel;

import static org.junit.jupiter.api.Assertions.*;

import cn.zhixing.travel.exception.BusinessException;
import cn.zhixing.travel.knowledge.TravelDocumentParser;
import cn.zhixing.travel.knowledge.TravelKnowledgeSplitter;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class TravelDocumentParserTest {
    private final TravelDocumentParser parser = new TravelDocumentParser();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(parser, "maxBytes", 20971520L);
        ReflectionTestUtils.setField(parser, "maxChars", 200000);
    }

    record Sample(String name, byte[] bytes, String expected) {
        Sample(String name, byte[] bytes) {
            this(name, bytes, "Travel guide");
        }
    }

    private static Sample text(String name, String text) {
        return new Sample(name, text.getBytes(StandardCharsets.UTF_8));
    }

    static Stream<Sample> documents() throws Exception {
        var samples = new java.util.ArrayList<Sample>();
        for (String ext :
                new String[] {
                    "txt",
                    "md",
                    "markdown",
                    "log",
                    "csv",
                    "tsv",
                    "json",
                    "xml",
                    "html",
                    "htm",
                    "rtf"
                }) {
            String content =
                    switch (ext) {
                        case "html", "htm" ->
                                "<html><body><h1>Travel guide</h1><p>Quiet hotel"
                                        + " area.</p></body></html>";
                        case "xml" -> "<?xml version=\"1.0\"?><guide>Travel guide</guide>";
                        case "json" -> "{\"guide\":\"Travel guide\"}";
                        case "rtf" ->
                                "{\\r"
                                    + "tf1\\ansi\\deff0{\\fonttbl{\\f0 Arial;}}\\f0\\fs24 Travel"
                                    + " guide\\par}";
                        default -> "Travel guide\nQuiet hotel area.";
                    };
            samples.add(text("guide." + ext, content));
        }
        try (var doc = new XWPFDocument();
                var out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Travel guide");
            doc.write(out);
            samples.add(new Sample("guide.docx", out.toByteArray()));
        }
        try (var book = new XSSFWorkbook();
                var out = new ByteArrayOutputStream()) {
            book.createSheet("Travel").createRow(0).createCell(0).setCellValue("Travel guide");
            book.write(out);
            samples.add(new Sample("guide.xlsx", out.toByteArray()));
        }
        try (var book = new HSSFWorkbook();
                var out = new ByteArrayOutputStream()) {
            book.createSheet("Travel").createRow(0).createCell(0).setCellValue("Travel guide");
            book.write(out);
            samples.add(new Sample("guide.xls", out.toByteArray()));
        }
        try (var slides = new XMLSlideShow();
                var out = new ByteArrayOutputStream()) {
            slides.createSlide().createTextBox().setText("Travel guide");
            slides.write(out);
            samples.add(new Sample("guide.pptx", out.toByteArray()));
        }
        try (var slides = new HSLFSlideShow();
                var out = new ByteArrayOutputStream()) {
            slides.createSlide().createTextBox().setText("Travel guide");
            slides.write(out);
            samples.add(new Sample("guide.ppt", out.toByteArray()));
        }
        samples.add(new Sample("guide.pdf", pdf(false, false)));
        try (var input =
                TravelDocumentParserTest.class.getResourceAsStream("/knowledge/poi-simple.doc")) {
            samples.add(
                    new Sample(
                            "guide.doc",
                            java.util.Objects.requireNonNull(input).readAllBytes(),
                            "This is a simple file"));
        }
        try (var out = new ByteArrayOutputStream();
                var zip = new ZipOutputStream(out)) {
            put(zip, "mimetype", "application/epub+zip");
            put(
                    zip,
                    "META-INF/container.xml",
                    "<?xml version=\"1.0\"?><container"
                        + " xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\""
                        + " version=\"1.0\"><rootfiles><rootfile full-path=\"content.opf\""
                        + " media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
            put(
                    zip,
                    "content.opf",
                    "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\""
                            + " version=\"2.0\" unique-identifier=\"id\"><metadata"
                            + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>Travel"
                            + " guide</dc:title><dc:language>en</dc:language><dc:identifier"
                            + " id=\"id\">travel-test</dc:identifier></metadata><manifest><item"
                            + " id=\"chapter\" href=\"chapter.xhtml\""
                            + " media-type=\"application/xhtml+xml\"/></manifest><spine><itemref"
                            + " idref=\"chapter\"/></spine></package>");
            put(
                    zip,
                    "chapter.xhtml",
                    "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Travel"
                            + " guide</title></head><body><p>Travel guide</p></body></html>");
            zip.finish();
            samples.add(new Sample("guide.epub", out.toByteArray()));
        }
        for (String ext : new String[] {"odt", "ods", "odp"}) {
            String family =
                    switch (ext) {
                        case "odt" -> "text";
                        case "ods" -> "spreadsheet";
                        default -> "presentation";
                    };
            try (var out = new ByteArrayOutputStream();
                    var zip = new ZipOutputStream(out)) {
                put(zip, "mimetype", "application/vnd.oasis.opendocument." + family);
                put(
                        zip,
                        "META-INF/manifest.xml",
                        "<manifest:manifest"
                            + " xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\"><manifest:file-entry"
                            + " manifest:full-path=\"/\""
                            + " manifest:media-type=\"application/vnd.oasis.opendocument."
                                + family
                                + "\"/></manifest:manifest>");
                put(
                        zip,
                        "content.xml",
                        "<?xml version=\"1.0\"?><office:document-content"
                            + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
                            + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\"><office:body><office:text><text:p>Travel"
                            + " guide</text:p></office:text></office:body></office:document-content>");
                zip.finish();
                samples.add(new Sample("guide." + ext, out.toByteArray()));
            }
        }
        return samples.stream();
    }

    private static void put(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static byte[] pdf(boolean blank, boolean encrypted) throws Exception {
        try (var doc = new PDDocument();
                var out = new ByteArrayOutputStream()) {
            var page = new PDPage();
            doc.addPage(page);
            if (!blank)
                try (var stream = new PDPageContentStream(doc, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700);
                    stream.showText("Travel guide");
                    stream.endText();
                }
            if (encrypted)
                doc.protect(
                        new StandardProtectionPolicy(
                                "owner-secret", "reader-secret", new AccessPermission()));
            doc.save(out);
            return out.toByteArray();
        }
    }

    @ParameterizedTest
    @MethodSource("documents")
    void extractsCommonFormats(Sample sample) {
        var result =
                parser.parse(
                        new MockMultipartFile(
                                "file", sample.name(), "application/octet-stream", sample.bytes()));
        assertTrue(result.text().contains(sample.expected()), sample.name() + ": " + result.text());
    }

    @Test
    void blankPdfDoesNotClaimSuccessfulIngestion() throws Exception {
        var error =
                assertThrows(
                        BusinessException.class,
                        () ->
                                parser.parse(
                                        new MockMultipartFile(
                                                "file",
                                                "scan.pdf",
                                                "application/pdf",
                                                pdf(true, false))));
        assertTrue(error.getMessage().contains("OCR"));
    }

    @Test
    void encryptedPdfHasClearFailure() throws Exception {
        var error =
                assertThrows(
                        BusinessException.class,
                        () ->
                                parser.parse(
                                        new MockMultipartFile(
                                                "file",
                                                "locked.pdf",
                                                "application/pdf",
                                                pdf(false, true))));
        assertTrue(error.getMessage().contains("加密"));
    }

    @Test
    void rejectsUnsupportedOrEmptyFilesAndExcessText() {
        assertThrows(
                BusinessException.class,
                () ->
                        parser.parse(
                                new MockMultipartFile(
                                        "file",
                                        "a.exe",
                                        "application/octet-stream",
                                        new byte[] {1, 2, 3})));
        assertThrows(
                BusinessException.class,
                () ->
                        parser.parse(
                                new MockMultipartFile(
                                        "file", "a.txt", "text/plain", new byte[] {})));
        ReflectionTestUtils.setField(parser, "maxChars", 10);
        assertThrows(
                BusinessException.class,
                () ->
                        parser.parse(
                                new MockMultipartFile(
                                        "file",
                                        "a.txt",
                                        "text/plain",
                                        "Travel guide with lots of content"
                                                .getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void chineseFilenameAndTextArePreservedAndParentsArePersisted() {
        var parsed =
                parser.parse(
                        new MockMultipartFile(
                                "file",
                                "../好友攻略.md",
                                "text/plain",
                                "# 西安\n推荐住在安静的街区。".getBytes(StandardCharsets.UTF_8)));
        assertEquals("好友攻略.md", parsed.filename());
        var chunks = new TravelKnowledgeSplitter().split("alice", "document-1", parsed);
        assertFalse(chunks.isEmpty());
        assertEquals("alice", chunks.get(0).metadata().getString("owner_id"));
        assertTrue(chunks.get(0).metadata().getString("parent_text").contains("安静"));
    }
}
