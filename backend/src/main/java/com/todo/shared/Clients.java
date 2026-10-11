package com.todo.shared;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * SDK clients built once per execution environment. Static initialisation means they are
 * captured in the SnapStart snapshot instead of being rebuilt on every cold start.
 *
 * Credentials must come from the default chain (container credentials endpoint), not
 * environment variables: those are not present when the SnapStart snapshot is taken.
 *
 * Each client is in its own holder class so a function only initialises the clients it uses.
 */
public final class Clients {

    private static final Region REGION = Region.of(System.getenv().getOrDefault("AWS_REGION", "eu-west-1"));

    private Clients() {
    }

    public static SnsClient sns() {
        return Sns.CLIENT;
    }

    public static DynamoDbClient dynamoDb() {
        return DynamoDb.CLIENT;
    }

    public static SqsClient sqs() {
        return Sqs.CLIENT;
    }

    public static SchedulerClient scheduler() {
        return Scheduler.CLIENT;
    }

    private static final class Sns {
        static final SnsClient CLIENT = SnsClient.builder()
                .region(REGION)
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    private static final class DynamoDb {
        static final DynamoDbClient CLIENT = DynamoDbClient.builder()
                .region(REGION)
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    private static final class Sqs {
        static final SqsClient CLIENT = SqsClient.builder()
                .region(REGION)
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    private static final class Scheduler {
        static final SchedulerClient CLIENT = SchedulerClient.builder()
                .region(REGION)
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }
}
