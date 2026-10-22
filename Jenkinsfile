@Library('devops-pipelines') _

pipeline {
    agent any

    environment {
        // ========== CREDENCIAIS (gerenciadas pelo Jenkins) ==========
        CLICKUP_TOKEN   = credentials('clickup-api-token')
        GITHUB_TOKEN    = credentials('github-token')

        // ========== DADOS DO PROJETO ==========
        GH_OWNER        = 'Neovia-Engenharia'
        GH_REPO         = 'clima-service'
        APP_NAME        = 'clima-service'

        // ========== REGISTRY (ACR no caso original) ==========
        ACR_ENDPOINT    = 'neoviaportaldevregistry.azurecr.io'
        ACR_ENDPOINT_PRD = 'neoviaportalprdregistry.azurecr.io'

        // ========== NAMESPACE E DEPLOYMENT KUBERNETES ==========
        K8S_NAMESPACE   = 'default'
        K8S_DEPLOYMENT  = 'clima-service'
        K8S_CONTAINER   = 'clima-service'
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build & Unit Test') {
            steps {
                mavenBuild(
                    goals: 'clean package',
                    skipTests: true,
                    publishJUnit: true
                )
            }
        }

        //stage('Code Quality') {
        //    steps {
        //        sonarQubeAnalysis()
        //    }
        // }

        //stage('Dependency Check') {
        //    steps {
        //        dependencyCheck()
        //    }
        //}

        stage('Package & Publish') {
            steps {
                script {
                    env.GIT_SHORT_HASH = sh(
                        script: 'git rev-parse --short HEAD',
                        returnStdout: true
                    ).trim()
                    echo "Tag da imagem: ${env.GIT_SHORT_HASH}"
                }

                withCredentials([usernamePassword(
                    credentialsId: 'acr-credentials',
                    usernameVariable: 'ACR_USER',
                    passwordVariable: 'ACR_PASS'
                )]) {
                    sh """
                        ./mvnw clean package jib:build -DskipTests \\
                            -Djib.to.auth.username=${ACR_USER} \\
                            -Djib.to.auth.password=${ACR_PASS} \\
                            -Djib.to.image=${ACR_ENDPOINT}/$APP_NAME:${GIT_SHORT_HASH}
                    """
                    sh """
                        ./mvnw jib:build -DskipTests \\
                            -Djib.to.auth.username=${ACR_USER} \\
                            -Djib.to.auth.password=${ACR_PASS} \\
                            -Djib.to.image=${ACR_ENDPOINT}/$APP_NAME:latest
                    """
                    script {
                        if (env.BRANCH_NAME == 'main') {
                            sh """
                                ./mvnw jib:build -DskipTests \\
                                    -Djib.to.auth.username=${ACR_USER} \\
                                    -Djib.to.auth.password=${ACR_PASS} \\
                                    -Djib.to.image=${ACR_ENDPOINT_PRD}/$APP_NAME:${GIT_SHORT_HASH}
                            """
                            sh """
                                ./mvnw jib:build -DskipTests \\
                                    -Djib.to.auth.username=${ACR_USER} \\
                                    -Djib.to.auth.password=${ACR_PASS} \\
                                    -Djib.to.image=${ACR_ENDPOINT_PRD}/$APP_NAME:latest
                            """
                        }
                    }
                }
            }
        }

        stage('Deploy to DEV') {
            when {
                branch 'dev'
            }
            steps {
                script {
                    def exists = sh(
                        script: "kubectl get deployment ${K8S_DEPLOYMENT} --namespace=${K8S_NAMESPACE} >/dev/null 2>&1",
                        returnStatus: true
                    ) == 0
                    if (!exists) {
                        echo "AVISO: deployment '${K8S_DEPLOYMENT}' nao existe no namespace '${K8S_NAMESPACE}'. Imagem publicada (${GIT_SHORT_HASH}); deploy pulado ate o workload ser criado."
                        return
                    }
                    sh """
                        kubectl set image deployment/${K8S_DEPLOYMENT} \\
                            ${K8S_CONTAINER}=${ACR_ENDPOINT}/$APP_NAME:${GIT_SHORT_HASH} \\
                            --namespace=${K8S_NAMESPACE} \\
                            --record
                    """
                    sh """
                        kubectl rollout restart deployment/${K8S_DEPLOYMENT} \\
                            --namespace=${K8S_NAMESPACE}
                    """
                    sh """
                        kubectl rollout status deployment/${K8S_DEPLOYMENT} \\
                            --namespace=${K8S_NAMESPACE} \\
                            --timeout=300s
                    """
                    echo "Deploy concluído com sucesso no namespace ${K8S_NAMESPACE}, imagem: ${GIT_SHORT_HASH}"
                }
            }
        }
        stage('Deploy to PRD') {
            when {
                branch 'main'
            }
            steps {
                script {
                    def exists = sh(
                        script: "kubectl get deployment ${K8S_DEPLOYMENT} --namespace=${K8S_NAMESPACE} >/dev/null 2>&1",
                        returnStatus: true
                    ) == 0
                    if (!exists) {
                        echo "AVISO: deployment '${K8S_DEPLOYMENT}' nao existe no namespace '${K8S_NAMESPACE}'. Imagem publicada (${GIT_SHORT_HASH}); Deploy pulado."
                        return
                    }
                    sh """
                        kubectl set image deployment/${K8S_DEPLOYMENT} \\
                            ${K8S_CONTAINER}=${ACR_ENDPOINT_PRD}/$APP_NAME:${GIT_SHORT_HASH} \\
                            --namespace=${K8S_NAMESPACE} \\
                            --record
                    """
                    sh """
                        kubectl rollout restart deployment/${K8S_DEPLOYMENT} \\
                            --namespace=${K8S_NAMESPACE}
                    """
                    sh """
                        kubectl rollout status deployment/${K8S_DEPLOYMENT} \\
                            --namespace=${K8S_NAMESPACE} \\
                            --timeout=300s
                    """
                    echo "Deploy PRD concluido no namespace ${K8S_NAMESPACE}, imagem: ${GIT_SHORT_HASH}"
                }
            }
        }
    }

    post {
        always {
            script {
                        if (env.BRANCH_NAME == 'main') {
                            clickupNotifyPRD()
                        } else {
                            clickupNotifyDEV()
                        }
                    }
        }
        success {
            echo "Pipeline concluída com sucesso! Deploy em DEV realizado (tag ${env.GIT_SHORT_HASH})."
        }
        failure {
            echo "Falha na pipeline. Verifique os logs."
        }
    }
}
