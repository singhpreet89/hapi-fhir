package ca.uhn.fhir.jpa.starter.interceptors;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.api.Interceptor;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;


/**
 * ? Block all other API end points except Patient resource
 * ? Allow all Patient / swagger-ui / api-docs / metadata routes
 */

@Component
@Interceptor
public class IncomingRequestInterceptor {
   private static final Logger logger = LoggerFactory.getLogger(IncomingRequestInterceptor.class);

   @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_PROCESSED)
   public void incomingRequestPreProcessed(HttpServletRequest servletRequest) {
      logger.info("--------------- INCOMING REQUEST INTERCEPTOR ---------------");

      String requestURI = servletRequest.getRequestURI();
      if (!requestURI.startsWith("/fhir/Patient") && !requestURI.startsWith("/fhir/swagger-ui") && !requestURI.equals("/fhir/api-docs") && !requestURI.equals("/fhir/metadata")) {
         String extracted = "-";
         int index = requestURI.indexOf("/fhir/");
         if(index != -1) {
            extracted = requestURI.substring(index + "/fhir/".length());
         }

         throw new InvalidRequestException("HAPI-0302: Unknown resource type " + "'" + extracted + "'" + " - Server knows how to handle: [Patient]");
      }
   }
}