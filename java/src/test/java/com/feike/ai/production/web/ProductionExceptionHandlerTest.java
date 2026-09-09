package com.feike.ai.production.web;

import com.feike.ai.production.auth.model.LoginRequestDTO;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工业级错误契约：校验失败给字段中文，未知异常不回堆栈。
 */
@DisplayName("ProductionExceptionHandler")
class ProductionExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new ProductionExceptionHandler())
            .setValidator(validator)
            .build();
    }

    @Test
    void validationFailureShouldBeBadRequestWithoutStack() throws Exception {
        mockMvc.perform(post("/api/v1/_probe/echo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bad_request"))
            .andExpect(jsonPath("$.message").value(containsString("用户名不能为空")))
            .andExpect(jsonPath("$.path").doesNotExist())
            .andExpect(jsonPath("$.timestamp").doesNotExist());
    }

    @Test
    void unreadableBodyShouldBeBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/_probe/echo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("not-json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bad_request"))
            .andExpect(jsonPath("$.message").value("请求体无法解析"));
    }

    @Test
    void unknownExceptionShouldHideStackAndUseGenericCopy() throws Exception {
        String body = mockMvc.perform(get("/api/v1/_probe/boom"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("internal_error"))
            .andExpect(jsonPath("$.message").value("系统繁忙，请稍后重试"))
            .andExpect(jsonPath("$.path").doesNotExist())
            .andExpect(jsonPath("$.error").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertFalse(body.contains("secret stack should not leak"));
        assertFalse(body.contains("IllegalStateException"));
        org.hamcrest.MatcherAssert.assertThat(body, not(containsString("ProbeController")));
    }

    /**
     * 仅供本测试挂到 Advice 上，不进入生产装配。
     */
    @RestController
    @RequestMapping("/api/v1/_probe")
    static class ProbeController {

        @PostMapping("/echo")
        String echo(@Valid @RequestBody LoginRequestDTO body) {
            return body.username();
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("secret stack should not leak");
        }
    }
}
