package cn.zhixing.travel.knowledge;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.segment.TextSegment;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TravelKnowledgeSplitter {
    /** 父段随子段持久化；重启后无需依赖上传者本地文件或全局内存索引。 */
    public List<TextSegment> split(String owner, String id, TravelDocumentParser.Parsed parsed) {
        var parents =
                new DocumentByParagraphSplitter(1000, 200).split(Document.from(parsed.text()));
        var children = new ArrayList<TextSegment>();
        for (int i = 0; i < parents.size(); i++) {
            String parentId = id + "__parent_" + i;
            Metadata metadata =
                    new Metadata()
                            .put("owner_id", owner)
                            .put("document_id", id)
                            .put("source", parsed.filename())
                            .put("parent_id", parentId)
                            .put("parent_text", parents.get(i).text());
            var parts =
                    new DocumentByParagraphSplitter(300, 100)
                            .split(Document.from(parents.get(i).text(), metadata));
            for (int j = 0; j < parts.size(); j++) {
                children.add(
                        TextSegment.from(
                                parsed.filename() + "\n" + parts.get(j).text(),
                                parts.get(j)
                                        .metadata()
                                        .copy()
                                        .put("child_id", parentId + "__child_" + j)
                                        .put("chunk_type", "child")));
            }
        }
        return children;
    }
}
