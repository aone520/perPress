package com.per.server.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器：将各类异常统一转换为 R 响应结构，避免堆栈信息直接暴露给前端
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常
     *
     * @param e 业务异常
     * @return 携带业务错误码的统一响应
     */
    @ExceptionHandler(BizException.class)
    public R<Void> handleBizException(BizException e) {
        return R.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理请求体参数校验异常（@Valid 校验失败）
     *
     * @param e 参数校验异常
     * @return 提示第一个校验失败信息的统一响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getDefaultMessage())
                .orElse("参数校验失败");
        return R.error(message);
    }

    /**
     * 处理不存在的接口或静态资源，返回真实 HTTP 404，避免被兜底异常误报为服务器内部错误。
     *
     * @param e 资源不存在异常
     * @return HTTP 404 与统一错误体
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNoResourceFoundException(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(R.error(HttpStatus.NOT_FOUND.value(), "请求资源不存在"));
    }

    /**
     * 兜底处理未知异常
     *
     * @param e 未知异常
     * @return 统一的服务器内部错误响应
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("服务器内部异常", e);
        return R.error("服务器内部错误：" + e.getMessage());
    }
}
