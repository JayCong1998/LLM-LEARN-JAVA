package com.jaycong.know.engine.storage.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.minio.MinioClient;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StorageConfigurationTest {

    @Test
    void shouldCreateEmbeddingAndObjectStorageBeans() {
        StorageConfiguration storageConfiguration = new StorageConfiguration();
        ElasticsearchProperties elasticsearchProperties = createElasticsearchProperties();
        MinioProperties minioProperties = createMinioProperties();

        EmbeddingModel embeddingModel = storageConfiguration.embeddingModel(elasticsearchProperties);
        EmbeddingStore<?> embeddingStore = storageConfiguration.embeddingStore(elasticsearchProperties,
            mock(RestClient.class));
        MinioClient minioClient = storageConfiguration.minioClient(minioProperties);

        assertThat(embeddingModel).isNotNull();
        assertThat(embeddingStore).isNotNull();
        assertThat(minioClient).isNotNull();
    }

    private ElasticsearchProperties createElasticsearchProperties() {
        ElasticsearchProperties elasticsearchProperties = new ElasticsearchProperties();
        elasticsearchProperties.setHost("http://127.0.0.1:9200");
        elasticsearchProperties.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        elasticsearchProperties.setModelName("text-embedding-v4");
        elasticsearchProperties.setApiKey("test-key");
        elasticsearchProperties.setDimensions(1536);
        elasticsearchProperties.setIndexName("knowledge_segment_embeddings");
        return elasticsearchProperties;
    }

    private MinioProperties createMinioProperties() {
        MinioProperties minioProperties = new MinioProperties();
        minioProperties.setEndpoint("http://localhost:9000");
        minioProperties.setAccessKey("minioadmin");
        minioProperties.setSecretKey("minioadmin");
        return minioProperties;
    }
}
