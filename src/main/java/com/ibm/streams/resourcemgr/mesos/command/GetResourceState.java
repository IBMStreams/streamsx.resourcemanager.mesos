package com.ibm.streams.resourcemgr.mesos.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

//import org.json.simple.JSONArray;
//import org.json.simple.JSONObject;
//import org.json.simple.parser.JSONParser;

import com.ibm.streams.resourcemgr.ClientConnection;
import com.ibm.streams.resourcemgr.ClientConnectionFactory;
import com.ibm.streams.resourcemgr.ResourceException;
import com.ibm.streams.resourcemgr.mesos.StreamsMesosConstants;

public class GetResourceState {
	static public void main(String[] args) {
		String zkConnect = null;
		String domain = null;
		String resourceType = StreamsMesosConstants.RESOURCE_TYPE_DEFAULT;
		boolean longVersion = false;
		for (int index = 0; index < args.length; ++index) {
			String arg = args[index];
			if (arg.equals(StreamsMesosConstants.ZK_ARG) && index + 1 < args.length) {
				zkConnect = args[++index];
			}
			else if (arg.equals(StreamsMesosConstants.DOMAIN_ID_ARG) && index + 1 < args.length) {
				domain = args[++index];
			}
			else if (arg.equals(StreamsMesosConstants.TYPE_ARG) && index + 1 < args.length) {
				resourceType = args[++index];
			}
			else if (arg.equals(StreamsMesosConstants.CUSTOM_ARG_LONG)) {
				longVersion = true;
			}
		}
		
		if (zkConnect == null) {
			zkConnect = System.getenv("STREAMS_ZKCONNECT");
		}
		
		if (zkConnect == null) {
			System.out.println("usage:  --zkconnect <host:port,...>");
		}
		
		ClientConnection client = null;
		try {
			// Connect to the Resource Manager
			if (domain != null) {
				client = ClientConnectionFactory.createConnection("GetResourceStateCommand", resourceType, zkConnect, domain);
			}
			else {
				client = ClientConnectionFactory.createConnection("GetResourceStateCommand",  resourceType, zkConnect);
			}
			client.connect();
		} catch (ResourceException e) {
			if (domain != null)
				System.out.println("Error: Could not connect to Streams Mesos Resource Manager with domain = " + domain + ": " + e.toString());
			else
				System.out.println("Error: Could not connect to Streams Mesos Resource Manager for all domains (no domain specified): " + e.toString());
			System.exit(1);
		}
		
		try {
			// Create the command json
			ObjectMapper mapper = new ObjectMapper();
			ObjectNode data = mapper.createObjectNode();
			
			data.put(StreamsMesosConstants.CUSTOM_COMMAND, StreamsMesosConstants.CUSTOM_COMMAND_GET_RESOURCE_STATE);
			data.put(StreamsMesosConstants.CUSTOM_PARM_LONG, String.valueOf(longVersion));
			
			// Call the command on the client
			String response = client.customCommand(data.toString(), Locale.getDefault());
			
			// Parse the JSON
			ObjectNode responseObj = (ObjectNode) mapper.readTree(response);
			ArrayNode resources = (ArrayNode)responseObj.get(StreamsMesosConstants.CUSTOM_RESULT_RESOURCES);
			
			// Create output table headings
			String colHeadingsBase[] = {"Display Name", "Native Name", "Mesos Task ID", "Resource State", "Request State", "Completion Status", "Host Name", "Is Master"};
			String colHeadingsLong[] = {"Tags","Cores","Memory"};
			List<String> colNameList = new ArrayList<String>();
			colNameList.addAll(Arrays.asList(colHeadingsBase));
			if (longVersion)
				colNameList.addAll(Arrays.asList(colHeadingsLong));
			
			// Get the table body
			List<String[]> tableBody = new ArrayList<String[]>();
			for (int i = 0; i < resources.size(); i++) {
				JsonNode resource = resources.get(i);
				String row[] = new String[colNameList.size()];
				row[0] = resource.get(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_STREAMS_ID).asText();
				row[1] = resource.get(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_ID).asText();
				row[2] = resource.get(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_TASK_ID).asText();
				row[3] = resource.get(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_RESOURCE_STATE).asText();
				row[4] = resource.get(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_REQUEST_STATE).asText();
				row[5] = resource.get(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_COMPLETION_STATUS).asText();
				row[6] = resource.get(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_HOST_NAME).asText();
				row[7] = resource.get(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_IS_MASTER).asText();
				if (longVersion) {
					row[8] = resource.get(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_TAGS).asText();
					row[9] = resource.get(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_CORES).asText();
					row[10] = resource.get(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_MEMORY).asText();

				}
				tableBody.add(row);
			}
			
			// Get width of headings 
			int colWidths[] = new int[colNameList.size()];
			for (int i = 0; i < colNameList.size(); i++) {
				colWidths[i] = colNameList.get(i).length();
			}
			
			// Get max widths including body with 
			for (String[] row : tableBody) {
				for (int i = 0; i < colNameList.size(); i++) {
					colWidths[i] = Math.max(colWidths[i], row[i].length());
				}
			}
			
			// Create output format string
			int colGap = 2;
			StringBuffer fmt = new StringBuffer();
			for (int i = 0; i < colNameList.size(); i++) {
				fmt.append("%-" + String.valueOf(colWidths[i] + colGap) + "s");
			}
			fmt.append("\n");
			
			// Output the Table
			if (!longVersion) {
				System.out.format(fmt.toString(), colNameList.get(0), colNameList.get(1), colNameList.get(2), colNameList.get(3), colNameList.get(4), colNameList.get(5), colNameList.get(6), colNameList.get(7));
			} else {
				System.out.format(fmt.toString(), colNameList.get(0), colNameList.get(1), colNameList.get(2), colNameList.get(3), colNameList.get(4), colNameList.get(5), colNameList.get(6), colNameList.get(7), colNameList.get(8), colNameList.get(9), colNameList.get(10));

			}
			for (String[] row: tableBody) {
				if (!longVersion) {
					System.out.format(fmt.toString(), row[0], row[1], row[2], row[3], row[4], row[5], row[6], row[7]);
				} else {
					System.out.format(fmt.toString(), row[0], row[1], row[2], row[3], row[4], row[5], row[6], row[7], row[8], row[9], row[10]);
	
				}
			}
			
		
		} catch (Throwable t) { t.printStackTrace(); }
		finally {
			if (client != null) {
				try {client.close(); } catch (Throwable t) {}
			}
		}
	}

}
