package com.feike.ai.production.web;

import com.feike.ai.production.chat.service.SessionBusyException;
import com.feike.ai.production.chat.service.SessionDisabledException;
import com.feike.ai.production.guardrail.service.GuardrailBlockedException;
import com.feike.ai.production.ratelimit.service.RateLimitExceededException;
import com.feike.ai.production.secret.service.SecretUnavailableException;
import com.feike.ai.production.session.service.SessionNotOwnedException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

/**
 * 只扫工业级包，把业务失败收成 {@link ErrorVO}。
 * <p>
 * 不照搬「一律 HTTP 200 + Result」：鉴权、限流、ingest 已经靠真实状态码工作，成功体也不套 {@code data}。
 * 未知异常打 error 堆栈，对外只给通用中文，避免把类名和现场漏给浏览器。
 */
@RestControllerAdvice(basePackages = "com.feike.ai.production")
@ConditionalOnProperty(prefix = "app.production", name = "enabled", havingValue = "true")
public class ProductionExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProductionExceptionHandler.class);

    /**
     * 目录化业务失败。
     *
     * @param ex 业务异常
     * @return {@code {code,message}}
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorVO> handleBusiness(BusinessException ex) {
        ErrorCodeEnum errorCode = ex.getErrorCode();
        log.warn("业务异常 {}: {}", errorCode.getCode(), ex.getMessage());
        return respond(errorCode, ex.getMessage());
    }

    /**
     * 会话不属于当前租户：对外 404，避免靠 403 探测别人的 sessionId。
     *
     * @param ex 域异常
     * @return 404
     */
    @ExceptionHandler(SessionNotOwnedException.class)
    public ResponseEntity<ErrorVO> handleSessionNotOwned(SessionNotOwnedException ex) {
        log.warn("会话不可见: {}", ex.getMessage());
        return respond(ErrorCodeEnum.SESSION_NOT_FOUND, ex.getMessage());
    }

    /**
     * 同一会话上一轮尚未结束。
     *
     * @param ex 域异常
     * @return 409
     */
    @ExceptionHandler(SessionBusyException.class)
    public ResponseEntity<ErrorVO> handleSessionBusy(SessionBusyException ex) {
        log.warn("会话繁忙: {}", ex.getMessage());
        return respond(ErrorCodeEnum.SESSION_BUSY, ex.getMessage());
    }

    /**
     * 本次部署没开多轮会话。
     *
     * @param ex 域异常
     * @return 501
     */
    @ExceptionHandler(SessionDisabledException.class)
    public ResponseEntity<ErrorVO> handleSessionDisabled(SessionDisabledException ex) {
        log.warn("会话未启用: {}", ex.getMessage());
        return respond(ErrorCodeEnum.SESSION_DISABLED, ex.getMessage());
    }

    /**
     * 令牌桶打满。控制器可能已写过 Retry-After，这里再写一遍以免从 Service 直接抛出时丢头。
     *
     * @param ex 限流异常
     * @return 429
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorVO> handleRateLimited(RateLimitExceededException ex) {
        log.warn("触发限流: retryAfter={}s", ex.retryAfterSeconds());
        return ResponseEntity.status(ErrorCodeEnum.RATE_LIMITED.getHttpStatus())
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
            .header("X-RateLimit-Remaining", String.valueOf(ex.remaining()))
            .body(new ErrorVO(ErrorCodeEnum.RATE_LIMITED.getCode(), ex.getMessage()));
    }

    /**
     * 护栏拦截：机器码沿用抛点上的 {@code input_deny} / {@code output_deny} / {@code citation_required}。
     *
     * @param ex 护栏异常
     * @return 422
     */
    @ExceptionHandler(GuardrailBlockedException.class)
    public ResponseEntity<ErrorVO> handleGuardrail(GuardrailBlockedException ex) {
        log.warn("护栏拦截 {}: {}", ex.code(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(new ErrorVO(ex.code(), ex.getMessage()));
    }

    /**
     * 信封密钥不可用。文案已是中文运维提示，不再包装一层英文 status。
     *
     * @param ex 密钥异常
     * @return 503
     */
    @ExceptionHandler(SecretUnavailableException.class)
    public ResponseEntity<ErrorVO> handleSecret(SecretUnavailableException ex) {
        log.warn("密钥不可用: {}", ex.getMessage());
        return respond(ErrorCodeEnum.SECRET_UNAVAILABLE, ex.getMessage());
    }

    /**
     * Bean Validation 失败（{@code @Valid} 请求体）。
     *
     * @param ex 校验异常
     * @return 400
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorVO> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(ProductionExceptionHandler::fieldMessage)
            .collect(Collectors.joining("；"));
        log.warn("请求体校验失败: {}", message);
        return respond(ErrorCodeEnum.BAD_REQUEST, message);
    }

    /**
     * 表单 / 查询绑定失败。
     *
     * @param ex 绑定异常
     * @return 400
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorVO> handleBind(BindException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(ProductionExceptionHandler::fieldMessage)
            .collect(Collectors.joining("；"));
        log.warn("参数绑定失败: {}", message);
        return respond(ErrorCodeEnum.BAD_REQUEST, message);
    }

    /**
     * 方法参数上的 {@code @NotBlank} 等（{@code @Validated} 控制器）。
     *
     * @param ex 约束异常
     * @return 400
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorVO> handleConstraint(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
            .map(ConstraintViolation::getMessage)
            .collect(Collectors.joining("；"));
        log.warn("约束校验失败: {}", message);
        return respond(ErrorCodeEnum.BAD_REQUEST, message);
    }

    /**
     * JSON 无法反序列化。
     *
     * @param ex 读失败
     * @return 400
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorVO> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体无法解析: {}", ex.getMessage());
        return respond(ErrorCodeEnum.BAD_REQUEST, "请求体无法解析");
    }

    /**
     * 缺查询参数 / 头 / 类型不匹配：仍是客户端问题，不要落到 500。
     *
     * @param ex MVC 绑定异常
     * @return 400
     */
    @ExceptionHandler({
        MissingServletRequestParameterException.class,
        MissingRequestHeaderException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorVO> handleMissingParam(Exception ex) {
        log.warn("请求参数错误: {}", ex.getMessage());
        return respond(ErrorCodeEnum.BAD_REQUEST, ErrorCodeEnum.BAD_REQUEST.getDefaultMessage());
    }

    /**
     * 漏网的 {@link ResponseStatusException}，仍收成同一契约，避免 Boot 默认 {@code error/path}。
     *
     * @param ex Spring 状态异常
     * @return 对应 HTTP 状态
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorVO> handleResponseStatus(ResponseStatusException ex) {
        ErrorCodeEnum errorCode = fromStatus(ex.getStatusCode().value());
        String message = ex.getReason() == null ? errorCode.getDefaultMessage() : ex.getReason();
        log.warn("状态异常 {}: {}", errorCode.getCode(), message);
        return respond(errorCode, message);
    }

    /**
     * 未知运行时失败：对外通用中文，对内打堆栈。
     * <p>
     * 故意只收 {@link RuntimeException}，把 {@code ServletException} 族（如 405）留给 MVC 默认解析，
     * 避免把「方法不允许」收成 500。
     *
     * @param ex 未分类运行时异常
     * @return 500
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorVO> handleUnknown(RuntimeException ex) {
        log.error("工业级未处理异常", ex);
        return respond(ErrorCodeEnum.INTERNAL_ERROR, ErrorCodeEnum.INTERNAL_ERROR.getDefaultMessage());
    }

    private static ResponseEntity<ErrorVO> respond(ErrorCodeEnum errorCode, String message) {
        String text = message == null || message.isBlank() ? errorCode.getDefaultMessage() : message;
        return ResponseEntity.status(errorCode.getHttpStatus()).body(new ErrorVO(errorCode.getCode(), text));
    }

    private static String fieldMessage(FieldError error) {
        String defaultMessage = error.getDefaultMessage();
        String detail = defaultMessage == null || defaultMessage.isBlank() ? "不合法" : defaultMessage;
        return error.getField() + " " + detail;
    }

    private static ErrorCodeEnum fromStatus(int status) {
        return switch (status) {
            case 400 -> ErrorCodeEnum.BAD_REQUEST;
            case 401 -> ErrorCodeEnum.AUTH_INVALID;
            case 403 -> ErrorCodeEnum.FORBIDDEN;
            case 404 -> ErrorCodeEnum.SESSION_NOT_FOUND;
            case 409 -> ErrorCodeEnum.CONFLICT;
            case 410 -> ErrorCodeEnum.RUN_GONE;
            case 422 -> ErrorCodeEnum.BAD_REQUEST;
            case 429 -> ErrorCodeEnum.RATE_LIMITED;
            case 501 -> ErrorCodeEnum.AGENT_NOT_ASSEMBLED;
            case 503 -> ErrorCodeEnum.SECRET_UNAVAILABLE;
            default -> ErrorCodeEnum.INTERNAL_ERROR;
        };
    }
}
