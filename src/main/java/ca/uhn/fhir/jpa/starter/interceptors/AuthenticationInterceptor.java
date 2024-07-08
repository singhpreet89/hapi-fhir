package ca.uhn.fhir.jpa.starter.interceptors;

import java.net.URI;
import org.slf4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.LoggerFactory;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URISyntaxException;
import java.util.stream.Collectors;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import jakarta.servlet.http.HttpServletRequest;
import java.net.http.HttpResponse.BodyHandlers;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.apache.http.client.HttpResponseException;

import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;


import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.*;

public class AuthenticationInterceptor {
    private static final Logger logger = LoggerFactory.getLogger(AuthenticationInterceptor.class);

    private int apiErrorCode        = 500;
    private String apiErrorResponse = "Internal Server error.";


    /**
     * ? Custom request wrapper to cache the request body
     */
    private static class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {
        private byte[] cachedBody;

        public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
            super(request);
            InputStream requestInputStream = request.getInputStream();
            this.cachedBody = requestInputStream.readAllBytes();
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new CachedBodyServletInputStream(this.cachedBody);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(this.cachedBody);
            return new BufferedReader(new InputStreamReader(byteArrayInputStream));
        }

        private static class CachedBodyServletInputStream extends ServletInputStream {
            private final ByteArrayInputStream byteArrayInputStream;

            public CachedBodyServletInputStream(byte[] cachedBody) {
                this.byteArrayInputStream = new ByteArrayInputStream(cachedBody);
            }

            @Override
            public boolean isFinished() {
                return byteArrayInputStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read() throws IOException {
                return byteArrayInputStream.read();
            }
        }
    }

    /**
     * 
     * @param RequestDetails requestDetails
     * @param HttpServletRequest servletRequest
     * @param ServletRequestDetails servletRequestDetails
     * 
     * @throws AuthenticationException
     */
    @Hook(Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED)
    public void incomingRequestPreHandled(RequestDetails requestDetails, HttpServletRequest servletRequest, ServletRequestDetails servletRequestDetails) {
    // public void incomingRequestPreHandled(RequestDetails requestDetails, ServletRequestDetails servletRequestDetails) {
        System.out.println("--------------- AUTHENTICATION INTERCEPTOR ---------------");
    
        String authHeader       = requestDetails.getHeader("Authorization");
        String BEARER_PREFIX    = "Bearer ";
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            throw new AuthenticationException("Invalid or missing Authorization header.");
        }

        String token                    = authHeader.substring(BEARER_PREFIX.length()).trim();
        boolean validateTokenResponse   = validateTokenAndRequest(token, servletRequest, requestDetails, servletRequestDetails);

