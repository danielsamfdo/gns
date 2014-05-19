package edu.umass.cs.gns.nio;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;


/**
@author V. Arun
 */

/* This class exists primarily as a GNS wrapper around NIOTransport. NIOTransport 
 * is for general-purpose NIO byte stream communication between numbered nodes as 
 * specified by the NodeConfig interface and a data processing worker as specified 
 * by the DataProcessingWorker interface. 
 * 
 * The GNS-specific functions include (1) delay emulation, (2) short-circuiting 
 * local sends by directly sending it to packetDemux, and (3) adding GNS-specific 
 * headers to NIO's byte stream abstraction, and (4) supporting a crazy number of 
 * redundant public methods to do different kinds of sends. These methods exist 
 * only for backwards compatibility.  
 * 
 * 
 */
public class GNSNIOTransport extends NIOTransport implements GNSNIOTransportInterface{
	
	Timer timer = new Timer();
	
	public GNSNIOTransport(int id, NodeConfig nodeConfig, JSONMessageExtractor worker) throws IOException {
		super(id, nodeConfig, worker); // Switched order of the latter two arguments
	}
	public void addPacketDemultiplexer(PacketDemultiplexer pd) {
		((JSONMessageExtractor)this.worker).addPacketDemultiplexer(pd);
	}
	
	/********************Start of send methods*****************************************/

    /* WARNING: This method returns a meaningless value. Need to get
     * return value from task scheduled in the future, which is 
     * not possible while maintaining NIO semantics. So we can
     * not have a meaningful return value while being non-blocking.
     * Such is life.
     * 
     * Experiments using this method should plan for return values
     * being meaningless.
     */
    public int sendToID(int id, JSONObject jsonData) throws IOException {
    	int sent = 0;
    	if(GNSDelayEmulator.isDelayEmulated()) {
        GNSDelayEmulator.sendWithDelay(timer, this, id, jsonData);
    		sent = jsonData.length(); // cheating!
    	} else sent = this.sendToIDActual(id, jsonData);
    	return sent;
    }

	/* This method adds a header only if a socket channel is used to send to
	 * a remote node, otherwise it hands over the message directly to the worker.
	 */
	protected int sendToIDActual(int destID, JSONObject jsonData) throws IOException {
		int written = 0;
		if(destID==this.myID) {
			ArrayList<JSONObject> jsonArray = new ArrayList<JSONObject>();
			jsonArray.add(jsonData);
			NIOInstrumenter.incrSent(); // instrumentation
			((JSONMessageExtractor)worker).processJSONMessages(jsonArray);
			written = jsonData.length();
		}
		else {
			String headeredMsg = JSONMessageExtractor.prependHeader(jsonData.toString());
			written = this.sendUnderlying(destID, headeredMsg.getBytes());
		}
		return written;
	}
	/********************End of public send methods*****************************************/	
	
	/* This method is really redundant. But it exists so that there is one place where
	 * all NIO sends actually happen given the maddening number of different public send
	 * methods above. Do NOT add more gunk to this method.
	 */
	private int sendUnderlying(int id, byte[] data) throws IOException {
		return this.send(id, data);
	}
	private static JSONObject JSONify(int msgNum, String s) throws JSONException{
		return new JSONObject("{\"msg\" : \"" + s + "\" , \"msgNum\" : " + msgNum + "}");
	}
	

