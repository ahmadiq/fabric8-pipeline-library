#!/usr/bin/groovy
package io.stakater

import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonSlurper
import io.fabric8.kubernetes.api.KubernetesHelper
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.openshift.client.DefaultOpenShiftClient
import io.fabric8.openshift.client.OpenShiftClient
import jenkins.model.Jenkins

import java.util.regex.Pattern

def setupWorkspaceForRelease(String project, String useGitTagOrBranchForNextVersion = "", String mvnExtraArgs = "", String currentVersion = "") {
    def flow = new io.fabric8.Fabric8Commands()

    sh "git config user.email admin@stakater.com"
    sh "git config user.name stakater-release"

    sh "git tag -d \$(git tag)"
    sh "git fetch --tags"

    if (!useGitTagOrBranchForNextVersion.equalsIgnoreCase("branch")) {
        def newVersion = flow.getNewVersionFromTag(currentVersion)
        echo "New release version ${newVersion}"
        sh "mvn -U versions:set -DnewVersion=${newVersion} " + mvnExtraArgs
        sh "git commit -a -m 'release ${newVersion}'"
        flow.pushTag(newVersion)
    } else {
        sh 'mvn build-helper:parse-version versions:set -DnewVersion=\\\${parsedVersion.majorVersion}.\\\${parsedVersion.minorVersion}.\\\${parsedVersion.nextIncrementalVersion} ' + mvnExtraArgs
    }

    if (!useGitTagOrBranchForNextVersion.equalsIgnoreCase("tag")) {
        def releaseVersion = flow.getProjectVersion()

        // delete any previous branches of this release
        try {
            sh "git checkout -b release-v${releaseVersion}"
        } catch (err) {
            sh "git branch -D release-v${releaseVersion}"
            sh "git checkout -b release-v${releaseVersion}"
        }
    }
}

def stageSonartypeRepo() {
    def flow = new io.fabric8.Fabric8Commands()

    try {
        sh "mvn clean -B"
        sh "mvn -V -B -e -U install org.sonatype.plugins:nexus-staging-maven-plugin:1.6.7:deploy -P release -P openshift -DnexusUrl=http://nexus -Ddocker.push.registry=${env.FABRIC8_DOCKER_REGISTRY_SERVICE_HOST}:${env.FABRIC8_DOCKER_REGISTRY_SERVICE_PORT}"

    } catch (err) {
        hubot room: 'release', message: "Release failed when building and deploying to Nexus ${err}"
        currentBuild.result = 'FAILURE'
        error "ERROR Release failed when building and deploying to Nexus ${err}"
    }
    // the sonartype staging repo id gets written to a file in the workspace
    return flow.getRepoIds()
}

def releaseSonartypeRepo(String repoId) {
    try {
        // release the sonartype staging repo
        sh "mvn org.sonatype.plugins:nexus-staging-maven-plugin:1.6.5:rc-release -DnexusUrl=http://nexus -DstagingRepositoryId=${repoId} -Ddescription=\"Next release is ready\" -DstagingProgressTimeoutMinutes=60"

    } catch (err) {
        sh "mvn org.sonatype.plugins:nexus-staging-maven-plugin:1.6.5:rc-drop -DnexusUrl=http://nexus -DstagingRepositoryId=${repoId} -Ddescription=\"Error during release: ${err}\" -DstagingProgressTimeoutMinutes=60"
        currentBuild.result = 'FAILURE'
        error "ERROR releasing sonartype repo ${repoId}: ${err}"
    }
}

return this