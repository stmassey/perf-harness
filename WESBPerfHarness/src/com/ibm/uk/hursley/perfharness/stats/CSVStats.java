/*
 * Created on 14-Mar-2007
 *
 * TODO To change the template for this generated file go to
 * Window - Preferences - Java - Code Style - Code Templates
 */
package com.ibm.uk.hursley.perfharness.stats;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ListIterator;
import java.util.Locale;
import java.util.TimerTask;
import java.util.logging.Level;

import com.ibm.pcounters.PCounterNative;
import com.ibm.pcounters.PCounterNetwork;
import com.ibm.pcounters.PCounterStore;
import com.ibm.uk.hursley.perfharness.Config;
import com.ibm.uk.hursley.perfharness.ControlThread;
import com.ibm.uk.hursley.perfharness.Log;
import com.ibm.uk.hursley.perfharness.WorkerThread;
import com.ibm.uk.hursley.perfharness.util.TypedPropertyException;

/**
 * @author jms
 *
 * TODO To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Style - Code Templates
 */
public class CSVStats extends Statistics {

	//Limit per thread reporting to 128 threads
	int diffarray[] =  new int[1024];

	final String sdfDatePattern = "yyyy/MM/dd";
	final String sdfTimePattern = "HH:mm:ss";
	final SimpleDateFormat sdfDate = new SimpleDateFormat(sdfDatePattern, Locale.US); 
	final SimpleDateFormat sdfTime = new SimpleDateFormat(sdfTimePattern, Locale.US); 

	//Handle for writing summary.txt run summaries
	private BufferedWriter bw = null;

	//Task providing period updates to system.out
	private TimerTask displayTask;
	
	private boolean cpuMonitoringStarted = false;
	private boolean monitorServerCPU = false;
	private boolean monitorDB2CPU = false;
	
	private PCounterNetwork pcntServer;
	private PCounterNetwork pcntDB2;
	private PCounterNative pcntClient;

	private PCounterStore clientResults;
	private PCounterStore serverResults;
	private PCounterStore db2Results;
	
	public static void registerConfig() {
		// static validation of parameters
		Config.registerSelf( CSVStats.class );		
	}
	
	public CSVStats(ControlThread parent) {
		super(parent);
		initialisePerfMon();
		
		if (interval > 0) {
			updateValues();
			ControlThread.getTaskScheduler().schedule( displayTask = new Display(), interval, interval );
		}
	}

	protected void initialisePerfMon() {
		pcntClient = new PCounterNative();
		pcntClient.addCountersFromFile();
		String serverHostName = Config.parms.getString("zs");
		if (!serverHostName.equals("")) {
			try {
				pcntServer = PCounterNetwork.create(serverHostName);
				pcntServer.addCountersFromFile();
				monitorServerCPU = true;
			} catch (Exception e) {
				Log.logger.log(Level.SEVERE,"Cannot access stats service on Server");
			}
		}
		String dbHostName = Config.parms.getString("zd");
		if (!dbHostName.equals("")) {
			try {
				pcntDB2 = PCounterNetwork.create(dbHostName);
				pcntDB2.addCountersFromFile();
				monitorDB2CPU = true;
			} catch (Exception e) {
				Log.logger.log(Level.SEVERE,"Cannot access stats service on DB2");
			}
		}
	}
	
	protected void openSummaryFile() {
		String filename = Config.parms.getString("zf");
		try {
			bw = new BufferedWriter(new FileWriter(filename, true));
		} catch (IOException ioe) {
			Log.logger.log(Level.SEVERE,"Cannot open CSV Stats output file:" + filename);
			bw = null;
		}
	}

	protected void closeSummaryFile() {
		try {
			bw.flush();
			bw.close();
		} catch (IOException ioe) {
			Log.logger.log(Level.SEVERE,"Cannot flush/close CSV Stats output file");
		}
		bw = null;
	}
	
	protected void writeSummary(StringBuffer sb) {
		openSummaryFile();
		if (bw != null) {
			try {
				bw.write(sb.toString());
				bw.newLine();
				bw.flush();
			} catch (IOException ioe) {
				Log.logger.log(Level.SEVERE,"Cannot write to output file");
				bw = null;
			}
		}
		closeSummaryFile();
	}
	
	public void timerStarted() {
		super.timerStarted();

		if (trimInterval==0) {
			//No trim interval, start CPU collection here
			System.out.println("CPU collection started from beginning of run");
			startCPU();
		}
	}
	
	protected void notifyMeasurementPeriod() {
		super.notifyMeasurementPeriod();
		//Start CPU collection here
		System.out.println("CPU collection started (trim)");
		//Allow CPU to be monitored by the CPU Timer thread (move from display thread)
		startCPU();
	}
	
