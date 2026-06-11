package com.MooseIngest.core;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.List;

public class GoogleDriveManager {

    private static final String APPLICATION_NAME = "Moose Ingest Tool";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH = "tokens"; 
    private static final List<String> SCOPES = Collections.singletonList(DriveScopes.DRIVE);
    
    private Drive driveService;

    public GoogleDriveManager() throws Exception {
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        
        InputStream in = GoogleDriveManager.class.getResourceAsStream("/credentials.json");
        if (in == null) throw new Exception("Cannot find credentials.json in resources folder!");
        
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
                .setAccessType("offline")
                .build();
                
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        Credential credential = new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");

        this.driveService = new Drive.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    private String extractFolderId(String input) {
        if (input == null || input.trim().isEmpty()) return "root"; 
        String id = input.trim();
        
        if (id.contains("folders/")) {
            String[] parts = id.split("folders/");
            id = parts[1];
            if (id.contains("?")) id = id.split("\\?")[0];
        } else if (id.contains("id=")) {
            String[] parts = id.split("id=");
            id = parts[1];
            if (id.contains("&")) id = id.split("&")[0];
        }
        if (id.endsWith("/")) id = id.substring(0, id.length() - 1);
        return id;
    }

    private String getOrCreateFolder(String folderName, String parentId) throws Exception {
        String query = "'" + parentId + "' in parents and mimeType='application/vnd.google-apps.folder' and name='" + folderName + "' and trashed=false";
        
        // --- UPDATED FOR SHARED DRIVES ---
        Drive.Files.List request = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setCorpora("allDrives") // Searches personal AND shared team drives
                .setSupportsAllDrives(true) 
                .setIncludeItemsFromAllDrives(true)
                .setFields("files(id, name)");
                
        FileList result = request.execute();

        if (result.getFiles() != null && !result.getFiles().isEmpty()) {
            return result.getFiles().get(0).getId(); 
        } else {
            File folderMetadata = new File();
            folderMetadata.setName(folderName);
            folderMetadata.setMimeType("application/vnd.google-apps.folder"); 
            folderMetadata.setParents(Collections.singletonList(parentId)); 

            // --- UPDATED FOR SHARED DRIVES ---
            File folder = driveService.files().create(folderMetadata)
                    .setSupportsAllDrives(true) // Allows directory creation on corporate shared drives
                    .setFields("id")
                    .execute();
            return folder.getId();
        }
    }

    public String uploadFile(java.io.File localFile, String cloudFolderName, String targetUrlOrId) throws Exception {
        String resolvedParentId = extractFolderId(targetUrlOrId);
        String cardFolderId = getOrCreateFolder(cloudFolderName, resolvedParentId);

        File fileMetadata = new File();
        fileMetadata.setName(localFile.getName());
        fileMetadata.setParents(Collections.singletonList(cardFolderId)); 

        FileContent mediaContent = new FileContent("application/octet-stream", localFile);

        // --- UPDATED FOR SHARED DRIVES ---
        Drive.Files.Create request = driveService.files().create(fileMetadata, mediaContent)
                .setSupportsAllDrives(true); // Allows files to nest inside Shared Drives
                
        request.setFields("id");
        
        MediaHttpUploader uploader = request.getMediaHttpUploader();
        uploader.setDirectUploadEnabled(false); 
        uploader.setChunkSize(MediaHttpUploader.MINIMUM_CHUNK_SIZE * 32); 

        File uploadedFile = request.execute();
        System.out.println("🚀 GOOGLE DRIVE API SUCCESS!");
        System.out.println("File Name: " + localFile.getName());
        System.out.println("Uploaded File ID: " + uploadedFile.getId());
        System.out.println("Parent Folder ID it was sent to: " + fileMetadata.getParents());
        return uploadedFile.getId();
    }
}