package com.cheq.support.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the in-memory {@link SimpleVectorStore} over the configured Gemini
 * {@link EmbeddingModel}. The store starts empty; ingestion either loads it from
 * {@code vectorstore.json} (warm start) or populates and saves it (cold start).
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    public SimpleVectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
