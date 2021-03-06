package com.swisscom.cloud.sb.broker.services.kubernetes.facade

import com.swisscom.cloud.sb.broker.services.kubernetes.client.rest.KubernetesClient
import com.swisscom.cloud.sb.broker.services.kubernetes.config.AbstractKubernetesServiceConfig
import com.swisscom.cloud.sb.broker.services.kubernetes.config.KubernetesConfig
import com.swisscom.cloud.sb.broker.services.kubernetes.endpoint.EndpointMapper
import groovy.json.JsonSlurper
import groovy.transform.TypeChecked
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.web.client.HttpStatusCodeException

import static groovy.transform.TypeCheckingMode.SKIP

abstract class AbstractKubernetesFacade implements KubernetesFacade {
    protected final KubernetesClient<?> kubernetesClient
    protected final KubernetesConfig kubernetesConfig
    protected final AbstractKubernetesServiceConfig kubernetesServiceConfig

    @Autowired
    AbstractKubernetesFacade(KubernetesClient kubernetesClient, KubernetesConfig kubernetesConfig, AbstractKubernetesServiceConfig abstractKubernetesServiceConfig) {
        this.kubernetesClient = kubernetesClient
        this.kubernetesConfig = kubernetesConfig
        this.kubernetesServiceConfig = abstractKubernetesServiceConfig
    }

    @Override
    boolean isKubernetesDeploymentSuccessful(String serviceInstanceGuid) {
        try {
            def pods = getPodList(serviceInstanceGuid)
            def consideredPods = getPodsConsideredForReadiness(pods)
            return checkPodsReadinessState(consideredPods)
        } catch (HttpStatusCodeException e) {
            log.error("Readiness check for kubernetes service with instance guid " + serviceInstanceGuid
                    + " failed, got HTTP status code: " + e.getStatusCode().toString())
            return false
        }
    }

    @TypeChecked(SKIP)
    private List getPodList(String serviceInstanceGuid) throws HttpStatusCodeException {
        ResponseEntity<String> podListResponse = kubernetesClient.exchange(
                EndpointMapper.INSTANCE.getEndpointUrlByType("Namespace").getFirst() + '/' + serviceInstanceGuid + '/pods',
                HttpMethod.GET, "", String.class)
        def jsonSlurper = new JsonSlurper()
        def podList = jsonSlurper.parseText(podListResponse.body)
        assert podList.kind == 'PodList'
        return podList.items
    }

    /**
     * healthz is an internally defined label in our kubernetes service deployments.
     */
    @TypeChecked(SKIP)
    private List getPodsConsideredForReadiness(List pods) {
        if (kubernetesServiceConfig.enablePodLabelHealthzFilter) {
            return pods.findAll { it.metadata.labels.healthz == "true" }
        } else {
            return pods
        }
    }

    @TypeChecked(SKIP)
    private boolean checkPodsReadinessState(List pods) {
        def podsConditions = pods.collect { it.status.conditions }.flatten()
        def hasConditionsReadyAndStatusFalse = podsConditions.findAll { it.type == 'Ready' }.findAll {
            it.status == 'False'
        }
        return (hasConditionsReadyAndStatusFalse.size() == 0 && !pods.empty)
    }
}