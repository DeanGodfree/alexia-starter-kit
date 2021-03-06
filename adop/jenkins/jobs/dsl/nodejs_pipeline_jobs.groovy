// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"

// Variables
def referenceAppGitRepo = "alexia-starter-kit.git"
def referenceAppGitUrl = "ssh://jenkins@gerrit:29418/${PROJECT_NAME}/" + referenceAppGitRepo
def lambdaCFRepoName= "lambda_cf.git"
def lambdaCFRepoUrl = "ssh://jenkins@gerrit:29418/${PROJECT_NAME}/" + lambdaCFRepoName
def projectNameKey = projectFolderName.toLowerCase().replace("/", "-")

// Jobs
def createLambdaFunctionStack = freeStyleJob(projectFolderName + "/Create_Lambda_Function_Stack")
def install = freeStyleJob(projectFolderName + "/Install")
def test = freeStyleJob(projectFolderName + "/Test")
def lint = freeStyleJob(projectFolderName + "/Lint")
def createSpeechAssets = freeStyleJob(projectFolderName + "/Create_Speech_Assets")
def deployLambda = freeStyleJob(projectFolderName + "/Deploy_Lambda")

// Views
def pipelineView = buildPipelineView(projectFolderName + "/Alexia_Starter_Kit_Pipeline")

pipelineView.with{
    title('Alexia Starter Kit Pipeline')
    displayedBuilds(5)
    selectedJob(projectFolderName + "/Create_Lambda_Function_Stack")
    showPipelineParameters()
    showPipelineDefinitionHeader()
    refreshFrequency(5)
}

createLambdaFunctionStack.with{
    description("")
	logRotator {
		numToKeep(25)
    }
	parameters{
		stringParam("LAMBDA_FUNCTION_CODE_REPO_URL","https://github.com/kramos/alexia-starter-kit.git","Specify Repo URL to use for cloning the code for the Lambda function.")
		stringParam("LAMBDA_FUNCTION_NAME","ExampleFunction","Specify a name for the lambda function.")
		stringParam("LAMBDA_HANDLER","index.handler","The Handler for the Lambda Function")
		stringParam("LAMBDA_FUNCTION_DESCRIPTION","ASimpleLambdaFunction","Description of the Lambda Function")
		stringParam("LAMBDA_EXECUTION_ROLE","","The Amazon Resource Name (ARN) of the AWS Identity and Access Management (IAM) execution role that Lambda assumes when it runs your code to access AWS services.")
		stringParam("LAMBDA_RUNTIME","nodejs4.3","The runtime environment for the Lambda function you are uploading.")
		choiceParam("AWS_REGION", ['eu-west-1', 'us-west-1', 'us-east-1', 'us-west-2', 'eu-central-1', 'ap-northeast-1', 'ap-southeast-1', 'ap-southeast-2', 'sa-east-1'], "The AWS Region to deploy the Stacks.")
		credentialsParam("AWS_CREDENTIALS"){
			type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
			required()
			defaultValue('aws-credentials')
			description('AWS access key and secret key for your account')
		}
	}
	label("aws")
	environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
	  env('LAMBDA_CF_REPO',lambdaCFRepoUrl)
    }
	scm{
		git{
			remote{
				name("origin")
				url("${referenceAppGitUrl}")
				credentials("adop-jenkins-master")
			}
			branch("*/master")
		}
	}
	wrappers {
		preBuildCleanup()
		injectPasswords {
            injectGlobalPasswords()
        }
		maskPasswords()
		sshAgent("adop-jenkins-master")
		credentialsBinding {
		  usernamePassword("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", '${AWS_CREDENTIALS}')
		}
	}
	triggers {
        gerrit{
            events{
                refUpdated()
            }
            configure { gerritxml ->
                gerritxml / 'gerritProjects' {
                    'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.GerritProject' {
                        compareType("PLAIN")
                        pattern(projectFolderName + "/" + referenceAppgitRepo)
                        'branches' {
                            'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.Branch' {
                                compareType("PLAIN")
                                pattern("master")
                            }
                        }
                    }
                }
                gerritxml / serverName("__ANY__")
            }
        }
    }
	steps {
		shell('''#!/bin/bash -ex
		
		git clone ${LAMBDA_CF_REPO}
		
		lambda_function_stack_name="${LAMBDA_FUNCTION_NAME}Stack"
		
		aws cloudformation create-stack --stack-name ${lambda_function_stack_name} --region ${AWS_REGION} --capabilities "CAPABILITY_IAM" \\
			--tags "Key=CreatedBy,Value=Jenkins" \\
			--template-body file://$WORKSPACE/lambda_cf/Lambda_Function_CF.json \\
			--parameters \\
			ParameterKey=LambdaFunctionName,ParameterValue=${LAMBDA_FUNCTION_NAME} \\
			ParameterKey=LambdaHandler,ParameterValue=${LAMBDA_HANDLER} \\
			ParameterKey=LambdaFunctionDescription,ParameterValue=${LAMBDA_FUNCTION_DESCRIPTION} \\
			ParameterKey=LambdaExecutionRoleArn,ParameterValue=${LAMBDA_EXECUTION_ROLE} \\
			ParameterKey=LambdaRuntime,ParameterValue=${LAMBDA_RUNTIME}
			
		''')
	}
	publishers{
		archiveArtifacts("**/*")
		downstreamParameterized{
		  trigger(projectFolderName + "/Install"){
			condition("UNSTABLE_OR_BETTER")
			parameters{
			  predefinedProp("B",'${BUILD_NUMBER}')
			  predefinedProp("PARENT_BUILD",'${JOB_NAME}')
			  predefinedProp("LAMBDA_APP_URL",'${LAMBDA_FUNCTION_CODE_REPO_URL}')
			}
		  }
		}
	}
}

