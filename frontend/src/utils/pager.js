/**
 * 管理端分页统一配置。
 *
 * 管理端每一个列表查询都必须能选每页条数——所以这里只有一份定义，各页引用同一个常量，
 * 避免"某页加了 sizes、另一页漏了"这种漂移（页面多了必然发生）。
 *
 * 用法：`layout` 直接绑到 el-pagination，`@size-change` 里把 `current` 置 1 再重新加载。
 * 注意顺序：**先改 current 再请求**——el-pagination 在 size 变化后会做一次"当前页超出总页数就
 * 夹到最后一页"的兜底，此时它也会发 current-change；把 current 先置 1 就让它无事可做，
 * 从而只发一次请求（否则会连发两次、后到的那次赢，看到的页码可能不是第 1 页）。
 */
export const PAGE_SIZES = [10, 20, 50, 100]

/** 常规布局：带总数 */
export const PAGE_LAYOUT = 'total, sizes, prev, pager, next'

/** 总数已在工具栏别处显示的页面用这个，避免同一屏出现两个"共 N 条" */
export const PAGE_LAYOUT_NO_TOTAL = 'sizes, prev, pager, next'

/** 切每页条数时的统一动作：回到第 1 页并重新加载 */
export function applySizeChange(page, size, reload) {
  page.size = size
  page.current = 1
  reload()
}
