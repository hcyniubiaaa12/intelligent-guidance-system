import { defineStore } from 'pinia'
import { ref } from 'vue'
import { countPendingBuckets, countPendingRecords } from '../api/admin'

/**
 * 审核队列的两个计数。收在一个 store 里，是因为它们常常被**同一个动作**一起改变：
 * 聚合会消耗待归桶记录、又可能造出新的待审桶；通过 / 驳回 / 忽略 / 修正重审则直接消耗待审桶。
 * 各页面各拉各的就会出现「一处变了、另一处没动」——侧栏徽标尤其明显：它挂在 AdminLayout 上，
 * 而 AdminLayout 是 /admin 的父级路由组件，整个管理端会话只挂载一次，
 * 原来那次 onMounted 取完数就再也不刷新了。
 *
 * <p>两个数的失败处理**刻意不同**：
 * - 待审桶数当徽标用，取不到就按 0 处理（宁可少一个徽标，也不要弹错）；
 * - 待归桶记录数会显示成一句人话，而它的 0 是**有意义的业务状态**（「当前没有待聚合的错误样本」），
 *   所以取不到时不能拿 0 兜底——那等于把「没取到」伪装成「没有」。用 recordCountFailed 分开记。
 */
export const useReviewStore = defineStore('review', () => {
  /** 待审桶数：侧栏徽标 */
  const pendingBucketCount = ref(0)
  /** 待归桶记录数：null 表示还没取到（首次加载中），0 是真实业务值 */
  const pendingRecordCount = ref(null)
  /** 待归桶记录数这次没取到（区别于「确实就是 0」） */
  const recordCountFailed = ref(false)

  async function refreshPendingBuckets() {
    try {
      pendingBucketCount.value = (await countPendingBuckets()) || 0
    } catch {
      pendingBucketCount.value = 0
    }
  }

  async function refreshPendingRecords() {
    try {
      const total = await countPendingRecords()
      // 后端返回 Long。拿不到数字就当这次没取到，**不折叠成 0**——
      // 0 是「当前没有待聚合的错误样本」这个真实结论，不能被当成兜底值用
      if (typeof total !== 'number') throw new Error('待归桶数量返回了非数字')
      pendingRecordCount.value = total
      recordCountFailed.value = false
    } catch {
      recordCountFailed.value = true
    }
  }

  /** 两个一起刷：进审核页时、以及每次聚合之后（聚合会同时动这两个数） */
  async function refreshAll() {
    await Promise.all([refreshPendingRecords(), refreshPendingBuckets()])
  }

  return {
    pendingBucketCount,
    pendingRecordCount,
    recordCountFailed,
    refreshPendingBuckets,
    refreshPendingRecords,
    refreshAll
  }
})