	/* The test code here is mostly identical to that of NIOTransport but tests
	 * JSON messages, headers, and delay emulation features. Need to test it with 
	 * the rest of GNS.
	 */
	public static void main(String[] args) {
		int msgNum=0;
		int port = 2000;
		int nNodes=100;
		SampleNodeConfig snc = new SampleNodeConfig(port);
		snc.localSetup(nNodes+2);
		JSONMessageExtractor[] workers = new JSONMessageExtractor[nNodes+1];
		for(int i=0; i<nNodes+1; i++) workers[i] = new JSONMessageExtractor(new DefaultPacketDemultiplexer());
		GNSNIOTransport[] niots = new GNSNIOTransport[nNodes];
		
		try {
			int smallNNodes = 2;
			for(int i=0; i<smallNNodes; i++) {
				niots[i] = new GNSNIOTransport(i, snc, workers[i]);
				new Thread(niots[i]).start();
			}			
			
			/*************************************************************************/
			/* Test a few simple hellos. The sleep is there to test 
			 * that the successive writes do not "accidentally" benefit
			 * from concurrency, i.e., to check that OP_WRITE flags will
			 * be set correctly.
			 */
			niots[1].sendToIDActual(0, JSONify(msgNum++, "Hello from 1 to 0"));
			niots[0].sendToIDActual(1, JSONify(msgNum++, "Hello back from 0 to 1"));
			niots[0].sendToIDActual(1, JSONify(msgNum++, "Second hello back from 0 to 1"));
			try {Thread.sleep(1000);} catch(Exception e){e.printStackTrace();}
			niots[0].sendToIDActual(1, JSONify(msgNum++, "Third hello back from 0 to 1"));
			niots[1].sendToIDActual(0, JSONify(msgNum++, "Thank you for all the hellos back from 1 to 0"));
			/*************************************************************************/
			
			int seqTestNum=1;
			Thread.sleep(2000);
			System.out.println("\n\n\nBeginning test of " + seqTestNum + " random, sequential messages");
			Thread.sleep(1000);
			
			/*************************************************************************/
			//Create the remaining nodes up to nNodes
			for(int i=smallNNodes; i<nNodes; i++) {
				niots[i] = new GNSNIOTransport(i, snc, workers[i]);
				new Thread(niots[i]).start();
			}			
			
			// Test a random, sequential communication pattern
			for(int i=0; i<nNodes*seqTestNum;i++) {
				int k = (int)(Math.random()*nNodes);
				int j = (int)(Math.random()*nNodes);
				System.out.println("Message " + i + " with msgNum " + msgNum);
				niots[k].sendToIDActual(j, JSONify(msgNum++, "Hello from " + k + " to " + j));
			}

			int oneToOneTestNum=1;
			/*************************************************************************/
			Thread.sleep(1000);
			System.out.println("\n\n\nBeginning test of " + oneToOneTestNum*nNodes + 
					" random, concurrent, 1-to-1 messages with emulated delays");
			Thread.sleep(1000);
			/*************************************************************************/
			// Test a random, concurrent communication pattern with emulated delays
			ScheduledExecutorService execpool = Executors.newScheduledThreadPool(5);
			class TX extends TimerTask {
				GNSNIOTransport sndr=null;
				private int rcvr=-1;
				int msgNum=-1;
				TX(int i, int id, GNSNIOTransport[] n, int m) {
					sndr = n[i];
					rcvr = id;
					msgNum = m;
				}
				TX(GNSNIOTransport niot, int id, int m) {
					sndr = niot;
					rcvr = id;
					msgNum = m;
				}
				public void run() {
					try {
						sndr.sendToIDActual(rcvr, JSONify(msgNum, "Hello from " + sndr.myID + " to " + rcvr));
					} catch(IOException e) {
						e.printStackTrace();
					} catch (JSONException e) {
						e.printStackTrace();
					}
				}
			}
			GNSDelayEmulator.emulateDelays();

			GNSNIOTransport concurrentSender = new GNSNIOTransport(nNodes, snc, workers[nNodes]);
			new Thread(concurrentSender).start();
			for(int i=0; i<nNodes*oneToOneTestNum; i++) {
				TX task = new TX(concurrentSender, 0, msgNum++);
				System.out.println("Scheduling random message " + i + " with msgNum " + msgNum);
				execpool.schedule(task, 0, TimeUnit.MILLISECONDS);
			}
			
			/*************************************************************************/
			Thread.sleep(1000);
			System.out.println("\n\n\nBeginning test of random, concurrent, " +
			" any-to-any messages with emulated delays");
			Thread.sleep(1000);
			/*************************************************************************/			
			
			int load = nNodes*25;
			int msgsToFailed=0;
			ScheduledFuture<TX>[] futures = new ScheduledFuture[load];
			for(int i=0; i<load; i++) {
				int k = (int)(Math.random()*nNodes);
				int j = (int)(Math.random()*nNodes);
				//long millis = (long)(Math.random()*1000);
				
				if(i%100==0) {
					j = nNodes+1; // Periodically try sending to a non-existent node
					msgsToFailed++;
				}
				
				TX task = new TX(k, j, niots, msgNum++);
				System.out.println("Scheduling random message " + i + " with msgNum " + msgNum);
				futures[i] = (ScheduledFuture<TX>)execpool.schedule(task, 0, TimeUnit.MILLISECONDS);
			}
			int numExceptions = 0;
			for(int i=0; i<load; i++) {
				try {
					futures[i].get();
				} catch(Exception e) {
					//e.printStackTrace();
					numExceptions++;
				}
			}

			/*************************************************************************/

			Thread.sleep(2000);
			System.out.println("\n\n\nPrinting overall stats. Number of exceptions =  " + numExceptions);
			System.out.println((new NIOInstrumenter()+"\n"));	
			boolean pending=false;
			for(int i=0; i<nNodes; i++) {
				if(niots[i].getPendingSize() > 0) {
					System.out.println("Pending messages at node " + i + " : " + niots[i].getPendingSize());
					pending=true;
				}
			}
			int missing = NIOInstrumenter.getMissing();
			assert(pending==false || missing==msgsToFailed) : 
				"Unsent pending messages in NIO";
			if(!pending || missing==msgsToFailed) System.out.println("\nSUCCESS: no pending messages to non-failed nodes!");

	} catch (IOException e) {
		e.printStackTrace();
	} catch(Exception e) {
		e.printStackTrace();
	}
	}
}
