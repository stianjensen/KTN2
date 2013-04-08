/*
 * Created on Oct 27, 2004
 */
package no.ntnu.fp.net.co;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

//import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import no.ntnu.fp.net.admin.Log;
import no.ntnu.fp.net.cl.ClException;
import no.ntnu.fp.net.cl.ClSocket;
import no.ntnu.fp.net.cl.KtnDatagram;
import no.ntnu.fp.net.cl.KtnDatagram.Flag;

/**
 * Implementation of the Connection-interface. <br>
 * <br>
 * This class implements the behaviour in the methods specified in the interface
 * {@link Connection} over the unreliable, connectionless network realised in
 * {@link ClSocket}. The base class, {@link AbstractConnection} implements some
 * of the functionality, leaving message passing and error handling to this
 * implementation.
 * 
 * @author Sebj�rn Birkeland and Stein Jakob Nordb�
 * @see no.ntnu.fp.net.co.Connection
 * @see no.ntnu.fp.net.cl.ClSocket
 */
public class ConnectionImpl extends AbstractConnection {

	/** Keeps track of the used ports for each server port. */
	private static Map<Integer, Boolean> usedPorts = Collections.synchronizedMap(new HashMap<Integer, Boolean>());
	private final int MAXRESENDS = 5;
	private final int MAXRECEIVES = 5;
	private int resends = 0;
	private int receives = 0;
	private KtnDatagram oldPacket = null;


	/**
	 * Initialise initial sequence number and setup state machine.
	 * 
	 * @param myPort
	 *            - the local port to associate with this connection
	 */
	public ConnectionImpl(int myPort) {
		super();

		this.myPort = myPort;
		this.myAddress = getIPv4Address();

		System.out.println(state);
	}

	public ConnectionImpl(String myAddress, int newPort, String remoteAddress, int remotePort) {
		this.myAddress = myAddress;
		this.myPort = newPort;
		this.remoteAddress = remoteAddress;
		this.remotePort = remotePort;
		state = State.SYN_RCVD;

	}

	private String getIPv4Address() {
		try {
			return InetAddress.getLocalHost().getHostAddress();
		}
		catch (UnknownHostException e) {
			return "127.0.0.1";
		}
	}

	private int getNewPort() {
		return (int) (Math.random()*30000+10000);
	}

	/**
	 * Establish a connection to a remote location.
	 * 
	 * @param remoteAddress
	 *            - the remote IP-address to connect to
	 * @param remotePort
	 *            - the remote portnumber to connect to
	 * @throws IOException
	 *             If there's an I/O error.
	 * @throws java.net.SocketTimeoutException
	 *             If timeout expires before connection is completed.
	 * @see Connection#connect(InetAddress, int)
	 */
	public void connect(InetAddress remoteAddress, int remotePort) throws IOException,
	SocketTimeoutException {
		this.remoteAddress = remoteAddress.getHostAddress();
		this.remotePort = remotePort;
		KtnDatagram packet = constructInternalPacket(Flag.SYN);
		try {
			simplySendPacket(packet);
		} catch (IOException e) {
			System.out.println("ioexception");
			e.printStackTrace();
		} catch (ClException e) {
			System.out.println("clexception");
			e.printStackTrace();
		}
		state = State.SYN_SENT;
		KtnDatagram received = receiveAck();
		if (received != null) {
			this.remotePort = received.getSrc_port();
			state = State.SYN_RCVD;
			if (received.getFlag() == Flag.SYN_ACK) {
				sendAck(received, false);
				state = State.ESTABLISHED;
			}
		} else {
			throw new SocketTimeoutException(); 
		}
	}

	/**
	 * Listen for, and accept, incoming connections.
	 * 
	 * @return A new ConnectionImpl-object representing the new connection.
	 * @see Connection#accept()
	 */
	public Connection accept() throws IOException, SocketTimeoutException {
		//throw new NotImplementedException();
		System.out.println("accept");
		state = State.LISTEN;
		KtnDatagram packet;

		do {
			packet = receivePacket(true);
			System.out.println(packet);
		} while (packet == null || packet.getFlag() != Flag.SYN);
		this.remoteAddress = packet.getSrc_addr();
		this.remotePort = packet.getSrc_port();
		state = State.SYN_RCVD;
		ConnectionImpl connection = new ConnectionImpl(myAddress, getNewPort(), remoteAddress, remotePort);
		connection.sendAck(packet, true);

		KtnDatagram ack = connection.receiveAck();

		if (ack != null) {
			System.out.println("ACK is not null");
			if (ack.getFlag() == Flag.ACK) {
				System.out.println("accepted");
				connection.state = State.ESTABLISHED;
				state = State.LISTEN;
				return connection;
			}
		}
		throw new SocketTimeoutException();
	}

