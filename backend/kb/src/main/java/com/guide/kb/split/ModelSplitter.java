package com.guide.kb.split;

import java.util.List;

/**
 * 模型切（切分三层的最下一层）：给一段**没有标题块**的文本，让模型只给**边界**。
 *
 * <p>本条路径只有两个约束，缺一不可：
 * <ol>
 *   <li><b>只给边界、不给 title</b>——title 是推荐卡溯源引用要显示的东西，模型起的标题
 *       在原文里根本不存在，溯源就退化成"模型的转述"（= 幻觉）。title 只由结构切给。</li>
 *   <li><b>结果必须过产出校验</b>（见 {@link DocSplitter}）——不能寄望"模型切不出来就返回空"：
 *       模型不会说自己切不出来，它会编一个看起来合理的切分。</li>
 * </ol>
 *
 * <p>抽成接口是为了让 {@link DocSplitter} 保持纯逻辑可测：切分策略不该把模型调用焊死在里面。
 */
public interface ModelSplitter {

    /**
     * @param text   待切文本（无标题块的连续正文）
     * @param params 切分参数（模型侧的块长要求与它同源）
     * @return 按原文顺序切出的片段；**必须原样覆盖原文**，不得改写、不得丢字
     */
    List<String> split(String text, SplitParams params);
}
