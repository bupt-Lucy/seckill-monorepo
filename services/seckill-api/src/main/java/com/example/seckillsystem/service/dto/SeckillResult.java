package com.example.seckillsystem.service.dto;

public class SeckillResult {
    private final boolean accepted;
    private final String code;
    private final String message;
    private final String requestId;

    private SeckillResult(boolean accepted, String code, String message, String requestId) {
        this.accepted = accepted;
        this.code = code;
        this.message = message;
        this.requestId = requestId;
    }

    public static SeckillResult queued(String requestId) {
        return new SeckillResult(true, "QUEUED",
                "排队中，稍后请查询结果。requestId=" + requestId,
                requestId);
    }

    public static SeckillResult duplicate() {
        return new SeckillResult(false, "DUPLICATE", "重复下单", null);
    }

    public static SeckillResult soldOut() {
        return new SeckillResult(false, "SOLD_OUT", "库存已售罄", null);
    }

    public static SeckillResult bucketEmpty() {
        return new SeckillResult(false, "BUCKET_EMPTY", "当前库存不足，请稍后再试", null);
    }

    public static SeckillResult error(String message) {
        return new SeckillResult(false, "ERROR", message, null);
    }

    public boolean isAccepted() {
        return accepted;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getRequestId() {
        return requestId;
    }
}
