package com.guide.auth;

import com.guide.auth.dto.HealthTagAdminDTO;
import com.guide.auth.entity.HealthTag;
import com.guide.auth.enums.HealthTagType;
import com.guide.auth.mapper.HealthTagMapper;
import com.guide.auth.service.HealthTagAdminService;
import com.guide.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 慢病标签词表管理（单据 05）：列表含停用、新增默认启用、启停只翻转 enabled。
 *
 * <p>核心口径「停用只作用入口 vs 已保存档案照常召回」在 {@code HealthProfileServiceTest}
 * 里钉（那是入口列表与召回组装的交界处）；这里只管管理端的读 / 写行为。
 */
class HealthTagAdminServiceTest {

    private HealthTagMapper healthTagMapper;
    private HealthTagAdminService service;

    @BeforeEach
    void setUp() {
        healthTagMapper = mock(HealthTagMapper.class);
        service = new HealthTagAdminService(healthTagMapper);
    }

    private HealthTag tag(String id, String term, HealthTagType type, int enabled) {
        HealthTag t = new HealthTag();
        t.setId(id);
        t.setTerm(term);
        t.setType(type);
        t.setEnabled(enabled);
        return t;
    }

    private HealthTagAdminDTO.TagAdd req(String term, String type) {
        HealthTagAdminDTO.TagAdd r = new HealthTagAdminDTO.TagAdd();
        r.setTerm(term);
        r.setType(type);
        return r;
    }

    @Test
    @DisplayName("列表含停用项：启用与停用都在且带 enabled 状态（停用不是删除，要能重新启用）")
    void listIncludesDisabled() {
        when(healthTagMapper.selectList(any())).thenReturn(List.of(
                tag("t1", "高血压", HealthTagType.CHRONIC, 1),
                tag("t2", "冠心病", HealthTagType.CHRONIC, 0)));

        List<HealthTagAdminDTO.TagVO> tags = service.listAll();

        assertThat(tags).hasSize(2);
        assertThat(tags).extracting(HealthTagAdminDTO.TagVO::getTerm).containsExactly("高血压", "冠心病");
        assertThat(tags).extracting(HealthTagAdminDTO.TagVO::getEnabled).containsExactly(1, 0);
        assertThat(tags.get(0).getType()).isEqualTo("chronic");
    }

    @Test
    @DisplayName("新增：默认启用，term/type 落库")
    void addInsertsEnabled() {
        when(healthTagMapper.countIncludingDeleted("高血压", "chronic")).thenReturn(0);

        service.add(req("高血压", "chronic"));

        ArgumentCaptor<HealthTag> captor = ArgumentCaptor.forClass(HealthTag.class);
        verify(healthTagMapper).insert(captor.capture());
        HealthTag saved = captor.getValue();
        assertThat(saved.getTerm()).isEqualTo("高血压");
        assertThat(saved.getType()).isEqualTo(HealthTagType.CHRONIC);
        assertThat(saved.getEnabled()).isEqualTo(1);
    }

    @Test
    @DisplayName("新增重复（含被逻辑删过的物理行）：拒绝且不插入（物理唯一键 uk_ht_term_type）")
    void addRejectsDuplicate() {
        when(healthTagMapper.countIncludingDeleted("高血压", "chronic")).thenReturn(1);

        BizException e = assertThrows(BizException.class, () -> service.add(req("高血压", "chronic")));

        assertEquals(2009, e.getCode());
        verify(healthTagMapper, never()).insert(any(HealthTag.class));
    }

    @Test
    @DisplayName("非法类别：拒绝且不插入")
    void addRejectsInvalidType() {
        assertThrows(BizException.class, () -> service.add(req("高血压", "chronicle")));

        verify(healthTagMapper, never()).insert(any(HealthTag.class));
    }

    @Test
    @DisplayName("停用：只翻转 enabled 1→0，只更新标签行（不触碰任何档案）")
    void toggleDisables() {
        when(healthTagMapper.selectById("t1")).thenReturn(tag("t1", "高血压", HealthTagType.CHRONIC, 1));

        HealthTagAdminDTO.TagVO vo = service.toggle("t1");

        assertThat(vo.getEnabled()).isEqualTo(0);
        ArgumentCaptor<HealthTag> captor = ArgumentCaptor.forClass(HealthTag.class);
        verify(healthTagMapper).updateById(captor.capture());
        assertThat(captor.getValue().getEnabled()).isEqualTo(0);
    }

    @Test
    @DisplayName("启用：0→1")
    void toggleEnables() {
        when(healthTagMapper.selectById("t2")).thenReturn(tag("t2", "冠心病", HealthTagType.CHRONIC, 0));

        assertThat(service.toggle("t2").getEnabled()).isEqualTo(1);
    }

    @Test
    @DisplayName("标签不存在：拒绝")
    void toggleMissing() {
        when(healthTagMapper.selectById("x")).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.toggle("x"));

        assertEquals(2010, e.getCode());
    }
}
