package com.guide.rag.dto;

/**
 * 候选科室（由 chat 层从 kb 的 dept 表读取后传入）。
 * rag 层不感知业务状态，科室范围与简介都由调用方给定——只推荐这里列出的科室。
 *
 * @param intro 科室简介（如"诊治冠心病、高血压……"）；可为空。由 chat 组装好传入，
 *              rag 只负责渲染，**不自己去查科室表或文档表**。带简介是为了让模型对候选科室的
 *              认知来自本项目数据，而不是自身先验（否则会推荐一个知识库里零内容的科室）。
 */
public record DeptOption(String id, String name, String intro) {
}
