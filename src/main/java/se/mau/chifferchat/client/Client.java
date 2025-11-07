package se.mau.chifferchat.client;

import javafx.application.Platform;
import se.mau.chifferchat.crypto.CryptoKeyGenerator;
import se.mau.chifferchat.crypto.Decryption;
import se.mau.chifferchat.ui.ChatController;

import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class Client implements Runnable {
    private Socket client;
    private BufferedReader in;
    private PrintWriter out;
    private volatile boolean listening = true;
    private volatile boolean loggedIn = false;

    private volatile ChatController controller;
    private volatile String username;

    private final Map<String, PublicKey> publicKeyCache = new HashMap<>();
    private PublicKey publicKey;
    private PrivateKey privateKey;

    public Client() {
    }

    public void connect() {
        Thread clientThread = new Thread(this);
        clientThread.setDaemon(true);
        clientThread.setName("Client Thread");
        clientThread.start();
    }

    @Override
    public void run() {
        try {
            client = new Socket("localhost", 5090);
            out = new PrintWriter(client.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(client.getInputStream()));

            try {
                KeyPair keyPair = CryptoKeyGenerator.generateRSAKeyPair();
                this.publicKey = keyPair.getPublic();
                this.privateKey = keyPair.getPrivate();
            } catch (NoSuchAlgorithmException e) {
                System.err.println("Failed to generate RSA keypair: " + e.getMessage());
                shutdown();
                return;
            }

            if (controller != null) {
                Platform.runLater(() -> controller.setConnectionStatus(true));
            }

            int waitCount = 0;
            while (!loggedIn && waitCount < 100) {  // 5 second timeout
                Thread.sleep(50);
                waitCount++;
            }

            if (!loggedIn) {
                System.err.println("Login timeout - no username provided");
                shutdown();
                return;
            }

            if (username != null && out != null) {
                out.println(username);
                System.out.println("Sent username: " + username);
            } else {
                System.err.println("Username is null, cannot proceed");
                shutdown();
                return;
            }

            String publicKeyB64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
            out.println("/pubkey " + publicKeyB64);
            System.out.println("Sent public key");

            String line;


            while (listening && (line = in.readLine()) != null) {

                if (line.startsWith("/key ")) {
                    try {
                        String[] parts = line.split(" ", 3);
                        String targetUser = parts[1];
                        byte[] keyBytes = Base64.getDecoder().decode(parts[2]);
                        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
                        KeyFactory kf = KeyFactory.getInstance("RSA");
                        PublicKey targetPubKey = kf.generatePublic(spec);
                        publicKeyCache.put(targetUser, targetPubKey);
                        System.out.println("Stored public key for user: " + targetUser);
                    } catch (Exception e) {
                        System.err.println("Failed to parse public key: " + e.getMessage());
                    }
                    continue;
                }

                final String decryptedMessage;

                if (line.startsWith("Welcome ") ||
                        line.endsWith(" joined the chat!") ||
                        line.endsWith(" left the chat!")) {

                    decryptedMessage = line;
                }
                // Check if it's a user message with "Username: " prefix
                else if (line.contains(": ")) {
                    int colonIndex = line.indexOf(": ");
                    String senderName = line.substring(0, colonIndex);
                    String encryptedPart = line.substring(colonIndex + 2);

                    try {
                        if (encryptedPart.contains(":") && encryptedPart.split(":").length == 3) {
                            String[] parts = encryptedPart.split(":", 3);
                            String encryptedAESKey = parts[0];
                            String ivBase64 = parts[1];
                            String encryptedMessage = parts[2];

                            SecretKey aesKey = Decryption.decryptAESKeyRSA(encryptedAESKey, privateKey);

                            byte[] ivBytes = Base64.getDecoder().decode(ivBase64);
                            GCMParameterSpec iv = new GCMParameterSpec(128, ivBytes);

                            String plainMessage = Decryption.decryptAES(encryptedMessage, aesKey, iv);

                            decryptedMessage = senderName + ": " + plainMessage;
                        } else {
                            // Fallback to simple RSA decryption
                            String plainMessage = Decryption.decryptRSA(encryptedPart, privateKey);
                            decryptedMessage = senderName + ": " + plainMessage;
                        }
                    } catch (Exception e) {
                        System.err.println("Failed to decrypt message: " + e.getMessage());
                        System.err.println("Message was: " + line.substring(0, Math.min(50, line.length())) + "...");
                        continue;
                    }
                } else {
                    decryptedMessage = line;
                }

                if ("/quit".equals(decryptedMessage)) {
                    break;
                }
                if (controller != null) {
                    Platform.runLater(() -> controller.receiveMessage(decryptedMessage));
                }
            }

        } catch (IOException | InterruptedException e) {
            System.out.println("Client connection lost.");
            e.printStackTrace();
        } finally {
            shutdown();
        }
    }

    private void shutdown() {
        if (loggedIn && username != null && !username.isBlank() && out != null) {
            out.println("/quit");
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        listening = false;
        loggedIn = false;
        try {
            if (client != null && !client.isClosed()) client.close();
            if (in != null) in.close();
            if (out != null) out.close();
        } catch (IOException e) {
            // ignore
        } finally {
            ChatController ctrl = controller;
            if (ctrl != null) {
                Platform.runLater(() -> ctrl.setConnectionStatus(false));
            }
        }
    }

    public void disconnect() {
        shutdown();
    }

    public void setLoggedIn(boolean loggedIn) {
        this.loggedIn = loggedIn;
    }

    public void setController(ChatController controller) {
        this.controller = controller;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void sendMessage(String message) {
        if (out != null) {
            out.println(message);
        }
    }

    public PrintWriter getOut() {
        return out;
    }

    public BufferedReader getIn() {
        return in;
    }

    public PublicKey getPublicKeyForUser(String username) {
        return publicKeyCache.get(username);
    }
}
