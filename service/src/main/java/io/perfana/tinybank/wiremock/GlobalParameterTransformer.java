package io.perfana.tinybank.wiremock;

import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ResponseTransformer;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.Response;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class GlobalParameterTransformer extends ResponseTransformer {

    private static final Map<String, Object> globalParameters = new ConcurrentHashMap<>();
    public static final Random RANDOM = new Random();

    public static void setGlobalParameter(String key, Object value) {
        globalParameters.put(key, value);
    }

    public static Object getGlobalParameter(String key) {
        return globalParameters.get(key);
    }

    @Override
    public Response transform(Request request, Response response, FileSource files, Parameters parameters) {
        // Get the current failure rate from global parameters or use default
        int failureRate = globalParameters.containsKey("failureRate")
                ? (int) globalParameters.get("failureRate")
                : 0;

        // Apply the failure rate logic
        if (failureRate > 0 && RANDOM.nextInt(100) < failureRate) {
            return Response.response()
                    .status(503)
                    .headers(response.getHeaders())
                    .body("{\"error\": \"Service Unavailable\", \"message\": \"Random failure generated\"}")
                    .build();
        }

        return response;
    }

    @Override
    public String getName() {
        return "global-parameter-transformer";
    }

    @Override
    public boolean applyGlobally() {
        return true; // Apply to all stubs
    }
}