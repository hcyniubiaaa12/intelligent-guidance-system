package com.guide.auth;

import com.guide.auth.dto.UserAdminDTO;
import com.guide.auth.entity.SensitiveWord;
import com.guide.auth.enums.SensitiveWordType;
import com.guide.auth.mapper.SensitiveWordMapper;
import com.guide.auth.service.SensitiveWordAdminService;
import com.guide.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 敏感词库单测：单条添加去重、批量导入按行去重过滤、类型切换。
 */
class SensitiveWordAdminServiceTest {

    private SensitiveWordMapper wordMapper;
    private SensitiveWordAdminService service;

    @BeforeEach
    void setUp() {
        wordMapper = mock(SensitiveWordMapper.class);
        service = new SensitiveWordAdminService(wordMapper);
    }

    private UserAdminDTO.WordAdd add(String word, String type) {
        UserAdminDTO.WordAdd dto = new UserAdminDTO.WordAdd();
        dto.setWord(word);
        dto.setType(type);
        return dto;
    }

    @Test
    void addInsertsBannedByDefault() {
        when(wordMapper.selectCount(any())).thenReturn(0L);
        service.add(add("骗人的医院", null));
        ArgumentCaptor<SensitiveWord> captor = ArgumentCaptor.forClass(SensitiveWord.class);
        verify(wordMapper).insert(captor.capture());
        assertEquals(SensitiveWordType.BANNED, captor.getValue().getType());
        assertEquals(1, captor.getValue().getEnabled());
    }

    @Test
    void addDuplicateThrowsExists() {
        when(wordMapper.selectCount(any())).thenReturn(1L);
        BizException e = assertThrows(BizException.class, () -> service.add(add("滚", "watch")));
        assertEquals(2005, e.getCode());
    }

    @Test
    void addInvalidTypeThrowsParamInvalid() {
        BizException e = assertThrows(BizException.class, () -> service.add(add("词", "bogus")));
        assertEquals(1001, e.getCode());
    }

    @Test
    void importDedupesAndSkipsExisting() {
        // "词B" 已存在；"词B/词B/词C" 去重后剩 B、C → B 跳过、C 导入
        when(wordMapper.selectCount(any())).thenReturn(1L, 0L);

        UserAdminDTO.WordImport dto = new UserAdminDTO.WordImport();
        dto.setText("词B\n词B\n 词C \n\n");
        dto.setType("watch");
        UserAdminDTO.ImportResult result = service.importWords(dto);

        assertEquals(1, result.getImported());
        assertEquals(1, result.getSkipped());
        ArgumentCaptor<SensitiveWord> captor = ArgumentCaptor.forClass(SensitiveWord.class);
        verify(wordMapper).insert(captor.capture());
        assertEquals(SensitiveWordType.WATCH, captor.getValue().getType());
    }

    @Test
    void importUniqueKeyConflictSkipsWord() {
        when(wordMapper.selectCount(any())).thenReturn(0L);
        when(wordMapper.insert(any(SensitiveWord.class)))
                .thenThrow(new DataIntegrityViolationException("uk_sw_word"));

        UserAdminDTO.WordImport dto = new UserAdminDTO.WordImport();
        dto.setText("并发词");
        UserAdminDTO.ImportResult result = service.importWords(dto);
        // 冲突被吞掉不抛异常——但**不许虚报**：这条没进库，imported 就该是 0、skipped 是 1
        assertEquals(0, result.getImported());
        assertEquals(1, result.getSkipped());
    }

    @Test
    void addRevivesSoftDeletedWordInsteadOfColliding() {
        // 逻辑上查不到（@TableLogic 加了 deleted=0），但物理行还占着唯一键 uk_sw_word
        when(wordMapper.selectCount(any())).thenReturn(0L);
        when(wordMapper.revive("滚", "banned")).thenReturn(1);

        service.add(add("滚", "banned"));

        // 复活而不是 INSERT：INSERT 会撞键（2026-09-26 实测：种子那条路径把应用启动带崩过）
        verify(wordMapper, never()).insert(any(SensitiveWord.class));
    }

    @Test
    void addStillRejectsLiveDuplicate() {
        when(wordMapper.selectCount(any())).thenReturn(1L);
        when(wordMapper.revive(any(), any())).thenReturn(0);

        BizException e = assertThrows(BizException.class, () -> service.add(add("狗屎", "banned")));
        assertEquals(2005, e.getCode());
        verify(wordMapper, never()).insert(any(SensitiveWord.class));
    }

    @Test
    void importRevivesSoftDeletedWordAndCountsItImported() {
        when(wordMapper.selectCount(any())).thenReturn(0L);
        when(wordMapper.revive("滚", "banned")).thenReturn(1);

        UserAdminDTO.WordImport dto = new UserAdminDTO.WordImport();
        dto.setText("滚");
        UserAdminDTO.ImportResult result = service.importWords(dto);

        // 口径 = "这些词现在生效了"：复活也算导入成功
        assertEquals(1, result.getImported());
        assertEquals(0, result.getSkipped());
        verify(wordMapper, never()).insert(any(SensitiveWord.class));
    }

    @Test
    void toggleFlipsEnabled() {
        SensitiveWord entity = new SensitiveWord();
        entity.setId("w1");
        entity.setEnabled(1);
        when(wordMapper.selectById("w1")).thenReturn(entity);
        service.toggleEnabled("w1");
        assertEquals(0, entity.getEnabled());
        verify(wordMapper).updateById(entity);
    }

    @Test
    void convertToBannedChangesType() {
        SensitiveWord entity = new SensitiveWord();
        entity.setId("w2");
        entity.setType(SensitiveWordType.WATCH);
        when(wordMapper.selectById("w2")).thenReturn(entity);
        service.convertToBanned("w2");
        assertEquals(SensitiveWordType.BANNED, entity.getType());
        verify(wordMapper).updateById(entity);
    }
}