	private void startCPU() {
		pcntClient.start();
		try {
			if (monitorServerCPU) pcntServer.start();
			if (monitorDB2CPU) pcntDB2.start();
		} catch (Exception e) {
			Log.logger.log(Level.SEVERE,"Cannot start collecting CPU data");
		}
		cpuMonitoringStarted = true;
	}

	private void stopCPU() {
		try {
			pcntClient.stop();
			clientResults = pcntClient.getResults();
			if (monitorServerCPU) {
				pcntServer.stop(); 
				serverResults = pcntServer.getResults();
			}
			if (monitorDB2CPU) { 
				pcntDB2.stop(); 
				db2Results = pcntDB2.getResults();
			}
		} catch (Exception e) {
			Log.logger.log(Level.SEVERE,"Cannot stat collecting CPU data");
		}
	}

	private int[] getCPU() {
		int[] array = new int[3];
		if (cpuMonitoringStarted == false) {
			int[] zeros = {0,0,0};
			return zeros;
		}
		try {
			array[0] = Math.round(clientResults.getCounterMeanByID(clientResults.getCounterIDByShortName("CPU")));
			if (monitorServerCPU) 
				array[1] = Math.round(serverResults.getCounterMeanByID(serverResults.getCounterIDByShortName("CPU")));
			if (monitorDB2CPU)  
				array[2] = Math.round(db2Results.getCounterMeanByID(db2Results.getCounterIDByShortName("CPU")));
		} catch (Exception e) {
			Log.logger.log(Level.SEVERE,"Cannot extract results from PCounterStores");
		}
		return(array);
	}
	
	public void stop() {
		super.stop();
		stopCPU();
		displayTask.cancel();
	}

	//creates String to output through Display Task every interval
	protected void makeStringBuffer( StringBuffer sb ) {
		if ( do_id.length()>0 ) {
			// Add our process id to output
			sb.append("id=").append(do_id).append(",");
		}
		updateValues();
		int total = 0;
		int diff;
		
		int shortest = curr.length<prev.length?curr.length:prev.length;
		for (int j = 0; j < shortest; j++) {
			diff = curr[j] - prev[j];
			if (do_perThread) {
				diffarray[j] = diff;
			}
			total += diff;
		}
		// Add on new threads
		for (int j = shortest; j<curr.length; j++ ) {
			total += curr[j];
			if (do_perThread) {
				diffarray[j] = curr[j];
			}
		}
		
		long period = (currMeasurementTime-prevMeasurementTime);
		// If within 0.5% of expected value
		if ( (Math.abs(period-interval)*1000)/interval<5 ) {
			// force a "perfect" value
			period=interval;
		}

		sb.append("Threads,").append( parent.getRunningWorkers() );
		sb.append(",MsgRate,").append(numberFormat.format((double) (total*1000) / period));
		if (do_perThread) {
			sb.append(",IndThreads");
			for (int i=0; i<curr.length; i++) {
				sb.append("," + diffarray[i]);
			}
		}
	}
	
