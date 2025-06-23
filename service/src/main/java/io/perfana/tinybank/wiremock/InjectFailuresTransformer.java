package io.perfana.tinybank.wiremock;

import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inject failure response at given failure rate.
 *
 * This is a global failure rate for all stubs using this failure injector.
 */
public class InjectFailuresTransformer implements ResponseTransformerV2 {

    private static volatile int failureRate = 0;

    private static final Random RANDOM = new Random();

    public static void setFailureRate(int failureRate) {
        InjectFailuresTransformer.failureRate = failureRate;
    }

    public static int getFailureRate() {
        return InjectFailuresTransformer.failureRate;
    }

    @Override
    public String getName() {
        return "inject-failures-transformer";
    }

    @Override
    public Response transform(Response response, ServeEvent serveEvent) {
        // Apply the failure rate logic
        if (failureRate > 0 && RANDOM.nextInt(100) < failureRate) {
            return Response.response()
                    .status(500)
                    .headers(response.getHeaders())
                    .body("{\"error\": \"Service Error\", \"message\": \"Random failure generated\"}")
                    .build();
        }

        return response;
    }

    @Override
    public boolean applyGlobally() {
        return false;
    }
}