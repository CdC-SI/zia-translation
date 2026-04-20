@Library('zas-pipelinelibrary') _

mavenPipelineTemplate {
    node = 'java-21'
    dockerConfig = [imageRoot: 'zas/copilot', imageName: 'translation-service']
    email = [recipients: 'matthieu.vinciarelli@zas.admin.ch']
    uploadArtefact = 'true'
    triggerDevPromotion = [
        repositoryName: 'copilot-ocp-promote',
        versionProperty: 'translation-service.image.version'
    ]
}
