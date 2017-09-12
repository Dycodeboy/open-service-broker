package com.swisscom.cloud.sb.broker.services.kubernetes.facade.redis

import com.swisscom.cloud.sb.broker.backup.SystemBackupOnShield
import com.swisscom.cloud.sb.broker.backup.shield.ShieldTarget
import com.swisscom.cloud.sb.broker.model.DeprovisionRequest
import com.swisscom.cloud.sb.broker.model.ProvisionRequest
import com.swisscom.cloud.sb.broker.model.ServiceDetail
import com.swisscom.cloud.sb.broker.model.ServiceInstance
import com.swisscom.cloud.sb.broker.services.kubernetes.client.rest.KubernetesClient
import com.swisscom.cloud.sb.broker.services.kubernetes.config.KubernetesConfig
import com.swisscom.cloud.sb.broker.services.kubernetes.dto.Port
import com.swisscom.cloud.sb.broker.services.kubernetes.dto.ServiceResponse
import com.swisscom.cloud.sb.broker.services.kubernetes.endpoint.EndpointMapper
import com.swisscom.cloud.sb.broker.services.kubernetes.endpoint.parameters.EndpointMapperParamsDecorated
import com.swisscom.cloud.sb.broker.services.kubernetes.endpoint.parameters.KubernetesRedisConfigUrlParams
import com.swisscom.cloud.sb.broker.services.kubernetes.facade.AbstractKubernetesFacade
import com.swisscom.cloud.sb.broker.services.kubernetes.facade.redis.config.KubernetesRedisConfig
import com.swisscom.cloud.sb.broker.services.kubernetes.templates.KubernetesTemplate
import com.swisscom.cloud.sb.broker.services.kubernetes.templates.KubernetesTemplateManager
import com.swisscom.cloud.sb.broker.services.kubernetes.templates.constants.KubernetesTemplateConstants
import com.swisscom.cloud.sb.broker.util.ServiceDetailKey
import com.swisscom.cloud.sb.broker.util.ServiceDetailsHelper
import com.swisscom.cloud.sb.broker.util.StringGenerator
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.util.Pair
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component

@Component
@Slf4j
@CompileStatic
class KubernetesFacadeRedis extends AbstractKubernetesFacade implements SystemBackupOnShield {
    private final KubernetesTemplateManager kubernetesTemplateManager
    private final EndpointMapperParamsDecorated endpointMapperParamsDecorated
    private final KubernetesRedisConfig kubernetesRedisConfig

    @Autowired
    KubernetesFacadeRedis(KubernetesClient kubernetesClient, KubernetesConfig kubernetesConfig, KubernetesRedisConfig kubernetesRedisConfig, KubernetesTemplateManager kubernetesTemplateManager, EndpointMapperParamsDecorated endpointMapperParamsDecorated) {
        super(kubernetesClient, kubernetesConfig, kubernetesRedisConfig)
        this.kubernetesTemplateManager = kubernetesTemplateManager
        this.endpointMapperParamsDecorated = endpointMapperParamsDecorated
        this.kubernetesRedisConfig = kubernetesRedisConfig
    }


    Collection<ServiceDetail> provision(ProvisionRequest context) {
        def bindingMap = createBindingMap(context)
        def templates = kubernetesTemplateManager.getTemplates(context.plan.templateUniqueIdentifier)
        def templateEngine = new groovy.text.SimpleTemplateEngine()
        List<ResponseEntity> responses = new LinkedList()
        for (KubernetesTemplate kubernetesTemplate : templates) {
            def bindedTemplate = templateEngine.createTemplate(kubernetesTemplate.template).make(bindingMap).toString()
            Pair<String, ?> urlReturn = endpointMapperParamsDecorated.getEndpointUrlByTypeWithParams(KubernetesTemplate.getKindForTemplate(bindedTemplate), (new KubernetesRedisConfigUrlParams()).getParameters(context))
            responses.add(kubernetesClient.exchange(urlReturn.getFirst(), HttpMethod.POST, bindedTemplate, urlReturn.getSecond().class))
        }
        return buildServiceDetailsList(bindingMap.get(KubernetesTemplateConstants.REDIS_PASS.getValue()), responses)
    }

