package io.perfana.tinybank.wiremock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import java.util.Map;

public class FailureRateTransformer implements ResponseTransformerV2 {

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public Response transform(Response response, ServeEvent serveEvent) {
        try {
            String body =serveEvent.getRequest().getBodyAsString();

            System.out.println("Received request to set failure rate: " + body);
            Map<String, Object> params = OBJECT_MAPPER.readValue(body, Map.class);

            if (params.containsKey("rate")) {
                int rate = Integer.parseInt(params.get("rate").toString());
                GlobalParameterTransformer.setGlobalParameter("failureRate", rate);
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
