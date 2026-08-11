package com.byeolnaerim.mongodsl.internal.update;

import java.util.List;
import org.bson.Document;

/** BSON update definition supporting either a classic update document or an update pipeline. */
public interface MongoUpdateDefinition {
    boolean isPipeline();
    Document document();
    List<Document> pipeline();
}
