package conn;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;

public class OLSRConn extends SimpleConn {

  /**
   * Time interval to check if the routes have converged. Check every 5 seconds,
   * if converged, stop sending HELLO and TC message.
   */
  private final static long CONVERGENCE_CHECK_INTERVAL = 5000L;

  /**
   * A set of all one-hop neighbors, created during connection time.
   */
  private final HashSet<Integer> OneHopNeighbors = new HashSet<>();

  /**
   * A set of all potential two-hop neighbors, updated with every received
   * HELLO message
   */
  private final HashMap<Integer, HashSet<Integer>> PotentialTwoHopNeighbor =
    new HashMap<>();

  /**
   * Total number of nodes existing in the network. This value is used to
   * confirm all other nodes have responded ACK after broadcasting a message.
   */
  private int totalNodeNumber;

  /**
   * Multipoint Relays (MPR) set.
   * The idea is to minimize the flooding of broadcast messages. Each node in
   * the network selects a set of neighboring nodes to retransmits its packet.
   * The set is called MPR set.
   * 1. Each node selects its MPRs among its one hop neighbors.
   * 2. The set must cover all the nodes that are two hops away.
   * 3. Every node in the two hop neighborhood of node N must have a
   * bidirectional link towards MPR(N).
   * <p>
   * Note that, multipoint relays are selected among one hop neighbors with a
   * bidirectional link. Therefore, selecting route through multipoint relays
   * automatically avoids the problems associated with data packets transfer
   * on unidirectional links.
   * <p>
   * The information required to calculate the MPRs:
   * 1. The set of one-hop neighbors and the two-hop neighbors.
   * 2. There are many ways you can select MPRs from your neighbors.
   */
  private HashSet<Integer> MultiPointRelays = new HashSet<>();

  /**
   * Sequence number (version) for MPR set.
   */
  private long MultiPointRelaysSeq = 0L;

  /**
   * MPR Selector (MS) is a neighboring node which has selected me as an MPR.
   * On reception of HELLO message, each node can construct its MPR Selector
   * table with the nodes who have selected it as a multipoint relay. A
   * sequence number is also associated with the MPR selector table.
   */
  private final HashSet<Integer> MPRSelectors = new HashSet<>();

  /**
   * Sequence number (version) for MS set.
   */
  private long MPRSelectorsSeq = 0L;

  /**
   * Flag indicating if MS set have changed and need to send TC again.
   */
  private volatile boolean MPRSelectorSent = true;

  /**
   * Collection of all received TC message. The key is the originator of the TC
   * message, the value is the pair of MS set & sequence number in the TC
   * message.
   */
  private HashMap<Integer, Pair<HashSet<Integer>, Long>> TopologyTable = new HashMap<>();

  /**
   * Routing table is calculated upon every TopologyTable update, i.e., every
   * TC message received.
   */
  private HashMap<Integer, Pair<Integer, Integer>> RoutingTable = new HashMap<>();

  /**
   * A broadcast message might be received from multiple relays, so a last
   * received message sequence for each originator is maintained. The key
   * is the message originator's ID. If a message was already seen, discard
   * directly.
   */
  private HashMap<Integer, Long> LastMsgSeq = new HashMap<>();

  /**
   * The message queue for the Conn. Creator of conn should be responsible to
   * process these message.
   */
  private ConcurrentLinkedQueue<Serializable> messageQueue = new ConcurrentLinkedQueue<>();

  /**
   * A boolean field indicating if the network has converged.
   */
  private volatile boolean converge = false;

  /**
   * This semaphore is used to ensure that a broadcast is never started
   * unless the previous broadcast has finished.
   */
  private final Semaphore pendingBroadcastACK = new Semaphore(0);

  /**
   * Constructor of OLSRConn. The total number of nodes are needed to ensure
   * broadcast atomicity.
   *
   * @param nodeId          Node id for the conn channel.
   * @param port            Port to listen socket connection.
   * @param totalNodeNumber Total number of node in the network.
   * @throws IOException If the given port was already taken by other
   *                     application, throw the exception.
   */
  public OLSRConn(int nodeId, int port, int totalNodeNumber) throws IOException {
    super(nodeId, port);
    this.totalNodeNumber = totalNodeNumber;
  }

