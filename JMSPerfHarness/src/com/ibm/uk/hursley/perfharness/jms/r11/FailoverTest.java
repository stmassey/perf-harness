/********************************************************* {COPYRIGHT-TOP} ***
* Copyright 2016 IBM Corporation
*
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the MIT License
* which accompanies this distribution, and is available at
* http://opensource.org/licenses/MIT
********************************************************** {COPYRIGHT-END} **/


package com.ibm.uk.hursley.perfharness.jms.r11;

import java.util.logging.Level;

import javax.jms.Message;
import javax.jms.Queue;

import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.uk.hursley.perfharness.Config;
import com.ibm.uk.hursley.perfharness.Log;
import com.ibm.uk.hursley.perfharness.WorkerThread;

/**
 * Sends a message then receives one from the same queue.  Normal use is with
 * correlation identifier to ensure the same message is received.
 * @author Marc Carter, IBM 
 */
public final class FailoverTest extends JMS11WorkerThread implements WorkerThread.Paceable {

	@SuppressWarnings("unused")
	private static final String c = com.ibm.uk.hursley.perfharness.Copyright.COPYRIGHT; // IGNORE compiler warning

    Message inMessage = null;
    Message outMessage = null;
    String correlID = null;
    static String hostnames[] = new String[2];
    
    boolean reconnectAttempted;
    static int failoverMessages = 0;
    static int failoverStart = 0;
    boolean messagesPreloaded;
    int activeQM = 0;
    int messageCountAtLastFailover = 0;
    
    
    public static void registerConfig() {
		Config.registerSelf( FailoverTest.class );
		
        // failoverCount is the number of messages to have put/got before initiating failover sequence (preload/failover)
        failoverStart = Config.parms.getInt("fs");
		
        // failoverMessages is the number of messages to place on the queue before requesting failover
        failoverMessages = Config.parms.getInt("fm");
        
        hostnames[0] = Config.parms.getString("jh");
        hostnames[1] = Config.parms.getString("ji");
        
	}    
    
    /**
     * Constructor for JMSClientThread.
     * @param name
     */
    public FailoverTest(String name) {
        super(name);
        inMessage = null;
        outMessage = null;
        correlID = null;
        reconnectAttempted = false;
        messagesPreloaded = false;
    }

	protected void buildJMSResources() throws Exception {
		super.buildJMSResources();
			
        // Open queues
        if (destProducer == null) {
        	destProducer = jmsProvider.lookupQueue(destFactory.generateDestination(getThreadNum()), session).destination;
        }
        
        outMessage = msgFactory.createMessage(session, getName(), 0);
        String selector = null;
        
        // Use CorrelID Based Selector
       	if (Config.parms.getBoolean("co")) {
       		correlID = msgFactory.setJMSCorrelationID(this, outMessage);
      	}
        if (correlID != null) {
    		StringBuffer sb = new StringBuffer("JMSCorrelationID='");
    		sb.append(correlID);
    		sb.append("'");
    		selector = sb.toString();
    	}
        
        String destName = getDestinationName( destProducer );
        Log.logger.log(Level.FINE, "Creating receiver on {0} selector:{1}", new Object[] {destName, selector});
        System.out.println("Creating receiver on " + destName + " with selector: " + selector);
        messageConsumer = session.createConsumer((Queue)destProducer, selector);

        Log.logger.log(Level.FINE, "Creating sender on {0}", destName );
        messageProducer = session.createProducer((Queue)destProducer );
	}

    public void run() {
        run(this, null);  // call superclass generic method.
    } 
    
    /**
     * Send a message to one queue then get it back again. 
     */
	public final boolean oneIteration() throws Exception {
	    Exception storedException = null;
        boolean exceptionReceived = false;
        try {
           	messageProducer.send(outMessage, deliveryMode, priority, expiry);
           	if (transacted) session.commit();

           	if ((inMessage = messageConsumer.receive(timeout)) != null) {
           		if (transacted) session.commit();
           		incIterations();
           	} else {
           		throw new Exception("No response to message (" + outMessage.getJMSMessageID() + ")");
           	}
        } catch(Exception e) {
            storedException = e;
            int currentIterations = getIterations();
            Integer messagesProcessed = currentIterations - messageCountAtLastFailover;
            messageCountAtLastFailover = currentIterations;
            Log.logger.log(Level.INFO, (new StringBuilder("Exception received: ")).append(storedException).toString());
            Log.logger.log(Level.INFO, "{0} messages processed from {1} on QM {2}", new Object[] {
            	messagesProcessed, getDestinationName(destProducer), activeQM
            });
            exceptionReceived = true;
        }
        
        if(exceptionReceived) {
        	Long start = Long.valueOf(System.currentTimeMillis());
        	//switch Active QM; thread specific, so no synch required
        	if (activeQM == 0) {
        		activeQM = 1;
        	} else {
        		activeQM = 0;
        	}
            Log.logger.log(Level.INFO, "Will now attempt connection to QM: " + activeQM + "; hostname: " + hostnames[activeQM]);
            ((MQConnectionFactory)cf).setHostName(hostnames[activeQM]);
            try {
                buildJMSResources();
            } catch(Exception e) {
                Log.logger.log(Level.INFO, (new StringBuilder("Exception received connecting to new HA host: ")).append(e).toString());
                throw e;
            }
            Long now = Long.valueOf(System.currentTimeMillis());
            Log.logger.log(Level.INFO, "Time to connect to new host is {0} seconds", new Object[] {
                Long.valueOf((now.longValue() - start.longValue()) / 1000L)
            });
        }
        
        return true;
    }
}
