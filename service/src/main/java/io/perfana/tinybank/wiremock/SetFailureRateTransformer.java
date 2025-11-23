package io.perfana.tinybank.wiremock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import java.util.Map;

/**
 * Helper transformer to set the failure rate with remote calls to the stub.
 */
public class SetFailureRateTransformer implements ResponseTransformerV2 {

    public static final ObjectReader MAP_READER = new ObjectMapper().readerFor(Map.class);

    @Override
    public Response transform(Response response, ServeEvent serveEvent) {
        try {
            String body = serveEvent.getRequest().getBodyAsString();
            System.out.println("Received request to set failure rate: " + body);

            Map<String, Object> params = MAP_READER.readValue(body);

            if (params.containsKey("rate")) {
                int rate = Integer.parseInt(params.get("rate").toString());
                InjectFailuresTransformer.setFailureRate(rate);
                return Response.response()
                        .status(200)
                        .body("{\"status\": \"success\", \"message\": \"Failure rate set to " + rate + "%\"}")
                        .build();
            }

            return Response.response()
                    .status(400)
                    .body("{\"status\": \"error\", \"message\": \"Missing 'rate' parameter\"}")
                    .build();

        } catch (Exception e) {
            return Response.response()
                    .status(500)
                    .body("{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    @Override
    public String getName() {
        return "set-failure-rate-transformer";
    }

    @Override
    public boolean applyGlobally() {
        return false;
    }
}
