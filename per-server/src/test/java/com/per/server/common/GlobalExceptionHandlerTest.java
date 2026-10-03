package com.per.server.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    @Test
    void missingResourceReturnsHttp404InsteadOfInternalError() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<R<Void>> response = handler.handleNoResourceFoundException(
                new NoResourceFoundException(HttpMethod.GET, "missing"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(404, response.getBody().getCode());
        assertEquals("请求资源不存在", response.getBody().getMessage());
    }
}
