package cn.flying.fisco_bcos.adapter.model;

/**
 * 区块链操作异常
 * 统一封装不同链的异常信息
 */
public class ChainException extends RuntimeException {

    private final ChainType chainType;
    private final String operation;

    public ChainException(String message) {
        super(message);
        this.chainType = null;
        this.operation = null;
    }

    public ChainException(String message, Throwable cause) {
        super(message, cause);
        this.chainType = null;
        this.operation = null;
    }

    public ChainException(ChainType chainType, String operation, String message) {
        super(String.format("[%s] %s failed: %s", chainType, operation, message));
        this.chainType = chainType;
        this.operation = operation;
    }

    public ChainException(ChainType chainType, String operation, String message, Throwable cause) {
        super(String.format("[%s] %s failed: %s", chainType, operation, message), cause);
        this.chainType = chainType;
        this.operation = operation;
    }

    public ChainType getChainType() {
        return chainType;
    }

    public String getOperation() {
        return operation;
    }
}
