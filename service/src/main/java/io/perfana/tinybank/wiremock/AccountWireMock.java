package io.perfana.tinybank.wiremock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.Options;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

public class AccountWireMock {

    private static boolean isMtlsEnabled() {
        String sys = System.getProperty("mtls.enabled");
        String env = System.getenv("MTLS_ENABLED");
        return (sys != null && sys.equalsIgnoreCase("true")) || (env != null && env.equalsIgnoreCase("true"));
    }

    public static void main(String[] args) {

        boolean mtls = isMtlsEnabled();

        WireMockConfiguration options = WireMockConfiguration.options()
                .port(30123)
                .disableRequestJournal()
                .asynchronousResponseEnabled(true)
                .asynchronousResponseThreads(256)
                .useChunkedTransferEncoding(Options.ChunkedEncodingPolicy.BODY_FILE) // disable chunking for short responses
                .extensions(new InjectFailuresTransformer(), new SetFailureRateTransformer());

        if (mtls) {
            System.out.println("mTLS enabled for AccountWireMock: using keystore target/generated-certs/server.p12");
            options = options
                    .httpsPort(31123)
                    .keystorePath("target/generated-certs/server.p12")
                    .keystorePassword("changeit")
                    .keyManagerPassword("changeit")
                    .keystoreType("PKCS12")
                    .trustStorePath("target/generated-certs/server-truststore.p12")
                    .trustStorePassword("changeit")
                    .trustStoreType("PKCS12")
                    .needClientAuth(true);
        }

        WireMockServer wireMockServer = new WireMockServer(options);
        wireMockServer.start();

        WireMock.configureFor("localhost", 30123);

        WireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/account?userId=u1234"))
                .inScenario("account-failures")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withStatus(200)
                        .withBody("{ \"customer\": { \"name\": \"John Doe\" }, \"accountNumber\": \"LT121000011234567890\", \"name\": \"John's Tiny Payments Account\"}")
                        .withTransformers("inject-failures-transformer")
                )
        );

        WireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/account?userId=u1234"))
                .inScenario("account-failures")
                .whenScenarioStateIs("account-failure-20")
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withStatus(200)
                        .withBody("{ \"customer\": { \"name\": \"John Doe\" }, \"accountNumber\": \"LT121000011234567890\", \"name\": \"John's Tiny Payments Account\"}")
                        .withTransformers("inject-failures-transformer")
                )
        );

        WireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/account?userId=u5678"))
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{ \"customer\": { \"name\":  \"Mary Jane\" },  \"accountNumber\": \"NL91ABNA0417164300\", \"name\": \"Mary's Tiny Savings Account\" }")
                        .withTransformers("inject-failures-transformer")
                )
        );

        WireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/account?userId=u9012"))
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{ \"customer\": { \"name\":  \"Alice Coop\" },  \"accountNumber\": \"US12BOFA0000123456\", \"name\": \"Alice's Tiny Payments Account\" }")
                        .withTransformers("inject-failures-transformer")
                )
        );

        // below are the admin stubs

        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/admin/activate-failures"))
                .inScenario("account-failures")
                .willSetStateTo("account-failure-20")
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\": \"success\", \"message\": \"Activated account failures with 20% failure rate\"}")
                ));

        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/admin/deactivate-failures"))
                .inScenario("account-failures")
                .willSetStateTo(Scenario.STARTED)  // Reset to the default scenario state
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\": \"success\", \"message\": \"Deactivated account failures, returning to normal operation\"}")
                ));

        WireMock.stubFor(WireMock.post(WireMock.urlPathMatching("/admin/set-failure-rate"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withTransformers("set-failure-rate-transformer") // this set the rate in InjectFailuresTransformer
                ));

        System.out.println("WireMock server started at http://localhost:30123");

    }
}
