package com.guide.kb.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.guide.common.entity.BaseEntity;
import com.guide.common.model.LayoutBlock;
import lombok.Getter;
import lombok.Setter;

/**
 * 知识片段（链路 B）：MySQL 存事实，向量在 pgvector 同步。
 * 回流 approve 生成的合成 chunk（症状→正确科室鉴别语料）也写这里，
 * doc_id 指向系统级「回流知识文档」容器，走链路 B 同一流水线——闭环生效唯一机制。
 */
@Getter
@Setter
@TableName("kb_chunk")
public class KbChunk extends BaseEntity {

    /** 合成 chunk 指向系统级「回流知识文档」容器 */
    private String docId;

    private String title;

    private String content;

    private Integer seq;

    /** 外部版面识别出的类型；非外部识别来源为 unknown */
    private LayoutBlock.BlockType chunkType;
}
