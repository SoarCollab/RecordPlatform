package cn.flying.platformapi.constant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * @program: RecordPlatform
 * @description: 返回结果封装
 * @author flyingcoding
 * @create: 2025-01-15 15:38
 */
@Getter
@Setter
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // 操作代码
    private Integer code;

    // 提示信息
    private String message;

    //交易返回值
    private String transactionHash;

    // 结果数据
    private T data;

    @JsonCreator
    public Result(
            @JsonProperty("code") Integer code,
            @JsonProperty("message") String message,
            @JsonProperty("data") T data
    ) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public Result(ResultEnum resultCode) {
        this.code = resultCode.getCode();
        this.message = resultCode.getMessage();
    }

    public Result(ResultEnum resultCode, T data) {
        this.code = resultCode.getCode();
        this.message = resultCode.getMessage();
        this.data = data;
    }
    public Result(String message) {
        this.message = message;
    }

    /**
     * 判断是否为成功结果
     */
    public boolean isSuccess() {
        return Objects.equals(this.code, ResultEnum.SUCCESS.getCode());
    }
    //成功返回封装-无数据
    public static Result<String> success() {
        return new Result<>(ResultEnum.SUCCESS.getCode(), ResultEnum.SUCCESS.getMessage(), null);
    }
    //成功返回封装-带数据
    public static <T> Result<T> success(T data) {
        return new Result<>(ResultEnum.SUCCESS.getCode(), ResultEnum.SUCCESS.getMessage(), data);
    }
    //失败返回封装-使用默认提示信息
    public static Result<String> error() {
        return new Result<>(ResultEnum.FAIL.getCode(), ResultEnum.FAIL.getMessage(), null);
    }
    //失败返回封装-使用返回结果枚举提示信息
    public static Result<String> error(ResultEnum resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null);
    }
    //失败返回封装-携带数据与错误信息一起返回
    public static <T> Result<T> error(ResultEnum resultCode, T data) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), data);
    }
    //失败返回封装-使用自定义提示信息
    public static Result<String> error(String message) {
        return new Result<>(500, message, null);
    }
}