  /**
   * Return the MPR, i.e., the tree neighbor to forward the broadcast message
   * of current node.
   *
   * @return All tree neighbors as a set.
   */
  public HashSet<Integer> getMultiPointRelays() {
    return new HashSet<>(MultiPointRelays);
  }

  /**
   * Return the MPR, i.e., the tree neighbor to forward the broadcast message
   * of current node.
   *
   * @return All tree neighbors as a set.
   */
  public HashSet<Integer> getMPRSelectors() {
    return new HashSet<>(MPRSelectors);
  }

  /**
   * Connect all neighbor and start sending control message to build network
   * topology.
   *
   * @param connectionList List of directly connected hosts.
   * @throws IOException If one of the directly connected neighbor's
   *                     connection failed, throw the exception.
   */
  @Override
  public void connect(Map<Integer, Pair<String, Integer>> connectionList) throws IOException {

    super.connect(connectionList);

    // initializing one hop neighbor set.
    OneHopNeighbors.addAll(connectionList.keySet());

    // periodically send HELLO message.
    Timer HelloTimer = new Timer("Link Sensing Timer");
    HelloTimer.scheduleAtFixedRate(
      new HelloTask(),
      HelloTask.delay,
      HelloTask.period
    );

    // periodically send TC message.
    Timer TopologyControlTimer = new Timer("Topology Control Timer");
    TopologyControlTimer.scheduleAtFixedRate(
      new TopologyControlTask(),
      TopologyControlTask.delay,
      TopologyControlTask.period
    );

    // Thread to process all received control messages.
    Thread MessageProcessor = new Thread(new Runnable() {
      @Override
      public void run() {
        while (true) {
          Message message = OLSRConn.super.nextMessage();
          switch (message.getType()) {
            case ACK:
              OLSRConn.this.processRcvdACKMsg(message);
              break;
            case HELLO:
              System.out.println("Received\t" + message);
              OLSRConn.this.processRcvdHelloMsg(message);
              break;
            case TC:
              System.out.println("Received\t" + message);
              OLSRConn.this.processRcvdTopologyControlMsg(message);
              break;
            case BCAST:
              OLSRConn.this.processRcvdBroadcastMsg(message);
              break;
            default:
              break;
          }
        }
      }
    });

    MessageProcessor.start();

    // Check if network has converged every 5 seconds.
    // The network is converged if MPR sequence did not changed after one
    // interval.
    while (true) {
      try {
        long oldMPRSeq = MultiPointRelaysSeq;
        Thread.sleep(CONVERGENCE_CHECK_INTERVAL);
        if (oldMPRSeq == MultiPointRelaysSeq) {
          HelloTimer.cancel();
          TopologyControlTimer.cancel();
          converge = true;
          break;
        }
      } catch (InterruptedException e) {
        e.printStackTrace();
      }

    }
  }

  @Override
  public void send(int id, Serializable data) {
    Message message = new Message(Message.Type.DATA, nodeId, nodeId, id, data);
    send(getNextHop(message.getReceiverId()), message);
  }

  @Override
  public synchronized void broadcast(Serializable data) {
    Message message = new Message(Message.Type.BCAST, nodeId, data);
    broadcast(message);

    // down(pendingBroadcastACK, totalNodeNumber - 1)
    // This operation will block unless all totalNodeNumber - 1 ACK messages
    // were received (aka, totalNodeNumber - 1 times up() called).
    pendingBroadcastACK.acquireUninterruptibly(totalNodeNumber - 1);
  }

  @Override
  public Serializable getMessage() {
    while (true) {
      if (messageQueue.isEmpty())
        continue;
      return messageQueue.poll();
    }
  }

  @Override
  public boolean hasConverged() {
    return converge;
  }

  public HashMap<Integer, Pair<Integer, Integer>> getRoutingTable() {
    return new HashMap<>(RoutingTable);
  }

  /**
   * Reply ACK to broadcast message
   * @param id
   */
  public void sendACK(int id) {
    Message message = new Message(Message.Type.ACK, nodeId, nodeId, id, null);
    send(getNextHop(message.getReceiverId()), message);
  }

  /**
   * Forward an unicast message based on routing table.
   *
   * @param message Message to forward.
   */
  private void forward(Message message) {
    message.setSenderId(nodeId);
    send(getNextHop(message.getReceiverId()), message);
  }

