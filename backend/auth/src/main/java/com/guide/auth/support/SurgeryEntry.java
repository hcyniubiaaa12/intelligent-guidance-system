package com.guide.auth.support;

/**
 * 健康档案里的一条手术（工单 03）：{@code {"value":"阑尾切除术","occurredOn":"2023-10"}}。
 *
 * <p>时间精确到月（{@code YYYY-MM}），空 = 未填。组装时按发生月份与当前相差是否
 * 超过 12 个月分成「近期手术」与「既往手术」。
 *
 * <p>老档案列里存的是字符串数组（{@code ["阑尾切除术"]}），读取侧转成时间字段为空的条目。
 */
public record SurgeryEntry(String value, String occurredOn) {
}