	/**
	 * Send a message from the application.
	 * 
	 * @param msg
	 *            - the String to be sent.
	 * @throws ConnectException
	 *             If no connection exists.
	 * @throws IOException
	 *             If no ACK was received.
	 * @see AbstractConnection#sendDataPacketWithRetransmit(KtnDatagram)
	 * @see no.ntnu.fp.net.co.Connection#send(String)
	 */
	public void send(String msg) throws ConnectException, IOException {
		if(state != State.ESTABLISHED)
			throw new ConnectException("Connection not established");

		KtnDatagram packet = constructDataPacket(msg);
		sendDataPacketWithRetransmit(packet);
		KtnDatagram received = receiveAck();
		
		if(received == null || received.getFlag() == Flag.NONE) {
			System.out.println("no ACK received");
			if(resends < MAXRESENDS) {
				resends++;
				send(msg); //kaller seg selv. hvis vi fortsatt ikke mottar ACK, return
				resends = 0;
				return;
			}
			else {
				//kommer vi s� langt er connection lost
				state = State.CLOSED;
				System.out.println("Connection lost");
			}
		}
	}

	/**
	 * Wait for incoming data.
	 * 
	 * @return The received data's payload as a String.
	 * @see Connection#receive()
	 * @see AbstractConnection#receivePacket(boolean)
	 * @see AbstractConnection#sendAck(KtnDatagram, boolean)
	 */
	public String receive() throws ConnectException, IOException {
		System.out.println("datoramagram");
		KtnDatagram packet;
		try {
			packet = receivePacket(false);
		} catch (EOFException e) {
			state = State.CLOSE_WAIT;
			throw new EOFException();
		}

		if (packet == null) {
			if (receives < MAXRECEIVES) {
				receives++;
				String msg = receive();
				receives = 0;
				return msg;
			} else {
				state = state.CLOSED;
				throw new ConnectException();
			}
		} else {
			System.out.println("packolini: " + packet);
			if (isValid(packet)) {
				sendAck(packet, false);
				oldPacket = packet;
				return (String) packet.getPayload();
			} else {
				System.out.println("!!!!!!wrong checksum!!!!!");
				sendAck(oldPacket, false);
				return receive();
			}
		}

	}

	/**
	 * Close the connection.
	 * 
	 * @see Connection#close()
	 */
	public void close() throws IOException {
		//state = State.CLOSED;
		switch (state) {
		case SYN_RCVD:
		case ESTABLISHED:
			KtnDatagram packet = constructInternalPacket(Flag.FIN);
			try {
				simplySendPacket(packet);
			} catch (IOException e) {
				System.out.println("ioexception");
				e.printStackTrace();
			} catch (ClException e) {
				System.out.println("clexception");
				e.printStackTrace();
			}
			state = State.FIN_WAIT_1;
			KtnDatagram ack = receiveAck();
			if (ack != null || ack.getFlag() == Flag.ACK) {
				state = State.FIN_WAIT_2;
			} else {
				throw new IOException();
			}
			KtnDatagram fin = receivePacket(true);
			if (fin != null && fin.getFlag() == Flag.FIN) {
				sendAck(fin, false);
				state = State.TIME_WAIT;
				try {
					wait(TIMEOUT);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				state = State.CLOSED;
			}
			break;
		case LISTEN:
			state = State.CLOSED;
			break;
		case CLOSE_WAIT:
			try {
				simplySendPacket(constructInternalPacket(Flag.FIN));
			} catch (ClException e) {
				e.printStackTrace();
			}
			state = State.LAST_ACK;
			KtnDatagram closeAck = receiveAck();
			if (closeAck != null && closeAck.getFlag() == Flag.ACK){
				state = State.CLOSED;
			}
			break;
		}
	}

	/**
	 * Test a packet for transmission errors. This function should only called
	 * with data or ACK packets in the ESTABLISHED state.
	 * 
	 * @param packet
	 *            Packet to test.
	 * @return true if packet is free of errors, false otherwise.
	 */
	protected boolean isValid(KtnDatagram packet) {
		return packet.getChecksum() == packet.calculateChecksum();
	}
}
