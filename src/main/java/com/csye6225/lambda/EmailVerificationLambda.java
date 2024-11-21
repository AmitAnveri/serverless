package com.csye6225.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class EmailVerificationLambda implements RequestHandler<SNSEvent, String> {

    private static final String MAILGUN_API_KEY = System.getenv("MAILGUN_API_KEY");
    private static final String MAILGUN_DOMAIN = System.getenv("MAILGUN_DOMAIN");
    private static final String DB_SECRET_NAME = System.getenv("DB_SECRET_NAME");
    private static final String VERIFICATION_EXPIRY = System.getenv("VERIFICATION_EXPIRY");
    private static final String DOMAIN_NAME = System.getenv("DOMAIN_NAME");

    private static final OkHttpClient client = new OkHttpClient();

    @Override
    public String handleRequest(SNSEvent event, Context context) {
        context.getLogger().log("Lambda triggered with SNS event.");
        try {
            context.getLogger().log("Received SNS event: " + event);

            for (SNSEvent.SNSRecord record : event.getRecords()) {
                String message = record.getSNS().getMessage();
                context.getLogger().log("Processing SNS message: " + message);

                // Parse the message as JSON
                Map<String, String> parsedMessage = parseMessage(message, context);

                String email = parsedMessage.get("email");
                if (email == null || email.isEmpty()) {
                    context.getLogger().log("No 'email' field found in message: " + message);
                    throw new IllegalArgumentException("Email is missing in SNS message.");
                }
                context.getLogger().log("Extracted email: " + email);

                // Retrieve Database Credentials
                context.getLogger().log("Fetching database credentials...");
                Map<String, String> dbCredentials = getDbCredentials(context);
                context.getLogger().log("Database credentials fetched successfully.");

                // Generate Verification Token
                String token = UUID.randomUUID().toString();
                Instant expiry = Instant.now().plusSeconds(Long.parseLong(VERIFICATION_EXPIRY));
                context.getLogger().log("Generated verification token: " + token);
                context.getLogger().log("Token expiry set to: " + expiry);

                // Store Verification Info in RDS
                context.getLogger().log("Storing verification token in the database...");
                storeVerificationToken(dbCredentials, email, token, context);
                context.getLogger().log("Verification token stored successfully.");

                // Send Verification Email
                context.getLogger().log("Sending verification email to: " + email);
                sendVerificationEmail(email, token, context);
                context.getLogger().log("Verification email sent successfully to: " + email);
            }
            return "Emails processed successfully.";
        } catch (Exception e) {
            context.getLogger().log("Error occurred: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }


    private Map<String, String> parseMessage(String message, Context context) {
        try {
            context.getLogger().log("Parsing SNS message...");
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readValue(message, Map.class);
        } catch (Exception e) {
            context.getLogger().log("Error parsing SNS message: " + e.getMessage());
            context.getLogger().log("Raw message: " + message);
            throw new RuntimeException("Error parsing SNS message: " + e.getMessage(), e);
        }
    }

    private Map<String, String> getDbCredentials(Context context) {
        try {
            context.getLogger().log("Retrieving secrets from Secrets Manager...");
            GetSecretValueResponse getSecretValueResponse;
            try (SecretsManagerClient secretsClient = SecretsManagerClient.create()) {
                GetSecretValueRequest getSecretValueRequest = GetSecretValueRequest.builder()
                        .secretId(DB_SECRET_NAME)
                        .build();
                getSecretValueResponse = secretsClient.getSecretValue(getSecretValueRequest);
            }

            String secretString = getSecretValueResponse.secretString();
            context.getLogger().log("Secrets retrieved successfully.");
            return parseSecretString(secretString, context);
        } catch (Exception e) {
            context.getLogger().log("Error retrieving secrets from Secrets Manager: " + e.getMessage());
            throw new RuntimeException("Error retrieving secrets: " + e.getMessage(), e);
        }
    }

    private Map<String, String> parseSecretString(String secretString, Context context) {
        try {
            context.getLogger().log("Parsing secrets JSON...");
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readValue(secretString, Map.class);
        } catch (Exception e) {
            context.getLogger().log("Error parsing secrets JSON: " + e.getMessage());
            context.getLogger().log("Raw secrets JSON: " + secretString);
            throw new RuntimeException("Error parsing secrets JSON: " + e.getMessage(), e);
        }
    }

    private void storeVerificationToken(Map<String, String> dbCredentials, String email, String token, Context context) {
        String dbUrl = String.format("jdbc:postgresql://%s:%s/%s",
                dbCredentials.get("DB_HOST"), dbCredentials.get("DB_PORT"), dbCredentials.get("DB_NAME"));

        context.getLogger().log("Connecting to database at: " + dbUrl);
        try (Connection connection = DriverManager.getConnection(dbUrl, dbCredentials.get("DB_USERNAME"), dbCredentials.get("DB_PASSWORD"))) {
            // Updated SQL to match the SentEmail entity structure
            String sql = "INSERT INTO sent_emails (email, token, sent_at, status) VALUES (?, ?, ?, ?)";

            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, email);                          // Setting email
                ps.setString(2, token);                          // Setting token
                ps.setTimestamp(3, java.sql.Timestamp.from(Instant.now())); // Setting sent_at as the current timestamp
                ps.setString(4, "PENDING");                      // Setting status as 'PENDING' initially

                ps.executeUpdate();
            }
            context.getLogger().log("Token stored successfully for email: " + email);
        } catch (Exception e) {
            context.getLogger().log("Error storing token in database: " + e.getMessage());
            throw new RuntimeException("Error storing token: " + e.getMessage(), e);
        }
    }

    private void sendVerificationEmail(String email, String token, Context context) {
        String verificationLink = String.format("http://%s/v1/user/verify?token=%s", DOMAIN_NAME, token);
        context.getLogger().log("Generated verification link: " + verificationLink);

        RequestBody formBody = new FormBody.Builder()
                .add("from", "support@" + MAILGUN_DOMAIN)
                .add("to", email)
                .add("subject", "Verify Your Email")
                .add("text", "Click this link to verify your email: " + verificationLink)
                .add("html", "<p>Click this link to verify your email: <a href=\"" + verificationLink + "\">" + verificationLink + "</a></p>")
                .add("o:tag", "verification-email")
                .add("o:tracking", "yes")
                .add("o:tracking-clicks", "htmlonly")
                .add("o:tracking-opens", "yes")
                .build();

        Request request = new Request.Builder()
                .url("https://api.mailgun.net/v3/" + MAILGUN_DOMAIN + "/messages")
                .post(formBody)
                .addHeader("Authorization", Credentials.basic("api", MAILGUN_API_KEY))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String responseBody = response.body() != null ? response.body().string() : "No Response Body";
                context.getLogger().log("Failed to send email. Response: " + responseBody);
                throw new Exception("Failed to send email. Status: " + response.code());
            }
            context.getLogger().log("Email sent successfully. Response: " + response.body().string());
        } catch (Exception e) {
            context.getLogger().log("Error sending email: " + e.getMessage());
            throw new RuntimeException("Error sending email: " + e.getMessage(), e);
        }
    }
}
