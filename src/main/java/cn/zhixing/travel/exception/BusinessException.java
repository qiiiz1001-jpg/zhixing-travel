package cn.zhixing.travel.exception;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** */
@EqualsAndHashCode(callSuper = true)
@Data
public class BusinessException extends RuntimeException {

    protected Integer errorCode;
    protected String errorMsg;

    public BusinessException(Integer errorCode, String errorMsg) {
        super(errorMsg);
        this.errorCode = errorCode;
        this.errorMsg = errorMsg;
    }
}
