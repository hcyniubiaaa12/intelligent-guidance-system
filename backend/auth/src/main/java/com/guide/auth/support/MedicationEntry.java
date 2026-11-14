package com.guide.auth.support;

/**
 * 健康档案里的一条用药（工单 03）：
 * {@code {"value":"二甲双胍","startedOn":"2024-03","endedOn":null}}。
 *
 * <p>时间精确到月（{@code YYYY-MM}），空 = 未填。{@code endedOn} 非空表示已停药——
 * 组装时归入「已停用药」，不再算「近期用药」；为空表示仍在使用。
 *
 * <p>老档案列里存的是字符串数组（{@code ["二甲双胍"]}），读取侧必须把它转成
 * 时间字段为空的条目；写路径统一写对象数组（见《数据库设计.md》的 JSON 快照惯例）。
 */
public record MedicationEntry(String value, String startedOn, String endedOn) {
}
