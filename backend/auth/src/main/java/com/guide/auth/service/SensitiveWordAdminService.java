package com.guide.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.guide.auth.dto.UserAdminDTO;
import com.guide.auth.entity.SensitiveWord;
import com.guide.auth.enums.SensitiveWordType;
import com.guide.auth.mapper.SensitiveWordMapper;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import com.guide.common.util.PageUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 敏感词库管理（链路 A 入口校验的词库维护，总体架构 6.2/6.3）：
 * 分页查询、单条添加、批量导入（一行一词自动去重）、启停用、观察词转禁止词、删除（逻辑删）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveWordAdminService {

    private final SensitiveWordMapper wordMapper;

    public PageUtil pageWords(long current, long size, String type) {
        SensitiveWordType typeEnum = (type == null || type.isBlank()) ? null : parseType(type);
        Page<SensitiveWord> page = wordMapper.selectPage(PageUtil.page(current, size),
                Wrappers.<SensitiveWord>lambdaQuery()
                        .eq(typeEnum != null, SensitiveWord::getType, typeEnum)
                        .orderByDesc(SensitiveWord::getCreatedAt));
        return PageUtil.of(page, this::toVO);
    }

    /**
     * 单条添加：词唯一（DB 唯一键兜底），新词默认启用。
     *
     * <p><b>曾被删过的词按"复活"处理</b>：物理行还在、唯一键还占着，直接 INSERT 只会撞键。
     * 报一句"已存在"而列表里又找不到它，只会让人以为系统坏了——管理员点"添加"的意图很明确：
     * 这个词要在库里。复活顺带保留了 hit_count 等历史。
     */
    public void add(UserAdminDTO.WordAdd dto) {
        SensitiveWordType type = parseType(dto.getType());
        String word = dto.getWord().trim();
        if (exists(word)) {
            throw new BizException(ErrorCode.SENSITIVE_WORD_EXISTS);
        }
        if (wordMapper.revive(word, type.getCode()) > 0) {
            log.info("敏感词「{}」是曾被删除的词，已复活（保留原命中计数）", word);
            return;
        }
        SensitiveWord entity = new SensitiveWord();
        entity.setWord(word);
        entity.setType(type);
        entity.setHitCount(0);
        entity.setEnabled(1);
        try {
            wordMapper.insert(entity);
        } catch (DataIntegrityViolationException e) {
            throw new BizException(ErrorCode.SENSITIVE_WORD_EXISTS);
        }
    }

    /**
     * 批量导入：按行拆分 → trim → 去空去重 → 过滤已存在 → 批量入库。
     * 返回导入数 / 跳过（重复）数，供前端提示。
     *
     * <p>与 {@link #add} 同一条规则：**曾被删过的词复活**，不因为"物理行还占着唯一键"而静默丢掉。
     * 计数口径 = "这些词现在生效了"——复活也算导入成功。
     */
    public UserAdminDTO.ImportResult importWords(UserAdminDTO.WordImport dto) {
        SensitiveWordType type = parseType(dto.getType());
        Set<String> words = new LinkedHashSet<>();
        for (String line : dto.getText().split("\\r?\\n")) {
            String word = line.trim();
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        int imported = 0;
        for (String word : words) {
            if (exists(word)) {
                continue;
            }
            if (wordMapper.revive(word, type.getCode()) > 0) {
                imported++;
                continue;
            }
            SensitiveWord entity = new SensitiveWord();
            entity.setWord(word);
            entity.setType(type);
            entity.setHitCount(0);
            entity.setEnabled(1);
            try {
                wordMapper.insert(entity);
                imported++;
            } catch (DataIntegrityViolationException e) {
                // 并发导入撞唯一键：跳过该词继续（不计入 imported，别虚报）
            }
        }
        UserAdminDTO.ImportResult result = new UserAdminDTO.ImportResult();
        result.setImported(imported);
        result.setSkipped(words.size() - imported);
        return result;
    }

    /** 启用/停用切换（停用词不参与入口校验） */
    public void toggleEnabled(String id) {
        SensitiveWord entity = require(id);
        entity.setEnabled(entity.getEnabled() == 1 ? 0 : 1);
        wordMapper.updateById(entity);
    }

    /** 观察词一键转禁止词（管理端约定操作） */
    public void convertToBanned(String id) {
        SensitiveWord entity = require(id);
        entity.setType(SensitiveWordType.BANNED);
        wordMapper.updateById(entity);
    }

    public void delete(String id) {
        require(id);
        wordMapper.deleteById(id);
    }

    private boolean exists(String word) {
        return wordMapper.selectCount(Wrappers.<SensitiveWord>lambdaQuery()
                .eq(SensitiveWord::getWord, word)) > 0;
    }

    private SensitiveWord require(String id) {
        SensitiveWord entity = wordMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.SENSITIVE_WORD_NOT_FOUND);
        }
        return entity;
    }

    private SensitiveWordType parseType(String type) {
        if (type == null || type.isBlank()) {
            return SensitiveWordType.BANNED;
        }
        // 入参是小写编码值（见数据库设计 §0），不能用 valueOf（大小写敏感）
        for (SensitiveWordType t : SensitiveWordType.values()) {
            if (t.getCode().equals(type)) {
                return t;
            }
        }
        throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "敏感词类型仅支持 banned/watch");
    }

    private UserAdminDTO.WordVO toVO(SensitiveWord entity) {
        UserAdminDTO.WordVO vo = new UserAdminDTO.WordVO();
        vo.setId(entity.getId());
        vo.setWord(entity.getWord());
        vo.setType(entity.getType().getCode());
        vo.setHitCount(entity.getHitCount());
        vo.setEnabled(entity.getEnabled());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
