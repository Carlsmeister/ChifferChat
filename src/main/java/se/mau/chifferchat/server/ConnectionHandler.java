package se.mau.chifferchat.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class ConnectionHandler implements Runnable {

    private final Server server;
    private final Socket client;
    private BufferedReader in;
    private PrintWriter out;

    public ConnectionHandler(Server server, Socket client) {
        this.server = server;
        this.client = client;
    }


    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            out = new PrintWriter(client.getOutputStream(), true);

            String clientUsername = in.readLine();

            out.println("Welcome " + clientUsername + "!");
            System.out.println(clientUsername + " connected");
            server.broadcastMessage(clientUsername + " joined the chat!", this);
            String message;

            while ((message = in.readLine()) != null) {

                if (message.startsWith("/pubkey ")) {
                    String keyB64 = message.substring(8).trim();
                    server.addPublicKey(clientUsername, keyB64);
                    continue;
                }
                if (message.startsWith("/getkey ")) {
                    String target = message.substring(8).trim();
                    String key = server.getPublicKey(target);
                    if (key != null) out.println("/key " + target + " " + key);
                    else out.println("/error No key for " + target);
                    continue;
                }
                if (message.startsWith("/quit")) {
                    server.broadcastMessage(clientUsername + " left the chat!", this);
                    System.out.println(clientUsername + " left the chat!");
                    shutdown();
                    break;
                }

                server.broadcastMessage(clientUsername + ": " + message, this);
            }
        } catch (IOException e) {
            shutdown();
        }
    }

    public void sendMessage(String message) {
        out.println(message);
    }

    public void shutdown() {
        try {
            if (!client.isClosed()) client.close();
            if (in != null) in.close();
            if (out != null) out.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            server.removeConnection(this);
        }
    }
}
