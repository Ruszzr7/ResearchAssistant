package com.research.assistant.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AiRequestConcurrencyInterceptorTest {

    @Test
    void rejectsWhenSingleNodeCapacityIsExhaustedAndReleasesAfterCompletion() throws Exception {
        AiRequestConcurrencyInterceptor interceptor =
                new AiRequestConcurrencyInterceptor(1, new ObjectMapper());
        MockHttpServletRequest first = new MockHttpServletRequest();
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(first, firstResponse, new Object())).isTrue();

        MockHttpServletRequest second = new MockHttpServletRequest();
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(second, secondResponse, new Object())).isFalse();
        assertThat(secondResponse.getStatus()).isEqualTo(429);
        assertThat(secondResponse.getContentAsString()).contains("并发已达上限");

        interceptor.afterCompletion(first, firstResponse, new Object(), null);
        assertThat(interceptor.preHandle(new MockHttpServletRequest(),
                new MockHttpServletResponse(), new Object())).isTrue();
    }
}
