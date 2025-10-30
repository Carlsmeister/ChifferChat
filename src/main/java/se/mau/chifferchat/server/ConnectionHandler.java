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

            out.println("Welcome!");
            out.println("Please enter a nickname: ");
            String nickName = in.readLine();
            System.out.println(nickName + " connected");
            server.broadcastMessage(nickName + " joined the chat!", this);
            out.println("Write \"/help\" to see the list of commands!");
            String message;

            while ((message = in.readLine()) != null) {
                if (message.equals("help")) {
                    out.println(" \n Commands");
                    out.println("--------------------");
                    out.println(
                            "/nick - Change your nickname by writing /nick \"space\" and your new nickname.\n" +
                                    "/quit - Leave the chat"
                    );
                } else if (message.startsWith("/nick")) {
                    String[] messageParts = message.split(" ", 2);
                    if (messageParts.length == 2) {
                        server.broadcastMessage(nickName + " renamed themselves to " + messageParts[1] + "!", this);
                        System.out.println(nickName + " renamed themselves to " + messageParts[1] + "!");
                        nickName = messageParts[1];
                        out.println("Successfully changed nickname to: " + nickName + "!");
                    } else {
                        out.println("No nickname provided!");
                    }
                } else if (message.startsWith("/quit")) {
                    server.broadcastMessage(nickName + " left the chat!", this);
                    System.out.println(nickName + " left the chat!");

                    shutdown();
                } else {
                    server.broadcastMessage(nickName + ": " + message, this);
                }

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
