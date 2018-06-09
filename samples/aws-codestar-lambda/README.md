Pedestal on API Gateway and Lambda
===================================

This is an example on running a Pedestal Service on AWS Lambda via API Gateway.
It's designed to be a [CodeStar](https://aws.amazon.com/codestar/) project and ships with CodePipeline and CloudFormation support.

Even though this project can be deployed on AWS Lambda, it can still run on Jetty.

## What's Here

This sample includes:

* README.md - this file
* buildspec.yml - this file is used by AWS CodeBuild to build the web
  service
* project.clj - managing dependencies
* src/ - this directory contains your Clojure/Pedestal service source files
* template.yml - this file contains the Serverless Application Model (SAM) used
  by AWS Cloudformation to deploy your application to AWS Lambda and Amazon API
  Gateway.

## Getting Started on Jetty or running locally

1. Start the application: `lein run`
2. Go to [localhost:8080](http://localhost:8080/) to see: `Hello World!`
3. Read your app's source code at src/pedestal_lambda/service.clj. Explore the docs of functions
   that define routes and responses.
4. Run your app's tests with `lein test`. Read the tests at test/pedestal_lambda/service_test.clj.
5. Learn more! See the [Links section below](#links).


## Getting Started on AWS

1. Create a new CodeStar project, based on the Java+Lambda template
2. Delete all the files in that project and replace them with these files (including `.gitignore`).
3. Commit and push the changes up into the repo, kicking off the CodePipeline
4. Visit your Prod URL (you may need to go to /about to see a result).


## Configuration

To configure logging see config/logback.xml. By default, the app logs to stdout and logs/.
To learn more about configuring Logback, read its [documentation](http://logback.qos.ch/documentation.html).


## Developing your service

1. Start a new REPL: `lein repl`
2. Start your service in dev-mode: `(def dev-serv (run-dev))`
3. Connect your editor to the running REPL session.
   Re-evaluated code will be seen immediately in the service.

### [Docker](https://www.docker.com/) container support

1. Build an uberjar of your service: `lein uberjar`
2. Build a Docker image: `sudo docker build -t pedestal-lambda .`
3. Run your Docker image: `docker run -p 8080:8080 pedestal-lambda`

### [OSv](http://osv.io/) unikernel support with [Capstan](http://osv.io/capstan/)

1. Build and run your image: `capstan run -f "8080:8080"`

Once the image it built, it's cached.  To delete the image and build a new one:

1. `capstan rmi pedestal-lambda; capstan build`


## What Do I Do Next Regarding AWS?

Start making changes to the sample code and push to your project's repository.
Notice how changes to AWS CodeCommit are automatically picked up and deployed.

Learn more about Serverless Application Model (SAM) and how it works here:
https://github.com/awslabs/serverless-application-model/blob/master/HOWTO.md

AWS Lambda Developer Guide:
http://docs.aws.amazon.com/lambda/latest/dg/deploying-lambda-apps.html

Learn more about AWS CodeStar by reading the user guide, and post questions and
comments about AWS CodeStar on the AWS CodeStar forum.

AWS CodeStar User Guide:
http://docs.aws.amazon.com/codestar/latest/userguide/welcome.html

AWS CodeStar Forum: https://forums.aws.amazon.com/forum.jspa?forumID=248

## Links
* [Other examples](https://github.com/pedestal/samples)
