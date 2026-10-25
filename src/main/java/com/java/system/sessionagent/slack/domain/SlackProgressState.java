package com.java.system.sessionagent.slack.domain;

public enum SlackProgressState {
    QUEUED, PREPARING, MODEL_CALL, TOOL_EXECUTION, HISTORY_COMMIT, RETRY, STOPPING,
    INTERRUPTED, DELIVERING, COMPLETED, CANCELLED, FAILED, DELIVERY_FAILED, WAITING_USER,
    SKILL_LOADING, SKILL_LOADED, SKILL_FAILED, SKILL_INTERRUPTED;

    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED || this == FAILED || this == DELIVERY_FAILED || this == WAITING_USER;
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
            case WAITING_USER -> "調查已暫停，等待您選擇繼續調查或整理既有證據；尚未交付報告";
            case SKILL_LOADING -> "正在載入技能";
            case SKILL_LOADED -> "技能已載入";
            case SKILL_FAILED -> "技能載入失敗";
            case SKILL_INTERRUPTED -> "技能載入已中斷";
        };
    }
}
