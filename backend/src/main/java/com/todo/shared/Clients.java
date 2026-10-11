package com.todo.shared;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * SDK clients built once per execution environment. Static initialisation means they are
 * captured in the SnapStart snapshot instead of being rebuilt on every cold start.
 *
 * Credentials must come from the default chain (container credentials endpoint), not
 * environment variables: those are not present when the SnapStart snapshot is taken.
 */
public final class Clients {

    private static final Region REGION = Region.of(System.getenv().getOrDefault("AWS_REGION", "eu-west-1"));

    public static final SnsClient SNS = SnsClient.builder()
            .region(REGION)
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .httpClient(UrlConnectionHttpClient.create())
            .build();

    private Clients() {
    }
}
