package com.MooseIngest.core;

import javafx.concurrent.Task;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class IngestTask extends Task<Boolean> {

    private List<File> sourceDirs;
    private List<File> destDirs;
    private boolean copyAllFiles;
    private boolean uploadToCloud; 
    private volatile boolean isPaused = false;
    private String sessionLogFileName;

    private class MediaItem {
        File sourceRoot;
        File file;
        MediaItem(File root, File f) { 
            this.sourceRoot = root; 
            this.file = f; 
        }
    }

    public IngestTask(List<File> sourceDirs, List<File> destDirs, boolean copyAllFiles, boolean uploadToCloud) {
        this.sourceDirs = sourceDirs;
        this.destDirs = destDirs;
        this.copyAllFiles = copyAllFiles;
        this.uploadToCloud = uploadToCloud;
        DateTimeFormatter fileFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
        this.sessionLogFileName = "ingest_log_" + fileFormatter.format(LocalDateTime.now()) + ".csv";
    }

    public void togglePause() { 
        this.isPaused = !this.isPaused; 
    }

    @Override
    protected Boolean call() throws Exception {
        
        GoogleDriveManager driveManager = null;
        
        // --- CLEANED UP AUTHENTICATION BLOCK ---
        if (uploadToCloud) {
            updateMessage("Authenticating with Google Drive...");
            try {
                driveManager = new GoogleDriveManager();
            } catch (Exception e) {
                System.err.println("Google Auth Failed!");
                e.printStackTrace(); 
                updateMessage("❌ ERROR: Google Drive Authentication Failed.");
                return false;
            }
        }

        if (isCancelled()) return false;

        updateMessage("Scanning files and folder structures...");
        List<MediaItem> allMedia = new ArrayList<>();
        long totalBatchBytes = 0;

        for (File src : sourceDirs) {
            if (isCancelled()) return false;
            List<File> files = getFilesToCopy(src);
            for (File f : files) {
                allMedia.add(new MediaItem(src, f));
                if (!f.isDirectory()) {
                    totalBatchBytes += f.length();
                }
            }
        }

        if (allMedia.isEmpty()) {
            updateMessage("No files or folders found to copy!");
            return false;
        }

        long overallCopiedBytes = 0;
        long batchStartTime = System.currentTimeMillis();

        for (int i = 0; i < allMedia.size(); i++) {
            if (isCancelled()) return handleCancellation();

            MediaItem item = allMedia.get(i);
            File currentFile = item.file;
            
            Path relativePath = item.sourceRoot.toPath().relativize(currentFile.toPath());
            String cardFolderName = item.sourceRoot.getName();
            if (cardFolderName == null || cardFolderName.isEmpty()) cardFolderName = "Camera_Card"; 

            if (currentFile.isDirectory()) {
                for (File destRoot : destDirs) {
                    File destDir = destRoot.toPath().resolve(cardFolderName).resolve(relativePath).toFile();
                    destDir.mkdirs(); 
                }
                continue; 
            }

            List<FileOutputStream> activeStreams = new ArrayList<>();
            List<File> allDestFilesForThisItem = new ArrayList<>();
            boolean fileNeedsCopying = false;

            for (File destRoot : destDirs) {
                File destFile = destRoot.toPath().resolve(cardFolderName).resolve(relativePath).toFile();
                destFile.getParentFile().mkdirs();
                allDestFilesForThisItem.add(destFile);

                if (!destFile.exists() || destFile.length() != currentFile.length()) {
                    activeStreams.add(new FileOutputStream(destFile));
                    fileNeedsCopying = true;
                }
            }

            if (fileNeedsCopying && !activeStreams.isEmpty()) {
                MessageDigest md5 = MessageDigest.getInstance("MD5");
                long fileStartTime = System.currentTimeMillis();

                try (FileInputStream fis = new FileInputStream(currentFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        
                        if (isCancelled()) {
                            for (FileOutputStream fos : activeStreams) { fos.close(); }
                            for (File destFile : allDestFilesForThisItem) {
                                if (destFile.exists()) destFile.delete();
                            }
                            return false;
                        }

                        while (isPaused) {
                            if (isCancelled()) return handleCancellation();
                            updateMessage("⏸ PAUSED - " + currentFile.getName());
                            Thread.sleep(500);
                        }
                        
                        for (FileOutputStream fos : activeStreams) {
                            fos.write(buffer, 0, bytesRead);
                        }
                        md5.update(buffer, 0, bytesRead);
                        
                        overallCopiedBytes += bytesRead;
                        updateProgress(overallCopiedBytes, totalBatchBytes);
                        
                        long timeElapsed = System.currentTimeMillis() - batchStartTime;
                        if (timeElapsed > 0 && !isPaused) {
                            double speed = (double) overallCopiedBytes / timeElapsed; 
                            long remainingMs = (long) ((totalBatchBytes - overallCopiedBytes) / speed);
                            long mins = (remainingMs / 1000) / 60;
                            long secs = (remainingMs / 1000) % 60;
                            updateMessage(String.format("File %d of %d | %s | ETA: %02d:%02d", 
                                                        (i + 1), allMedia.size(), currentFile.getName(), mins, secs));
                        }
                    }
                } catch (Exception e) {
                    for (FileOutputStream fos : activeStreams) { fos.close(); }
                    System.err.println("Transfer interrupted!");
                    updateMessage("❌ ERROR: Drive Disconnected!");
                    return false;
                }

                for (FileOutputStream fos : activeStreams) { fos.close(); }

                if (isCancelled()) return handleCancellation();

                byte[] digestBytes = md5.digest();
                StringBuilder hexString = new StringBuilder();
                for (byte b : digestBytes) hexString.append(String.format("%02x", b));
                
                String[] metadata = {"-", "-", "-"};
                String fileNameLower = currentFile.getName().toLowerCase();
                boolean isVideo = fileNameLower.endsWith(".mov") || fileNameLower.endsWith(".mp4") || 
                                  fileNameLower.endsWith(".mxf") || fileNameLower.endsWith(".braw") || 
                                  fileNameLower.endsWith(".r3d");

                if (isVideo) {
                    updateMessage("Extracting metadata for: " + currentFile.getName());
                    metadata = extractMetadata(currentFile);
                }
                
                writeLogToAllDests(currentFile, allDestFilesForThisItem, hexString.toString(), (System.currentTimeMillis() - fileStartTime), metadata);
            }

            // --- ALL FILES GO TO GOOGLE DRIVE NOW ---
            if (uploadToCloud && driveManager != null) {
                if (isCancelled()) return handleCancellation();
                
                updateMessage(String.format("☁️ Uploading to Google Drive: %s (This may take a while)", currentFile.getName()));
                
                String sharedStudioFolderId = "root"; 
                Properties prop = new Properties();
                try (FileInputStream configInput = new FileInputStream("config.properties")) {
                    prop.load(configInput);
                    sharedStudioFolderId = prop.getProperty("google.drive.shared.folder.id", "root");
                } catch (Exception ex) {}
                
                try {
                    driveManager.uploadFile(currentFile, cardFolderName, sharedStudioFolderId);
                } catch (Exception e) {
                    System.err.println("Failed to upload " + currentFile.getName() + " to Google Drive.");
                    e.printStackTrace();
                }
            }
        }

        updateMessage("✅ Batch Ingest & Cloud Upload Complete!");
        return true;
    }
    
    private boolean handleCancellation() {
        updateMessage("🛑 Operation cancelled.");
        return false;
    }

    private List<File> getFilesToCopy(File dir) {
        List<File> fileList = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) { 
                    if (copyAllFiles) fileList.add(file);
                    fileList.addAll(getFilesToCopy(file)); 
                } else {
                    if (copyAllFiles) {
                        fileList.add(file);
                    } else {
                        String name = file.getName().toLowerCase();
                        if (name.endsWith(".mov") || name.endsWith(".mp4") || name.endsWith(".mxf") || name.endsWith(".braw") || name.endsWith(".r3d")) {
                            fileList.add(file);
                        }
                    }
                }
            }
        }
        return fileList;
    }

    private String[] extractMetadata(File file) {
        String[] meta = {"Unknown", "Unknown", "Unknown"};
        try {
            ProcessBuilder pb = new ProcessBuilder("/opt/homebrew/bin/mediainfo", "--Inform=Video;%Format%|%Width%x%Height%|%FrameRate%", file.getAbsolutePath());
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            process.waitFor();
            if (line != null && line.contains("|")) { meta = line.trim().split("\\|"); }
        } catch (Exception e) {}
        return meta;
    }

    private void writeLogToAllDests(File sourceFile, List<File> destFiles, String md5Hash, long durationMs, String[] metadata) {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String now = dtf.format(LocalDateTime.now());
        double durationSec = durationMs / 1000.0;
        String codec = metadata.length > 0 ? metadata[0] : "Unknown";
        String res = metadata.length > 1 ? metadata[1] : "Unknown";
        String fps = metadata.length > 2 ? metadata[2] : "Unknown";

        StringBuilder allDestsString = new StringBuilder();
        for (File f : destFiles) allDestsString.append(f.getAbsolutePath()).append("; ");

        // Compile local CSV logs only if destination folders are present
        if (!destFiles.isEmpty()) {
            for (File destRoot : destDirs) {
                File logFile = new File(destRoot, sessionLogFileName);
                boolean isNewFile = !logFile.exists();
                try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, true))) {
                    if (isNewFile) writer.println("Date/Time,Original File,Source Path,All Destinations,File Size (Bytes),MD5 Checksum,Copy Duration (Sec),Codec,Resolution,Framerate");
                    writer.printf("\"%s\",\"%s\",\"%s\",\"%s\",%d,\"%s\",%.1f,\"%s\",\"%s\",\"%s\"%n",
                            now, sourceFile.getName(), sourceFile.getAbsolutePath(), allDestsString.toString(), 
                            sourceFile.length(), md5Hash, durationSec, codec, res, fps);
                } catch (Exception e) {}
            }
        }

        // --- NEW BATCH ID GENERATION ---
        String batchId = sessionLogFileName.replace(".csv", "").replace("ingest_log_", "Ingest_");

        // Dispatch metadata synchronization payload to external Notion cloud database
        NotionLogger.logToNotion(
            batchId, // <--- Passing the Batch ID as the first parameter
            sourceFile.getName(), 
            now, 
            sourceFile.getAbsolutePath(), 
            allDestsString.length() == 0 ? "Cloud Only Ingest" : allDestsString.toString(), // <--- FIXED THIS LINE
            sourceFile.length(), 
            md5Hash, 
            durationSec, 
            codec, 
            res, 
            fps
        );
    }
}