package com.MooseIngest.core;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MooseApp extends Application {

    private List<File> sourceDirs = new ArrayList<>();
    private List<File> destDirs = new ArrayList<>();
    private IngestTask currentTask;

    @Override
    public void start(Stage primaryStage) {
        
        ImageView logoView = new ImageView();
        try {
            Image logoImage = new Image(getClass().getResourceAsStream("/logo.png"));
            logoView.setImage(logoImage);
            logoView.setFitWidth(200);
            logoView.setPreserveRatio(true);
        } catch (Exception e) {}

        // --- SOURCES UI ---
        Label sourceLabel = new Label("Source Folders (Camera Cards):");
        ListView<String> sourceListView = new ListView<>();
        sourceListView.setPrefHeight(100);
        
        Button addSourceBtn = new Button("+ Add Source");
        Button clearSourcesBtn = new Button("Clear");
        HBox sourceBtns = new HBox(10, addSourceBtn, clearSourcesBtn);

        // --- DESTINATIONS UI ---
        Label destLabel = new Label("Destination Folders (Local/Server):");
        ListView<String> destListView = new ListView<>();
        destListView.setPrefHeight(100);
        
        Button addDestBtn = new Button("+ Add Destination");
        Button clearDestsBtn = new Button("Clear");
        HBox destBtns = new HBox(10, addDestBtn, clearDestsBtn);

        // --- TOGGLES ---
        CheckBox copyAllCheckbox = new CheckBox("Clone Entire Card (Include all XMLs, sidecars, and non-video files)");
        copyAllCheckbox.setSelected(true); 
        copyAllCheckbox.setStyle("-fx-text-fill: #a0a0a5; -fx-font-size: 13px;");

        // NEW: Google Drive Toggle
        CheckBox cloudUploadCheckbox = new CheckBox("☁️ Stream Directly to Google Drive (Bypasses Mac Storage)");
        cloudUploadCheckbox.setStyle("-fx-text-fill: #a0a0a5; -fx-font-size: 13px; -fx-font-weight: bold;");

        // --- CONTROLS ---
        Button ingestButton = new Button("Start Batch Ingest");
        ingestButton.setMaxWidth(Double.MAX_VALUE);
        ingestButton.setDisable(true);
        ingestButton.getStyleClass().add("action-button"); 
        
        Button pauseButton = new Button("⏸ Pause");
        pauseButton.setVisible(false); 
        pauseButton.setManaged(false); 
        pauseButton.setPrefWidth(100);
        
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(20);
        HBox.setHgrow(progressBar, Priority.ALWAYS); 
        
        Label statusLabel = new Label("Ready to copy...");
        statusLabel.getStyleClass().add("status-label");

        DirectoryChooser dirChooser = new DirectoryChooser();

        // --- ACTIONS ---
        addSourceBtn.setOnAction(e -> {
            dirChooser.setTitle("Select Source Folder");
            File dir = dirChooser.showDialog(primaryStage);
            if (dir != null && !sourceDirs.contains(dir)) {
                sourceDirs.add(dir);
                sourceListView.getItems().add(dir.getAbsolutePath());
                checkReadyToIngest(ingestButton, cloudUploadCheckbox);
            }
        });

        clearSourcesBtn.setOnAction(e -> {
            sourceDirs.clear();
            sourceListView.getItems().clear();
            checkReadyToIngest(ingestButton, cloudUploadCheckbox);
        });

        addDestBtn.setOnAction(e -> {
            dirChooser.setTitle("Select Local Destination");
            File dir = dirChooser.showDialog(primaryStage);
            if (dir != null && !destDirs.contains(dir)) {
                destDirs.add(dir);
                destListView.getItems().add(dir.getAbsolutePath());
                checkReadyToIngest(ingestButton, cloudUploadCheckbox);
            }
        });

        clearDestsBtn.setOnAction(e -> {
            destDirs.clear();
            destListView.getItems().clear();
            checkReadyToIngest(ingestButton, cloudUploadCheckbox);
        });

        // We check the logic whenever the cloud box is clicked too
        cloudUploadCheckbox.setOnAction(e -> checkReadyToIngest(ingestButton, cloudUploadCheckbox));

        ingestButton.setOnAction(e -> {
            addSourceBtn.setDisable(true);
            clearSourcesBtn.setDisable(true);
            addDestBtn.setDisable(true);
            clearDestsBtn.setDisable(true);
            copyAllCheckbox.setDisable(true);
            cloudUploadCheckbox.setDisable(true);
            ingestButton.setDisable(true);
            
            pauseButton.setVisible(true); 
            pauseButton.setManaged(true);
            pauseButton.setDisable(false);
            
            boolean copyAll = copyAllCheckbox.isSelected();
            boolean uploadToCloud = cloudUploadCheckbox.isSelected(); // Grab the cloud decision
            
            currentTask = new IngestTask(sourceDirs, destDirs, copyAll, uploadToCloud);

            progressBar.progressProperty().bind(currentTask.progressProperty());
            statusLabel.textProperty().bind(currentTask.messageProperty());

            currentTask.setOnSucceeded(event -> {
                pauseButton.setVisible(false);
                pauseButton.setManaged(false);
            });
            currentTask.setOnFailed(event -> {
                pauseButton.setVisible(false);
                pauseButton.setManaged(false);
            });

            Thread backgroundThread = new Thread(currentTask);
            backgroundThread.setDaemon(true);
            backgroundThread.start();
        });

        pauseButton.setOnAction(e -> {
            if (currentTask != null) {
                currentTask.togglePause();
                if (pauseButton.getText().equals("⏸ Pause")) {
                    pauseButton.setText("▶ Resume");
                    pauseButton.setStyle("-fx-background-color: #27ae60;");
                } else {
                    pauseButton.setText("⏸ Pause");
                    pauseButton.setStyle(""); 
                }
            }
        });

        HBox progressRow = new HBox(10);
        progressRow.setAlignment(Pos.CENTER_LEFT);
        progressRow.getChildren().addAll(progressBar, pauseButton);

        VBox layout = new VBox(12);
        layout.setPadding(new Insets(30));
        layout.setAlignment(Pos.TOP_CENTER);
        
        layout.getChildren().addAll(
            logoView, 
            sourceLabel, sourceListView, sourceBtns,
            destLabel, destListView, destBtns,
            copyAllCheckbox, 
            cloudUploadCheckbox, // Appears right below copy all
            ingestButton, progressRow, statusLabel
        );

        Scene scene = new Scene(layout, 700, 750); 
        try { scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm()); } catch (Exception e) {}

        primaryStage.setTitle("Moose Ingest Tool");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    // UPDATED logic: We allow ingest if they have a source AND (either a local dest OR the cloud box is checked)
    private void checkReadyToIngest(Button ingestBtn, CheckBox cloudBox) {
        if (!sourceDirs.isEmpty() && (!destDirs.isEmpty() || cloudBox.isSelected())) {
            ingestBtn.setDisable(false);
        } else {
            ingestBtn.setDisable(true);
        }
    }
}