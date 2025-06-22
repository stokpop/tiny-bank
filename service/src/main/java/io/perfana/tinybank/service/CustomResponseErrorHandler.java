package io.perfana.tinybank.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

import java.io.IOException;

public class CustomResponseErrorHandler implements ResponseErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomResponseErrorHandler.class);

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return (response.getStatusCode().is4xxClientError() || response.getStatusCode().is5xxServerError());
    }

    @Override
    public void handleError(ClientHttpResponse response) throws IOException {
        if (response.getStatusCode().is4xxClientError()) {
            log.warn("Handle error 400: {}", response.getStatusText());
            throw new ClientErrorException(response.getStatusCode(), response.getStatusText());
        } else if (response.getStatusCode().is5xxServerError()) {
            log.warn("Handle error 500: {}", response.getStatusText());
            throw new ServerErrorException(response.getStatusCode(), response.getStatusText());
        }
    }
}