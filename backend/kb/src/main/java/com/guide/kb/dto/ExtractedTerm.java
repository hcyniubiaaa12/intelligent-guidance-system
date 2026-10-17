package com.guide.kb.dto;

import com.guide.kb.enums.TermType;

/**
 * 模型从入库切片里抽出的一个术语（链路 B 抽取产物）。
 *
 * <p>它只是**候选**：词面已过 {@code MedicalTermService} 的同一套校验（trim、2–20 字、无空白），
 * 是否落进白名单、落进去是否生效，由 {@code mergeExtracted} 与人工审核开关决定。
 */
public record ExtractedTerm(String term, TermType type) {
}
