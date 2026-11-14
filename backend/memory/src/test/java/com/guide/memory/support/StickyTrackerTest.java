package com.guide.memory.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sticky 2 轮（纯类，零 mock）：命中后接下来两轮继续注入，第三轮消失。
 *
 * <p>口径：本轮命中时 {@code remember} 置 2；下一轮 {@code collectAndAdvance} 收集并减到 1，
 * 再下一轮收集并减到 0（清理），第三轮不再出现——「接下来 2 轮」指命中轮之后的两轮。
 */
class StickyTrackerTest {

    @Test
    @DisplayName("没有命中记录时收集为空")
    void emptyWhenNothingRemembered() {
        StickyTracker tracker = new StickyTracker();

        assertThat(tracker.collectAndAdvance("s1")).isEmpty();
        assertThat(tracker.collectAndAdvance(null)).isEmpty();
        assertThat(tracker.collectAndAdvance(" ")).isEmpty();
    }

    @Test
    @DisplayName("命中后接下来 2 轮都被收集，第 3 轮消失")
    void sticksForNextTwoRounds() {
        StickyTracker tracker = new StickyTracker();

        tracker.remember("s1", "m1", 0.88);

        List<StickyTracker.Hit> round1 = tracker.collectAndAdvance("s1");
        assertThat(round1).extracting(StickyTracker.Hit::memoryId).containsExactly("m1");
        assertThat(round1.get(0).score()).isEqualTo(0.88);

        List<StickyTracker.Hit> round2 = tracker.collectAndAdvance("s1");
        assertThat(round2).extracting(StickyTracker.Hit::memoryId).containsExactly("m1");

        assertThat(tracker.collectAndAdvance("s1")).isEmpty();
    }

    @Test
    @DisplayName("再次命中会把剩余轮数重置回 2（不累加、也不提前消失）")
    void rememberResetsRounds() {
        StickyTracker tracker = new StickyTracker();
        tracker.remember("s1", "m1", 0.9);
        tracker.collectAndAdvance("s1"); // 剩 1

        tracker.remember("s1", "m1", 0.95);

        assertThat(tracker.collectAndAdvance("s1")).hasSize(1);
        assertThat(tracker.collectAndAdvance("s1")).hasSize(1);
        assertThat(tracker.collectAndAdvance("s1")).isEmpty();
    }

    @Test
    @DisplayName("会话之间互不影响：s1 的 sticky 不会溢到 s2")
    void sessionsAreIsolated() {
        StickyTracker tracker = new StickyTracker();
        tracker.remember("s1", "m1", 0.9);

        assertThat(tracker.collectAndAdvance("s2")).isEmpty();
        assertThat(tracker.collectAndAdvance("s1")).hasSize(1);
    }
}
