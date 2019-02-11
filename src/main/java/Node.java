import conn.OLSRConn;
import org.apache.commons.lang3.tuple.Pair;
import parser.Parser;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

public class Node {
  private Map<Integer, Pair<String, Integer>> connectionList;
  private int nodeId;
  private int port;
  private int totalNumber;
  private OLSRConn conn;

  public Node(Map<Integer, Pair<String, Integer>> connectionList, int nodeId, int port, int totalNumber) {
    this.connectionList = connectionList;
    this.nodeId = nodeId;
    this.port = port;
    this.totalNumber = totalNumber;
  }

  private void init() throws IOException {
    this.conn = new OLSRConn(this.nodeId, this.port, totalNumber);
    this.conn.connect(this.connectionList);
  }

  private void start() throws InterruptedException {
    while (!conn.hasConverged()) {
      Thread.sleep(5000L);
    }

    Thread.sleep(5000L);
    System.out.println();
    System.out.println();
    System.out.println();
    System.out.println("============= network has converged =============");
    System.out.println("Node: " + nodeId);
    System.out.println("MPR (tree neighbors) set: " + conn.getMultiPointRelays());
    System.out.println("MPR selector set: " + conn.getMPRSelectors());
    System.out.println("routing table: " + conn.getRoutingTable());
    System.out.println("=================================================");
    System.out.println();
    System.out.println();
    System.out.println();

//    for (int i = 0; i < 5; i++) {
//      Thread.sleep(5000L);
//      conn.broadcast("Node " + nodeId + " send a broadcast msg");
//      Thread.sleep(5000L);
//    }

    BufferedReader br = null;
    try {
      br = new BufferedReader(new InputStreamReader(System.in));
      while (true) {
        System.out.print("Enter something:\n");
        String input = br.readLine();
        if (input.startsWith("mpr")) {
          System.out.println();
          System.out.println("\n=================================================");
          System.out.printf("Node %d's tree neighbors set: %s\n",
            nodeId, conn.getMultiPointRelays());
          System.out.println("=================================================");
          System.out.println();
          continue;
        }

        if ("q".equals(input)) {
          System.out.println("Exit!");
          System.exit(0);
        }
        System.out.println("Broadcast message: " + input);
        conn.broadcast(input);
      }
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      if (br != null) {
        try {
          br.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

  @Override
  public String toString() {
    return "Node[" + nodeId + ":" + port + ']';
  }

  public static void main(String[] args) throws FileNotFoundException, InterruptedException, UnknownHostException {
    Parser parser = new Parser();
    String hostName = InetAddress.getLocalHost().getHostName();
    parser.parseFile(args[0], hostName);
    int totalNumber = parser.getTotalNumber();
    List<Parser.HostInfo> hostInfos = parser.getHostInfos();
    for (Parser.HostInfo hostInfo : hostInfos) {
      try {
        Node node = new Node(
          hostInfo.neighbors,
          hostInfo.nodeId,
          hostInfo.host.getRight(),
          totalNumber
        );
        System.out.printf("Start Node %d.\n", node.nodeId);
        node.init();
        node.start();
        return;
      } catch (IOException e) {
        System.out.printf("%s already started, try next port.\n", hostInfo);
      }
    }
  }
}
