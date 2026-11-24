package cn.zhixing.travel.exception;

import cn.zhixing.travel.enums.CommonError;
import cn.zhixing.travel.model.dto.ApiResult;

import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ApiResult<?> fileTooLarge(
            org.springframework.web.multipart.MaxUploadSizeExceededException error) {
        return ApiResult.fail(413, "文件超过上传大小限制，单文件最多 20MB");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class})
    public ApiResult<?> methodArgumentNotValidException(MethodArgumentNotValidException e) {
        StringBuilder errorMsg = new StringBuilder();
        e.getFieldErrors()
                .forEach(
                        f ->
                                errorMsg.append(f.getField())
                                        .append(f.getDefaultMessage())
                                        .append(" & "));
        log.error("参数错误:{}", errorMsg, e);
        return ApiResult.fail(CommonError.INVALID_PARAMETER_ERROR.getCode(), "参数错误");
    }

    @ExceptionHandler({BusinessException.class})
    public ApiResult<?> businessException(BusinessException e) {
        log.info("业务异常:{}", e.getMessage());
        return ApiResult.fail(e.getErrorCode(), e.getErrorMsg());
    }

    /**
     * @param e 最大的异常
     * @return 返回统一的异常信息结构给前端
     */
    @ExceptionHandler(Throwable.class)
    public ApiResult<?> throwable(Throwable e) {
        log.error("服务器错误:{}", e.getMessage(), e);
        return ApiResult.fail(CommonError.SYSTEM_ERROR.getCode(), "服务器错误");
    }
}
