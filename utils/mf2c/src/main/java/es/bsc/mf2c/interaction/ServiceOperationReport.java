/*         
 *  Copyright 2002-2018 Barcelona Supercomputing Center (www.bsc.es)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package es.bsc.mf2c.interaction;

//import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.xml.bind.annotation.XmlRootElement;


@XmlRootElement
public class ServiceOperationReport {

    private static final SimpleDateFormat DATE_FORMATTER = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    private static final Client client;
    private final Thread updater = new Thread() {
        @Override
        public void run() {
            String expectedEndTime = "";
            while (execution_length == 0) {
                if (expectedEndTime.compareTo(expected_end_time) != 0) {
                    updateReport();
                    expectedEndTime = expected_end_time;
                }
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ie) {
                    //Do nothing
                }
            }
            updateReport();
        }
    };

    static {
        Client c = null;
        try {
            TrustManager[] noopTrustManager = new TrustManager[]{
                new X509TrustManager() {

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }

                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                    }
                }
            };

            SSLContext sc = SSLContext.getInstance("ssl");
            sc.init(null, noopTrustManager, null);

            c = ClientBuilder.newBuilder().sslContext(sc).build();
        } catch (Exception e) {
            e.printStackTrace();
            c = null;
        }
        client = c;

        // Install the all-trusting host verifier
        HttpsURLConnection.setDefaultHostnameVerifier((String hostname, SSLSession session) -> true);
    }

    private String targetAddress;

    // Requesting serviceId
    private ServiceInstance requesting_application_id;

    // UUID of the agent where the LcM called the startApplication method 
    private String compute_node_id;

    // Class and method names of the invoked operation 
    private String operation_name;

    // Identifier of the operation invocation 
    private String operation_id;

    // The difference, measured in milliseconds, between the operation start time and midnight, January 1, 1970 UTC
    private String start_time;

    // The difference, measured in milliseconds, between the operation forecasted end time time and midnight, January 1, 1970 UTC
    private String expected_end_time;

    // The difference, measured in milliseconds, between the operation start and end time
    private float execution_length;

    // operation result
    private String result;

    private String cimiResource = null;

    private AccessControlList acl = new AccessControlList();

    public ServiceOperationReport() {
        acl.setOwner(new Owner("test", "ROLE"));
        acl.addRule(new Rule("test", "ALL", "ROLE"));
        acl.addRule(new Rule("ANON", "ALL", "ROLE"));
    }

    public ServiceOperationReport(String targetAddress,
            String service_instance,
            String compute_node_id,
            String operation_name) {
        this();
        this.targetAddress = targetAddress;
        this.requesting_application_id = new ServiceInstance("service-instance/" + service_instance);
        this.compute_node_id = compute_node_id;
        this.operation_name = operation_name;
        this.operation_id = "";
        Date startDate = new Date(0);
        this.start_time = DATE_FORMATTER.format(startDate);
        Date expectedEndDate = new Date(0);
        this.expected_end_time = DATE_FORMATTER.format(expectedEndDate);

        this.execution_length = 0;

        this.result = "";
        createReport();
        updater.start();
    }

    public void startOperation(String operationId) {
        Date startDate = new Date(System.currentTimeMillis());
        this.start_time = DATE_FORMATTER.format(startDate);
        this.operation_id = operationId;
        updater.interrupt();
    }

    public void progress(long expectedEndTime) {
        Date endDate = new Date(expectedEndTime);
        this.expected_end_time = DATE_FORMATTER.format(endDate);
    }

    public void completed(long length, byte[] result) {
        Date endDate = new Date(System.currentTimeMillis());
        this.expected_end_time = DATE_FORMATTER.format(endDate);
        this.execution_length = length;
        if (result != null) {
            this.result = Base64.getEncoder().encodeToString(result);
        }
        updater.interrupt();
    }

    public void setCompute_node_id(String compute_node_id) {
        this.compute_node_id = compute_node_id;
    }

    public String getCompute_node_id() {
        return compute_node_id;
    }

    public void setExecution_length(float execution_length) {
        this.execution_length = execution_length;
    }

    public float getExecution_length() {
        return execution_length;
    }

    public void setExpected_end_time(long expected_end_time) {
        Date expectedEndDate = new Date(expected_end_time);
        this.expected_end_time = DATE_FORMATTER.format(expectedEndDate);
    }

    public void setExpected_end_time(String date) {
        this.expected_end_time = date;
    }

    public String getExpected_end_time() {
        return expected_end_time;
    }

    public void setOperation_id(String operation_id) {
        this.operation_id = operation_id;
    }

    public String getOperation_id() {
        return operation_id;
    }

    public void setOperation_name(String operation_name) {
        this.operation_name = operation_name;
    }

    public String getOperation_name() {
        return operation_name;
    }

    public void setResult(byte[] result) {
        this.result = Base64.getEncoder().encodeToString(result);
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getResult() {
        return result;
    }

    public void setRequesting_application_id(ServiceInstance requesting_application_id) {
        this.requesting_application_id = requesting_application_id;
    }

    public ServiceInstance getRequesting_application_id() {
        return requesting_application_id;
    }

    public void setStart_time(long start_time) {
        Date startDate = new Date(start_time);
        this.start_time = DATE_FORMATTER.format(startDate);
    }

    public void setStart_time(String start_time) {
        this.start_time = start_time;
    }

    public String getStart_time() {
        return start_time;
    }

    public AccessControlList getAcl() {
        return acl;
    }

    public void setAcl(AccessControlList acl) {
        this.acl = acl;
    }

    private void createReport() {
        if (targetAddress != null) {
            WebTarget target = client.target(targetAddress);
            target = target.path("service-operation-report");
            Response r;

            // DEBUGGER OF SERVICE_OPERATION_REPORT IN JSON
/* 
            try {
                ObjectMapper mapper = new ObjectMapper();
                String json = mapper.writeValueAsString(this);
                System.out.println("Publishing start of an operation execution:\n" + json);

                String request = json.replace("\"", "\\\"");
                StringBuilder reqCmd = new StringBuilder("curl");
                reqCmd.append(" -H \"slipstream-authn-info: internal ADMIN\"");
                reqCmd.append(" -H \"Content-type: application/json\"");
                reqCmd.append(" -d \"").append(request).append("\"");
                reqCmd.append(" -X POST");
                reqCmd.append(" " + target.getUri().toString());
                reqCmd.append(" --insecure");

                System.out.println("Similar Command:\n" + reqCmd.toString());

            } catch (Exception e) {
                e.printStackTrace();
            }
             */
            try {
                r = target
                        .request(MediaType.APPLICATION_JSON)
                        .header("slipstream-authn-info", "internal ADMIN")
                        .post(Entity.json(this), Response.class
                        );

                if (r.getStatusInfo()
                        .getStatusCode() != 201) {
                    System.err.println(r.getStatusInfo().getReasonPhrase());
                    System.err.println(r.readEntity(String.class));
                } else {
                    String response = r.readEntity(String.class);
                    int i = response.indexOf("resource-id");
                    response = response.substring(i);
                    i = response.indexOf(":");
                    response = response.substring(i + 1);
                    i = response.indexOf("\"");
                    response = response.substring(i + 1);
                    i = response.indexOf("\"");
                    this.cimiResource = response.substring(0, i);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void updateReport() {
        if (targetAddress != null) {
            WebTarget target = client.target(targetAddress);
            target = target.path(this.cimiResource);
            Response r;
            /*
            try {
                // DEBUGGER OF SERVICE_OPERATION_REPORT IN JSON
                ObjectMapper mapper = new ObjectMapper();
                String json = mapper.writeValueAsString(this);
                System.out.println("Publishing update of an operation execution:\n" + json);

                String request = json.replace("\"", "\\\"");
                StringBuilder reqCmd = new StringBuilder("curl");
                reqCmd.append(" -H \"slipstream-authn-info: super ADMIN\"");
                reqCmd.append(" -H \"Content-type: application/json\"");
                reqCmd.append(" -d \"").append(request).append("\"");
                reqCmd.append(" -X PUT");
                reqCmd.append(" " + target.getUri().toString());
                reqCmd.append(" --insecure");

                System.out.println("Similar Command:\n" + reqCmd.toString());

            } catch (Exception e) {
                e.printStackTrace();
            }
             */

            try {
                r = target
                        .request(MediaType.APPLICATION_JSON)
                        .header("slipstream-authn-info", "internal ADMIN")
                        .put(Entity.json(this), Response.class
                        );

                if (r.getStatusInfo()
                        .getStatusCode() != 200) {
                    System.err.println(r.getStatusInfo().getReasonPhrase());
                    System.err.println(r.readEntity(String.class));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        ServiceOperationReport report = new ServiceOperationReport(
                "https://localhost/api", // targetAddress
                UUID.randomUUID().toString(), // service_instance
                "127.0.0.1", // compute_node_id
                "test" // operation_name
        );
        report.startOperation(UUID.randomUUID().toString());
        Thread.sleep(15_000);
        report.completed(15_200, "It Works".getBytes());
    }

}
