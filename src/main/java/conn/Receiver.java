package conn;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Receiver implements Runnable {
  private ObjectInputStream inputStream;
  private ConcurrentLinkedQueue<Message> queue;

  Receiver(ObjectInputStream inputStream, ConcurrentLinkedQueue<Message> queue) {
    this.inputStream = inputStream;
    this.queue = queue;
  }

  @Override
  public void run() {
    try {
      while (true) {
        Message message = (Message) this.inputStream.readObject();
        queue.offer(message);
      }
    } catch (IOException e) {
      e.printStackTrace();
      System.err.println("input stream closed by other end.");
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
      System.err.println("input object class not found.");
    }
  }
}
