package com.guide.memory.service;

import com.guide.memory.enums.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * pgvector 记忆向量表读写（SQL 层）：只钉住**参数与过滤条件**——用户隔离、
 * 只召回两类背景、limit 生效；不重算 SQL 里的余弦表达式（那是数据库的事）。
 */
class MemoryVectorStoreTest {

    private JdbcTemplate jdbcTemplate;
    private MemoryVectorStore store;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        store = new MemoryVectorStore(jdbcTemplate);
    }

    @Test
    @DisplayName("search：按 user_id 与两类背景过滤，向量与 topK 都进 SQL 参数")
    @SuppressWarnings("unchecked")
    void searchFiltersByUserAndBackgroundTypes() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new MemoryVectorStore.ScoredMemory("m1", 0.83)));

        List<MemoryVectorStore.ScoredMemory> hits = store.search("u1", new float[]{0.1f, 0.2f}, 3);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("FROM user_memory_vec")
                .contains("WHERE user_id = ?")
                .contains("type IN (?, ?)")
                .contains("LIMIT ?");
        assertThat(args.getValue()).hasSize(6);
        assertThat(args.getValue()[0]).isEqualTo("[0.1,0.2]");
        assertThat(args.getValue()[1]).isEqualTo("u1");
        assertThat(args.getValue()[2]).isEqualTo(MemoryType.BACKGROUND_HEALTH.getCode());
        assertThat(args.getValue()[3]).isEqualTo(MemoryType.BACKGROUND_OTHER.getCode());
        assertThat(args.getValue()[4]).isEqualTo("[0.1,0.2]");
        assertThat(args.getValue()[5]).isEqualTo(3);
        assertThat(hits).containsExactly(new MemoryVectorStore.ScoredMemory("m1", 0.83));
    }

    @Test
    @DisplayName("upsert：同一 memory_id 走 ON CONFLICT 覆盖（编辑后重算向量不新增行）")
    void upsertOverwritesOnConflict() {
        store.upsert("m1", "u1", "background_health", new float[]{0.3f});

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());
        assertThat(sql.getValue())
                .contains("INSERT INTO user_memory_vec")
                .contains("ON CONFLICT (memory_id) DO UPDATE");
        assertThat(args.getValue()).hasSize(5); // memoryId, userId, type, vector, updated_at
        assertThat(args.getValue()[0]).isEqualTo("m1");
        assertThat(args.getValue()[1]).isEqualTo("u1");
        assertThat(args.getValue()[2]).isEqualTo("background_health");
        assertThat(args.getValue()[3]).isEqualTo("[0.3]");
        assertThat(args.getValue()[4]).isInstanceOf(LocalDateTime.class);
    }

    @Test
    @DisplayName("delete：按 memory_id 删除；空 id 不发 SQL")
    void deleteById() {
        store.delete("m1");
        store.delete(null);
        store.delete(" ");

        verify(jdbcTemplate).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("vectorUpdatedAt：空集合不查库；按 id 批量取回时间戳（缺行即缺 vector）")
    @SuppressWarnings("unchecked")
    void vectorUpdatedAtEmptyShortCircuits() throws Exception {
        assertThat(store.vectorUpdatedAt(List.of())).isEmpty();
        verifyNoInteractions(jdbcTemplate);

        LocalDateTime at = LocalDateTime.of(2026, 11, 14, 10, 0);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getString("memory_id")).thenReturn("m1");
                    when(rs.getTimestamp("updated_at")).thenReturn(java.sql.Timestamp.valueOf(at));
                    return List.of(mapper.mapRow(rs, 0));
                });

        Map<String, LocalDateTime> result = store.vectorUpdatedAt(List.of("m1", "m2"));

        assertThat(result).containsEntry("m1", at);
        assertThat(result).doesNotContainKey("m2");
    }
}
