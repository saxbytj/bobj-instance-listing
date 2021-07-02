package bobjutil;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import com.businessobjects.sdk.plugin.desktop.webi.IWebi;
import com.businessobjects.sdk.plugin.desktop.webi.IWebiPrompt;
import com.businessobjects.sdk.plugin.desktop.webi.internal.IWebiPrompts;
import com.crystaldecisions.sdk.exception.SDKException;
import com.crystaldecisions.sdk.framework.*;
import com.crystaldecisions.sdk.occa.infostore.*;
import com.crystaldecisions.sdk.plugin.desktop.common.IReportParameter;
import com.crystaldecisions.sdk.plugin.desktop.common.IReportParameterValue;
import com.crystaldecisions.sdk.plugin.desktop.program.IProgramBase;
import com.crystaldecisions.sdk.plugin.desktop.report.IReport;
import com.crystaldecisions.sdk.plugin.destination.diskunmanaged.IDiskUnmanaged;
import com.crystaldecisions.sdk.plugin.destination.diskunmanaged.IDiskUnmanagedOptions;
import com.crystaldecisions.sdk.plugin.destination.ftp.IFTP;
import com.crystaldecisions.sdk.plugin.destination.smtp.ISMTP;
import com.crystaldecisions.sdk.plugin.destination.smtp.ISMTPOptions;
import com.crystaldecisions.sdk.properties.IProperties;

/**
 * This is program creates a CSV listing of all un-paused recurring Webi instances in specified 
 * BusinessObjects repository. The listing including details of recurrence, prompts, destinations 
 * and any event dependencies.
 *
 * The program may be run from InfoView compiled as a JAR and uploaded to the repository, or run 
 * with credentials to the repository as parameters.
 */
public class InstanceListing implements IProgramBase {
	// The maximum number of instances to retrieve from the repository in each loop.
	private static final int BATCH_SIZE = 1000;
	// InfoStore query for finding the name of an Event based on its Id.
	private static final String EVENT_QUERY = "SELECT SI_NAME FROM CI_SYSTEMOBJECTS WHERE SI_ID=%d";
	// InfoStore query for retrieving the Disk Destination plugin - needed to process disk destinations
	private static final String DISK_PLUGIN_QUERY = "SELECT SI_NAME, SI_SERVER_KIND, SI_PROGID " + 
												    "FROM CI_SYSTEMOBJECTS " + 
												    "WHERE SI_PARENTID=29 " +
												    "AND SI_NAME='CrystalEnterprise.DiskUnmanaged'";
	// InfoStore query for retrieving the SMTP Destination plugin - needed to process email destinations
	private static final String SMTP_PLUGIN_QUERY = "SELECT SI_NAME, SI_SERVER_KIND, SI_PROGID " +
												    "FROM CI_SYSTEMOBJECTS " + 
												    "WHERE SI_PARENTID=29 " +
												    "AND SI_NAME='CrystalEnterprise.SMTP'";
	// InfoStore query for retrieving Webi instances
	private static final String INSTANCE_QUERY = "SELECT TOP %d * " +
												 "FROM CI_INFOOBJECTS " + 
												 "WHERE SI_INSTANCE=1 " +
												 "AND SI_RECURRING=1 " +
												 "AND SI_SCHEDULE_STATUS != 8 " + // Exclude Paused Instances
												 "AND ( SI_PROGID_MACHINE IN('CrystalEnterprise.Webi','CrystalEnterprise.Report') OR SI_PROGID = 'CrystalEnterprise.Publication' )" +
												 "AND SI_ID > %d " + 
												 "ORDER BY SI_ID ASC"; 
	
	private String username;
	private String password;
	private String CMSName;
	private String authType;
	
	private Locale locale = Locale.ENGLISH;
	
	/**
	 * Parses the command line arguments provided to the program.
	 *
	 * @param args Array of command line arguments provided to the program.
	 */
	public void parseArgs(String[] args) throws SDKException {
		for (String arg : args) {
			String[] splitArg = arg.split("=");
			
			if (splitArg.length == 2) {
				String argName = splitArg[0].toUpperCase();
				String argVal = splitArg[1];
				
				if (argName.equals("USER") || argName.equals("USERNAME")) {
					setUsername(argVal);
				}
				else if (argName.equals("PASSWORD")) {
					setPassword(argVal);
				}
				else if (argName.equals("CMSNAME")) {
					setCMSName(argVal);
				}
				else if (argName.equals("AUTHTYPE")) {
					setAuthType(argVal);
				} else {
					throw new SDKException.InvalidArg("Unknown argument: " + arg);
				}
				
			} else {
				throw new SDKException.InvalidArg("Failed to parse parameter: " + arg);
			}
		}
	}
	
