package conn;

import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.io.Serializable;
import java.util.Map;

/**
 * Conn interface defines the behavior of a communication channel.
 */
public interface Conn {
  /**
   * Establish connection based on given directly connected hosts list.
   *
   * @param connectionList List of directly connected hosts.
   * @throws IOException If one of the directly connected neighbor's
   *                     connection failed, throw the exception.
   */
  void connect(Map<Integer, Pair<String, Integer>> connectionList) throws IOException;

  /**
   * Send some serializable data to a destination node. Whoever call invoke
   * this method is responsible to deserialize the data in getMessage().
   *
   * @param id   Destination to send.
   * @param data Serializable data to send.
   */
  void send(int id, Serializable data);

  /**
   * Broadcast some serializable data to the netowrk. Whoever call invoke
   * this method is responsible to deserialize the data in getMessage().
   *
   * @param data Serializable data to send.
   */
  void broadcast(Serializable data);

  /***
   * Return the serializable data received. The caller is responsible to
   * deserialize the data.
   * @return Next serializable data.
   */
  Serializable getMessage();

  /**
   * @return If the network has converged.
   */
  boolean hasConverged();
}
