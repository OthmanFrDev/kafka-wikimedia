package dev.othmanfr;

import com.google.gson.JsonParser;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.client.indices.CreateIndexRequest;
import org.opensearch.client.indices.GetIndexRequest;
import org.opensearch.common.xcontent.XContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;

public class OpenSearchConsumer {
    private static Logger log = LoggerFactory.getLogger(OpenSearchConsumer.class.getSimpleName());

    public static void main(String[] args) throws IOException {
        // first create an opeanSearch client
        RestHighLevelClient openSearchClient = createOpenSearchClient();
        // create our kafka Client
        KafkaConsumer<String, String> kafkaConsumer = createKafkaConsumer();
        // main code logic

        // close things
        // we need to create the index on OpenSearch if it doesn't exist already
        try (openSearchClient; kafkaConsumer) {
            boolean isIndexExists = openSearchClient.indices().exists(new GetIndexRequest("wikimedia"), RequestOptions.DEFAULT);
            if (!isIndexExists) {
                CreateIndexRequest createIndexRequest = new CreateIndexRequest("wikimedia");
                openSearchClient.indices().create(createIndexRequest, RequestOptions.DEFAULT);
                log.info("The Wikimedia Index has been created");
            } else {
                log.info("The Wikimedia index already exists");
            }
            kafkaConsumer.subscribe(Collections.singleton("wikimedia.recentchange"));
            while (true) {
                ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofMillis(3000));
                int recordCount = records.count();
                log.info("Received " + recordCount + " record(s)");
                for (ConsumerRecord<String, String> record : records) {
                    // send the record into OpenSearch

                    /**
                     // strategy 1
                     // define an ID using Kafka Record coordinates

                     String id = record.topic() + "_" + record.partition() + "_" + record.offset();
                     **/
                    // strategy 2
                    //we extract the ID from the JSON value
                    String id = extractID(record.value());
                    try {
                        IndexRequest indexRequest = new IndexRequest("wikimedia").source(record.value(), XContentType.JSON).id(id);
                        IndexResponse indexResponse = openSearchClient.index(indexRequest, RequestOptions.DEFAULT);
                        log.info(indexResponse.getId());
                    } catch (Exception ex) {

                    }

                }
            }
        }
    }

    private static String extractID(String json) {
        return JsonParser.parseString(json)
                .getAsJsonObject()
                .get("meta")
                .getAsJsonObject()
                .get("id")
                .getAsString();
    }

    private static KafkaConsumer<String, String> createKafkaConsumer() {
        log.info("Start consuming event");
        Properties properties = new Properties();
        String groupId = "consumer-opensearch-demo";
        // broker properties
        properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:9091");
        // producer properties
        properties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        /**
         * The auto offset reset consumer configuration defines how a consumer
         * should behave when consuming from a topic partition when there is no initial offset
         *  none : throw an exception if no offest present for the consumer group.
         *  earliest : Reset offest to earliest offest. Consume from the beginning of the topic partition
         *  latest : Reset offest to latest offest. Consume from the end of the topic partition (Default)
         */
        properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        return new KafkaConsumer<>(properties);
    }

    public static RestHighLevelClient createOpenSearchClient() {
        String connString = "http://localhost:9200";

        // we build a URI from the connection string
        RestHighLevelClient restHighLevelClient;
        URI connUri = URI.create(connString);
        // extract login information if it exists
        String userInfo = connUri.getUserInfo();

        if (userInfo == null) {
            restHighLevelClient = new RestHighLevelClient(RestClient.builder(new HttpHost(connUri.getHost(), connUri.getPort())));
        } else {
            String[] auth = userInfo.split(":");

            CredentialsProvider cp = new BasicCredentialsProvider();
            cp.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(auth[0], auth[1]));
            restHighLevelClient = new RestHighLevelClient(RestClient.builder(new HttpHost(connUri.getHost(), connUri.getPort(), connUri.getScheme())).setHttpClientConfigCallback(httpAsyncClientBuilder -> httpAsyncClientBuilder.setDefaultCredentialsProvider(cp).setKeepAliveStrategy(new DefaultConnectionKeepAliveStrategy())));
        }
        return restHighLevelClient;
    }
}
