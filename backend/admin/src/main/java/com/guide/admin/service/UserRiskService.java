package com.guide.admin.service;

import com.guide.admin.dto.UserRiskDTO;
import com.guide.auth.dto.UserAdminDTO;
import com.guide.auth.entity.SensitiveWord;
import com.guide.auth.entity.UserViolation;
import com.guide.auth.enums.ViolationLevel;
import com.guide.auth.mapper.SensitiveWordMapper;
import com.guide.auth.service.SysConfigService;
import com.guide.auth.service.UserViolationService;
import com.guide.chat.port.SensitiveHitCounter;
import com.guide.feedback.enums.FilterAction;
import com.guide.feedback.service.SensitiveHitQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户风险视图编排（管理端用户管理页）：把「窗口内触发了多少词 + 被处置过什么」拼到用户行上。
 *
 * <p>为什么编排放 admin：数据在三个模块（feedback 的 filter_log、auth 的 user_violation 与
 * sensitive_word），任何一侧声明这个视图都会造成反向依赖；admin 是唯一允许同时依赖它们的地方。
 *
 * <p>窗口长度与阈值同源（都读 sys_config）——页面上「近 N 分钟」跟判定口径一致，
 * 不会出现「页面按 30 分钟统计、后端按 60 分钟判定」这种对不上的情况。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserRiskService {

    private final SysConfigService sysConfigService;
    private final SensitiveHitQueryService hitQueryService;
    private final SensitiveWordMapper sensitiveWordMapper;
    private final UserViolationService userViolationService;

    /** 当前生效的统计窗口（分钟） */
    public int windowMinutes() {
        return Math.max(1, sysConfigService.getInt(
                SysConfigService.KEY_SENSITIVE_WINDOW_MINUTES, 60));
    }

    /**
     * 给一页用户补上窗口统计（批量两次查询：命中计数一次、处置记录一次，不在循环里逐个回表）。
     * 就地写入 {@link UserAdminDTO.UserVO#getHits()}。
     */
    public void fillHits(List<UserAdminDTO.UserVO> users) {
        if (users == null || users.isEmpty()) {
            return;
        }
        int windowMinutes = windowMinutes();
        LocalDateTime since = LocalDateTime.now().minusMinutes(windowMinutes);
        List<String> userIds = users.stream().map(UserAdminDTO.UserVO::getId).toList();

        Map<String, SensitiveHitCounter.Counts> counts = hitQueryService.countsByUsers(userIds, since);
        Map<String, Integer> warns = warnCounts(userIds, since);

        for (UserAdminDTO.UserVO vo : users) {
            SensitiveHitCounter.Counts hit = counts.getOrDefault(vo.getId(), SensitiveHitCounter.Counts.zero());
            UserAdminDTO.HitStat stat = new UserAdminDTO.HitStat();
            stat.setWindowMinutes(windowMinutes);
            stat.setBannedHits((int) hit.banned());
            stat.setWatchHits((int) hit.watch());
            stat.setWarnCount(warns.getOrDefault(vo.getId(), 0));
            vo.setHits(stat);
        }
    }

    /** 某用户在某时间点之后的警告次数（不包含禁言：禁言是更重的处置，行上单独显示禁言状态） */
    private Map<String, Integer> warnCounts(List<String> userIds, LocalDateTime since) {
        Map<String, Integer> warns = new HashMap<>();
        for (UserViolation violation : userViolationService.listSince(userIds, since)) {
            if (violation.getLevel() != ViolationLevel.WARN) {
                continue;
            }
            warns.merge(violation.getUserId(), 1, Integer::sum);
        }
        return warns;
    }

    /**
     * 用户违规详情：窗口内按词聚合的命中明细 + 该窗口内的处置记录。
     *
     * <p>词面从敏感词库补齐；词已被逻辑删除时显示占位——**不能因为词条没了就把这条命中藏掉**，
     * 历史命中是事实，字段缺失只影响展示。
     */
    public UserRiskDTO.DetailVO detail(String userId) {
        int windowMinutes = windowMinutes();
        LocalDateTime since = LocalDateTime.now().minusMinutes(windowMinutes);

        List<SensitiveHitQueryService.WordHit> hits = hitQueryService.words(userId, since);
        Map<String, SensitiveWord> words = wordsById(hits);

        UserRiskDTO.DetailVO detail = new UserRiskDTO.DetailVO();
        detail.setUserId(userId);
        detail.setWindowMinutes(windowMinutes);
        detail.setMuteUntil(userViolationService.muteUntil(userId).orElse(null));
        detail.setBannedHits(sumOf(hits, FilterAction.BLOCKED));
        detail.setWatchHits(sumOf(hits, FilterAction.WATCHED));
        detail.setWords(hits.stream().map(hit -> {
            UserRiskDTO.WordHitVO vo = new UserRiskDTO.WordHitVO();
            SensitiveWord word = words.get(hit.wordId());
            vo.setWord(word == null ? "(词条已删除)" : word.getWord());
            vo.setType(word == null ? typeOf(hit) : word.getType().getCode());
            vo.setCount(hit.hitCount());
            vo.setLastAt(hit.lastAt());
            return vo;
        }).toList());

        List<UserRiskDTO.ViolationVO> records = new ArrayList<>();
        for (UserViolation violation : userViolationService.listSince(List.of(userId), since)) {
            UserRiskDTO.ViolationVO vo = new UserRiskDTO.ViolationVO();
            vo.setLevel(violation.getLevel().getCode());
            vo.setHitType(violation.getHitType().getCode());
            vo.setHitCount(violation.getHitCount());
            vo.setWindowMinutes(violation.getWindowMinutes());
            vo.setThreshold(violation.getThreshold());
            vo.setOccurredAt(violation.getOccurredAt());
            vo.setMuteUntil(violation.getMuteUntil());
            records.add(vo);
        }
        detail.setRecords(records);
        return detail;
    }

    /** 词面批量补齐（已逻辑删除的词查不出来，交给调用方显示占位） */
    private Map<String, SensitiveWord> wordsById(List<SensitiveHitQueryService.WordHit> hits) {
        List<String> ids = hits.stream().map(SensitiveHitQueryService.WordHit::wordId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return sensitiveWordMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(SensitiveWord::getId, Function.identity(), (a, b) -> a));
    }

    /** 词条已删除时至少保留类型（blocked → banned / watched → watch），不让明细缺一列 */
    private static String typeOf(SensitiveHitQueryService.WordHit hit) {
        return hit.action() == FilterAction.BLOCKED ? "banned" : "watch";
    }

    /** 按命中动作汇总词次 */
    private static long sumOf(List<SensitiveHitQueryService.WordHit> hits, FilterAction action) {
        return hits.stream().filter(hit -> hit.action() == action)
                .mapToLong(SensitiveHitQueryService.WordHit::hitCount).sum();
    }
}