install.with{
  description("This job performs an npm install")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Create_Lambda_Function_Stack","Parent build name")
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  label("docker")
  steps {
    shell('''set -x
            |echo Run an install 
            |
            |docker run \\
            |		--rm \\
            |		-v /var/run/docker.sock:/var/run/docker.sock \\
            |		-v jenkins_slave_home:/jenkins_slave_home/ \\
            |		--workdir /jenkins_slave_home/${PROJECT_NAME}/Create_Lambda_Function_Stack \\
            |		node \\
            |		npm install --save	
            |'''.stripMargin())
  }
  publishers{
    downstreamParameterized{
      trigger(projectFolderName + "/Test"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
        }
      }
    }
  }
}

test.with{
  description("When triggered this will run the tests.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Create_Lambda_Function_Stack","Parent build name")
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  steps {
    shell('''set -x
            |echo Run unit tests
            |
            |docker run \\
            |		--rm \\
            |		-v /var/run/docker.sock:/var/run/docker.sock \\
            |		-v jenkins_slave_home:/jenkins_slave_home/ \\
            |		--workdir /jenkins_slave_home/${PROJECT_NAME}/Create_Lambda_Function_Stack \\
            |		node \\
            |		npm run test
            |'''.stripMargin())
  }
  publishers{
    downstreamParameterized{
      trigger(projectFolderName + "/Lint"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
        }
      }
    }
  }
}

lint.with{
  description("This job will perform static code analysis")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Create_Lambda_Function_Stack","Parent build name")
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  label("docker")
  steps {
    shell('''set -x
            |echo Run static code analysis 
            |
            |docker run \\
            |		--rm \\
            |		-v /var/run/docker.sock:/var/run/docker.sock \\
            |		-v jenkins_slave_home:/jenkins_slave_home/ \\
            |		--workdir /jenkins_slave_home/${PROJECT_NAME}/Create_Lambda_Function_Stack \\
            |		node \\
            |		npm run lint
            |'''.stripMargin())
  }
  publishers{
    downstreamParameterized{
      trigger(projectFolderName + "/Create_Speech_Assets"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
        }
      }
    }
  }
}

createSpeechAssets.with{
  description("")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Create_Lambda_Function_Stack","Parent build name")
    credentialsParam("AWS_CREDENTIALS"){
      type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
      description('AWS access key and secret key for your account (needed to deploy to Lambda)')
      defaultValue('aws-credentials')
      required()
    }
  }

  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
    credentialsBinding {
      usernamePassword("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", '${AWS_CREDENTIALS}')
    }
  }
  label("docker")
  steps {
    shell('''set +x
            |echo Create the speech assets
            |
            |set -x
            |docker run \\
            |		--rm \\
            |		-v /var/run/docker.sock:/var/run/docker.sock \\
            |		-v jenkins_slave_home:/jenkins_slave_home/ \\
            |		--workdir /jenkins_slave_home/${PROJECT_NAME}/Create_Lambda_Function_Stack \\
            |		--env AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} \\
            |		--env AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} \\
            |		node \\
            |		npm start &
            |'''.stripMargin())

  }
  publishers{
    downstreamParameterized{
      trigger(projectFolderName + "/Deploy_Lambda"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
        }
      }
    }
  }
}


deployLambda.with{
  description("This job will deploy the service to AWS Lambda.  You must created a global parameter with your AWS ACCESS and SECRET access keys.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Create_Lambda_Function_Stack","Parent build name")
    credentialsParam("AWS_CREDENTIALS"){
      type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
      description('AWS access key and secret key for your account (needed to deploy to Lambda)')
      defaultValue('aws-credentials')
      required()
    }
  }

  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
    credentialsBinding {
      usernamePassword("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", '${AWS_CREDENTIALS}')
    }
  }

  label("docker")
  steps {
    shell('''set +x
            |echo Deploy the service to AWS Lambda
            |
            |set -x
            |docker run \\
            |		--rm \\
            |		-v /var/run/docker.sock:/var/run/docker.sock \\
            |		-v jenkins_slave_home:/jenkins_slave_home/ \\
            |		--workdir /jenkins_slave_home/${PROJECT_NAME}/Create_Lambda_Function_Stack \\
            |		--env AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} \\
            |		--env AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} \\
            |		node \\
            |		npm run deploy
            |
            |echo Deployed - now follow here to test this via an Echo: https://github.com/Accenture/alexia#create-alexa-skill		
            |'''.stripMargin())

  }
}

