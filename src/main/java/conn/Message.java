package conn;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Message defines the communication protocol between the Conn interface. It
 * carries a Serializable dataload field that can be used to transfer any
 * serializable object.
 */
class Message implements Serializable {

  private static AtomicLong SequenceCounter = new AtomicLong();
  private Type type;
  private long seq;
  private int senderId;
  private int originatorId;
  private int receiverId;
  Serializable dataload;

  Message(Type type, int senderId, Serializable dataload) {
    this.seq = SequenceCounter.getAndIncrement();
    this.type = type;
    this.senderId = senderId;
    this.originatorId = senderId;
    this.dataload = dataload;
  }

  Message(Type type, int senderId, int originatorId, int receiverId, Serializable dataload) {
    this.seq = SequenceCounter.getAndIncrement();
    this.type = type;
    this.senderId = senderId;
    this.originatorId = originatorId;
    this.receiverId = receiverId;
    this.dataload = dataload;
  }

  @Override
  public String toString() {
    return String.format("%s\tMessage { sender=%02d, origin=%02d, target=%02d, dataload=%s, seq=%d }",
      type, senderId, originatorId, receiverId, dataload, seq);
  }

  long getSeq() {
    return seq;
  }

  Type getType() {
    return type;
  }

  int getSenderId() {
    return senderId;
  }

  void setSenderId(int senderId) {
    this.senderId = senderId;
  }

  int getReceiverId() {
    return receiverId;
  }

  int getOriginatorId() {
    return originatorId;
  }

  Serializable getDataload() {
    return dataload;
  }

  enum Type {
    INIT, HELLO, TC, ACK, BCAST, DATA
  }
}
