package cn.zhixing.travel.knowledge;

import cn.zhixing.travel.exception.BusinessException;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Set;

/** 本地提取文字，不执行宏、脚本或文档中的指令，不调用聊天模型。 */
@Component
public class TravelDocumentParser {
    public static final Set<String> EXTENSIONS =
            Set.of(
                    "pdf",
                    "doc",
                    "docx",
                    "xls",
                    "xlsx",
                    "ppt",
                    "pptx",
                    "odt",
                    "ods",
                    "odp",
                    "rtf",
                    "txt",
                    "md",
                    "markdown",
                    "csv",
                    "tsv",
                    "json",
                    "xml",
                    "html",
                    "htm",
                    "log",
                    "epub");

    @Value("${rag.upload.max-bytes:20971520}")
    private long maxBytes;

    @Value("${rag.upload.max-text-chars:200000}")
    private int maxChars;

    public Parsed parse(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException(400, "请选择非空文档");
        if (file.getSize() > maxBytes) throw new BusinessException(413, "文件超过 20MB 限制");
        String name =
                file.getOriginalFilename() == null
                        ? ""
                        : file.getOriginalFilename().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isBlank() || name.length() > 255) throw new BusinessException(400, "文件名无效或过长");
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(extension)) throw new BusinessException(415, "不支持此格式，请查看支持格式列表");
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length > maxBytes) throw new BusinessException(413, "文件超过大小限制");
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, name);
            ParseContext context = new ParseContext();
            PDFParserConfig pdf = new PDFParserConfig();
            pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
            context.set(PDFParserConfig.class, pdf);
            TesseractOCRConfig ocr = new TesseractOCRConfig();
            ocr.setSkipOcr(true);
            context.set(TesseractOCRConfig.class, ocr);
            context.set(
                    EmbeddedDocumentExtractor.class,
                    new EmbeddedDocumentExtractor() {
                        public boolean shouldParseEmbedded(Metadata m) {
                            return false;
                        }

                        public void parseEmbedded(
                                java.io.InputStream stream,
                                org.xml.sax.ContentHandler handler,
                                Metadata m,
                                boolean html) {}
                    });
            BodyContentHandler handler = new BodyContentHandler(maxChars);
            new AutoDetectParser()
                    .parse(new ByteArrayInputStream(bytes), handler, metadata, context);
            String type = metadata.get("Content-Type");
            if (type != null) type = type.split(";", 2)[0].strip();
            if (!supportedType(type)) throw new BusinessException(415, "文件真实类型不在文档支持范围内");
            String text = handler.toString().replace("\u0000", "").strip();
            if (text.isBlank())
                throw new BusinessException(422, "未提取到文字；扫描 PDF/图片需先 OCR，当前未启用 OCR");
            return new Parsed(name, type, text, bytes);
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof org.apache.tika.exception.EncryptedDocumentException)
                    throw new BusinessException(422, "文档已加密，请解密后重新上传");
                if (cause instanceof org.apache.tika.exception.WriteLimitReachedException)
                    throw new BusinessException(413, "提取文字超过长度限制，请拆分文档后上传");
            }
            throw new BusinessException(422, "文档解析失败，请确认格式、文件完整性及是否加密");
        }
    }

    private boolean supportedType(String type) {
        if (type == null) return false;
        return type.startsWith("text/")
                || Set.of(
                                "application/pdf",
                                "application/msword",
                                "application/rtf",
                                "application/json",
                                "application/xml",
                                "application/xhtml+xml",
                                "application/epub+zip")
                        .contains(type)
                || type.startsWith("application/vnd.openxmlformats-officedocument.")
                || type.startsWith("application/vnd.oasis.opendocument.")
                || type.startsWith("application/vnd.ms-excel")
                || type.startsWith("application/vnd.ms-powerpoint");
    }

    public record Parsed(String filename, String contentType, String text, byte[] bytes) {}
}
