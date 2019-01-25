Welcome to the AWS CodeStar sample web service
==============================================

This sample code helps get you started with a simple Java web service using
AWS Lambda and Amazon API Gateway.

What's Here
-----------

This sample includes:

* README.md - this file
* buildspec.yml - this file is used by AWS CodeBuild to build the web
  service
* pom.xml - this file is the Maven Project Object Model for the web service
* src/main - this directory contains your Java service source files
* src/test - this directory contains your Java service unit test files
* template.yml - this file contains the AWS Serverless Application Model (AWS SAM) used
  by AWS CloudFormation to deploy your application to AWS Lambda and Amazon API
  Gateway.
* template-configuration.json - this file contains the project ARN with placeholders used for tagging resources with the project ID

What Do I Do Next?
------------------

If you have checked out a local copy of your repository you can start making changes
to the sample code.  We suggest making a small change to
/src/main/java/com/aws/codestar/projecttemplates/controller/HelloWorldController.java
first, so you can see how changes pushed to your project's repository are automatically
picked up by your project pipeline and deployed to AWS Lambda and Amazon API Gateway. (You can
watch the pipeline progress on your AWS CodeStar project dashboard.) Once you've seen
how that works, start developing your own code, and have fun!

To run your tests locally, go to the root directory of the sample code and run the
`mvn clean compile test` command, which AWS CodeBuild also runs through your `buildspec.yml` file.

To test your new code during the release process, modify the existing tests or add tests
to the tests directory. AWS CodeBuild will run the tests during the build stage of your
project pipeline. You can find the test results in the AWS CodeBuild console.

Learn more about Maven's [Standard Directory Layout](https://maven.apache.org/guides/introduction/introduction-to-the-standard-directory-layout.html).

Learn more about managing Maven dependencies with AWS SDK for Java using the
[Bill of Materials Module](https://aws.amazon.com/blogs/developer/managing-dependencies-with-aws-sdk-for-java-bill-of-materials-module-bom/).

Learn more about AWS CodeBuild and how it builds and tests your application here:
https://docs.aws.amazon.com/codebuild/latest/userguide/concepts.html

Learn more about AWS Serverless Application Model (AWS SAM) and how it works here:
https://github.com/awslabs/serverless-application-model/blob/master/HOWTO.md

AWS Lambda Developer Guide:
http://docs.aws.amazon.com/lambda/latest/dg/deploying-lambda-apps.html

Learn more about AWS CodeStar by reading the user guide, and post questions and
comments about AWS CodeStar on our forum.

User Guide: http://docs.aws.amazon.com/codestar/latest/userguide/welcome.html

Forum: https://forums.aws.amazon.com/forum.jspa?forumID=248

What Should I Do Before Running My Project in Production?
------------------

AWS recommends you review the security best practices recommended by the framework
author of your selected sample application before running it in production. You
should also regularly review and apply any available patches or associated security
advisories for dependencies used within your application.

Best Practices: https://docs.aws.amazon.com/codestar/latest/userguide/best-practices.html?icmpid=docs_acs_rm_sec
