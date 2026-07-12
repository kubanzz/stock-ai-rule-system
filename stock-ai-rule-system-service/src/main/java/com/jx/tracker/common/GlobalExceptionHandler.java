package com.jx.tracker.common;

import com.jx.tracker.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 全局异常处理器
 * 
 * @author Benajmin
 */
@RestControllerAdvice
public class GlobalExceptionHandler
{
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常
     */
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<AjaxResult> handleServiceException(ServiceException e, HttpServletRequest request)
    {
        log.error(e.getMessage(), e);
        Integer code = e.getCode();
        AjaxResult result = (code != null) ? AjaxResult.error(code, e.getMessage()) : AjaxResult.error(e.getMessage());
        HttpStatus status = code == null ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.resolve(code);
        return new ResponseEntity<>(result, status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status);
    }

    /**
     * 请求路径中缺少必需的路径变量
     */
    @ExceptionHandler(MissingPathVariableException.class)
    public ResponseEntity<AjaxResult> handleMissingPathVariableException(MissingPathVariableException e, HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        log.error("请求路径中缺少必需的路径变量'{}',发生系统异常.", requestURI, e);
        String msg = String.format("请求路径中缺少必需的路径变量[%s]", e.getVariableName());
        return new ResponseEntity<>(AjaxResult.error(400, msg), HttpStatus.BAD_REQUEST);
    }

    /**
     * 拦截未知的运行时异常
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<AjaxResult> handleRuntimeException(RuntimeException e, HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',发生未知异常.", requestURI, e);
        return new ResponseEntity<>(AjaxResult.error(500, e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * 系统异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<AjaxResult> handleException(Exception e, HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',发生系统异常.", requestURI, e);
        return new ResponseEntity<>(AjaxResult.error(500, e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * 自定义验证异常
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<AjaxResult> handleBindException(BindException e) {
        log.error(e.getMessage(), e);
        String message = e.getAllErrors().get(0).getDefaultMessage();
        return new ResponseEntity<>(AjaxResult.error(400, message), HttpStatus.BAD_REQUEST);
    }

    /**
     * 自定义验证异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AjaxResult> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        log.error(e.getMessage(), e);
        String message = e.getBindingResult().getFieldError().getDefaultMessage();
        return new ResponseEntity<>(AjaxResult.error(400, message), HttpStatus.BAD_REQUEST);
    }

}
