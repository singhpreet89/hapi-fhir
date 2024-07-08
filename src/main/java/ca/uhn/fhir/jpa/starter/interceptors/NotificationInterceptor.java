package ca.uhn.fhir.jpa.starter.interceptors;

import java.net.URI;
import org.slf4j.Logger;
import java.io.IOException;
import org.json.JSONObject;
import org.slf4j.LoggerFactory;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import ca.uhn.fhir.parser.IParser;
import java.net.http.HttpResponse;
import java.net.URISyntaxException;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.api.Interceptor;
import java.net.http.HttpResponse.BodyHandlers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import jakarta.servlet.http.HttpServletResponse;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.ResponseDetails;
import org.apache.http.client.HttpResponseException;
import org.hl7.fhir.instance.model.api.IBaseResource;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;

/**
 * 
 * * This Interceptor makes an API call to the Homecare Hub 
 * * The API calls initiated here will be Handled by "Fhir/NotificationController" in HCH
 * * The API calls initiated are non-blocking by default (i.e. The External Hospital Systems will receive the response back even if an exception is generated in this Interceptor)
 * *    Blocking respons eenable: change the return type of serverOutgoingResponse from void to boolean and return false is any exception is thrown ans return true for successfull execution 
 */
@Component
@Interceptor
public class NotificationInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(NotificationInterceptor.class);

    /**
     * 
     * @param RequestDetails requestDetails
     * @param HttpServletRequest servletRequest
     * @param ServletRequestDetails servletRequestDetails
     * @param IBaseResource iBaseResource
     * @param ResponseDetails responseDetails
     * @param HttpServletResponse httpServletResponse
     * 
     */
    @Hook(Pointcut.SERVER_OUTGOING_RESPONSE)
    public void serverOutgoingResponse(RequestDetails requestDetails, HttpServletRequest servletRequest, ServletRequestDetails servletRequestDetails, IBaseResource iBaseResource, ResponseDetails responseDetails, HttpServletResponse httpServletResponse) {
        try {
            System.out.println("--------------- NOTIFICATION INTERCEPTOR ---------------");
            
            Object[] contextAndId   = getResourceContext(iBaseResource);
            String resourceId       = (String) contextAndId[0];
            FhirContext context     = (FhirContext) contextAndId[1];
            
            IParser parser      = context.newJsonParser(); // Or context.newXmlParser() for XML
            String responseBody = parser.encodeResourceToString(iBaseResource);
            
            /**
             * ? Appending the encrypted 'authenticatedUserLoginId' with the 'responseBody' before being sent over to the POST Request
             */
            // String authenticatedUserLoginId = (String) servletRequest.getAttribute("authenticatedUserLoginId"); // Grab the value of 'authenticatedUserLoginId' initialized in AuthenticationInterceptor
            String authenticatedUserLoginId = (String) servletRequestDetails.getUserData().get("authenticatedUserLoginId"); // Grab the value of 'authenticatedUserLoginId' initialized in AuthenticationInterceptor
            JSONObject jsonResponse         = new JSONObject(responseBody);                                     // Parse the responseBody into a JSON object
            
            jsonResponse.put("authenticatedUserLoginId", authenticatedUserLoginId);                             // Add the authenticatedUserLoginId to the JSON object
            responseBody                    = jsonResponse.toString();                                          // Convert the modified JSON object back to a string

            String requestURI           = servletRequestDetails.getServletRequest().getRequestURI().toString(); // Alternatively: Could have also used HttpServletRequest servletRequest object's getRequestURI() method directly
            String requestHttpMethod    = servletRequestDetails.getServletRequest().getMethod();                // Alternatively: Could have also used HttpServletRequest servletRequest object's getMethod() method directly
            
            /**
             * ? Failsafe to handle cases that were not handled by IncomingRequestInterceptor
             */
            if(requestURI.startsWith("/fhir/Patient")) {
                notifyHCH(requestHttpMethod, resourceId, responseBody); // API Call to HCH
            }
        // } catch (IOException | InterruptedException | URISyntaxException | RuntimeException | Exception exception) {
        } catch (Exception exception) {
            System.out.println("************************** Notification Interceptor EXCEPTION **************************");
            
            if (exception instanceof HttpResponseException) {
                HttpResponseException httpResponseException = (HttpResponseException) exception;
                logger.error("HTTP Response Exception: {}", httpResponseException.getMessage());
                
                
                String[] parts = httpResponseException.getMessage().split(":");
                if (parts.length > 1) {
                    logger.error("Backend Error Code: {}", parts[0].trim());
                }
            } else if(exception instanceof URISyntaxException) {
                logger.error("URISyntaxException occurred: {}", exception.getMessage());
            } else {
                logger.error("Exception: {}", exception.getMessage());
            }
            exception.printStackTrace();
        }
    }

    /**
     * * Select the appropriate FhirContext version and resourceId based on the IBaseResource iBaseResource instance
     * * The resourceId is used for PUT / PATCH and DELETE operation thorugh an API call to HCH      
     * 
     * @param IBaseResource resource
     * @return
     */
    private Object[] getResourceContext(IBaseResource iBaseResource) {
        String resourceId   = null;
        FhirContext context = null;
        if (iBaseResource instanceof org.hl7.fhir.r5.model.Resource) {
            resourceId = ((org.hl7.fhir.r5.model.Resource) iBaseResource).getIdElement().getIdPart();
            context = FhirContext.forR4();
        } else if (iBaseResource instanceof org.hl7.fhir.r4.model.Resource) {
            resourceId = ((org.hl7.fhir.r4.model.Resource) iBaseResource).getIdElement().getIdPart();
            context = FhirContext.forR4();
        } else if (iBaseResource instanceof org.hl7.fhir.dstu3.model.Resource) {
            resourceId = ((org.hl7.fhir.dstu3.model.Resource) iBaseResource).getIdElement().getIdPart();
            context = FhirContext.forDstu3();
        } else if (iBaseResource instanceof org.hl7.fhir.dstu2.model.Resource) {
            resourceId = ((org.hl7.fhir.dstu2.model.Resource) iBaseResource).getIdElement().getIdPart();
            context = FhirContext.forDstu2();
        }
        return new Object[] {resourceId, context};  // Converting String resourceId to an object, context is already an object 
    }

    /**
     * * This method is responsible for making an API call to HCH if the external Hospital Sytem makes a request for Creating a new Patient / Unpdating an existing Patient / Deleting an existing Patient
     * 
     * @param String requestHttpMethod
     * @param String resourceId
     * @param String responseBody
     * 
     * @throws URISyntaxException
     * @throws IOException
     * @throws InterruptedException
     */
    private void notifyHCH(String requestHttpMethod, String resourceId, String responseBody) throws URISyntaxException, IOException, InterruptedException, HttpResponseException {
        String NOTIFICATIONS_URL                = System.getProperty("HCH_BASE_URL") + System.getProperty("HCH_NOTIFICATIONS_URL");
        String HCH_CLIENT_GRANT_ACCESS_TOKEN    = System.getProperty("HCH_CLIENT_GRANT_ACCESS_TOKEN");

        HttpRequest homecareHubRequest              = null;
        HttpResponse<String> homecareHubResponse    = null;
        HttpClient httpClient                       = HttpClient.newHttpClient();
        HttpRequest.Builder requestBuilder          = HttpRequest.newBuilder()
            .header("Authorization", "Bearer " + HCH_CLIENT_GRANT_ACCESS_TOKEN)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json");
        
        if("POST".equals(requestHttpMethod)) {
            homecareHubRequest = requestBuilder.uri(new URI(NOTIFICATIONS_URL))                      // ? This can throw the URISyntaxException
                .POST(HttpRequest.BodyPublishers.ofString(responseBody))                                        
                .build();
                
            homecareHubResponse = httpClient.send(homecareHubRequest, BodyHandlers.ofString());     // ? This can throw the IOException / InterruptedException / HttpResponseException
        } else if("PUT".equals(requestHttpMethod) || "PATCH".equals(requestHttpMethod)) {
            homecareHubRequest = requestBuilder.uri(new URI(NOTIFICATIONS_URL + "/" + resourceId))  // ? This can throw the URISyntaxException
                .method("PATCH", HttpRequest.BodyPublishers.ofString(responseBody))
                .build();

            homecareHubResponse = httpClient.send(homecareHubRequest, BodyHandlers.ofString());     // ? This can throw the IOException / InterruptedException / HttpResponseException
        } else if("DELETE".equals(requestHttpMethod)) {
            homecareHubRequest = requestBuilder.uri(new URI(NOTIFICATIONS_URL + "/" + resourceId))  // ? This can throw the URISyntaxException
                .DELETE()
                .build();

            homecareHubResponse = httpClient.send(homecareHubRequest, BodyHandlers.ofString());     // ? This can throw the IOException / InterruptedException / HttpResponseException
        }
                
        if(homecareHubResponse != null) {
            logger.info("HCH Response Code: {}", homecareHubResponse.statusCode());
            logger.info("HCH RESPONSE: {}", homecareHubResponse.body());

            if(homecareHubResponse.statusCode() >= 400) {
                throw new RuntimeException("HCH response error: Throwing a Runtime exception"); // ? Does not need to explicitely define it in the the method signature, because it indicate programmer errors or problems that the application should not try to catch 
            }
        } else {
            logger.info("The Request does not match POST, PUT, PATCH or DELETE.");
        }  
    }
}