        if (validateTokenResponse == false) {
            if(this.apiErrorCode == 401) {
                this.apiErrorResponse = "Unauthenticated.";
                logger.info(this.apiErrorResponse);
                throw new AuthenticationException(this.apiErrorResponse);
            } else if(this.apiErrorCode == 403) {
                this.apiErrorResponse = "Unautorized.";
                logger.info(this.apiErrorResponse);
                throw new ForbiddenOperationException(this.apiErrorResponse);
            } else if(this.apiErrorCode == 422) {
                logger.info(this.apiErrorResponse);
                throw new UnprocessableEntityException(this.apiErrorResponse);
            } else {
                logger.info(this.apiErrorResponse);
                throw new InternalErrorException(this.apiErrorResponse);
            }
        }
    }

    /**
     * * This method is responsible for making an API call to HCH to validate the Bearer Token and append the returned encrypted login_id to the Request object
     * * OR
     * * Validate the Request based on the VALIDATE_PATIENT environment variable
     * 
     * @param String token
     * @param HttpServletRequest servletRequest
     * @param RequestDetails requestDetails
     * @param ServletRequestDetails servletRequestDetails
     * 
     * @return boolean
     */
    private boolean validateTokenAndRequest(String token, HttpServletRequest servletRequest, RequestDetails requestDetails, ServletRequestDetails servletRequestDetails) {
        try {
            String AUTHENTICATE_USER_URL        = System.getProperty("HCH_BASE_URL") + System.getProperty("HCH_AUTHENTICATE_USER_URL");
            String VALIDATE_PATIENT             = System.getProperty("VALIDATE_PATIENT");
            String resourceName                 = requestDetails.getResourceName();
            HttpRequest authenticationRequest   = null;
            String requestHttpMethod            = servletRequestDetails.getServletRequest().getMethod();

            /**
             * ? When a POST or PATCH request is sent over for a Patient Resource, create a Validation request for Laravel along with the verify token request
             * ? Otherwise create a verify token request
             */
            if ("true".equals(VALIDATE_PATIENT) && "Patient".equals(resourceName) && ("POST".equals(requestHttpMethod) || "PATCH".equals(requestHttpMethod))) {
                // HttpServletRequest servletRequest = servletRequestDetails.getServletRequest();
                // Wrap the HttpServletRequest to cache the request body
                HttpServletRequest wrappedRequest;
                try {
                    wrappedRequest = new CachedBodyHttpServletRequest(servletRequest);
                    servletRequestDetails.setServletRequest(wrappedRequest); // Update the ServletRequestDetails with the wrapped request
                } catch (IOException e) {
                    throw new InternalErrorException("Failed to read request body.", e);
                }
                
                BufferedReader reader   = wrappedRequest.getReader();           // Read the cached incoming request
                String requestJson      = reader.lines().collect(Collectors.joining(System.lineSeparator()));
                JSONObject jsonObject   = new JSONObject(requestJson);          // Parse the JSON string using JSONObject
                
                String phone            = null;
                String email            = null;
                JSONArray telecomArray  = jsonObject.getJSONArray("telecom");    // Extract the telecom key
                
                for (int i = 0; i < telecomArray.length(); i++) {
                    JSONObject telecom  = telecomArray.getJSONObject(i);
                    String system       = telecom.getString("system");
                    
                    if ("phone".equals(system)) {
                        phone = telecom.getString("value");
                    } else if ("email".equals(system)) {
                        email = telecom.getString("value");
                    }
                }
    
                if (email == null || email.isEmpty()) {
                    this.apiErrorCode       = 422;
                    this.apiErrorResponse   = "The Email is required.";
                    return false;
                }
    
                if (phone == null || phone.isEmpty()) {
                    this.apiErrorCode       = 422;
                    this.apiErrorResponse   = "The phone number is required.";
                    return false;
                }

                JSONObject jsonPayloadObject = new JSONObject();
                jsonPayloadObject.put("email", email);
                jsonPayloadObject.put("phone", phone);
                jsonPayloadObject.put("requestHttpMethod", requestHttpMethod); // "POST" for create, "PATCH" for update
                
                // String jsonPayload = jsonPayloadObject.toString();
                authenticationRequest = HttpRequest.newBuilder()
                    .uri(new URI(AUTHENTICATE_USER_URL))                        // ? This can throw the URISyntaxException
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayloadObject.toString()))
                    .build();
            } else {
                authenticationRequest = HttpRequest.newBuilder()
                    .uri(new URI(AUTHENTICATE_USER_URL))                      // ? This can throw the URISyntaxException
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .GET()                                        
                    .build();
            }
    
            HttpClient httpClient                       = HttpClient.newHttpClient();
            HttpResponse<String> authenticationResponse = httpClient.send(authenticationRequest, BodyHandlers.ofString()); // ? This can throw the IOException / InterruptedException / HttpResponseException
            
            logger.info("Authentication Response code: {}", authenticationResponse.statusCode());
            logger.info("Authentication Response: {}", authenticationResponse.body());

            JSONObject jsonResponseObject = new JSONObject(authenticationResponse.body());
            if(authenticationResponse.statusCode() >= 400) {
                this.apiErrorCode = authenticationResponse.statusCode();
                
                if(this.apiErrorCode == 422) {
                    logger.info("Validation Error Object initialized: {}", jsonResponseObject.toString());
                    this.apiErrorResponse = jsonResponseObject.toString();
                }
                return false;
            }

            /**
             * ? Get the encrypted login_id from the verify response and append to the request going toward NotificationInterceptor
             * ? servletRequest.setAttribute() is used to initialize a request variable 
             * ? This is read inside the NotificationInterceptor before being merge to the Request oibject sent over to the HomecareHub aplication
             */
            
            // servletRequest.setAttribute("authenticatedUserLoginId", jsonResponseObject.getString("id"));
            servletRequestDetails.getUserData().put("authenticatedUserLoginId", jsonResponseObject.getString("id"));
            return true;
        } catch (Exception exception) { // Todo: (Check if Line 215 can Handle this) Remove this Handle exception block because we want to stop the execution of request at this point.
            System.out.println("************************** Authentication Interceptor EXCEPTION **************************");
            
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

            throw new InternalErrorException(this.apiErrorResponse);    // To stop the further executing and dirpatch the response back to the UI
        }
    }
}
