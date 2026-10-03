package com.guide.auth.seed;

import com.guide.auth.entity.HealthTag;
import com.guide.auth.enums.HealthTagType;
import com.guide.auth.mapper.HealthTagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 健康档案标签词表基础种子（开发夹具，guide.seed.enabled=true 时生效）。
 *
 * <p>为什么需要：词表是患者端「多选标签」的选项来源，也是后续「档案召回串」的原料——
 * 空表时患者端只能一直填「其他」自由文本，档案路的锚点也全落空。
 *
 * <p><b>判存在必须按"物理行"判</b>（{@link HealthTagMapper#countIncludingDeleted}）：
 * 逻辑删除的行仍占着唯一键 {@code uk_ht_term_type}。用按逻辑删除过滤的 {@code selectCount}
 * 会判成"不存在" → INSERT → 撞键 → <b>ApplicationRunner 抛异常，整个应用起不来</b>
 * （与 {@code sensitive_word} 同一场事故，见《数据库设计.md》§0.1）。
 * 对被删过的词的处理是<b>跳过而不是复活</b>——种子只负责把空库填上基础词，不推翻人工的决定。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "guide.seed.enabled", havingValue = "true")
public class HealthTagSeedRunner implements ApplicationRunner {

    /** 慢病（既往病史）：常见慢病，供患者端多选 */
    private static final List<String> CHRONIC = List.of(
            "高血压", "2型糖尿病", "1型糖尿病", "冠心病", "脑卒中",
            "慢性阻塞性肺疾病", "哮喘", "慢性胃炎", "消化性溃疡", "慢性肾炎",
            "慢性肾功能不全", "甲状腺功能减退", "甲状腺功能亢进", "高脂血症", "痛风",
            "类风湿关节炎", "骨关节炎", "颈椎病", "腰椎间盘突出", "慢性乙型肝炎",
            "脂肪肝", "贫血", "心房颤动", "心力衰竭", "抑郁症");

    /** 长期用药：常见慢病长期服用的药物类别/通用名 */
    private static final List<String> MEDICATION = List.of(
            "阿司匹林", "氯吡格雷", "他汀类药物", "氨氯地平", "缬沙坦",
            "美托洛尔", "二甲双胍", "格列美脲", "胰岛素", "左甲状腺素",
            "华法林", "布洛芬");

    /** 过敏史类别：常见过敏原 */
    private static final List<String> ALLERGY = List.of(
            "青霉素类", "头孢类", "磺胺类", "阿司匹林", "海鲜", "花粉", "尘螨", "鸡蛋");

    private final HealthTagMapper healthTagMapper;

    @Override
    public void run(ApplicationArguments args) {
        int chronic = seed(CHRONIC, HealthTagType.CHRONIC);
        int medication = seed(MEDICATION, HealthTagType.MEDICATION);
        int allergy = seed(ALLERGY, HealthTagType.ALLERGY);
        if (chronic + medication + allergy > 0) {
            log.info("健康档案标签词表种子装载完成：新增慢病 {} 条、用药 {} 条、过敏 {} 条（已存在的不重复插入），管理端可启停",
                    chronic, medication, allergy);
        }
    }

    private int seed(List<String> terms, HealthTagType type) {
        int inserted = 0;
        for (String term : terms) {
            // 物理存在即跳过（含被管理端删过的词）：唯一键认物理行，不认 deleted 标志
            if (healthTagMapper.countIncludingDeleted(term, type.getCode()) > 0) {
                continue;
            }
            HealthTag entity = new HealthTag();
            entity.setTerm(term);
            entity.setType(type);
            entity.setEnabled(1);
            healthTagMapper.insert(entity);
            inserted++;
        }
        return inserted;
    }
}
