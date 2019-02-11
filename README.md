# Advanced Operating System Project 2

## Team Member
- Jianjun Du (jxd151630)
- Jing Zhou (jxz160330)
- Zhang Lizhong (lxz160730)

## Complie
Run `mvn package` in the folder, compiled and packaged `Project2-1.0-SNAPSHOT-jar-with-dependencies.jar` would be in `target` folder.

## Run
To run the compiled jar file, use following command in `dcXX.utdallas.edu` VMs.

```
java -jar Project2-1.0-SNAPSHOT-jar-with-dependencies.jar <config file location>

# Example:
# java -jar Project2-1.0-SNAPSHOT-jar-with-dependencies.jar ~/launch/config.txt
```

Also, the script `launch.sh` and `cleanup.sh` is modified to use the above command with `$HOME/launch/config.txt` as default configuration file.

## Program Output

The program will print all incoming/outgoing messages in standard output which includes send `HELLO`, `TC` messages and forward `TC` message, followed like this: 
```
Send		HELLO	Message { sender=05, origin=05, target=00, dataload=HelloDataload[OneHopNeighbor=[2, 3, 6, 8], MultiPointRelays=[3, 8]], seq=9 }
Received	HELLO	Message { sender=02, origin=02, target=00, dataload=HelloDataload[OneHopNeighbor=[5, 3], MultiPointRelays=[5, 3]], seq=14 }
Received	HELLO	Message { sender=08, origin=08, target=00, dataload=HelloDataload[OneHopNeighbor=[5, 9], MultiPointRelays=[5]], seq=22 }
Received	TC	Message { sender=03, origin=04, target=00, dataload=TCDataload[MPRSelectors=[1, 3, 6, 7], MPRSelectorsSeq=4], seq=20 }
Forward 	TC	Message { sender=05, origin=04, target=00, dataload=TCDataload[MPRSelectors=[1, 3, 6, 7], MPRSelectorsSeq=4], seq=20 }
```

After converging, the program will stop sending message and print tree neighbor set.

```
Node: 1
MPR (tree neighbors) set: [3, 4]
MPR selector set: []
routing table: {1=(1,0), 2=(3,2), 3=(3,1), 4=(4,1), 5=(3,2), 6=(4,2), 7=(4,2), 8=(3,3), 9=(3,4)}

```

And we can test broadcast service:

```
Enter something:
```

Then input "test boardcast service", we can see the output in local node.
``` 
Enter something:
test boardcast service
Broadcast message: test boardcast service
Send		BCAST	Message { sender=05, origin=05, target=00, dataload=test boardcast service, seq=138 }
Received	ACK	Message { sender=03, origin=03, target=05, dataload=null, seq=138 }
Received	ACK	Message { sender=06, origin=06, target=05, dataload=null, seq=138 }
Received	ACK	Message { sender=08, origin=08, target=05, dataload=null, seq=136 }
Received	ACK	Message { sender=02, origin=02, target=05, dataload=null, seq=91 }
Received	ACK	Message { sender=08, origin=09, target=05, dataload=null, seq=92 }
Received	ACK	Message { sender=03, origin=04, target=05, dataload=null, seq=138 }
Received	ACK	Message { sender=03, origin=01, target=05, dataload=null, seq=91 }
Received	ACK	Message { sender=03, origin=07, target=05, dataload=null, seq=92 }
```

In other node, we can see the output like this:
```
Received	BCAST	Message { sender=05, origin=05, target=00, dataload=test boardcast service, seq=138 }
Forward 	BCAST	Message { sender=03, origin=05, target=00, dataload=test boardcast service, seq=138 }
Reply ACK to 5
Forward 	ACK	Message { sender=04, origin=04, target=05, dataload=null, seq=138 }
Forward 	ACK	Message { sender=01, origin=01, target=05, dataload=null, seq=91 }
Forward 	ACK	Message { sender=04, origin=07, target=05, dataload=null, seq=92 }
```