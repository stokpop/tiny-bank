package io.perfana.tinybank.wiremock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.Options;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

public class BalanceWireMock {
    public static void main(String[] args) {
        WireMockConfiguration options = WireMockConfiguration.options()
                .port(30124)
                .disableRequestJournal()
                .asynchronousResponseEnabled(true)
                .asynchronousResponseThreads(256)
                .useChunkedTransferEncoding(Options.ChunkedEncodingPolicy.BODY_FILE)
                .extensions(new InjectFailuresTransformer(), new SetFailureRateTransformer());

        WireMockServer wireMockServer = new WireMockServer(options);
        wireMockServer.start();

        WireMock.configureFor("localhost", 30124);

        WireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/balance?accountNumber=LT121000011234567890"))
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{ \"amount\": 1000, \"currency\": \"EUR\" }")
                        .withTransformers("inject-failures-transformer")
                )
        );

        WireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/balance?accountNumber=NL91ABNA0417164300"))
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{ \"amount\": -200, \"currency\": \"EUR\" }")
                        .withTransformers("inject-failures-transformer")
                )
        );

        WireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/balance?accountNumber=US12BOFA0000123456"))
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{ \"amount\": 89000, \"currency\": \"USD\" }")
                        .withTransformers("inject-failures-transformer")
                )
        );

        // admin stubs

        WireMock.stubFor(WireMock.post(WireMock.urlPathMatching("/admin/set-failure-rate"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withTransformers("set-failure-rate-transformer")
                ));

        System.out.println("WireMock server started at http://localhost:30124");
    }
}
