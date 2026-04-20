# CI/CD Interne

## Contexte

Le projet zia-translation nécessite une pipeline CI/CD pour automatiser le build, les tests, la création d'image Docker et le déploiement sur l'infrastructure OpenShift interne ZAS.

## Description

Mettre en place deux fichiers à la racine du projet :

- **Jenkinsfile** : définit la pipeline CI/CD en utilisant la shared library `zas-pipelinelibrary` et le template `mavenPipelineTemplate`.
- **Dockerfile** : définit l'image Docker de l'application basée sur l'image de base Java 21 ZAS.

## Fichiers à créer

### Jenkinsfile

Emplacement : `Jenkinsfile` (racine du projet)

```groovy
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
```

### Dockerfile

Emplacement : `Dockerfile` (racine du projet)

```dockerfile
FROM docker-commons.zas.admin.ch/zas/imagebase/application/java:21-openjdk-headless-ubi-2.7.0
COPY target/zia-translation.jar /app/
CMD java \
    -XX:+UseG1GC \
    -XX:+ExplicitGCInvokesConcurrent \
    -XX:MaxGCPauseMillis=500 \
    -XX:ParallelGCThreads=2 \
    -Xms256M \
    -Xmx2048M \
    -XX:MinHeapFreeRatio=10 \
    -XX:MaxHeapFreeRatio=20 \
    -XX:GCTimeRatio=4 \
    -XX:AdaptiveSizePolicyWeight=90 \
    -jar /app/zia-translation.jar
```

## Règles métier

- Le Jenkinsfile utilise la shared library `zas-pipelinelibrary` et le template `mavenPipelineTemplate`.
- Le nœud de build est `java-21`.
- L'image Docker est publiée sous `zas/copilot/translation-service`.
- La promotion automatique vers l'environnement dev est déclenchée via le repository `copilot-ocp-promote` avec la propriété `translation-service.image.version`.
- Le Dockerfile utilise l'image de base ZAS `java:21-openjdk-headless-ubi-2.7.0`.
- L'artefact copié dans l'image est `zia-translation.jar` (nom défini par le `finalName` du build Maven).
- Les options JVM sont optimisées pour un environnement conteneurisé (G1GC, limites mémoire, ratios adaptatifs).

## Critères d'acceptation

- [ ] Le fichier `Jenkinsfile` existe à la racine du projet avec le contenu spécifié
- [ ] Le fichier `Dockerfile` existe à la racine du projet avec le contenu spécifié
- [ ] Le `finalName` du Maven build produit `zia-translation.jar` (cohérent avec le `COPY` du Dockerfile)

