package com.onelostlogician.aws.proxy;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.onelostlogician.aws.proxy.ApiGatewayProxyResponse.ApiGatewayProxyResponseBuilder;
import org.apache.log4j.Logger;
import org.json.simple.JSONObject;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;
import static javax.ws.rs.core.HttpHeaders.ACCEPT;
import static javax.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static javax.ws.rs.core.Response.Status.*;

public abstract class LambdaProxyHandler<MethodHandlerConfiguration extends Configuration>
        implements RequestHandler<ApiGatewayProxyRequest, ApiGatewayProxyResponse> {
    public static final String ACCESS_CONTROL_REQUEST_METHOD = "Access-Control-Request-Method";
    public static final String ACCESS_CONTROL_REQUEST_HEADERS = "Access-Control-Request-Headers";
    private final Logger logger = Logger.getLogger(getClass());
    private final boolean optionsSupport;
    private final Map<String, Function<MethodHandlerConfiguration, MethodHandler>> methodHandlerMap;

    public LambdaProxyHandler(boolean withOptionsSupport) {
        this(withOptionsSupport, new HashMap<>());
    }

    public LambdaProxyHandler(boolean withOptionsSupport, Map<String, Function<MethodHandlerConfiguration, MethodHandler>> methodHandlerMap) {
        this.optionsSupport = withOptionsSupport;
        this.methodHandlerMap = methodHandlerMap;
    }

    public void registerMethodHandler(String method, Function<MethodHandlerConfiguration, MethodHandler> methodHandlerConstuctor) {
        methodHandlerMap.put(method, methodHandlerConstuctor);
    }

    @Override
    public ApiGatewayProxyResponse handleRequest(ApiGatewayProxyRequest request, Context context) {
        ApiGatewayProxyResponse response;
        try {
            MethodHandlerConfiguration configuration;
            try {
                configuration = getConfiguration(request, context);
            }
            catch (Exception e) {
                throw new LambdaException(getServerErrorResponse("This service is mis-configured. Please contact your system administrator.\n", e));
            }

            String method = request.getHttpMethod();
            logger.info("Method: " + method + "\n");

            if (optionsSupport) {
                handleOptionsRequest(request, configuration);
            }
            else if (!methodHandlerMap.keySet().contains(method)) {
                ApiGatewayProxyResponse wrongMethod =
                        new ApiGatewayProxyResponseBuilder()
                                .withStatusCode(BAD_REQUEST.getStatusCode())
                                .withBody(String.format("Lambda cannot handle the method %s", method))
                                .build();
                throw new LambdaException(wrongMethod);
            }
            MethodHandler methodHandler = getMethodHandler(configuration, method);
            Map<String, String> headers = request.getHeaders();

            validateHeaderOrThrow(headers, CONTENT_TYPE, UNSUPPORTED_MEDIA_TYPE);
            validateHeaderOrThrow(headers, ACCEPT, UNSUPPORTED_MEDIA_TYPE);

            MediaType contentType;
            MediaType accept;
            try {
                String contentTypeString = requireNonNull(headers.get(CONTENT_TYPE)).toLowerCase();
                contentType = MediaType.valueOf(contentTypeString);
                String acceptString = requireNonNull(headers.get(ACCEPT)).toLowerCase();
                accept = MediaType.valueOf(acceptString);
            }
            catch (IllegalArgumentException e) {
                ApiGatewayProxyResponse malformedMediaType =
                        new ApiGatewayProxyResponseBuilder()
                                .withStatusCode(BAD_REQUEST.getStatusCode())
                                .withBody(String.format("Malformed media type. %s", e.getMessage()))
                                .build();
                throw new LambdaException(malformedMediaType);
            }

            logger.info("Content-Type: " + contentType + "\n");
            logger.info("Accept: " + accept + "\n");

            response = methodHandler.handle(request, contentType, accept, context);
        }
        catch (Error e) {
            response = new ApiGatewayProxyResponseBuilder()
                            .withStatusCode(INTERNAL_SERVER_ERROR.getStatusCode())
                            .withBody(String.format("Failed to parse: %s", request))
                            .build();
        }
        catch(LambdaException e) {
            response = e.getResponse();
        }
        catch (Exception e) {
            response = getServerErrorResponse("", e);
        }

        logger.info(String.format("Completed response: %s with size %s.\n", response.getStatusCode(), response.getBody().length()));
        return response;
    }

    private void handleOptionsRequest(
            ApiGatewayProxyRequest request,
            MethodHandlerConfiguration configuration
    ) throws LambdaException {
        Map<String, String> headers = request.getHeaders();
        if (!headers.keySet().contains(ACCESS_CONTROL_REQUEST_METHOD)) {
            ApiGatewayProxyResponse wrongHeaders = new ApiGatewayProxyResponseBuilder()
                            .withStatusCode(BAD_REQUEST.getStatusCode())
                            .withBody(String.format("Options method should include the %s header", ACCESS_CONTROL_REQUEST_METHOD))
                            .build();
            throw new LambdaException(wrongHeaders);
        }
        String methodBeingInvestigated = headers.get(ACCESS_CONTROL_REQUEST_METHOD);
        if (!methodHandlerMap.keySet().contains(methodBeingInvestigated)) {
            ApiGatewayProxyResponse wrongMethod = new ApiGatewayProxyResponseBuilder()
                            .withStatusCode(BAD_REQUEST.getStatusCode())
                            .withBody(String.format("Lambda cannot handle the method %s", methodBeingInvestigated))
                            .build();
            throw new LambdaException(wrongMethod);
        }
        Collection<String> requiredHeaders = getMethodHandler(configuration, methodBeingInvestigated).getRequiredHeaders();
        String proposedRequestHeadersStr = headers.get(ACCESS_CONTROL_REQUEST_HEADERS);
        if (!requiredHeaders.isEmpty() && proposedRequestHeadersStr == null) {
            ApiGatewayProxyResponse wrongHeaders =
                    new ApiGatewayProxyResponseBuilder()
                            .withStatusCode(BAD_REQUEST.getStatusCode())
                            .withBody(String.format("The required header(s) not present: %s", ACCESS_CONTROL_REQUEST_HEADERS))
                            .build();
            throw new LambdaException(wrongHeaders);
        }
        List<String> proposedRequestHeaders = asList(proposedRequestHeadersStr
                .replaceAll("\\s","")
                .split(",")
        );
        if (!proposedRequestHeaders.containsAll(requiredHeaders)) {
            ApiGatewayProxyResponse wrongHeaders =
                    new ApiGatewayProxyResponseBuilder()
                            .withStatusCode(BAD_REQUEST.getStatusCode())
                            .withBody(String.format("The required header(s) not present: %s", String.join(", ", requiredHeaders)))
                            .build();
            throw new LambdaException(wrongHeaders);
        }

        ApiGatewayProxyResponse optionsOk =
                new ApiGatewayProxyResponseBuilder()
                        .withStatusCode(OK.getStatusCode())
                        .build();
        throw new LambdaException(optionsOk);
    }

    private MethodHandler<?, ?> getMethodHandler(MethodHandlerConfiguration configuration, String method) {
        Function<MethodHandlerConfiguration, MethodHandler> methodHandlerConstructor = methodHandlerMap.get(method);
        return methodHandlerConstructor.apply(configuration);
    }

    private ApiGatewayProxyResponse getServerErrorResponse(String baseMessage, Exception e) {
        JSONObject body = new JSONObject();
        StringBuilder errorMessage = new StringBuilder();
        if (baseMessage != null && !baseMessage.isEmpty()) {
            errorMessage.append(baseMessage)
                        .append("\n");
        }
        errorMessage.append(e.getMessage());
        body.put("message", errorMessage.toString());
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        body.put("cause", sw.toString());
        return new ApiGatewayProxyResponseBuilder()
                .withStatusCode(INTERNAL_SERVER_ERROR.getStatusCode())
                .withBody(body.toJSONString())
                .build();
    }

    protected abstract MethodHandlerConfiguration getConfiguration(ApiGatewayProxyRequest request, Context context);

    private void validateHeaderOrThrow(Map<String, String> headers, String header, Status errorStatus) throws LambdaException {
        if (!headers.containsKey(header)) {
            ApiGatewayProxyResponse noHeaders = new ApiGatewayProxyResponseBuilder()
                    .withStatusCode(errorStatus.getStatusCode())
                    .withBody(String.format("No %s header", header))
                    .build();
            throw new LambdaException(noHeaders);
        }
    }
}