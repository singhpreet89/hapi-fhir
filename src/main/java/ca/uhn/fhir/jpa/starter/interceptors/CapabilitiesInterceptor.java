package ca.uhn.fhir.jpa.starter.interceptors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.api.Interceptor;
import org.springframework.stereotype.Component;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.instance.model.api.IBaseConformance;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceComponent;

/**
 * ? Only display swagger-ui links related to Patient resource and System Level Operations at http://localhost:8080/fhir/swagger-ui/index.html
 * ? Only Display API documentation related to Patient resource and System Level Operations at http://localhost:8080/fhir/api-docs
 * ? Only displays the Patient related Actions at http://localhost:8080/
 *   
 * ! Only works with r4 resources for now
 * ! Need to implement a dynamic approach to handle other resource types
 * 
 * @param baseConformance
 * 
 */
@Component
@Interceptor
public class CapabilitiesInterceptor {
    private static final Logger logger = LoggerFactory.getLogger(CapabilitiesInterceptor.class);

    @Hook(Pointcut.SERVER_CAPABILITY_STATEMENT_GENERATED)
    public void customize(IBaseConformance baseConformance) {
        logger.info("--------------- CAPABILIIES INTERCEPTOR ---------------");

        CapabilityStatement capabilityStatement = (CapabilityStatement) baseConformance;

        // Remove all resource types except "Patient"
        for (CapabilityStatementRestComponent rest : capabilityStatement.getRest()) {
            // rest.getResource().removeIf(resource -> !resource.getType().equals("Patient")); // OR
            rest.getResource().removeIf(resource -> !isAllowedResource(resource));
        }
    }

    /**
     * ? List of allowed resource types from the documentation
     * ? "Patient", "Observation", "Encounter", /* Add other resource types as needed
     */
    private boolean isAllowedResource(CapabilityStatementRestResourceComponent resource) {
        String[] allowedResources = {"Patient", /* Add other resource types as needed */};

        for (String allowedResource : allowedResources) {
            if (resource.getType().equals(allowedResource)) {
                return true;
            }
        }
        return false;
    }
}
