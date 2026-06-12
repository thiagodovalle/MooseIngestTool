package com.MooseIngest.core;

import java.io.FileInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Properties;

/**
 * Handles asynchronous logging of ingest metadata to a centralized Notion database.
 */
public class NotionLogger {

    /**
     * Sanitizes input strings to ensure valid formatting within the JSON payload.
     */
    private static String escapeStr(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Dispatches metadata payload to the designated Notion database via HTTP POST.
     */
    public static void logToNotion(String batchSession, String fileName, String dateTime, String sourcePath, String destinations, 
                                   long fileSize, String md5, double durationSec, 
                                   String codec, String res, String fps) {
        
        String secret = "";
        String dbId = "";

        // Load API credentials from local configuration
        try (FileInputStream configInput = new FileInputStream("config.properties")) {
            Properties prop = new Properties();
            prop.load(configInput);
            secret = prop.getProperty("notion.api.secret", "");
            dbId = prop.getProperty("notion.database.id", "");
        } catch (Exception e) {
            System.err.println("Configuration Error: Unable to read keys from config.properties");
            return;
        }

        if (secret.isEmpty() || dbId.isEmpty()) {
            System.err.println("Configuration Error: Notion credentials undefined.");
            return;
        }

        // Construct JSON payload matching database schema constraints
        String jsonBody = "{"
            + "\"parent\": { \"database_id\": \"" + dbId + "\" },"
            + "\"properties\": {"
            + "  \"Filename\": { \"title\": [ { \"text\": { \"content\": \"" + escapeStr(fileName) + "\" } } ] },"
            + "  \"Batch Session\": { \"rich_text\": [ { \"text\": { \"content\": \"" + escapeStr(batchSession) + "\" } } ] },"
            + "  \"Date/Time\": { \"rich_text\": [ { \"text\": { \"content\": \"" + escapeStr(dateTime) + "\" } } ] },"
            + "  \"Source Path\": { \"rich_text\": [ { \"text\": { \"content\": \"" + escapeStr(sourcePath) + "\" } } ] },"
            + "  \"Destinations\": { \"rich_text\": [ { \"text\": { \"content\": \"" + escapeStr(destinations) + "\" } } ] },"
            + "  \"MD5 Checksum\": { \"rich_text\": [ { \"text\": { \"content\": \"" + escapeStr(md5) + "\" } } ] },"
            + "  \"Codec\": { \"rich_text\": [ { \"text\": { \"content\": \"" + escapeStr(codec) + "\" } } ] },"
            + "  \"Resolution\": { \"rich_text\": [ { \"text\": { \"content\": \"" + escapeStr(res) + "\" } } ] },"
            + "  \"Framerate\": { \"rich_text\": [ { \"text\": { \"content\": \"" + escapeStr(fps) + "\" } } ] },"
            + "  \"File Size\": { \"number\": " + fileSize + " },"
            + "  \"Duration\": { \"number\": " + durationSec + " }"
            + "}}";

        // Execute API request over HTTP/2 client
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.notion.com/v1/pages"))
                    .header("Authorization", "Bearer " + secret)
                    .header("Notion-Version", "2022-06-28")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                System.err.println("Notion API Failure: " + response.body());
            } else {
                System.out.println("Notion Transaction Success: Logged metadata for " + fileName);
            }
        } catch (Exception e) {
            System.err.println("Network Error: Failed to communicate with the Notion endpoint.");
            e.printStackTrace();
        }
    }
}