	/* (non-Javadoc)
	 * @see com.ibm.uk.hursley.perfharness.stats.Statistics#printFinalSummary()
	 */
	public void printFinalSummary() {
		// TODO Auto-generated method stub
		boolean time_to_last_fire = Config.parms.getString("sd").toLowerCase().equals( Statistics.STATS_TLF );
		
		StringBuffer finalSummary = new StringBuffer();
		long totalIterations = 0;
		double totalDuration = 0;
		double totalRate = 0;
		int counted = 0;
		
		if ( Config.parms.getBoolean("su") ) {
			ListIterator<?> iter = workers.listIterator();
			while ( iter.hasNext() ) {
				WorkerThread worker = (WorkerThread)iter.next();
				
				long threadEndTime = worker.getEndTime();
				if ( threadEndTime==0 ) {
					// Use approximate value if we cannot find a specific answer.
					threadEndTime = endTime;
				}
				
				long threadStartTime;
				if ( trimInterval==0 ) {
					threadStartTime = worker.getStartTime();
				} else {
					threadStartTime = trimTime;
				}
				
				// Override the above for different timing modes
				if ( time_to_last_fire ) {
					threadStartTime = JVMStartTime;
				}
				
				long iterations = worker.getIterations();
				
				if ( trimTime!=0 ) {
					// The linkage between array index and ArrayList index is not proven.  This is a
					// dangerous line of code!
					iterations -= trimValues[ workers.indexOf( worker ) ];
				}
				
				long duration = threadEndTime-threadStartTime;
				double rate = (double) (iterations * 1000) / duration;
				
				// [#49447] totalSeconds misreported when no mesages are sent
				if ( threadStartTime==0 ) {
					// thread was never started but has been given a default endTime
					// NB. rate will always be 0
					duration = 0;
				}
				
				totalIterations += iterations;
				totalDuration += duration;
				totalRate += rate;
				counted++;

			} // end while workers
			
			// Maybe we should test to see if average duration > 90% intended
			// duration, else set MsgRate to zero. Shortened run implies exception
			// Config.rl() - Config.sw = intended
			double avgDuration = totalDuration / (1000 * counted);
			int targetTime = (Config.parms.getInt("rl") - trimInterval);
			if (avgDuration < (targetTime * 0.9)) {
					totalRate = 0;
					Log.logger.fine("Msg Rate set to 0 as test duration not valid; avgDuration: " + avgDuration + "; targetTime: " + targetTime + "; minTime: " + (0.9 * targetTime));
			}		
			String messageFile = Config.parms.getString("mf");
			String messageSize = extractMsgSize(messageFile);
			if (messageSize.equals("")) {
				// If we cant extract message size from supplied input file, use ms if available
				messageSize = "" + Config.parms.getInt("ms");
			}
			
			int[] cpuResults = getCPU();
			
			finalSummary.append(getTimeStamp());
			finalSummary.append("," + getTarget());
			finalSummary.append(",MsgSize," + messageSize);
			finalSummary.append(",Threads," + Config.parms.getInt("nt"));
			finalSummary.append(",MsgRate," + numberFormat.format(totalRate));
			finalSummary.append(",ClientCPU," + cpuResults[0]);
			finalSummary.append(",ServerCPU," + cpuResults[1]);
			finalSummary.append(",DB2CPU," + cpuResults[2]);
			finalSummary.append(",Iterations," + totalIterations);
			finalSummary.append(",AvgDuration," + numberFormat.format(avgDuration));
			finalSummary.append(",Tx," + getTx());
			finalSummary.append(",Persistent," + getPp());
			finalSummary.append(",UseCorrId," + getCorrID());

			writeSummary(finalSummary);
			System.out.println(finalSummary);
		} // end if su
	}

	public String extractMsgSize(String s) {
		if (s == null) return "";
		int i = s.length()-1;
		if (i < 0) return "";
		
		while ((i >= 0) && !(Character.isDigit(s.charAt(i)))) {
			i--;
		}
		int endindex = i + 2; // capturing b, K or M
		
		i = 0;
		while ((i < endindex) && !(Character.isDigit(s.charAt(i)))) {
			i++;
		}
		int startindex = i; 

		if ((startindex >= 0) && 
			(endindex >= 0) &&
			(startindex <= endindex)) {
			return s.substring(startindex, endindex);
		}
		return s;
	}
	
	private class Display extends TimerTask {
		private final StringBuffer sb = new StringBuffer(128);
		public void run() {
			sb.setLength(0);
			makeStringBuffer(sb);
			System.out.println(sb.toString());
		} 
	}
	
	public String getTimeStamp() {
		Date d = new Date();
		StringBuffer sb = new StringBuffer();
		sb.append(sdfDate.format(d));
		sb.append(",");
		sb.append(sdfTime.format(d));
		
		return sb.toString();
	}
	
	public String getTarget() {
		String dest="null";
		String suffix=null;
		try {
			dest = Config.parms.getString("iq");
		} catch (TypedPropertyException tpe) {
			try {
				dest = Config.parms.getString("ur");
			} catch (TypedPropertyException tpe2) {
				try {
					dest = Config.parms.getString("d");
				} catch (TypedPropertyException tpe3) {
				}
			}
		}
		try {
			suffix = Config.parms.getString("dx");
		} catch (TypedPropertyException tpe3) {
		}	
		if (suffix!=null) {
			dest += suffix;
		}
		return dest;
	}
	
	public Boolean getTx() {
		Boolean tx = false;
		try {
			tx = Config.parms.getBoolean("tx");
		} catch (TypedPropertyException tpe) {
		}
		return tx;
	}
	public Boolean getPp() {
		Boolean pp = false;
		try {
			pp = Config.parms.getBoolean("pp");
		} catch (TypedPropertyException tpe) {
		}
		return pp;
	}
	public Boolean getCorrID() {
		Boolean co = false;
		try {
			co = Config.parms.getBoolean("co");
		} catch (TypedPropertyException tpe) {
		}
		return co;
	}

	@Override
	public String requestStatistics() {
		// TODO Auto-generated method stub
		return null;
	}

}
