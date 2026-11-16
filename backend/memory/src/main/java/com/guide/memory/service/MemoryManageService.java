package com.guide.memory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryActor;
import com.guide.memory.enums.MemoryOp;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import com.guide.memory.mapper.MemoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 记忆控制面的写操作：编辑、删除单条、一键清空（{@code PUT/DELETE /api/memory}）。
 *
 * <p>与 {@link MemoryQueryService} 分工：那边只读、这边只写，互不掺职责。
 * 抽取写入走 {@link MemoryExtractionService}，同样不经过这里——抽取是系统行为，
 * 这三个方法是患者本人对记忆的直接处置。
 *
 * <p>三条口径：
 * <ul>
 *   <li><b>编辑只改值</b>：{@code mvalue} 之外一列不动（出处、观察时间、类型、有效期都保留）。
 *       所以显式走 {@code LambdaUpdateWrapper.set}，不用 {@code updateById}——后者会把整份实体写回去。</li>
 *   <li><b>归属校验失败按「不存在」返回</b>：别人的记忆与不存在的记忆给同一个错误码，
 *       不泄露「这条记忆存不存在」。</li>
 *   <li><b>删除是逻辑删除 + 审计留痕</b>：{@code @TableLogic} 负责软删，每次删除追加
 *       {@code user_memory_log}（actor=user），患者事后能追溯「我删过什么」。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class MemoryManageService {

    /** 与抽取侧的记忆值上限一致（实体注释 ≤60 字） */
    static final int VALUE_MAX_CHARS = 60;

    private final MemoryMapper memoryMapper;
    private final MemoryLogService memoryLogService;
    private final MemoryVectorIndexer vectorIndexer;

    /**
     * 改一条记忆的值。
     *
     * @throws BizException 值为空 / 超长（PARAM_INVALID），或记忆不属于当前用户、
     *                      已删除、已过期（MEMORY_NOT_FOUND）
     */
    @Transactional
    public void updateValue(String userId, String id, String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "记忆内容不能为空");
        }
        if (trimmed.length() > VALUE_MAX_CHARS) {
            throw new BizException(ErrorCode.PARAM_INVALID, "记忆内容不能超过 " + VALUE_MAX_CHARS + " 字");
        }

        Memory row = requireEditable(userId, id);
        boolean background = row.getType() == MemoryType.BACKGROUND_HEALTH
                || row.getType() == MemoryType.BACKGROUND_OTHER;
        LocalDateTime now = LocalDateTime.now();

        // 内容列只写 mvalue：source_quote / observed_at / type / valid_* 一概不动。
        // updated_at 是书签（不是内容）：背景记忆靠它被向量回填任务识别为「需要重算」，
        // 否则编辑后的值还在用旧文本的向量做召回。update(null, wrapper) 不会自动填充它。
        // retrieval_text 是 mvalue 的 embedding 派生列：不同步刷新会让重算出来的向量仍指向旧值。
        var update = Wrappers.<Memory>lambdaUpdate()
                .set(Memory::getMvalue, trimmed)
                .set(Memory::getUpdatedAt, now)
                .eq(Memory::getId, id)
                .eq(Memory::getUserId, userId)
                .eq(Memory::getStatus, MemoryStatus.ACTIVE);
        if (background) {
            update.set(Memory::getRetrievalText, trimmed);
        }
        memoryMapper.update(null, update);

        memoryLogService.append(id, userId, MemoryOp.UPDATE, row.getMvalue(), trimmed, MemoryActor.USER);

        // 背景值变了就立刻重算向量；失败由 indexer 降级（WARN），回填任务会按 updated_at 再补。
        if (background) {
            row.setMvalue(trimmed);
            row.setRetrievalText(trimmed);
            row.setUpdatedAt(now);
            vectorIndexer.index(row);
        }
    }

    /**
     * 删除一条记忆（逻辑删除）。
     *
     * @throws BizException 记忆不属于当前用户、已删除或已过期（MEMORY_NOT_FOUND）
     */
    @Transactional
    public void delete(String userId, String id) {
        Memory row = requireEditable(userId, id);

        // @TableLogic：deleteById 落成 deleted=1，不物理删除
        memoryMapper.deleteById(id);
        memoryLogService.append(id, userId, MemoryOp.DELETE, row.getMvalue(), null, MemoryActor.USER);
        // 向量不在 MySQL 事务里：删失败只留下一条「回表即被过滤」的孤儿行，
        // 召回仍以 MySQL 为准；反过来若事务回滚，回填任务会把缺的向量补回来。
        vectorIndexer.delete(id);
    }

    /**
     * 清空当前用户的全部生效记忆。逐条逻辑删除并留痕，返回删除条数。
     *
     * <p>只动 {@code status=active} 的行：已过期记忆本就不参与注入，不该被「清空」顺手改写状态。
     */
    @Transactional
    public int clearAll(String userId) {
        List<Memory> rows = memoryMapper.selectList(Wrappers.<Memory>lambdaQuery()
                .eq(Memory::getUserId, userId)
                .eq(Memory::getStatus, MemoryStatus.ACTIVE));
        if (rows == null || rows.isEmpty()) {
            return 0;
        }

        int removed = 0;
        for (Memory row : rows) {
            // 服务侧再校验一次归属：即便上层查询被改坏，也不能删到别人的行
            if (!editable(row, userId)) {
                continue;
            }
            memoryMapper.deleteById(row.getId());
            memoryLogService.append(row.getId(), userId, MemoryOp.DELETE,
                    row.getMvalue(), null, MemoryActor.USER);
            vectorIndexer.delete(row.getId());
            removed++;
        }
        return removed;
    }

    private Memory requireEditable(String userId, String id) {
        Memory row = (id == null || id.isBlank()) ? null : memoryMapper.selectById(id);
        if (!editable(row, userId)) {
            throw new BizException(ErrorCode.MEMORY_NOT_FOUND);
        }
        return row;
    }

    /** 可被患者处置的行：本人、未逻辑删除、生效中 */
    private boolean editable(Memory row, String userId) {
        return row != null
                && userId != null
                && userId.equals(row.getUserId())
                && (row.getDeleted() == null || row.getDeleted() == 0)
                && row.getStatus() == MemoryStatus.ACTIVE;
    }
}