  /**
   * Forward a broadcast message if the sender is in the MS set.
   *
   * @param message Message to forward.
   */
  private void forwardBroadcast(Message message) {
    if (this.MPRSelectors.contains(message.getSenderId())) {
      int oldSenderID = message.getSenderId();
      message.setSenderId(nodeId);
      System.out.println("Forward \t" + message);
      broadcast(message, oldSenderID);
    }
  }

  /**
   * Select MPR set based on given one-hop & two-hop neighbors set. Here we
   * used a greedy algorithm to select MPR set: each time select the one-hop
   * neighbor that can cover most number of remaining two-hop neighbor.
   *
   * @param neighbors one-hop neighbor and their related potential
   *                  two-hop neighbor mapping.
   * @param twohops   two-hop neighbors set.
   */
  private void selectMPR(HashMap<Integer, HashSet<Integer>> neighbors, HashSet<Integer> twohops) {
    HashSet<Integer> MPR = new HashSet<>();
    while (!twohops.isEmpty()) {
      Integer maxID = -1;
      int maxIntersectionSize = 0;
      for (HashMap.Entry<Integer, HashSet<Integer>> neighbor : neighbors.entrySet()) {
        if (MPR.contains(neighbor.getKey()))
          continue;
        HashSet<Integer> intersection = new HashSet<>(neighbor.getValue());
        intersection.retainAll(twohops);
        if (intersection.size() > maxIntersectionSize) {
          maxID = neighbor.getKey();
          maxIntersectionSize = intersection.size();
        }
      }
      MPR.add(maxID);
      twohops.removeAll(neighbors.get(maxID));
    }
    if (!MultiPointRelays.equals(MPR)) {
      MultiPointRelays = MPR;
      MultiPointRelaysSeq++;
    }
  }

  /**
   * Each node periodically broadcasts its HELLO messages. These are received
   * by all one-hop neighbors, but they are not relayed to further node.
   * <p>
   * A HELLO message contains:
   * 1. List of bidirectional neighbors.
   * 2. list of one directional neighbors (the list of addresses of the
   * neighbors which are heard by this node, i.e. received HELLO message
   * from, but the link is not yet validated as bidirectional.)
   * 3. MPR set. In the first round, there is no MPR set, since there is no
   * neighbor from the node's perspective.
   */
  private void sendHelloMsg() {
    HashSet<Integer> TwoHopNeighbors = new HashSet<>();
    for (HashSet<Integer> potential : this.PotentialTwoHopNeighbor.values()) {
      for (Integer id : potential) {
        if (id == this.nodeId || this.OneHopNeighbors.contains(id))
          continue;
        TwoHopNeighbors.add(id);
      }
    }

    selectMPR(this.PotentialTwoHopNeighbor, TwoHopNeighbors);

    HelloDataload dataload = new HelloDataload(
      this.OneHopNeighbors,
      this.MultiPointRelays
    );
    Message hello = new Message(
      Message.Type.HELLO,
      this.nodeId,
      dataload
    );
    super.broadcast(hello);
  }

  /**
   * In order to build intra-forwarding databased needed for routing packet,
   * each node periodically broadcast specific control message called
   * Topology Control (TC) messages to declare its MPR selector set.
   * 1. Nodes with a non-empty MPR selector periodically flood their MS via a
   * TC message.
   * 2. Message might not be sent if there are no updates.
   * 3. Message contains: a) MPR Selector set (MS); b) Sequence number.
   */
  private void sendTopologyControlMsg() {
    if (MPRSelectorSent)
      return;
    TCDataload dataload = new TCDataload(
      this.MPRSelectors,
      this.MPRSelectorsSeq
    );
    Message tc = new Message(Message.Type.TC, nodeId, dataload);
    super.broadcast(tc);
    MPRSelectorSent = false;
  }

  /**
   * Upon receiving ACK of a broadcast message, up(pendingACK) semaphore. If
   * current node is not the destination of the ACK message, forward the
   * message.
   *
   * @param message Received ACK message.
   */
  private void processRcvdACKMsg(Message message) {
    if (nodeId == message.getReceiverId()) {
      System.out.println("Received\t" + message);

      // up(pendingBroadcastACK)
      pendingBroadcastACK.release(1);
    } else {
      System.out.println("Forward \t" + message);
      forward(message);
    }
  }

