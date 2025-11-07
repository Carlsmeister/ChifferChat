package se.mau.chifferchat.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import se.mau.chifferchat.client.Client;
import se.mau.chifferchat.crypto.CryptoKeyGenerator;
import se.mau.chifferchat.crypto.Encryption;

import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.PublicKey;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;

public class ChatController {

    private final DateTimeFormatter clockFormat = DateTimeFormatter.ofPattern("HH:mm");
    private final DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm");
    public ImageView userAvatar;
    public Label usernameLabel;
    private Client client;
    @FXML
    private Label clockLabel;
    @FXML
    private Label connectionStatus;
    @FXML
    private ListView<ChatMessage> chatList;
    @FXML
    private ListView<String> userList;
    @FXML
    public HBox userInfo;
    @FXML
    private TextField messageField;
    @FXML
    private Button sendButton;
    @FXML
    private Button settingsButton;
    @FXML
    private Button logoutButton;


    @FXML
    public void initialize() {
        this.client = HelloApplication.getClient();
        client.setController(this);

        String me = client.getUsername();
        if (me != null && !me.isBlank()) {
            usernameLabel.setText(me + " (You)");
        }

        Platform.runLater(() -> messageField.requestFocus());

        startClock();

        loadIcons();

        userList.getItems().addAll("Carl", "Alex", "Maya", "Evelyn");
        styleUserList();

        chatList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(ChatMessage item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label msg = new Label(item.text());
                msg.getStyleClass().add("chat-message");
                Label ts = new Label(item.time().format(timeFormat));
                ts.getStyleClass().add("text-muted");
                ts.setMinWidth(40);

                Region leftSpacer = new Region();
                Region rightSpacer = new Region();
                HBox.setHgrow(leftSpacer, Priority.ALWAYS);
                HBox.setHgrow(rightSpacer, Priority.ALWAYS);
                HBox hbox;
                switch (item.type()) {
                    case SENT -> {
                        msg.getStyleClass().add("sent");
                        hbox = new HBox(leftSpacer, ts, msg);
                        hbox.setSpacing(8);
                    }
                    case RECEIVED -> {
                        msg.getStyleClass().add("received");
                        hbox = new HBox(msg, ts, rightSpacer);
                        hbox.setSpacing(8);
                    }
                    default -> { // SYSTEM
                        msg.getStyleClass().add("system-message");
                        hbox = new HBox(leftSpacer, msg, rightSpacer);
                        hbox.setSpacing(8);
                    }
                }
                setGraphic(hbox);
                setText(null);
            }
        });

        sendButton.setOnAction(e -> sendMessage());
        messageField.setOnAction(e -> sendMessage());

        setConnectionStatus(client.getOut() != null);

        Stage stage = SceneManager.getStage();

        stage.setOnCloseRequest(event -> {
            event.consume();
            client.disconnect();
            Platform.exit();
            System.exit(0);
        });
    }

    private void styleUserList() {
        userList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText("\uD83D\uDC64 " + item);
            }
        });
    }

    private void startClock() {
        Timer timer = new Timer(true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> clockLabel.setText(LocalTime.now().format(clockFormat)));
            }
        }, 0, 1000);
    }

    private void sendMessage() {
        String message = messageField.getText().trim();
        if (message.isEmpty()) return;

        String targetUser = getSelectedUser();

        if (targetUser != null) {
            PublicKey receiverPublicKey = client.getPublicKeyForUser(targetUser);

            if (receiverPublicKey == null) {
                client.sendMessage("/getkey " + targetUser);

                waitForPublicKey(targetUser, 1000) // Wait up to 1 second
                        .thenAcceptAsync(pubKey -> {
                            if (pubKey != null) {
                                encryptAndSendMessage(message, targetUser, pubKey);
                            } else {
                                appendSystemMessage("Cannot send: No public key for " + targetUser);
                            }
                        }, Platform::runLater)
                        .exceptionally(ex -> {
                            appendSystemMessage("Error fetching key: " + ex.getMessage());
                            return null;
                        });
            } else {
                encryptAndSendMessage(message, targetUser, receiverPublicKey);
            }

            String label = "You: " + message;
            chatList.getItems().add(new ChatMessage(label, LocalDateTime.now(), MessageType.SENT));
            autoScroll();
            messageField.clear();

        } else {
            client.sendMessage(message);

            String label = "You: " + message;
            chatList.getItems().add(new ChatMessage(label, LocalDateTime.now(), MessageType.SENT));
            autoScroll();
            messageField.clear();
        }
    }

    private CompletableFuture<PublicKey> waitForPublicKey(String username, long maxWaitMs) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            int pollInterval = 50;

            while (System.currentTimeMillis() - startTime < maxWaitMs) {
                PublicKey key = client.getPublicKeyForUser(username);
                if (key != null) {
                    return key;
                }

                try {
                    Thread.sleep(pollInterval);
                    pollInterval = Math.min(pollInterval + 25, 200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return null;
        });
    }

    private void encryptAndSendMessage(String message, String targetUser, PublicKey receiverPublicKey) {
        try {
            SecretKey aesKey = CryptoKeyGenerator.generateAESKey();
            GCMParameterSpec iv = CryptoKeyGenerator.generateIv();

            String encryptedMessage = Encryption.encryptAES(message, aesKey, iv);

            String encryptedAESKey = Encryption.encryptAESKeyRSA(aesKey, receiverPublicKey);

            String ivBase64 = Base64.getEncoder().encodeToString(iv.getIV());

            String fullMessage = encryptedAESKey + ":" + ivBase64 + ":" + encryptedMessage;

            client.sendMessage(fullMessage);

        } catch (Exception e) {
            System.err.println("Encryption failed: " + e.getMessage());
            Platform.runLater(() -> appendSystemMessage("Failed to encrypt message"));
        }
    }

    private String getSelectedUser() {
        String selected = userList.getSelectionModel().getSelectedItem();
        if (selected != null && !selected.equals(client.getUsername())) {
            return selected;
        }
        return null;
    }

    public void receiveMessage(String message) {

        MessageType type = MessageType.RECEIVED;
        if (message.endsWith(" joined the chat!") || message.endsWith(" left the chat!")) {
            type = MessageType.SYSTEM;
            updateUserList(message);
        } else if (!message.contains(": ")) {
            type = MessageType.SYSTEM;
        }
        chatList.getItems().add(new ChatMessage(message, LocalDateTime.now(), type));
        autoScroll();
        flashNewMessage();
    }

    private void flashNewMessage() {
        String prev = chatList.getStyle();
        chatList.setStyle("-fx-background-color: #3a3d43;");
        PauseTransition pt = new PauseTransition(Duration.millis(120));
        pt.setOnFinished(e -> chatList.setStyle(prev));
        pt.play();
    }

    private void updateUserList(String message) {
        String name = message.replace(" joined the chat!", "").replace(" left the chat!", "");
        if (message.contains(" joined the chat!")) {
            if (!userList.getItems().contains(name)) userList.getItems().add(name);
        } else if (message.contains(" left the chat!")) {
            userList.getItems().remove(name);
        }
    }

    private void appendSystemMessage(String text) {
        chatList.getItems().add(new ChatMessage(text, LocalDateTime.now(), MessageType.SYSTEM));
        autoScroll();
    }

    private void autoScroll() {
        Platform.runLater(() -> chatList.scrollTo(chatList.getItems().size() - 1));
    }

    public void setConnectionStatus(boolean online) {
        if (connectionStatus == null) return;
        connectionStatus.setText(online ? "🟢" : "🔴");
        connectionStatus.getStyleClass().removeAll("status-online", "status-offline");
        connectionStatus.getStyleClass().add(online ? "status-online" : "status-offline");
    }

    @FXML
    private void onSettings() {
        appendSystemMessage("Settings are not implemented yet.");
    }

    @FXML
    private void onLogout() {
        if (client != null) client.sendMessage("/quit");
        appendSystemMessage("You left the chat.");
        logoutToLogin();
    }

    private void logoutToLogin() {
        try {
            HelloApplication.resetClient();
            SceneManager.switchScene("/se/mau/chifferchat/login-view.fxml", "ChifferChat – Login");
        } catch (Exception ignored) {
        }
    }

    private void loadIcons() {
        FontIcon settingsIcon = new FontIcon("fas-cog");
        settingsIcon.setIconSize(20);
        settingsIcon.setIconColor(javafx.scene.paint.Color.DARKORANGE);
        userInfo.getChildren().add(3, settingsIcon);
        settingsIcon.setOnMouseClicked(e -> onSettings());
    }

    private enum MessageType {SENT, RECEIVED, SYSTEM}

    private record ChatMessage(String text, LocalDateTime time, MessageType type) {
    }
}
