// Note: This doesn't appear to be used; it is not compiled or packaged in the 0.5.10 JAR and it doesn't compile cleanly either.
// It was added 5/17/18 by DeGrandis and never changed, and can probably be deleted.
package io.pedestal.aws.lambda;

import com.amazonaws.services.lambda.runtime.Context;

public class PedestalLambdaContainerHandler<RequestType, ResponseType> extends AwsLambdaServletContainerHandler<RequestType, ResponseType, AwsProxyHttpServletRequest, AwsHttpServletResponse> {

    public static PedestalLambdaContainerHandler<AwsProxyRequest, AwsProxyResponse> getAwsProxyHandler() throws ContainerInitializationException {
        return new PedestalLambdaContainerHandler<>(new AwsProxyHttpServletRequestReader(),
                                                    new AwsProxyHttpServletResponseWriter(),
                                                    new AwsProxySecurityContextWriter(),
                                                    new AwsProxyExceptionHandler());
    }

    public PedestalLambdaContainerHandler(RequestReader<RequestType, AwsProxyHttpServletRequest> requestReader,
                                          ResponseWriter<AwsHttpServletResponse, ResponseType> responseWriter,
                                          SecurityContextWriter<RequestType> securityContextWriter,
                                          ExceptionHandler<ResponseType> exceptionHandler)
            throws ContainerInitializationException {
        super(requestReader, responseWriter, securityContextWriter, exceptionHandler);

    }

    @Override
    protected AwsHttpServletResponse getContainerResponse(CountDownLatch latch) {
        return new AwsHttpServletResponse(latch);
    }

     @Override
     protected void handleRequest(AwsProxyHttpServletRequest httpServletRequest, AwsHttpServletResponse httpServletResponse, Context lambdaContext)
     throws Exception {
     throw new Exception("Not implemented! Subclass to control handling");
     }

}