  /**
   * Upon receiving BROADCAST message, check if the message has been seen
   * before. If not, forward the message if the sender of the message is in
   * the MS set. And then send an ACK to the originator of the BROADCAST
   * message.
   *
   * @param message Received BROADCAST message.
   */
  private void processRcvdBroadcastMsg(Message message) {
    int origin = message.getOriginatorId();
    if (LastMsgSeq.getOrDefault(origin, 0L) >= message.getSeq())
      return;
    LastMsgSeq.put(origin, message.getSeq());
    System.out.println("Received\t" + message);
    forwardBroadcast(message);
    System.out.println("Reply ACK to " + message.getOriginatorId());
    sendACK(message.getOriginatorId());
  }

  /**
   * Upon receiving HELLO message, update potential neighbor mapping and
   * update MS set based on declared MPR set in the HELLO message. No
   * forwarding is needed for HELLO message.
   *
   * @param message Received HELLO message.
   */
  private void processRcvdHelloMsg(Message message) {
    HelloDataload dataload = (HelloDataload) message.getDataload();

    PotentialTwoHopNeighbor.put(message.getSenderId(), dataload.OneHopNeighbor);

    if (dataload.MultiPointRelays.contains(nodeId)) {
      if (!MPRSelectors.contains(message.getSenderId())) {
        MPRSelectors.add(message.getSenderId());
        MPRSelectorsSeq++;
        MPRSelectorSent = false;
      }
    } else {
      if (MPRSelectors.contains(message.getSenderId())) {
        MPRSelectors.remove(message.getSenderId());
        MPRSelectorsSeq++;
        MPRSelectorSent = false;
      }
    }
  }

  /**
   * Upon receiving TC message, recalculate the Topology Table and forward
   * the TC if the sender is in the MS set.
   * <p>
   * 1. If there exist some entry to the same destination X with higher
   * Sequence Number, the TC message is ignored.
   * 2. If there exist some entry to the same destination X with lower
   * Sequence Number, the topology entry is removed and the new one is recorded.
   * 3. If the entry (i.e. X, Y) is the same as in TC message, the holding
   * time of this entry is refreshed.
   * 4. If X is no longer in MS of Y, remove entry (X, Y).
   * 5. If there are no corresponding entry – the new entry (X, Y) is recorded.
   *
   * @param message Received TC message.
   */
  private void processRcvdTopologyControlMsg(Message message) {
    TCDataload dataload = (TCDataload) message.dataload;
    Long currentSeq = 0L;
    if (TopologyTable.containsKey(message.getOriginatorId()))
      currentSeq = TopologyTable.get(message.getOriginatorId()).getRight();

    if (currentSeq >= dataload.MPRSelectorsSeq)
      return;
    TopologyTable.put(message.getOriginatorId(), new ImmutablePair<>(dataload.MPRSelectors, dataload.MPRSelectorsSeq));
    forwardBroadcast(message);
    calcRoutingTable();
  }

  /**
   * @return the topology table snapshot.
   */
  private HashMap<Integer, HashSet<Integer>> getTopologyTableSnapshot() {
    HashMap<Integer, HashSet<Integer>> topology = new HashMap<>();
    topology.put(nodeId, OneHopNeighbors);
    for (Map.Entry<Integer, Pair<HashSet<Integer>, Long>> topoEntry : TopologyTable.entrySet()) {
      topology.put(topoEntry.getKey(), topoEntry.getValue().getLeft());
    }
    return topology;
  }

