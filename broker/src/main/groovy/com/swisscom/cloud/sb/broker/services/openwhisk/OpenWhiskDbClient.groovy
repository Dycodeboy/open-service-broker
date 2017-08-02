package com.swisscom.cloud.sb.broker.services.openwhisk

import com.fasterxml.jackson.databind.JsonNode
import com.swisscom.cloud.sb.broker.util.RestTemplateFactory
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate

@Component
@CompileStatic
@Slf4j
class OpenWhiskDbClient {

    private final String protocol
    private final String host
    private final String port
    private final String username
    private final String password
    private final String localUser
    private final String hostname
    private final RestTemplateFactory restTemplateFactory

    @Autowired
    OpenWhiskDbClient(OpenWhiskConfig openWhiskConfig, RestTemplateFactory restTemplateFactory){
        this.protocol = openWhiskConfig.openWhiskDbProtocol
        this.host = openWhiskConfig.openWhiskDbHost
        this.port = openWhiskConfig.openWhiskDbPort
        this.username = openWhiskConfig.openWhiskDbUser
        this.password = openWhiskConfig.openWhiskDbPass
        this.localUser = openWhiskConfig.openWhiskDbLocalUser
        this.hostname = openWhiskConfig.openWhiskDbHostname
        this.restTemplateFactory = restTemplateFactory
    }

    private RestTemplate createRestTemplate() {
        return restTemplateFactory.buildWithBasicAuthentication(username, password)
    }

    String getSubjectFromDB(String subject){

        try {
            return createRestTemplate().getForEntity("${protocol}://${host}:${port}/${localUser}_${hostname}_subjects/${subject}", String.class).getBody()
        } catch (HttpClientErrorException ex) {
            log.info("Http error exception = ${ex}")
            log.info("Subject does not exist")
            return null
        }

    }

    String insertIntoDatabase(JsonNode payload){

        return createRestTemplate().postForEntity("${protocol}://${host}:${port}/${localUser}_${hostname}_subjects", payload, String.class).getBody()
    }

    String deleteSubjectFromDb(String subject, String rev) {

        return createRestTemplate().exchange("${protocol}://${host}:${port}/${localUser}_${hostname}_subjects/${subject}?rev=${rev}", HttpMethod.DELETE,null, String.class).getBody()
    }
}
