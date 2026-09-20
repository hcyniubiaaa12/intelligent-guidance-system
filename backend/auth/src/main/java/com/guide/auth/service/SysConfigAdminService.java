package com.guide.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guide.auth.dto.SysConfigAdminDTO;
import com.guide.auth.entity.SysConfig;
import com.guide.auth.mapper.SysConfigMapper;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时参数管理（管理端 LLM 配置页的「检索与聚合参数」面板，链路 5 参数维护）。
 *
 * <p>设计取舍：**参数项由代码侧白名单决定**（键、类型、取值范围、默认值、说明都在 {@link ParamSpec} 里），
 * 管理端只能改这些键的值——不让前端决定可以写哪些配置，避免字典表被塞进任意键、
 * 也避免「页面上有输入框但没有任何代码读它」的假配置。
 *
 * <p>写入后调 {@link SysConfigService#refresh()} 让进程内缓存立即失效，不依赖 60s TTL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysConfigAdminService {

    /** 参数类型：决定后端校验口径与前端控件 */
    public enum ParamType {
        /** 整数 */
        INT,
        /** 小数（阈值类一律 0–1） */
        DECIMAL,
        /** 布尔（true/false） */
        BOOL
    }

    /**
     * 参数分组：决定它出现在管理端哪个面板。
     * 分组只是呈现归属，写入校验一律走同一份白名单——分组不是权限边界。
     */
    public enum ParamGroup {
        /** 链路参数：检索/追问/聚合/术语审核（LLM 配置页「检索与聚合参数」） */
        LINK,
        /** 敏感词处置：统计窗口与警告/禁言阈值（用户管理页「敏感词库 → 处置规则」） */
        SENSITIVE
    }

    /**
     * 参数白名单项。
     *
     * @param range 取值范围的展示文案（bool 为空）
     */
    private record ParamSpec(String key, String label, ParamType type,
                             double min, double max, String defaultValue,
                             String range, String remark, ParamGroup group) {
    }

    private static final List<ParamSpec> SPECS = List.of(
            new ParamSpec(SysConfigService.KEY_RETRIEVE_TOP_K, "向量召回 Top-K", ParamType.INT, 1, 50, "10",
                    "1–50", "pgvector 与 ES 各召回条数", ParamGroup.LINK),
            new ParamSpec(SysConfigService.KEY_RETRIEVE_TOP_N, "重排后 Top-N", ParamType.INT, 1, 20, "5",
                    "1–20", "送入 Prompt 的切片数", ParamGroup.LINK),
            new ParamSpec(SysConfigService.KEY_ASK_MAX_ROUNDS, "追问轮数上限", ParamType.INT, 1, 10, "3",
                    "1–10", "超限后强制出低置信度结论", ParamGroup.LINK),
            new ParamSpec(SysConfigService.KEY_UPGRADE_ROUNDS, "追问升级阈值（轮次）", ParamType.INT, 1, 10, "3",
                    "1–10", "达到阈值升级处置", ParamGroup.LINK),
            new ParamSpec(SysConfigService.KEY_CLUSTER_BUCKET_THRESHOLD, "聚合归桶相似度阈值", ParamType.DECIMAL, 0, 1, "0.85",
                    "0–1", "语义归桶余弦相似度下限", ParamGroup.LINK),
            new ParamSpec(SysConfigService.KEY_LOW_CONFIDENCE, "低置信度分流阈值", ParamType.DECIMAL, 0, 1, "0.5",
                    "0–1", "低于此值不进聚合，进知识盲区榜", ParamGroup.LINK),
            new ParamSpec(SysConfigService.KEY_TERM_MANUAL_REVIEW, "术语人工审核开关", ParamType.BOOL, 0, 0, "true",
                    "", "开启后术语白名单变更需人工确认", ParamGroup.LINK),
            new ParamSpec(SysConfigService.KEY_SENSITIVE_WINDOW_MINUTES, "统计窗口（分钟）", ParamType.INT, 1, 1440, "60",
                    "1–1440", "滑动窗口：只看最近这段时间内的命中词次", ParamGroup.SENSITIVE),
            new ParamSpec(SysConfigService.KEY_SENSITIVE_BANNED_WARN, "禁止词警告阈值（词次）", ParamType.INT, 1, 1000, "10",
                    "1–1000", "窗口内禁止词命中达此值 → 警告", ParamGroup.SENSITIVE),
            new ParamSpec(SysConfigService.KEY_SENSITIVE_BANNED_MUTE, "禁止词禁言阈值（词次）", ParamType.INT, 1, 1000, "30",
                    "1–1000", "窗口内禁止词命中达此值 → 禁言；须大于警告阈值", ParamGroup.SENSITIVE),
            new ParamSpec(SysConfigService.KEY_SENSITIVE_WATCH_WARN, "观察词警告阈值（词次）", ParamType.INT, 1, 1000, "25",
                    "1–1000", "窗口内观察词命中达此值 → 警告（观察词不禁言）", ParamGroup.SENSITIVE),
            new ParamSpec(SysConfigService.KEY_SENSITIVE_MUTE_MINUTES, "禁言时长（分钟）", ParamType.INT, 1, 1440, "60",
                    "1–1440", "到期自动解除，无需人工操作", ParamGroup.SENSITIVE));

    /** 校验通过、待写入的一项（校验阶段产出，写入阶段消费） */
    private record Resolved(ParamSpec spec, String value) {
    }

    private static final Map<String, ParamSpec> SPEC_MAP = buildSpecMap();

    private final SysConfigMapper sysConfigMapper;
    private final SysConfigService sysConfigService;

    /**
     * 指定分组的受管参数（白名单顺序即页面顺序），值缺失时回落到代码侧默认值。
     * 分组决定它出现在哪个面板，不影响写入校验——校验一律走全量白名单。
     */
    public List<SysConfigAdminDTO.ParamVO> listParams(ParamGroup group) {
        List<SysConfigAdminDTO.ParamVO> result = new ArrayList<>();
        for (ParamSpec spec : SPECS) {
            if (group != null && spec.group() != group) {
                continue;
            }
            SysConfigAdminDTO.ParamVO vo = new SysConfigAdminDTO.ParamVO();
            vo.setKey(spec.key());
            vo.setLabel(spec.label());
            vo.setValue(sysConfigService.get(spec.key(), spec.defaultValue()));
            vo.setDefaultValue(spec.defaultValue());
            vo.setType(spec.type().name().toLowerCase());
            vo.setRange(spec.range());
            vo.setRemark(spec.remark());
            result.add(vo);
        }
        return result;
    }

    /**
     * 批量保存：**先全量校验、再统一写入**，最后失效缓存。
     *
     * <p>为什么不是「边校验边写 + 事务回滚」：分两步走让「有一项非法则一项都不落库」成为
     * 校验阶段的自然结果，不依赖回滚；也让 {@link SysConfigService#refresh()} 一定发生在
     * 所有写操作之后——否则清缓存可能先于提交，别的线程会在窗口期用旧值把缓存重新填上。
     */
    public void updateParams(List<SysConfigAdminDTO.ParamItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        List<Resolved> resolved = new ArrayList<>(items.size());
        for (SysConfigAdminDTO.ParamItem item : items) {
            ParamSpec spec = SPEC_MAP.get(item.getKey());
            if (spec == null) {
                throw new BizException(ErrorCode.CONFIG_KEY_UNKNOWN.getCode(),
                        "不支持的配置项：" + item.getKey());
            }
            resolved.add(new Resolved(spec, normalize(spec, item.getValue())));
        }
        checkSensitiveThresholds(resolved);
        for (Resolved item : resolved) {
            // 原值取自本次 selectOne（不走 SysConfigService 的缓存，否则日志可能把新值当旧值打出来）
            String before = upsert(item.spec(), item.value());
            log.info("运行时参数更新：{} {} → {}（默认 {}）", item.spec().key(),
                    before == null ? "(未设置)" : before, item.value(), item.spec().defaultValue());
        }
        sysConfigService.refresh();
    }

    /**
     * 跨项校验：禁止词的禁言阈值必须**大于**警告阈值。
     *
     * <p>为什么必须拦：两个阈值是分开的输入框，配成「禁言 ≤ 警告」时不会报任何错——
     * 判定顺序是「先看禁言线」，于是警告这一档**永远不会触发**，页面上却一切正常。
     * 这类静默失效只能靠校验挡住。
     *
     * <p>只在本次提交涉及这两个键时才校验：否则保存一个无关参数会因历史配置被拒，让人摸不着头脑。
     */
    private void checkSensitiveThresholds(List<Resolved> resolved) {
        boolean touched = resolved.stream().anyMatch(item ->
                SysConfigService.KEY_SENSITIVE_BANNED_WARN.equals(item.spec().key())
                        || SysConfigService.KEY_SENSITIVE_BANNED_MUTE.equals(item.spec().key()));
        if (!touched) {
            return;
        }
        int warn = effectiveInt(resolved, SysConfigService.KEY_SENSITIVE_BANNED_WARN);
        int mute = effectiveInt(resolved, SysConfigService.KEY_SENSITIVE_BANNED_MUTE);
        if (mute <= warn) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                    "禁止词禁言阈值（" + mute + "）必须大于警告阈值（" + warn + "），否则警告永远不会触发");
        }
    }

    /** 本次提交里改了就用新值，没改就用当前生效值（读缓存，与运行时判定同源） */
    private int effectiveInt(List<Resolved> resolved, String key) {
        for (Resolved item : resolved) {
            if (item.spec().key().equals(key)) {
                return Integer.parseInt(item.value());
            }
        }
        return sysConfigService.getInt(key, Integer.parseInt(SPEC_MAP.get(key).defaultValue()));
    }

    /** 按类型解析并做范围校验，返回规范化后的存储值 */
    private String normalize(ParamSpec spec, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(), spec.label() + " 不能为空");
        }
        String text = raw.trim();
        switch (spec.type()) {
            case INT -> {
                int value;
                try {
                    value = Integer.parseInt(text);
                } catch (NumberFormatException e) {
                    throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                            spec.label() + " 需为整数（" + spec.range() + "）");
                }
                if (value < spec.min() || value > spec.max()) {
                    throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                            spec.label() + " 需在 " + spec.range() + " 之间");
                }
                return String.valueOf(value);
            }
            case DECIMAL -> {
                double value;
                try {
                    value = Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                            spec.label() + " 需为小数（" + spec.range() + "）");
                }
                if (Double.isNaN(value) || value < spec.min() || value > spec.max()) {
                    throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                            spec.label() + " 需在 " + spec.range() + " 之间");
                }
                return String.valueOf(value);
            }
            case BOOL -> {
                if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
                    throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                            spec.label() + " 只接受 true / false");
                }
                return text.toLowerCase();
            }
            default -> throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                    "未知的参数类型：" + spec.type());
        }
    }

    /**
     * 存在则更新值与其说明，不存在则插入（键唯一，靠 uk_cfg_key 兜底）。
     *
     * @return 原值；原先没有该行时返回 null
     */
    private String upsert(ParamSpec spec, String value) {
        SysConfig existing = sysConfigMapper.selectOne(Wrappers.<SysConfig>lambdaQuery()
                .eq(SysConfig::getConfigKey, spec.key()).last("LIMIT 1"));
        if (existing == null) {
            SysConfig entity = new SysConfig();
            entity.setConfigKey(spec.key());
            entity.setConfigValue(value);
            entity.setRemark(spec.remark());
            sysConfigMapper.insert(entity);
            return null;
        }
        String before = existing.getConfigValue();
        existing.setConfigValue(value);
        existing.setRemark(spec.remark());
        sysConfigMapper.updateById(existing);
        return before;
    }

    private static Map<String, ParamSpec> buildSpecMap() {
        Map<String, ParamSpec> map = new LinkedHashMap<>();
        for (ParamSpec spec : SPECS) {
            map.put(spec.key(), spec);
        }
        return Map.copyOf(map);
    }
}
