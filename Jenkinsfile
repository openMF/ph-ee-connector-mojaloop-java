pipeline {
    agent any
    stages {
        stage('build') {
            steps {
                sh 'mvn --version'
                sh 'mvn -U clean package'
            }
        }
        stage('docker') {
            steps {
                sh 'docker build . -t paymenthubee.azurecr.io/phee/connector-mojaloop-java'
                sh 'docker push paymenthubee.azurecr.io/phee/connector-mojaloop-java'
            }
        }
    }
}