  /**
   * Calculate the routing table.
   * <p>
   * Each node maintains a routing table (in addition to the topology table)
   * to all known destinations in the network. The nodes which receive a TC
   * message parse and store some of the connected pair of form [last-hop,
   * node] where nodes are the address found in the TC message list. Routing
   * table is based on the information contained in the neighbor table and
   * the topology table.
   * <p>
   * Consider the destination sent out a flood that reached every node. The
   * message reached the source, of course. The message would be forwarded by
   * the destination's MPRs, and the MPR of the MPRs. Eventually, along the
   * path the message was transfers from destination to source. Each node is
   * the MPR of the previous node.
   * <p>
   * ALL INTERMEDIATE NODES ARE MPR’s OF SOME NODE (y is mpr of x), MPR’s
   * form a backbone network so to speaks.
   */
  private void calcRoutingTable() {
    HashMap<Integer, HashSet<Integer>> TopoSnapshot = getTopologyTableSnapshot();
    HashMap<Integer, Pair<Integer, Integer>> RoutingTable = new HashMap<>();
    HashMap<Integer, Pair<Integer, Integer>> NHopNeighbors = new HashMap<>();
    HashMap<Integer, Pair<Integer, Integer>> NplusOneHopNeighbors;

    // The calculation is basically a BFS starting from current node.
    NHopNeighbors.put(nodeId, new ImmutablePair<>(nodeId, 0));
    while (!NHopNeighbors.isEmpty()) {
      RoutingTable.putAll(NHopNeighbors);
      NplusOneHopNeighbors = new HashMap<>();
      for (HashMap.Entry<Integer, Pair<Integer, Integer>> NHopNeighbor : NHopNeighbors.entrySet()) {
        Integer NHopID = NHopNeighbor.getKey();
        if (!TopoSnapshot.containsKey(NHopID))
          continue;
        Pair<Integer, Integer> NHopNextHop = NHopNeighbor.getValue();
        for (Integer NplusOneHopNeighbor : TopoSnapshot.get(NHopID)) {
          if (RoutingTable.containsKey(NplusOneHopNeighbor))
            continue;
          NplusOneHopNeighbors.put(
            NplusOneHopNeighbor,
            new ImmutablePair<>(
              NHopNextHop.getLeft() == nodeId ? NplusOneHopNeighbor : NHopNextHop.getLeft(),
              NHopNextHop.getRight() + 1
            )
          );
        }
      }
      NHopNeighbors = NplusOneHopNeighbors;
    }
    this.RoutingTable = RoutingTable;
  }

  /**
   * Get next hop to send for specified target node.
   *
   * @param target Target node id.
   * @return Next hop for target node.
   */
  private int getNextHop(int target) {
    return this.RoutingTable.get(target).getLeft();
  }

  /**
   * HelloDataload defines the dataload for HELLO message.
   */
  static class HelloDataload implements Serializable {
    private HashSet<Integer> OneHopNeighbor;
    private HashSet<Integer> MultiPointRelays;

    HelloDataload(HashSet<Integer> oneHopNeighbor, HashSet<Integer> multiPointRelays) {
      OneHopNeighbor = oneHopNeighbor;
      MultiPointRelays = multiPointRelays;
    }

    @Override
    public String toString() {
      return "HelloDataload[" +
        "OneHopNeighbor=" + OneHopNeighbor +
        ", MultiPointRelays=" + MultiPointRelays +
        ']';
    }
  }

  /**
   * TCDataload defines the dataload for TC message.
   */
  static class TCDataload implements Serializable {
    private HashSet<Integer> MPRSelectors;
    private Long MPRSelectorsSeq;

    TCDataload(HashSet<Integer> MPRSelectors, Long MPRSelectorsSeq) {
      this.MPRSelectors = MPRSelectors;
      this.MPRSelectorsSeq = MPRSelectorsSeq;
    }

    @Override
    public String toString() {
      return "TCDataload[" +
        "MPRSelectors=" + MPRSelectors +
        ", MPRSelectorsSeq=" + MPRSelectorsSeq +
        ']';
    }
  }

  /**
   * Each node should periodically floods {@code HELLO} message for link
   * sensing. The node’s MPR set is included in the {@code HELLO} message.
   * <p>
   * The {@code HelloTask} periodically send out {@code HELLO} messages.
   */
  private class HelloTask extends TimerTask {

    final static long delay = 1000L;
    final static long period = 100L;

    @Override
    public void run() {
      sendHelloMsg();
    }
  }

  /**
   * Nodes with a non-empty {@code MS} periodically flood their {@code MS}
   * via a {@code TC} message.
   * <p>
   * The {@code TopologyControlTask} periodically send out {@code TC} messages
   * if update needed.
   */
  private class TopologyControlTask extends TimerTask {

    final static long delay = 1000L;
    final static long period = 200L;

    @Override
    public void run() {
      sendTopologyControlMsg();
    }
  }
}
