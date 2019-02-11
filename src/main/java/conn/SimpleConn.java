package conn;

import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SimpleConn implements Conn {
  private ConcurrentHashMap<Integer, Sender> senderMap;
  int nodeId;
  private ConcurrentLinkedQueue<Message> messageQueue;

  SimpleConn(int nodeId, int port) throws IOException {
    ServerSocket listener = new ServerSocket(port);

    this.nodeId = nodeId;
    this.senderMap = new ConcurrentHashMap<>();
    this.messageQueue = new ConcurrentLinkedQueue<>();

    new Thread(new Listener(listener)).start();
  }

  @Override
  public void connect(Map<Integer, Pair<String, Integer>> connectionList) throws IOException {
    int id, port;
    String host;
    for (Map.Entry<Integer, Pair<String, Integer>> entry : connectionList.entrySet()) {
      try {
        if (nodeId < entry.getKey())
          continue;
        id = entry.getKey();
        host = entry.getValue().getLeft();
        port = entry.getValue().getRight();

        connect(id, host, port);
      } catch (IOException e) {
        e.printStackTrace();
        System.err.println("Unable to connect to existing host");
      }
    }
  }

  private void connect(int id, String host, int port) throws IOException {
    Socket socket = null;
    int retry = 10;
    while (retry > 0) {
      try {
        socket = new Socket(host, port);
        break;
      } catch (IOException e) {
        retry--;
        if (retry == 0)
          throw e;
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e1) {
          e1.printStackTrace();
        }
      }
    }
    ObjectOutputStream outputStream = new ObjectOutputStream(socket.getOutputStream());
    ObjectInputStream inputStream = new ObjectInputStream(socket.getInputStream());
    System.out.println(inputStream);

    Sender sender = new Sender(outputStream);
    Thread senderThread = new Thread(sender);
    senderThread.start();
    senderMap.put(id, sender);

    Message message = new Message(Message.Type.INIT, nodeId, null);
    this.send(id, message);

    new Thread(new Receiver(inputStream, messageQueue)).start();
    System.out.println("Connected an exited host");
  }

  @Override
  public void send(int id, Serializable data) {
    Message message = new Message(Message.Type.ACK, nodeId, nodeId, id, data);
    send(id, message);
  }

  void send(int id, Message message) {
    senderMap.get(id).send(message);
  }

  @Override
  public void broadcast(Serializable data) {
    Message message = new Message(Message.Type.BCAST, nodeId, nodeId, -1, data);
    broadcast(message);
  }

  void broadcast(Message message) {
    System.out.println("Send\t\t" + message);
    broadcast(message, -1);
  }

  void broadcast(Message message, int exclude) {
    for (Map.Entry<Integer, Sender> sender : senderMap.entrySet()) {
      if (exclude != sender.getKey())
        sender.getValue().send(message);
    }
  }

  @Override
  public Serializable getMessage() {
    return nextMessage().getDataload();
  }

  @Override
  public boolean hasConverged() {
    return true;
  }

  Message nextMessage() {
    while (true) {
      if (messageQueue.isEmpty())
        continue;
      return messageQueue.poll();
    }
  }

  private class Listener implements Runnable {
    private ServerSocket serverSocket;

    private Listener(ServerSocket serverSocket) {
      this.serverSocket = serverSocket;
    }

    @Override
    public void run() {
      try {
        while (true) {
          Socket socket = serverSocket.accept();
          ObjectOutputStream outputStream = new ObjectOutputStream(socket.getOutputStream());
          ObjectInputStream inputStream = new ObjectInputStream(socket.getInputStream());
          Message message = (Message) inputStream.readObject();

          Sender sender = new Sender(outputStream);
          Thread senderThread = new Thread(sender);
          senderThread.start();
          senderMap.put(message.getSenderId(), sender);

          System.out.println("Received\t" + message);
          new Thread(new Receiver(inputStream, messageQueue)).start();
        }
      } catch (IOException e) {
        e.printStackTrace();
        System.err.println("Unable to start server logic");
      } catch (ClassNotFoundException e) {
        e.printStackTrace();
        System.err.println("Class of a serialized object cannot be found");
      }
    }
  }
}
