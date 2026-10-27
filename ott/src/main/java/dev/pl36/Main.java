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
    private static final String HELP = """
            usage: program_name <node_id> <config_file_path>
            """;

    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException(HELP);
        }

        int selfId = parseNodeId(args[0]);
        Path scenarioPath = Path.of(args[1]);

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

    private static int parseNodeId(String rawNodeId) {
        try {
            return Integer.parseInt(rawNodeId);
        } catch (NumberFormatException cause) {
            throw new IllegalArgumentException(
                    "node id must be an integer, got: " + rawNodeId, cause);
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
