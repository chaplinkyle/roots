targetScope = 'resourceGroup'

param name string = 'roots-automation'
param location string = resourceGroup().location
@description('Existing Container Apps environment with private database connectivity and logs configured.')
param environmentId string
@description('Existing user-assigned identity with ACR pull and Key Vault secret-read permissions.')
param identityId string
param registryServer string
@description('Reviewed Linux/amd64 automation image, including @sha256: digest.')
param image string

type SecretBinding = {
  name: string
  environment: string
  keyVaultUrl: string
}
@description('Versioned Key Vault references, never plaintext secret values. See parameters example.')
param secretBindings SecretBinding[]

var secretEnvironment = [for secret in secretBindings: { name: secret.environment, secretRef: secret.name }]

resource application 'Microsoft.App/containerApps@2025-07-01' = {
  name: name
  location: location
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${identityId}': {}
    }
  }
  properties: {
    environmentId: environmentId
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        external: true
        allowInsecure: false
        targetPort: 8080
        transport: 'http'
        stickySessions: { affinity: 'none' }
      }
      registries: [{ server: registryServer, identity: identityId }]
      secrets: [for secret in secretBindings: {
        name: secret.name
        keyVaultUrl: secret.keyVaultUrl
        identity: identityId
      }]
    }
    template: {
      terminationGracePeriodSeconds: 45
      containers: [{
        name: 'roots'
        image: image
        resources: { cpu: 1, memory: '2Gi' }
        env: concat([
          { name: 'ROOTS_HOST', value: '0.0.0.0' }
          { name: 'ROOTS_PORT', value: '8080' }
          { name: 'ROOTS_DEVELOPMENT', value: 'false' }
          { name: 'ROOTS_SECURE_COOKIES', value: 'true' }
          { name: 'ROOTS_SHUTDOWN_TIMEOUT', value: 'PT30S' }
          { name: 'ROOTS_MAX_REQUEST_BYTES', value: '65536' }
          { name: 'ROOTS_MAX_CONCURRENT_REQUESTS', value: '128' }
          { name: 'ROOTS_MAX_LIVE_VIEWS', value: '1' }
        ], secretEnvironment)
        probes: [
          {
            type: 'Startup'
            httpGet: { path: '/_roots/health', port: 8080, scheme: 'HTTP' }
            periodSeconds: 2
            timeoutSeconds: 2
            failureThreshold: 30
          }
          {
            type: 'Readiness'
            httpGet: { path: '/_roots/health', port: 8080, scheme: 'HTTP' }
            periodSeconds: 10
            timeoutSeconds: 3
            failureThreshold: 3
          }
          {
            type: 'Liveness'
            tcpSocket: { port: 8080 }
            periodSeconds: 15
            timeoutSeconds: 3
            failureThreshold: 3
          }
        ]
      }]
      scale: {
        minReplicas: 2
        maxReplicas: 4
        rules: [{ name: 'http', http: { metadata: { concurrentRequests: '32' } } }]
      }
    }
  }
}

output applicationUrl string = 'https://${application.properties.configuration.ingress.fqdn}'
output applicationId string = application.id
