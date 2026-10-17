package com.guide.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.guide.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 运行时可调配置字典表：聚合归桶阈值(0.85)、低置信度阈值(0.5)、
 * 术语人工审核开关、检索 Top-K/Top-N 等（链路 5 参数）；管理端可调。
 * LLM API Key/模型名不走本表，坚持 application-local.yml（密钥不进库）。
 */
@Getter
@Setter
@TableName("sys_config")
public class SysConfig extends BaseEntity {

    private String configKey;

    private String configValue;

    private String remark;
}