    private Map<String, String> createBindingMap(ProvisionRequest context) {
        def serviceDetailBindings = [
                (KubernetesTemplateConstants.SERVICE_ID.getValue()): context.getServiceInstanceGuid(),
                (KubernetesTemplateConstants.SPACE_ID.getValue())  : context.getSpaceGuid(),
                (KubernetesTemplateConstants.ORG_ID.getValue())    : context.getOrganizationGuid(),
                (KubernetesTemplateConstants.PLAN_ID.getValue())   : context.plan.guid,
        ]
        Map<String, String> planBindings = context.plan.parameters.collectEntries {
            [(it.getName() as String): it.getValue() as String]
        }
        def redisPassword = new StringGenerator().randomAlphaNumeric(30)
        def slaveofCommand = new StringGenerator().randomAlphaNumeric(30)
        def configCommand = new StringGenerator().randomAlphaNumeric(30)
        def otherBindings = [
                (KubernetesTemplateConstants.REDIS_PASS.getValue())     : redisPassword,
                (KubernetesTemplateConstants.SLAVEOF_COMMAND.getValue()): slaveofCommand,
                (KubernetesTemplateConstants.CONFIG_COMMAND.getValue()) : configCommand
        ]
        kubernetesRedisConfig.redisConfigurationDefaults << planBindings << serviceDetailBindings << otherBindings
    }

    void deprovision(DeprovisionRequest request) {
        kubernetesClient.exchange(EndpointMapper.INSTANCE.getEndpointUrlByType("Namespace").getFirst() + "/" + request.serviceInstanceGuid,
                HttpMethod.DELETE, "", Object.class)
    }

    private Collection<ServiceDetail> buildServiceDetailsList(String redisPassword, List<ResponseEntity> responses) {
        def serviceResponses = responses.findAll { it?.getBody() instanceof ServiceResponse }.collect {
            it.getBody().asType(ServiceResponse.class)
        }

        def masterAndShieldPorts = serviceResponses.findAll {
            it.spec.selector.role?.equals(KubernetesTemplateConstants.ROLE_MASTER.getValue())
        }.collect { it.spec.ports }.flatten() as List<Port>
        def masterPortDetails = masterAndShieldPorts.findAll { it.name.equals("redis") }.collect {
            ServiceDetail.from(ServiceDetailKey.KUBERNETES_REDIS_PORT_MASTER, it.nodePort.toString())
        }
        def shieldPortDetails = masterAndShieldPorts.findAll { it.name.equals("shield-ssh") }.collect {
            ServiceDetail.from(ServiceDetailKey.SHIELD_AGENT_PORT, it.nodePort.toString())
        }

        def slavePorts = serviceResponses.findAll {
            it.spec.selector.role?.startsWith(KubernetesTemplateConstants.ROLE_SLAVE.getValue())
        }.collect { it.spec.ports?.first()?.nodePort.toString() }
        def serviceDetailsSlavePorts = slavePorts.collect {
            ServiceDetail.from(ServiceDetailKey.KUBERNETES_REDIS_PORT_SLAVE, it)
        }

        def serviceDetails = [ServiceDetail.from(ServiceDetailKey.KUBERNETES_REDIS_PASSWORD, redisPassword),
                              ServiceDetail.from(ServiceDetailKey.KUBERNETES_REDIS_HOST, kubernetesRedisConfig.getKubernetesRedisHost())] +
                masterPortDetails + shieldPortDetails + serviceDetailsSlavePorts
        return serviceDetails
    }

    @Override
    ShieldTarget createShieldTarget(ServiceInstance serviceInstance) {
        Integer portMaster = ServiceDetailsHelper.from(serviceInstance.details).getValue(ServiceDetailKey.SHIELD_AGENT_PORT) as Integer
        new KubernetesRedisShieldTarget(namespace: serviceInstance.guid, port: portMaster)
    }

    @Override
    String systemBackupJobName(String jobPrefix, String serviceInstanceId) {
        "${jobPrefix}redis-${serviceInstanceId}"
    }

    @Override
    String systemBackupTargetName(String targetPrefix, String serviceInstanceId) {
        "${targetPrefix}redis-${serviceInstanceId}"
    }

    @Override
    String shieldAgent(ServiceInstance serviceInstance) {
        "${kubernetesRedisConfig.getKubernetesRedisHost()}:${ServiceDetailsHelper.from(serviceInstance.details).getValue(ServiceDetailKey.SHIELD_AGENT_PORT)}"
    }
}