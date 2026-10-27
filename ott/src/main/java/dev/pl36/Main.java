package dev.pl36;

import dev.pl36.routing.RoutingTable;
import dev.pl36.scenario.Scenario;
import dev.pl36.scenario.ScenarioParser;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.HashMap;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    private static final String NODE_ID_ENV = "OTT_NODE_ID";

    private static final String HELP = """
            usage: OTT_NODE_ID=<node id> program_name <config_file_path>
            """;

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException(HELP);
        }

        int selfId = readNodeIdFromEnvironment();
        Path scenarioPath = Path.of(args[0]);

        try {
            Scenario scenario = ScenarioParser.parse(scenarioPath);
            RoutingTable routingTable = RoutingTable.from(scenario, selfId);
            System.out.println(routingTable);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

//        try {
//            final var welcomeSocket = new ServerSocket(8080);
//            Thread.ofVirtual().name("worker-1").start(() -> {
//                try {
//                    TPCServer.start(welcomeSocket);
//                } catch (IOException e) {
//                    throw new RuntimeException(e);
//                }
//            });
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
//
//        try {
//            TCPClient.execute();
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        }
    }

    private static int readNodeIdFromEnvironment() {
        String rawNodeId = System.getenv(NODE_ID_ENV);
        if (rawNodeId == null || rawNodeId.isBlank()) {
            throw new IllegalStateException(NODE_ID_ENV + " is not set");
        }

        try {
            return Integer.parseInt(rawNodeId.trim());
        } catch (NumberFormatException cause) {
            throw new IllegalStateException(
                    NODE_ID_ENV + " must be an integer, got: " + rawNodeId, cause);
        }
    }

    class TCPClient {
        public static void execute() throws Exception
        {
            String sentence;
            String modifiedSentence;
            BufferedReader inFromUser = new BufferedReader(
                    new InputStreamReader(System.in));
            Socket clientSocket = new Socket("127.0.0.1", 8080);
            DataOutputStream outToServer = new DataOutputStream(
                    clientSocket.getOutputStream());
            BufferedReader inFromServer =
                    new BufferedReader(new InputStreamReader(
                            clientSocket.getInputStream()));
            sentence = "hello";
            outToServer.writeBytes(sentence + '\n');
            modifiedSentence = inFromServer.readLine();
            System.out.println("FROM SERVER: " + modifiedSentence);
            clientSocket.close();
        }
    }

    public class TPCServer {
        public static void start(ServerSocket welcomeSocket) throws IOException {
        String clientSentence;
        String capitalizedSentence;
        while(true) {
            Socket connectionSocket = welcomeSocket.accept();

            BufferedReader inFromClient =
                    new BufferedReader(new InputStreamReader(
                            connectionSocket.getInputStream()));

            DataOutputStream outToClient =
                    new DataOutputStream(
                            connectionSocket.getOutputStream());

            clientSentence = inFromClient.readLine();

            capitalizedSentence =
                    clientSentence.toUpperCase() + '\n';

            outToClient.writeBytes(capitalizedSentence);
        }
        }
    }

//    public class Table {
//        HashMap<Inet4Address, >
//    }

    public class Node {
        int id;
        Inet4Address ip;
        int port;
    }
}