	/**
	 * Entry point when running from command line instead of InfoView.
	 * This is creates an enterprise session based on command line arguments before calling {@link au.com.rac.bobjutil.InstanceListing#run run}.
	 */
	public static void main(String[] args) {
		IEnterpriseSession es = null;
		
		try {
			InstanceListing il = new InstanceListing();
			il.parseArgs(args);
			
			es = CrystalEnterprise.getSessionMgr().logon(il.getUsername(), il.getPassword(), il.getCMSName(), il.getAuthType());

			IInfoStore iStore = (IInfoStore) es.getService("", "InfoStore");
			
			il.run(es, iStore, null);
			
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		finally {
			if (es != null) {
				es.logoff();
			}
		}
	}

	/**
	 * Entry point when running the program from InfoView.
	 */
	@Override
	public void run(IEnterpriseSession session, IInfoStore iStore, String[] args)
			throws SDKException {
		try {
			int maxId = 0;
			
			for (;;) {
				IInfoObjects infoObjs = iStore.query(String.format(INSTANCE_QUERY, BATCH_SIZE, maxId));
				
				if (infoObjs.size() == 0) 
					break;
				
				if (maxId == 0) {
					System.out.println("CUID,Path,Title,Owner,Prompts,Schedule,Destinations,Next Run,Events");
				}
				
				for (Object obj : infoObjs) {
					IInfoObject infoObj = (IInfoObject)obj;
					
					maxId = infoObj.getID();
					
					String path = getInfoObjectPath(infoObj);
					String title = infoObj.getTitle();
					String cuid = infoObj.getCUID();
					String owner = infoObj.getOwner();
					
					IProperties props = infoObj.properties();
					String nextRun = props.getString("SI_NEXTRUNTIME");
					
					ISchedulingInfo schedulingInfo = infoObj.getSchedulingInfo();
					
					String promptSummary = processPrompts(infoObj);
					
					String scheduleDesc = processScheduleFrequency(schedulingInfo);
					
					String destSummary = processDestinations(iStore, schedulingInfo);
					
					String eventSummary = processEvents(iStore, schedulingInfo);
					
					System.out.printf("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"\n", 
							cuid, path, title, owner, promptSummary, scheduleDesc, destSummary, nextRun, eventSummary);
				}
				
			}
		} 
		catch (SDKException e) {
			throw e;
		}
		catch (Exception e) {
			throw new SDKException.ExceptionWrapper(e);
		}
	}

	/**
	 * Produce a summary of the prompts for the specified Webi instance.
	 * @param infoObj InfoObject representing the Web Intelligence Instance to process
	 * @return textual summary of the prompts for the Web Intelligence Instance
	 */
	private String processPrompts(IInfoObject infoObj) {
		StringBuilder promptStrBuilder = new StringBuilder();
		String progId = null;
		try {
			progId = infoObj.getProgID();
		} catch (SDKException e) {
			// Do Nothing
		}
		
		if ("CrystalEnterprise.Webi".equals(progId)) {
			try {
				IWebi webi = (IWebi)infoObj;
				
				IWebiPrompts prompts = webi.getPrompts();
				
				for (IWebiPrompt prompt : prompts) {
					String promptName = prompt.getName();
					
					List<String> values = prompt.getValues();
					
					if (values.size() == 1) {
						promptStrBuilder.append(String.format("%s='%s';", promptName, values.get(0)));
					}
					else if (values.size() > 1) {
						promptStrBuilder.append(String.format("%s={", promptName));
						for (String value : values) {
							promptStrBuilder.append(String.format("'%s';", value));
						}
						promptStrBuilder.append("};");
					} 
				}
			} 
			catch (SDKException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else if ("CrystalEnterprise.Report".equals(progId)){ 
			try {
				IReport report = (IReport)infoObj;
				
				@SuppressWarnings("unchecked")
				List<IReportParameter> params = report.getReportParameters();
				
				for (IReportParameter param : params) {
					String promptName = param.getParameterName();
					
					@SuppressWarnings("unchecked")
					List<IReportParameterValue> values = param.getCurrentValues();
					
					if (values.size() == 1) {
						promptStrBuilder.append(String.format("%s='%s';", promptName, values.get(0).makeDisplayString(locale)));
					}
					else if (values.size() > 1) {
						promptStrBuilder.append(String.format("%s={", promptName));
						for (IReportParameterValue value : values) {
							promptStrBuilder.append(String.format("'%s';", value.makeDisplayString(locale)));							
						}
						promptStrBuilder.append("};");
					}
				}
			}
			catch (SDKException e) {
				e.printStackTrace();
			}
			
		}
		else if ("CrystalEnterprise.Publication".equals(progId)){ 
			promptStrBuilder.append("Not Available");
		}
		return promptStrBuilder.toString();
	}
	
	/**
	 * Produce a summary of the recurrence frequency for the specified Webi instance.
	 * @param schedulingInfo ISchedulingInfo object for the Web Intelligence Instance to process
	 * @return textual summary of the recurrence frequency for the Web Intelligence Instance
	 */
	private String processScheduleFrequency(ISchedulingInfo schedulingInfo) {
		String frequencyDescription = null;
		
		switch (schedulingInfo.getType()) {
			case CeScheduleType.FIRST_MONDAY:
				frequencyDescription = "First Monday of month";
				break;
				
			case CeScheduleType.LAST_DAY:
				frequencyDescription = "Last day of month";
				break;
				
			case CeScheduleType.NTH_DAY:
				frequencyDescription = String.format("Day %d of month", schedulingInfo.getIntervalNthDay());
				break;
				
			case CeScheduleType.WEEKLY:
			case CeScheduleType.CALENDAR:
				frequencyDescription = getWeeklyFreqDescription(schedulingInfo);
				break;
				
			case CeScheduleType.DAILY:
				int dailyInterval = schedulingInfo.getIntervalDays();
				if (dailyInterval == 1) {
					frequencyDescription = "Daily";
				}
				else {
					frequencyDescription = String.format("Every %d days", dailyInterval);
				}
				break;
				
			case CeScheduleType.MONTHLY:
				int monthlyInterval = schedulingInfo.getIntervalMonths();
				if (monthlyInterval == 1) {
					frequencyDescription = "Every month";
				} else {
					frequencyDescription = String.format("Every %d months", monthlyInterval);
				}
				break;
				
			// No support for Calendar (template) and hourly as these aren't commonly used.
			case CeScheduleType.CALENDAR_TEMPLATE:
				frequencyDescription = "Calendar Template";
				break;
			case CeScheduleType.HOURLY:
				frequencyDescription = "Hourly";
				break;
			default:
				frequencyDescription = Integer.toString(schedulingInfo.getType());
				break;
		}
		
		return frequencyDescription;
	}
	
	/**
	 * Produce a summary of the destinations for the specified Webi instance.
	 *
	 * @param iStore InfoStore object used to retrieve destination plugins
	 * @param schedulingInfo ISchedulingInfo object for the Web Intelligence Instance to process
	 *
	 * @return textual summary of the destinations for the Web Intelligence Instance
	 */
	private String processDestinations(IInfoStore iStore, ISchedulingInfo schedulingInfo) throws SDKException {
		StringBuilder destStrBuilder = new StringBuilder();
		for (Object destObj : schedulingInfo.getDestinations()) {
			IDestination dest = (IDestination) destObj;
			IProperties destProps = dest.properties();
			
			IDestinationPlugin destPlugin = null;
			// The SI_PROGID property of the destination tells us what type of plugin is required to retrieve 
			// details of the destination.
			String progID = destProps.getString("SI_PROGID");
			
			if (progID.equals(ISMTP.PROGID)) {
				destPlugin = (IDestinationPlugin) iStore.query(SMTP_PLUGIN_QUERY).get(0);
				dest.copyToPlugin(destPlugin);
				
				ISMTPOptions smtpOpts = (ISMTPOptions) destPlugin.getScheduleOptions();
				
				@SuppressWarnings("unchecked")
				List<String> toAddrList = smtpOpts.getToAddresses();
				@SuppressWarnings("unchecked")
				List<String> ccAddrList = smtpOpts.getCCAddresses();
        
        @SuppressWarnings("unchecked")
				List<String> bccAddrList = smtpOpts.getBCCAddresses();

				List<String> addrList = new ArrayList<String>(toAddrList);
        addrList.addAll(ccAddrList);
        addrList.addAll(bccAddrList);
				
				destStrBuilder.append("Email={");
				for (String addr : addrList) {
					destStrBuilder.append(String.format("'%s';",addr));
				}
				destStrBuilder.append("}");
			}
			else if (progID.equals(IFTP.PROGID)) {
				// FTP destinations not configured or used at time of program development
				destStrBuilder.append("FTP (Unsupported)");
			} 
			else if (progID.equals(IDiskUnmanaged.PROGID)) {
				destPlugin = (IDestinationPlugin) iStore.query(DISK_PLUGIN_QUERY).get(0);
				dest.copyToPlugin(destPlugin);
				
				IDiskUnmanagedOptions diskUnmanagedOpts = (IDiskUnmanagedOptions) destPlugin.getScheduleOptions();
				
				@SuppressWarnings("unchecked")
				List<String> destFiles = diskUnmanagedOpts.getDestinationFiles();
				
				destStrBuilder.append(String.format("Disk='%s';",destFiles.get(0)));
			}
		}
		
		return destStrBuilder.toString();
	}

	/**
	 * Produce a summary of the event dependencies for the specified instance.
	 *
	 * @param iStore InfoStore object used to retrieve event names
	 * @param schedulingInfo ISchedulingInfo object for the Web Intelligence Instance to process
	 *
	 * @return textual summary of the event dependencies for the Web Intelligence Instance
	 */
	private String processEvents(IInfoStore iStore, ISchedulingInfo schedulingInfo) throws SDKException {
		StringBuilder eventStrBuilder = new StringBuilder();
		
		IEvents depEventIds = schedulingInfo.getDependencies();
		
		for (Object eventIdObj : depEventIds) {
			IInfoObjects eventInfoObjs = iStore.query(String.format(EVENT_QUERY, (Integer)eventIdObj));
			
			IInfoObject eventObj = (IInfoObject) eventInfoObjs.get(0);
			
			eventStrBuilder.append(String.format("'%s' (%d);", eventObj.getTitle(), (Integer)eventIdObj));
		}
		
		return eventStrBuilder.toString();
	}

	/**
	 * Returns the folder path for the specified document/instance.
	 *
	 * @param infoObject The specified document/instance 
	 * @return folder path for the specified document/instance
	 */
	private String getInfoObjectPath(IInfoObject infoObject) throws SDKException {
		StringBuilder pathStrBuilder = new StringBuilder();
		while (infoObject.getParentID() != 0) {
			infoObject = infoObject.getParent();
			pathStrBuilder.insert(0, infoObject.getTitle());
			pathStrBuilder.insert(0, "/");
		 }
		return pathStrBuilder.toString();
	}

	/**
	 * Produce a textual description for the scheduling recurrence of a 'WEEKLY' type.
	 * 
	 * @param schedulingInfo ISchedulingInfo for the instance to process
	 * 
	 * @return textual description of the scheduling recurrence
	 */
	private static String getWeeklyFreqDescription(ISchedulingInfo schedulingInfo) {
		String result;
		
		int freqType = schedulingInfo.getType();
		if (freqType != CeScheduleType.WEEKLY && freqType != CeScheduleType.CALENDAR) {
			throw new IllegalArgumentException("Supplied IScechedulingInfo was not of type WEEKLY or CALENDAR.");
		}
		
		ICalendarRunDays days = schedulingInfo.getCalendarRunDays();
		StringBuilder daysStringBuilder = new StringBuilder();
		for (Object dayObj : days) {
			ICalendarDay calendarDay = (ICalendarDay) dayObj;
			int dayOfWeek = calendarDay.getDayOfWeek();
			int weekOfMonth = calendarDay.getWeekNumber();
			if (dayOfWeek == ICalendarDay.ALL) {
				daysStringBuilder.append("each day ");
			}
			else {
				Calendar dayCal = Calendar.getInstance();
				dayCal.set(Calendar.DAY_OF_WEEK, dayOfWeek);
				String dayStr = dayCal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, java.util.Locale.UK);
				daysStringBuilder.append(dayStr);
			}
			if (weekOfMonth != ICalendarDay.ALL) {
				daysStringBuilder.append(String.format("of week %d of each month", weekOfMonth));
			}
			daysStringBuilder.append(", ");
		}
		String daysString = daysStringBuilder.toString();
		// Remove last comma
		daysString = daysString.substring(0, daysString.length()-2);
		
		if ("Mon, Tue, Wed, Thu, Fri".equals(daysString)) {
			result = "Every weekday";
		}
		else if ("Sun, Mon, Tue, Wed, Thu, Fri, Sat".equals(daysString)) {
			result = "Every day";
		}
		else {
			result = String.format("Weekly on %s", daysString);
		}
		return result;
	}
	
	// Boilerplate accessors & mutators for repository credential fields
	String getUsername() { return username; }
	void setUsername(String username) { this.username = username; }

	String getPassword() { return password; }
	void setPassword(String password) { this.password = password; }

	String getCMSName() { return CMSName; }
	void setCMSName(String cmsname) { this.CMSName = cmsname; }

	String getAuthType() { return authType; }
	void setAuthType(String authType) { this.authType = authType; }
}
