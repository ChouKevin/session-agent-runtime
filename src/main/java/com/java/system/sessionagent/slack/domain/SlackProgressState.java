package com.java.system.sessionagent.slack.domain;

public enum SlackProgressState {
    QUEUED, PREPARING, MODEL_CALL, TOOL_EXECUTION, HISTORY_COMMIT, RETRY, STOPPING,
    INTERRUPTED, DELIVERING, COMPLETED, CANCELLED, FAILED, DELIVERY_FAILED;

    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED || this == FAILED || this == DELIVERY_FAILED;
    }

    public String description() {
        return switch (this) {
            case QUEUED -> "已收到，排隊中";
            case PREPARING, HISTORY_COMMIT -> "正在準備回覆";
            case MODEL_CALL -> "正在等待模型回覆";
            case TOOL_EXECUTION -> "正在查詢資料";
            case RETRY -> "等待重試";
            case STOPPING -> "停止中";
            case INTERRUPTED -> "處理已中斷，等待恢復";
            case DELIVERING -> "正在準備傳送回覆";
            case COMPLETED -> "已完成，回覆已送出";
            case CANCELLED -> "已取消";
            case FAILED -> "處理失敗";
            case DELIVERY_FAILED -> "回覆傳送失敗";
        };
    }
}
