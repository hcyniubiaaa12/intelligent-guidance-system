<template>
  <div class="patient-root p-chat">
    <div class="p-chat__body">
      <!-- ---------- 左：我的就诊（会话目录，桌面常驻 / 手机覆盖层） ---------- -->
      <aside
        class="p-chat__side"
        :class="{ 'is-open': sideOpen, 'is-collapsed': isDesktop && sideHidden }"
        :style="isDesktop && !sideHidden ? { width: sideWidth + 'px' } : null"
        @click.self="sideOpen = false"
      >
        <!-- 精简轨（只在桌面收起时渲染）：收起后只剩标识——它本身就是展开入口，右边再给一个展开图标 -->
        <div class="p-chat__rail">
          <button class="p-chat__railbrand" title="展开侧栏" aria-label="展开侧栏" @click="toggleSide">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <rect x="2.6" y="2.6" width="18.8" height="18.8" stroke="currentColor" stroke-width="1.7" />
              <path d="M6.9 8h10.2" stroke="currentColor" stroke-width="2.3" />
              <path d="M6.9 12h6.8" stroke="currentColor" stroke-width="2" opacity=".52" />
              <path d="M6.9 16h3.6" stroke="currentColor" stroke-width="1.7" opacity=".3" />
            </svg>
          </button>
          <span class="p-chat__railsep" />
          <button class="p-chat__iconbtn" title="展开侧栏" aria-label="展开侧栏" @click="toggleSide">
            <svg width="15" height="15" viewBox="0 0 14 14" aria-hidden="true"><rect x="1.2" y="2.2" width="11.6" height="9.6" fill="none" stroke="currentColor" stroke-width="1.2"/><path d="M5 2.2v9.6" stroke="currentColor" stroke-width="1.2"/></svg>
          </button>
          <!-- 收起后仍要能开新咨询——否则得先展开侧栏才够得到 -->
          <button class="p-chat__iconbtn" title="新 的 咨 询" aria-label="新 的 咨 询" @click="startNew">
            <svg width="15" height="15" viewBox="0 0 14 14" aria-hidden="true"><circle cx="7" cy="7" r="5.6" fill="none" stroke="currentColor" stroke-width="1.2"/><path d="M7 4.8v4.4M4.8 7h4.4" fill="none" stroke="currentColor" stroke-width="1.2"/></svg>
          </button>
        </div>

        <div class="p-chat__sidebox">
          <!-- 侧栏抬头：标识 ＋ 品牌名 ＋ 收起按钮（学 DS 的排布）；「智能导诊」从对话区顶栏挪到这里 -->
          <div class="p-chat__hd">
            <span class="p-chat__logo" aria-hidden="true">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                <rect x="2.6" y="2.6" width="18.8" height="18.8" stroke="currentColor" stroke-width="1.7" />
                <path d="M6.9 8h10.2" stroke="currentColor" stroke-width="2.3" />
                <path d="M6.9 12h6.8" stroke="currentColor" stroke-width="2" opacity=".52" />
                <path d="M6.9 16h3.6" stroke="currentColor" stroke-width="1.7" opacity=".3" />
              </svg>
            </span>
            <span class="p-chat__brand">智能导诊<small>您的陪诊助手</small></span>
            <button class="p-chat__iconbtn p-chat__foldbtn" title="收起侧栏" aria-label="收起侧栏" @click="toggleSide">
              <svg width="15" height="15" viewBox="0 0 14 14" aria-hidden="true"><rect x="1.2" y="2.2" width="11.6" height="9.6" fill="none" stroke="currentColor" stroke-width="1.2"/><path d="M5 2.2v9.6" stroke="currentColor" stroke-width="1.2"/></svg>
            </button>
          </div>
          <!-- 新咨询放最上头：会话多了要能一眼够到（学 DeepSeek 的「开启新对话」） -->
          <button class="p-chat__new" @click="startNew">+ 新 的 咨 询</button>

          <div class="p-chat__sidehd">
            <div class="p-eyebrow">我 的 就 诊</div>
            <div class="p-chat__count">{{ tab === 'all' ? `${total} 次` : `${booked} 次挂号` }}</div>
            <div class="p-chat__tabs">
              <button :class="{ on: tab === 'all' }" @click="switchTab('all')">全 部 对 话</button>
              <button :class="{ on: tab === 'booked' }" @click="switchTab('booked')">挂 号 历 史</button>
            </div>
          </div>

          <div class="p-chat__list">
            <template v-for="day in shownDays" :key="day.date">
              <div class="p-day">
                <span>{{ shortDate(day.date) }}</span>
                <span class="n">{{ day.sessions.length }} 次{{ isCollapsed(day) ? ' · 已折叠' : '' }}</span>
              </div>
              <!-- 一行 = 会话 + 归档动作。动作是**兄弟** button（.p-i 自己是 button，里面不能再放 button），
                   绝对定位贴在右侧，hover 才浮现——与对话区的复制按钮同一套语言 -->
              <div v-for="s in visibleSessions(day)" :key="s.id" class="p-irow">
                <button
                  class="p-i"
                  :class="{ on: s.id === activeSessionId, dim: !s.hasResult }"
                  @click="openSession(s.id)"
                >
                  <span class="p-i__x">{{ s.firstComplaint || '（无内容）' }}</span>
                  <span class="p-i__q">{{ s.questionCount }} 问</span>
                  <span class="p-i__d">{{ hhmm(s.startedAt) }}</span>
                </button>
                <button class="p-irow__act" title="收进归档（不是删除，随时可取回）" @click="setArchived(s, true)">归 档</button>
              </div>
              <button v-if="isCollapsed(day)" class="p-fold" @click="expandDay(day.date)">
                展 开 该 天 全 部 {{ day.sessions.length }} 次
              </button>
              <button v-else-if="expanded.has(day.date)" class="p-fold" @click="collapseDay(day.date)">
                收 起 该 天
              </button>
            </template>
            <p v-if="!shownDays.length" class="p-chat__nores">
              {{ archivedTotal ? '都收进归档了' : tab === 'booked' ? '还没有挂过号' : '还没有就诊记录' }}
            </p>

            <!-- 归档区（收纳）：**按天分组**（口径=会话开始那天），条目仍能点开只读回放，只是不在主区 -->
            <template v-if="archivedDaysShown.length">
              <button class="p-fold" @click="archOpen = !archOpen">
                {{ archOpen ? '收 起 归 档' : `已 归 档 · ${archivedTotal} 次` }}
              </button>
              <template v-if="archOpen">
                <template v-for="day in archivedDaysShown" :key="day.date">
                  <div class="p-day">
                    <span>{{ shortDate(day.date) }}</span>
                    <span class="n">{{ day.sessions.length }} 次</span>
                  </div>
                  <div v-for="s in day.sessions" :key="s.id" class="p-irow is-arch">
                    <button
                      class="p-i"
                      :class="{ on: s.id === activeSessionId, dim: !s.hasResult }"
                      @click="openSession(s.id)"
                    >
                      <span class="p-i__x">{{ s.firstComplaint || '（无内容）' }}</span>
                      <span class="p-i__q">{{ s.questionCount }} 问</span>
                      <span class="p-i__d">{{ hhmm(s.startedAt) }}</span>
                    </button>
                    <button class="p-irow__act" title="取回主区" @click="setArchived(s, false)">取 回</button>
                  </div>
                </template>
              </template>
            </template>
          </div>

          <!-- 底部用户区（桌面常驻；手机在「会话」抽屉里） -->
          <div class="p-chat__user">
            <span class="p-chat__uava" aria-hidden="true">{{ (user.nickname || '我').slice(0, 1) }}</span>
            <button class="p-chat__unick" title="看我的挂号历史" @click="openBooked">
              {{ user.nickname || '我 的 就 诊' }}
            </button>
            <button class="p-chat__uprof" title="填写健康档案（选填）" @click="openProfile">健 康 档 案</button>
            <button class="p-chat__uout" @click="onLogout">退 出</button>
          </div>
        </div>
      </aside>

      <!-- ---------- 中：对话舞台 ---------- -->
      <div class="p-chat__stage">
        <!-- 顶栏只剩手机端：桌面端整条移除——标题、用户区都归左侧栏，对话区顶上不再有一条横线。
             （手机没有侧栏可挂，只能留在顶栏） -->
        <header class="p-topbar p-chat__topbar">
          <button class="p-chat__menubtn" @click="sideOpen = true">会 话</button>
          <div class="p-topbar__title">智能导诊</div>
          <div class="p-topbar__ops">
            <button class="p-topbar__nick" title="看我的挂号历史" @click="openBooked">{{ user.nickname || '我 的 就 诊' }}</button>
            <button class="p-topbar__logout" @click="onLogout">退 出</button>
          </div>
        </header>

        <!-- 对话区：有消息时＝对话流＋流程条＋底部输入；空状态时＝欢迎块＋输入框＋免责 一起居中 -->
        <div class="p-chat__main" :class="{ 'is-empty': isEmpty }">
          <main ref="threadEl" class="p-thread p-chat__thread">
            <!-- 空状态：一张还没填的陪诊卡——空状态是行动邀请，不是一句客套话 -->
            <div v-if="isEmpty" class="p-hello">
              <div class="p-hello__hd">
                <span class="p-hello__ava" aria-hidden="true">
                  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 6h16v11H8l-4 4z"/><path d="M12 9v5M9.5 11.5h5"/></svg>
                </span>
                <div>
                  <h2 class="p-hello__t">您好，我是您的陪诊助手</h2>
                  <p class="p-hello__lead">先跟我说说哪里不舒服，我帮您判断该去哪个科。</p>
                </div>
              </div>
              <div class="p-hello__label">说 清 这 三 件 事 就 够 了</div>
              <div class="p-hello__three">
                <div class="p-hello__bub"><b>① 部 位</b><span>哪里不舒服</span></div>
                <div class="p-hello__bub"><b>② 时 间</b><span>大概多久了</span></div>
                <div class="p-hello__bub"><b>③ 感 觉</b><span>是怎么个难受法</span></div>
              </div>
              <p class="p-hello__eg">"右下方肚子疼了两天，一阵一阵的，还伴着恶心，没发烧。"</p>
              <div class="p-hello__label">大 家 常 问 的</div>
              <div class="p-hello__chips">
                <button v-for="c in COMMON" :key="c" class="p-hello__chip" @click="useChip(c)">{{ c }}</button>
              </div>
            </div>

            <!-- 回放与实时共用同一套条目渲染：shown = 回放条目 或 本次对话条目 -->
            <template v-else>
              <template v-for="(m, i) in shown" :key="i">
                <!-- 用户消息：实心 teal 气泡（回放时带问题编号，书签靠它定位） -->
                <div
                  v-if="m.type === 'user'"
                  :ref="(el) => setQRef(el, m.qNo)"
                  class="p-q"
                  :class="{ 'is-active': m.qNo && m.qNo === activeQ }"
                >
                  <div class="p-q__col">
                    <div class="p-user">{{ m.content }}</div>
                    <button
                      class="p-act__btn"
                      :class="{ 'is-copied': copiedKey === 'u' + i }"
                      :aria-label="copiedKey === 'u' + i ? '已复制' : '复制这条主诉'"
                      @click="copyText('u' + i, m.content)"
                    >
                      <svg v-if="copiedKey !== 'u' + i" width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><rect x="3.5" y="3.5" width="7" height="7" fill="none" stroke="currentColor"/><path d="M8.5 3.5v-2h-7v7h2" fill="none" stroke="currentColor"/></svg>
                      <svg v-else width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><path d="M2 6.5 5 9.5 10 3.5" fill="none" stroke="currentColor" stroke-width="1.4"/></svg>
                      {{ copiedKey === 'u' + i ? '已 复 制' : '复 制' }}
                    </button>
                  </div>
                </div>

                <!-- 追问消息：与普通回复同款 -->
                <div v-else-if="m.type === 'question'" class="p-ai">
                  <span class="p-ai__ava" aria-hidden="true">
                    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 6h16v11H8l-4 4z"/><path d="M12 9v5M9.5 11.5h5"/></svg>
                  </span>
                  <div class="p-ai__bd">
                  <div class="p-ai__tag">陪 诊 助 手 · 追 问</div>
                  <div class="p-ai__text p-ask">{{ m.content }}</div>
                  <button
                    v-if="m.content"
                    class="p-act__btn"
                    :class="{ 'is-copied': copiedKey === 'q' + i }"
                    :aria-label="copiedKey === 'q' + i ? '已复制' : '复制这条追问'"
                    @click="copyText('q' + i, m.content)"
                  >
                    <svg v-if="copiedKey !== 'q' + i" width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><rect x="3.5" y="3.5" width="7" height="7" fill="none" stroke="currentColor"/><path d="M8.5 3.5v-2h-7v7h2" fill="none" stroke="currentColor"/></svg>
                    <svg v-else width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><path d="M2 6.5 5 9.5 10 3.5" fill="none" stroke="currentColor" stroke-width="1.4"/></svg>
                    {{ copiedKey === 'q' + i ? '已 复 制' : '复 制' }}
                  </button>
                  </div>
                </div>

                <!-- 资料回答：患者问的是知识库内容，系统如实复述片段作答（不推荐科室、不是追问）
                     与追问一样复用 AI 气泡的形状，只有标签不同——患者一眼能分出这不是分诊结论 -->
                <div v-else-if="m.type === 'info'" class="p-ai">
                  <span class="p-ai__ava" aria-hidden="true">
                    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 6h16v11H8l-4 4z"/><path d="M12 9v5M9.5 11.5h5"/></svg>
                  </span>
                  <div class="p-ai__bd">
                  <div class="p-ai__tag">陪 诊 助 手 · 资 料</div>
                  <div class="p-ai__text">{{ m.content }}</div>
                  <button
                    v-if="m.content"
                    class="p-act__btn"
                    :class="{ 'is-copied': copiedKey === 'if' + i }"
                    :aria-label="copiedKey === 'if' + i ? '已复制' : '复制这条资料回答'"
                    @click="copyText('if' + i, m.content)"
                  >
                    <svg v-if="copiedKey !== 'if' + i" width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><rect x="3.5" y="3.5" width="7" height="7" fill="none" stroke="currentColor"/><path d="M8.5 3.5v-2h-7v7h2" fill="none" stroke="currentColor"/></svg>
                    <svg v-else width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><path d="M2 6.5 5 9.5 10 3.5" fill="none" stroke="currentColor" stroke-width="1.4"/></svg>
                    {{ copiedKey === 'if' + i ? '已 复 制' : '复 制' }}
                  </button>
                  </div>
                </div>

                <!-- AI 回复：直排文字 + moss 小标签（SSE delta 逐字填充同一文本节点）
                     本块必须单行书写：.p-ai__text 是 pre-wrap，换行缩进会被原样渲染 -->
                <div v-else-if="m.type === 'ai'" class="p-ai">
                  <span class="p-ai__ava" aria-hidden="true">
                    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 6h16v11H8l-4 4z"/><path d="M12 9v5M9.5 11.5h5"/></svg>
                  </span>
                  <div class="p-ai__bd">
                  <div class="p-ai__tag">陪 诊 助 手</div>
                  <div class="p-ai__text">{{ m.content }}<span v-if="!isReplay && chat.streaming && i === shown.length - 1 && !m.content" class="p-ai__wait">正在整理…</span></div>
                  <button
                    v-if="m.content"
                    class="p-act__btn"
                    :class="{ 'is-copied': copiedKey === 'a' + i }"
                    :aria-label="copiedKey === 'a' + i ? '已复制' : '复制这条回复'"
                    @click="copyText('a' + i, m.content)"
                  >
                    <svg v-if="copiedKey !== 'a' + i" width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><rect x="3.5" y="3.5" width="7" height="7" fill="none" stroke="currentColor"/><path d="M8.5 3.5v-2h-7v7h2" fill="none" stroke="currentColor"/></svg>
                    <svg v-else width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><path d="M2 6.5 5 9.5 10 3.5" fill="none" stroke="currentColor" stroke-width="1.4"/></svg>
                    {{ copiedKey === 'a' + i ? '已 复 制' : '复 制' }}
                  </button>
                  </div>
                </div>

                <!-- 处置提示（敏感词累计触发的警告）：单独成泡，视觉上与诊断结论区分开 -->
                <div v-else-if="m.type === 'notice'" class="p-notice">{{ m.content }}</div>

                <!-- 推荐卡（签名元素 · 链路 A 结论单）：只有最新一张能继续挂号；回放时整张只读 -->
                <section v-else-if="m.type === 'card'" class="p-card">
                  <div class="p-card__head">
                    <div>
                      <div class="p-eyebrow">给 您 的 分 诊 结 论</div>
                      <div class="p-card__dept">{{ m.card.dept }}</div>
                    </div>
                    <div class="p-card__conf" :class="{ 'p-card__conf--low': isLow(m.card) }">
                      {{ confText(m.card.confidence) }}
                    </div>
                  </div>

                  <!-- 置信度条列表 Top3 -->
                  <div class="p-bars">
                    <div
                      v-for="(c, k) in rankedTop3(m.card)"
                      :key="c.deptId || c.name"
                      class="p-bar"
                      :class="{ 'p-bar--top': k === 0 }"
                    >
                      <span class="p-bar__rank">{{ k + 1 }}</span>
                      <span class="p-bar__name">{{ c.name }}</span>
                      <span class="p-bar__track"><span class="p-bar__fill" :style="{ width: (c.pct ?? 0) + '%' }" /></span>
                      <span class="p-bar__pct">{{ c.pct == null ? '—' : c.pct + '%' }}</span>
                    </div>
                  </div>

                  <p class="p-card__note">{{ m.card.note }}</p>

                  <!-- 脚注区：判断依据（1px dashed 上边框，注号对应证据顺序） -->
                  <div class="p-card__foot">
                    <span class="p-eyebrow">判 断 依 据</span>
                    <p v-for="c in m.card.cites" :key="c.no" class="p-cite">
                      <span class="p-cite__no">注{{ c.no }}</span>
                      <span class="p-cite__title">{{ c.title }}</span>
                      <span class="p-cite__body">{{ c.content }}</span>
                    </p>
                  </div>

                  <!-- 健康档案：患者自述，不是医学证据。单独一行、标成「健康档案」，与「判断依据」分开 -->
                  <p v-if="m.card.profileText" class="p-card__profile">
                    已参考您的健康档案：{{ m.card.profileText }}
                  </p>

                  <p v-if="isLow(m.card)" class="p-card__lowhint">
                    信息有限，结果仅供参考，建议进一步咨询医生。
                  </p>

                  <!-- 一键复制结论单（科室＋置信度＋依据），学 DS 的块级复制 -->
                  <button
                    class="p-act__btn p-card__copy"
                    :class="{ 'is-copied': copiedKey === 'c' + i }"
                    :aria-label="copiedKey === 'c' + i ? '已复制' : '复制这条分诊结论'"
                    @click="copyText('c' + i, cardText(m.card))"
                  >
                    <svg v-if="copiedKey !== 'c' + i" width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><rect x="3.5" y="3.5" width="7" height="7" fill="none" stroke="currentColor"/><path d="M8.5 3.5v-2h-7v7h2" fill="none" stroke="currentColor"/></svg>
                    <svg v-else width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><path d="M2 6.5 5 9.5 10 3.5" fill="none" stroke="currentColor" stroke-width="1.4"/></svg>
                    {{ copiedKey === 'c' + i ? '已 复 制' : '复 制 结 论' }}
                  </button>

                  <!-- 回放时已挂号的，补一行就诊信息；没有的什么都不加 -->
                  <div v-if="isReplay && m.card.booked" class="p-chat__visit">
                    就 诊　{{ m.card.actualDept }}<template v-if="m.card.actualDeptLocation"> · {{ m.card.actualDeptLocation }}</template>
                  </div>

                  <!-- 签名部件：三步陪伴带。**只在实时对话里出现**——回放是历史记录，
                       "接下来我陪您走这三步"在那时是句假话 -->
                  <div v-if="!isReplay" class="p-band">
                    <div class="p-band__hd">接下来我陪您走这三步</div>
                    <div class="p-band__row">
                      <span class="p-band__n p-band__n--now">
                        <span class="p-band__dot"></span><span class="p-band__t">① 拿到结论</span>
                      </span>
                      <span class="p-band__link"></span>
                      <span class="p-band__n">
                        <span class="p-band__dot"></span><span class="p-band__t">② 去挂号</span>
                      </span>
                      <span class="p-band__link"></span>
                      <span class="p-band__n">
                        <span class="p-band__dot"></span><span class="p-band__t">③ 确认完成</span>
                      </span>
                    </div>
                  </div>

                  <button
                    v-if="!isReplay"
                    class="p-btn p-card__go"
                    :disabled="i !== lastCardIndex"
                    @click="goRegister(m)"
                  >
                    我陪您 · 去模拟挂号
                  </button>
                </section>

                <!-- SSE error：line 描边块，文案说清原因与下一步 -->
                <div v-else-if="m.type === 'error'" class="p-error">
                  {{ m.message }}
                  <button class="p-error__retry" @click="retry(m)">重新发送</button>
                </div>
              </template>
            </template>
          </main>

          <!-- 步进流程条：① 导诊结论 → ② 模拟挂号 → ③ 确认完成（空状态时还没有任何步骤，不显示） -->
          <nav v-if="!isEmpty" class="p-steps">
            <span class="p-steps__item p-steps__item--now"><span class="p-steps__no">1</span>导诊结论</span>
            <span class="p-steps__link" />
            <span class="p-steps__item"><span class="p-steps__no">2</span>模拟挂号</span>
            <span class="p-steps__link" />
            <span class="p-steps__item"><span class="p-steps__no">3</span>确认完成</span>
          </nav>

          <!-- 输入区：一整块「书写区」——上面写字，下面一行小字＋发送（学 DeepSeek 网页版的排布，
               保持纸感的直角与细线）。看历史时换成只读条——一次会话 = 一次就诊，翻旧账不能往里写字 -->
          <footer v-if="!isReplay" class="p-composer">
            <div class="p-composer__box">
              <textarea
                ref="inputEl"
                v-model="draft"
                class="p-composer__input"
                rows="1"
                :placeholder="isEmpty ? '说吧，我在听…' : '还有什么想补充的，慢慢说…'"
                @input="autoGrow"
                @keydown.enter.exact.prevent="send()"
              />
              <div class="p-composer__bar">
                <span class="p-composer__hint">分诊建议，不能替代医生诊断</span>
                <button class="p-composer__send" :disabled="chat.streaming" aria-label="发送" @click="send()">
                  <svg width="15" height="15" viewBox="0 0 16 16" aria-hidden="true">
                    <path d="M8 13.5V3M3.2 7.8 8 3l4.8 4.8" fill="none" stroke="currentColor" stroke-width="1.8" />
                  </svg>
                </button>
              </div>
            </div>
          </footer>
          <div v-else class="p-chat__lock">
            <span class="p-chat__locktxt">历 史 记 录 · 只 读</span>
            <button class="p-chat__lockbtn" @click="startNew">开始新的咨询</button>
          </div>
        </div>
      </div>

      <!-- ---------- 右缘：问题导航（只在回放时）——悬浮面板，鼠标移到右缘就展开 ----------
           面板脱离布局流（absolute）：进出回放不会把中间那一列顶来顶去。
           一根 = 这条会话里患者的一个问题，自上而下 = 问题 1 → 最新一问，跟正文同向 -->
      <nav
        v-if="isReplay && marks.length"
        class="p-chat__marks"
        :class="{ 'is-open': marksOpen }"
        aria-label="用户问题"
        @mouseenter="marksOpen = true"
        @mouseleave="marksOpen = false"
      >
        <button class="p-chat__marksgrip" aria-label="展开问题导航" @click="onGripClick" />
        <div class="p-chat__markspanel">
          <div class="p-chat__markshd">
            <span class="p-eyebrow">用 户 问 题</span>
            <span class="p-chat__marksno">{{ activeQ ? `${activeQ} / ${marks.length}` : `${marks.length} 问` }}</span>
          </div>
          <!-- 放不下时面板内部自己滚（滚动条不画），滚到头再滚正文 -->
          <div
            ref="marksEl"
            class="p-chat__markset"
            :class="{ 'is-scroll': marksScroll }"
            @wheel="onMarksWheel"
          >
            <button
              v-for="q in marks"
              :key="q.no"
              :ref="(el) => setMarkRef(el, q.no)"
              class="p-mark"
              :class="{ on: q.no === activeQ }"
              :title="`问题 ${q.no} · ${hhmm(q.at)}`"
              :aria-label="`跳到问题 ${q.no}：${q.content}`"
              @click="jump(q.no)"
            >
              <span class="p-mark__x">{{ q.content }}</span>
              <span class="p-mark__bar" />
            </button>
          </div>
        </div>
      </nav>

      <!-- ---------- 健康档案（选填）：浮层 ----------
           外壳（遮罩 / 容器 / 抬头的关闭 / 进出节奏）走 patient.css 的**共享浮层语言**
           `.p-overlay*`，本页不再自己实现那几样——见设计文档 §3.8。
           表单内部（__row / __chip / __select…）仍是本页私有，等 B-03 对话页换皮再收拾。
           上限直接在源头挡住：标签多选到顶写不进、自由文本走 maxlength，并显示剩余额度 -->
      <Transition name="p-fade">
        <div v-if="profileOpen" class="p-overlay p-overlay--wide" @click.self="closeProfile">
          <div class="p-overlay__box" role="dialog" aria-label="我的健康档案">
            <header class="p-overlay__head">
              <div>
                <div class="p-overlay__title">健 康 档 案</div>
                <p class="p-overlay__sub">选填 · 帮分诊结合您的既往情况，随时可改</p>
              </div>
              <button class="p-overlay__close" aria-label="关闭健康档案" @click="closeProfile">关 闭</button>
            </header>

            <div v-if="profileLoading" class="p-prof__loading">正在加载…</div>

            <div v-else class="p-overlay__body">
              <div class="p-prof__row">
                <label class="p-prof__label">性　别</label>
                <select v-model="profileForm.gender" class="p-prof__select">
                  <option value="">不填</option>
                  <option v-for="g in profileOptions.genders" :key="g.value" :value="g.value">{{ g.label }}</option>
                </select>
              </div>
              <div class="p-prof__row">
                <label class="p-prof__label">年 龄 段</label>
                <select v-model="profileForm.ageRange" class="p-prof__select">
                  <option value="">不填</option>
                  <option v-for="a in profileOptions.ageRanges" :key="a.value" :value="a.value">{{ a.label }}</option>
                </select>
              </div>

              <section v-for="grp in profileGroups" :key="grp.key" class="p-prof__group">
                <div class="p-prof__ghead">
                  <span class="p-prof__glabel">{{ grp.label }}</span>
                  <span class="p-prof__quota">已选 {{ profileForm[grp.tags].length }} / 最多 {{ profileLimits.tagMax }} 项</span>
                </div>
                <div class="p-prof__chips">
                  <button
                    v-for="t in grp.options"
                    :key="t.id"
                    type="button"
                    class="p-prof__chip"
                    :class="{ 'is-on': profileForm[grp.tags].includes(t.term) }"
                    @click="toggleTag(grp.tags, t.term)"
                  >{{ t.term }}</button>
                  <p v-if="!grp.options.length" class="p-prof__none">词表暂无可选项，可在下方自由填写</p>
                </div>
                <div class="p-prof__other">
                  <input
                    v-model="profileForm[grp.other]"
                    class="p-prof__input"
                    type="text"
                    :placeholder="grp.placeholder"
                    :maxlength="textFieldMax(grp.other)"
                  />
                  <span class="p-prof__left">{{ textFieldRemaining(grp.other) }} 字</span>
                </div>
              </section>

              <p v-if="profileError" class="p-prof__err">{{ profileError }}</p>
            </div>

            <footer class="p-overlay__foot">
              <button class="p-btn" :disabled="profileLoading || profileSaving" @click="saveProfile">
                {{ profileSaving ? '保 存 中…' : '保 存 档 案' }}
              </button>
            </footer>
          </div>
        </div>
      </Transition>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { useChatStore } from '../../stores/chat'
import { logout as apiLogout } from '../../api/auth'
import { listSessions, sessionDetail, archiveSession } from '../../api/records'
import { getHealthProfile, saveHealthProfile, listHealthTags } from '../../api/profile'
import { streamChat } from '../../utils/sse'
import { track } from '../../utils/track'
import '../../styles/patient.css'

// 一天内会话超过这个数就折叠；折叠时只露最新这几条
const FOLD_OVER = 10
const FOLD_SHOW = 2

const router = useRouter()
const user = useUserStore()
const chat = useChatStore()

const draft = ref('')
const threadEl = ref(null)
const inputEl = ref(null)

/** 常见主诉：点一下填进输入框——**不直接发**（患者还能补一句"还伴着恶心"，也不至于误触烧掉一次模型调用） */
const COMMON = ['发热咳嗽', '肚子疼', '头疼头晕', '皮肤起疹', '心慌胸闷', '腰背酸痛']

function useChip(text) {
  const now = draft.value.trim()
  draft.value = now ? `${now}，${text}` : text
  inputEl.value?.focus()
  nextTick(autoGrow)
}

/** 输入框随内容长高（120px 封顶后自己滚）——别让一段长主诉挤在一条缝里写 */
function autoGrow() {
  const el = inputEl.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 120)}px`
}
// 当前流的终止句柄：离开页面或退出登录时中断，避免回调写已卸载的组件
let turnAbort = null

// ---------------------------------------------------------------- 左侧会话目录

const days = ref([])
const total = ref(0)
const booked = ref(0)
const tab = ref('all')
const sideOpen = ref(false)
const expanded = ref(new Set())
// 归档区（收纳）：归档的会话仍在列表数据里，后端按天分好（口径=会话开始那天），折在下面
const archivedDays = ref([])
const archOpen = ref(false)

// ---------- 侧栏收起/展开（学 DS：收起成一条只留「折叠图标＋新建对话」的精简轨） ----------
const SIDE_HIDDEN_KEY = 'p-chat-side-hidden'
const SIDE_W_DEFAULT = 228

const sideHidden = ref(localStorage.getItem(SIDE_HIDDEN_KEY) === '1')
// 只有桌面才收起侧栏；手机侧栏是覆盖层，开关走的是 sideOpen
const isDesktop = ref(window.matchMedia('(min-width: 768px)').matches)
const sideWidth = computed(() => SIDE_W_DEFAULT)

function toggleSide() {
  sideHidden.value = !sideHidden.value
  localStorage.setItem(SIDE_HIDDEN_KEY, sideHidden.value ? '1' : '0')
}

// ---------- 一键复制（学 DS：回复/结论旁的复制按钮，1.5s 后复位） ----------
const copiedKey = ref('')
let copiedTimer = null
async function copyText(key, text) {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    // 剪贴板 API 不可用（非安全上下文）时的兜底
    const ta = document.createElement('textarea')
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    try { document.execCommand('copy') } catch { /* 复制失败就静默，按钮照样复位 */ }
    ta.remove()
  }
  copiedKey.value = key
  clearTimeout(copiedTimer)
  copiedTimer = setTimeout(() => { copiedKey.value = '' }, 1500)
}

/** 结论卡复制成纯文本：科室＋置信度＋Top3＋说明＋依据，方便贴给医生/家人看 */
function cardText(card) {
  const lines = [`分诊结论：${card.dept}（参考置信度 ${confText(card.confidence)}）`]
  if (card.top3?.length) {
    lines.push('候选科室：' + rankedTop3(card).map((c, k) => `${k + 1}. ${c.name}${c.pct == null ? '' : ' ' + c.pct + '%'}`).join('，'))
  }
  if (card.note) lines.push(card.note)
  if (card.cites?.length) {
    lines.push('判断依据：')
    card.cites.forEach((c) => lines.push(`  注${c.no}　${c.title}\n    ${c.content}`))
  }
  if (card.profileText) lines.push(`已参考您的健康档案：${card.profileText}`)
  lines.push('（分诊建议，不能替代医生诊断）')
  return lines.join('\n')
}

/** 当前 tab 口径下要展示的天：全部对话 = 原样；挂号历史 = 只留挂过号的会话 */
const shownDays = computed(() => {
  if (tab.value === 'all') return days.value
  return days.value
    .map((d) => ({ ...d, sessions: d.sessions.filter((s) => s.booked) }))
    .filter((d) => d.sessions.length > 0)
})

/** 归档区跟着当前 tab 的过滤口径走（挂号历史 tab 里只列挂过号的归档会话），结构同主区（按天） */
const archivedDaysShown = computed(() => {
  if (tab.value === 'all') return archivedDays.value
  return archivedDays.value
    .map((d) => ({ ...d, sessions: d.sessions.filter((s) => s.booked) }))
    .filter((d) => d.sessions.length > 0)
})

/** 归档区总条数（折起时的「N 次」标签用） */
const archivedTotal = computed(() => archivedDaysShown.value.reduce((sum, d) => sum + d.sessions.length, 0))

/** 正在看的是哪一条：看历史就是历史的，实时对话就是本轮的 */
const activeSessionId = computed(() => replay.value?.session?.id || chat.sessionId || null)

async function loadSessions() {
  try {
    const data = await listSessions()
    days.value = data.days || []
    archivedDays.value = data.archivedDays || []
    total.value = data.totalSessions || 0
    booked.value = data.totalBooked || 0
  } catch (e) {
    // 侧栏拉不到不该挡住发消息；接口真挂了别处也会报错
    console.error('[chat] 会话列表拉取失败', e)
  }
}

/**
 * 归档 / 取回一条会话。归档是收纳不是删除：归档后仍能点开只读回放，随时可「取回」。
 *
 * <p>两个细节：
 * ① 归档的若正是当前正在看（回放）或正在聊的那条，**把视图一起收掉**——它已经不在列表主区了，
 *    还留在对话区会让人以为归档没生效；正在聊的那条则回到"新咨询"状态（新消息不会再回到归档会话）。
 * ② 归档后自动展开归档区一次：让患者看见"它去哪了"，否则像凭空消失。
 */
async function setArchived(session, next) {
  try {
    await archiveSession(session.id, next)
  } catch (e) {
    console.error('[chat] 归档操作失败', e)
    return
  }
  if (next) {
    // 归档当前活跃会话：连视图一起收
    if (replay.value?.session?.id === session.id) replay.value = null
    else if (chat.sessionId === session.id) chat.reset()
    archOpen.value = true
  } else if (archivedTotal.value <= 1) {
    // 取回的是归档区最后一条：连收纳区一起收起来
    archOpen.value = false
  }
  await loadSessions()
}

function switchTab(next) {
  tab.value = next
}

function isCollapsed(day) {
  return day.sessions.length > FOLD_OVER && !expanded.value.has(day.date)
}

function visibleSessions(day) {
  return isCollapsed(day) ? day.sessions.slice(0, FOLD_SHOW) : day.sessions
}

function expandDay(date) {
  expanded.value = new Set([...expanded.value, date])
}

function collapseDay(date) {
  const next = new Set(expanded.value)
  next.delete(date)
  expanded.value = next
}

// ---------------------------------------------------------------- 回放

const replay = ref(null)
const activeQ = ref(null)
const qRefs = ref({})

const isReplay = computed(() => !!replay.value)
/** 一句话都还没说过：空状态把欢迎块、输入框、免责当成一整块居中 */
const isEmpty = computed(() => !isReplay.value && chat.entries.length === 0)
/** 渲染的统一来源：看历史用回放条目，否则用本次对话条目 */
const shown = computed(() => (replay.value ? replay.value.entries : chat.entries))
const marks = computed(() => replay.value?.questions || [])

/** 把 RecordService 的详情转成对话区能渲染的条目——与实时 SSE 出来的形状保持一致 */
function toReplayEntries(d) {
  const out = (d.messages || []).map((m) => ({
    // 追问与资料回答在回放里也要保持各自的定性（否则同一条消息实时看是「· 资料」、历史里变成普通回复）
    type: m.role === 'user' ? 'user' : m.role === 'question' ? 'question' : m.role === 'info' ? 'info' : 'ai',
    content: m.content,
    qNo: m.role === 'user' ? m.questionNo : undefined
  }))
  if (d.card) out.push({ type: 'card', recordId: null, card: { ...d.card } })
  return out
}

async function openSession(id) {
  sideOpen.value = false
  try {
    const d = await sessionDetail(id)
    replay.value = { session: d.session, entries: toReplayEntries(d), questions: d.questions || [] }
    activeQ.value = null
    qRefs.value = {}
    markRefs.value = {}
    marksOpen.value = false // 每进一次回放都从收起态开始——面板别自己摊在正文上
    await nextTick()
    if (threadEl.value) threadEl.value.scrollTop = 0
    watchMarks()
  } catch (e) {
    console.error('[chat] 回放失败', e)
  }
}

/** 开一段全新咨询：撤回放、清对话态（下一条消息即新会话），光标直接落进输入框 */
function startNew() {
  turnAbort?.abort()
  replay.value = null
  activeQ.value = null
  sideOpen.value = false
  chat.reset()
  nextTick(() => inputEl.value?.focus())
}

// ---------------------------------------------------------------- 书签

const marksEl = ref(null)
const markRefs = ref({})
const marksScroll = ref(false)
const marksOpen = ref(false)

function setQRef(el, no) {
  if (el && no) qRefs.value[no] = el
}

function setMarkRef(el, no) {
  if (el && no) markRefs.value[no] = el
}

/** 触屏没有 hover：点一下把手切换面板（桌面端交给 mouseenter） */
function onGripClick() {
  if (window.matchMedia?.('(hover: none)').matches) marksOpen.value = !marksOpen.value
}

/** 面板内是否放不下：放不下才给它画上下渐隐（不溢出时渐隐会把最后一条抹淡） */
function syncMarksScroll() {
  const el = marksEl.value
  marksScroll.value = !!el && el.scrollHeight > el.clientHeight + 1
}

let marksRO = null
function watchMarks() {
  const el = marksEl.value
  if (!el || !marksRO) return
  marksRO.disconnect()
  marksRO.observe(el)
  syncMarksScroll()
}

/** 滚轮落在面板上：面板自己没滚到头就归面板，滚到头（或本来就放得下）转给正文 */
function onMarksWheel(e) {
  const el = marksEl.value
  if (!el) return
  if (marksScroll.value) {
    const atTop = el.scrollTop <= 0 && e.deltaY < 0
    const atBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 1 && e.deltaY > 0
    if (!atTop && !atBottom) return // 面板自己滚，不拦
  }
  e.preventDefault()
  if (threadEl.value) threadEl.value.scrollTop += e.deltaY
}

/** 把某一根书签带回视野（手动算，不用 scrollIntoView——免得连带滚到别的容器） */
function revealMark(no) {
  const box = marksEl.value
  const el = markRefs.value[no]
  if (!box || !el || !marksScroll.value) return
  const top = el.offsetTop
  const bottom = top + el.offsetHeight
  if (top < box.scrollTop) box.scrollTop = top - 6
  else if (bottom > box.scrollTop + box.clientHeight) box.scrollTop = bottom - box.clientHeight + 6
}

function jump(no) {
  activeQ.value = no
  revealMark(no)
  const el = qRefs.value[no]
  if (!el) return
  const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  el.scrollIntoView({ behavior: reduced ? 'auto' : 'smooth', block: 'center' })
}

// ---------------------------------------------------------------- 对话

// 只有最新一张结论卡可继续挂号（一个聊天页可先后承载多个会话与多张卡）
const lastCardIndex = computed(() => {
  for (let i = shown.value.length - 1; i >= 0; i--) {
    if (shown.value[i].type === 'card') return i
  }
  return -1
})

// 模型未给出合法置信度时后端置 null：显示「—」并走低置信度样式，不出现 NaN% / null%
function confText(confidence) {
  return typeof confidence === 'number' && Number.isFinite(confidence)
    ? `${Math.round(confidence * 100)}%`
    : '—'
}

// 低置信度以**后端下发为准**（阈值来自 sys_config，管理端可调）；
// 前端只在后端没给标志时按「置信度缺失」兜底，不自己写死阈值，避免与管理端口径打架
function isLow(card) {
  return card.lowConfidence === true || typeof card.confidence !== 'number'
}

/**
 * Top3 展示顺序：**结论主体（card.dept）钉在第 1 位，其余按 pct 降序、null 垫底**。
 *
 * 后端 rag 层已归一化过顺序（首位 = 顶层 dept），这里是同一口径的防御性渲染：
 * ① 2026-10-02 之前落库的 rec_top3 快照里顺序是乱的，回放时那批记录仍要显示正确；
 * ② 万一后端顺序又出问题，第一位也不能是备选科室——它得跟卡片顶部的大科室名一致。
 * 纯展示层重排，不改任何数据。
 */
function rankedTop3(card) {
  const list = Array.isArray(card.top3) ? [...card.top3] : []
  const rank = (c) => (typeof c.pct === 'number' && Number.isFinite(c.pct) ? c.pct : -1)
  // 稳定排序：pct 相同时保持后端给的先后（Array.prototype.sort 在现代引擎上稳定）
  list.sort((a, b) => rank(b) - rank(a))
  const top = list.findIndex((c) => c.name === card.dept)
  // 找到就钉到首位；找不到（老快照里科室名对不上）就保持降序——此时 pct 最高的那条就是 top1
  if (top > 0) list.unshift(...list.splice(top, 1))
  return list
}

let scrollScheduled = false
function scrollToBottom() {
  if (scrollScheduled) return
  scrollScheduled = true
  nextTick(() => {
    scrollScheduled = false
    const el = threadEl.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

// 用户主动发送：插用户气泡 + 跑一轮导诊
function send(text) {
  const content = (typeof text === 'string' ? text : draft.value).trim()
  if (!content || chat.streaming) return
  draft.value = ''
  nextTick(autoGrow) // 清空后缩回单行
  chat.pushEntry({ type: 'user', content })
  runTurn(content)
}

// 一轮导诊：占位 AI 气泡 → SSE 七事件（session / delta / question / result / notice / done / error）
// turnSeq 标记「当前这一轮」：终态事件（question/result/error）先于流关闭到达时，
// 收尾只认最新一轮，避免上一轮的收尾把新一轮的流式态关掉
let turnSeq = 0
async function runTurn(content) {
  if (chat.streaming) return
  const seq = ++turnSeq
  chat.streaming = true
  // 占位气泡的响应式引用：delta 直接追加到它，逐字填充同一个文本节点。
  // 用 let 是因为处置提示（notice）会另起一泡，之后答案的 delta 要落到新气泡里
  let bubble = chat.pushEntry({ type: 'ai', content: '' })
  scrollToBottom()

  const endTurn = () => {
    if (seq !== turnSeq) return
    chat.streaming = false
    scrollToBottom()
  }

  turnAbort = new AbortController()
  try {
    await streamChat(
      { sessionId: chat.sessionId, content },
      {
        onSession: ({ sessionId }) => chat.setSession(sessionId),

        onDelta: ({ text }) => {
          bubble.content += text
          scrollToBottom()
        },

        // 处置提示（敏感词累计触发的警告）：
        // ① 当前气泡还空着就把它收掉，免得留下一个空气泡；
        // ② 另起一泡显示提示，并把占位气泡换成新的——否则提示后面的答案仍写进同一个气泡，
        //    提示与结论就粘成一段话了（这正是后端用独立 notice 事件而不是 delta 的原因）
        onNotice: ({ content: text }) => {
          if (!bubble.content) chat.removeEntry(bubble)
          chat.pushEntry({ type: 'notice', content: text })
          bubble = chat.pushEntry({ type: 'ai', content: '' })
          scrollToBottom()
        },

        onQuestion: ({ content: full }) => {
          // 两条来源：① 模型已随 delta 流出追问文本——本事件只是定性标记，内容以事件为准，
          // 绝不能重复渲染；② 规则模板兜底（无 delta，占位气泡本就是空的）。
          // 两种情况下都复用当前气泡，视觉上等价于「新建一个追问气泡」。
          bubble.type = 'question'
          bubble.content = full
          endTurn()
        },

        // 资料回答（患者问的是知识库内容而不是描述症状）：内容同样已随 delta 流过，
        // 事件只做定性——复用当前气泡换个标签即可。它既不是追问（系统没在要信息）也不是结论
        // （没有科室推荐），必须让患者看出来，否则会把一段资料读成分诊结论。
        onInfo: ({ content: full }) => {
          bubble.type = 'info'
          bubble.content = full
          endTurn()
        },

        onResult: (data) => {
          // 结论前的自然语言（若有）保留在气泡里；没有则收起占位气泡
          if (!bubble.content) chat.removeEntry(bubble)
          chat.pushEntry({
            type: 'card',
            recordId: data.recordId,
            card: {
              deptId: data.deptId,
              dept: data.dept,
              confidence: data.confidence,
              top3: data.top3 || [],
              note: data.note,
              cites: data.cites || [],
              lowConfidence: data.lowConfidence,
              profileText: data.profileText || ''
            }
          })
          // 推荐卡渲染完成 = result_view 触点（旁路上报，失败静默）
          if (data.recordId) track('result_view', { recordId: data.recordId })
          endTurn()
        },

        // error 之后后端还会补一个 done：此时流式态已结束，重复收尾无害
        onDone: () => endTurn(),

        onError: ({ message }) => {
          if (!bubble.content) chat.removeEntry(bubble)
          chat.pushEntry({
            type: 'error',
            message: message || '网络中断，请检查连接后重试',
            text: content
          })
          endTurn()
        }
      },
      turnAbort.signal
    )
  } catch (e) {
    // streamChat 内部已兜底全部异常路径，这里只兜住意外，避免未处理的 Promise 拒绝
    console.error('[chat] 对话流异常', e)
  } finally {
    if (seq === turnSeq) {
      turnAbort = null
      chat.streaming = false
    }
    // 这一轮可能刚落库：刷新左侧列表（新会话 / 提问数 / 挂号状态都靠它）
    loadSessions()
  }
}

// 重发上一条输入：撤掉错误块再跑一轮（用户气泡已在对话流里，不重复插入）
function retry(entry) {
  if (chat.streaming) return
  const text = entry.text
  chat.removeEntry(entry)
  runTurn(text)
}

function goRegister(cardEntry) {
  // 把 Top3 的科室 id 按推荐次序一并带过去（逗号分隔）：挂号页据此把推荐科室提到列表最前，
  // 否则整份列表只能按创建时间序排，推荐科室夹在中间（患者要自己找哪条是「推 荐」）
  const rec = rankedTop3(cardEntry.card).map((c) => c.deptId).filter(Boolean)
  router.push({
    path: '/register',
    query: {
      recordId: cardEntry.recordId,
      deptId: cardEntry.card.deptId,
      ...(rec.length ? { rec: rec.join(',') } : {})
    }
  })
}

// 退出登录：先调后端删 Redis 登录态（登出即时失效），再清本地态跳登录页；
// 接口失败也照常清本地态，避免卡死
async function onLogout() {
  turnAbort?.abort()
  try {
    await apiLogout()
  } finally {
    chat.reset() // 清对话状态，避免换账号后看到上一账号的会话
    user.logout()
    router.push('/login')
  }
}

/** 点昵称 = 看自己的就诊记录：切到「挂号历史」并打开侧栏（桌面侧栏常驻，这个赋值无害） */
function openBooked() {
  tab.value = 'booked'
  sideOpen.value = true
}

// ---------------------------------------------------------------- 健康档案（选填）

const profileOpen = ref(false)
const profileLoading = ref(false)
const profileSaving = ref(false)
const profileError = ref('')
const profileTags = ref([])
// 上限来自后端受管参数；这里的默认值只在前端首次渲染、尚未拿到响应时兜底
const profileLimits = ref({ tagMax: 10, textMax: 50, textTotalMax: 120 })
const profileOptions = ref({ genders: [], ageRanges: [] })
const profileForm = ref(emptyProfile())

// 三个自由文本框共用一条合计额度：写满一个，另外两个的可写空间随之收窄
const OTHER_FIELDS = ['historyOther', 'medicationOther', 'allergyOther']

function emptyProfile() {
  return {
    gender: '',
    ageRange: '',
    historyTags: [],
    historyOther: '',
    medicationTags: [],
    medicationOther: '',
    allergyTags: [],
    allergyOther: ''
  }
}

/**
 * 三组「多选标签 + 其他自由文本」：标签按 type 从词表过滤。
 * 分组名与后端 `HealthTagType.label`（既往病史/长期用药/过敏史）各持一份——跨了语言边界
 * （后端 Java 枚举 / 前端展示文案），无法共享同一常量，故保留；改文案时两处一起改。
 */
const profileGroups = computed(() => [
  { key: 'history', label: '既 往 病 史', tags: 'historyTags', other: 'historyOther', type: 'chronic', placeholder: '词表里没有的病史，在这里补充' },
  { key: 'medication', label: '长 期 用 药', tags: 'medicationTags', other: 'medicationOther', type: 'medication', placeholder: '词表里没有的药物，在这里补充' },
  { key: 'allergy', label: '过 敏 史', tags: 'allergyTags', other: 'allergyOther', type: 'allergy', placeholder: '具体药名或过敏物，在这里补充' }
].map((g) => ({ ...g, options: profileTags.value.filter((t) => t.type === g.type) })))

function openProfile() {
  profileOpen.value = true
  sideOpen.value = false
  loadProfile()
}

function closeProfile() {
  profileOpen.value = false
}

async function loadProfile() {
  profileLoading.value = true
  profileError.value = ''
  try {
    const [data, tags] = await Promise.all([getHealthProfile(), listHealthTags()])
    profileTags.value = tags || []
    profileLimits.value = {
      tagMax: data.limits?.tagMax ?? 10,
      textMax: data.limits?.textMax ?? 50,
      textTotalMax: data.limits?.textTotalMax ?? 120
    }
    profileOptions.value = data.options || { genders: [], ageRanges: [] }
    profileForm.value = {
      gender: data.gender || '',
      ageRange: data.ageRange || '',
      historyTags: data.historyTags || [],
      medicationTags: data.medicationTags || [],
      allergyTags: data.allergyTags || [],
      historyOther: data.historyOther || '',
      medicationOther: data.medicationOther || '',
      allergyOther: data.allergyOther || ''
    }
  } catch (e) {
    console.error('[chat] 健康档案加载失败', e)
    profileError.value = e.message || '档案加载失败，请稍后重试'
  } finally {
    profileLoading.value = false
  }
}

/** 多选标签：超上限**当场写不进**（后端仍会二次校验，前端只是即时提示） */
function toggleTag(field, term) {
  const list = profileForm.value[field]
  const idx = list.indexOf(term)
  if (idx >= 0) {
    list.splice(idx, 1)
    return
  }
  if (list.length >= profileLimits.value.tagMax) {
    profileError.value = `每类最多选 ${profileLimits.value.tagMax} 项，先取消一项再选`
    return
  }
  profileError.value = ''
  list.push(term)
}

/**
 * 单框可写上限 = min(单框上限, 合计上限 − 另外两框已写字数)。
 * 作为 input 的 maxlength ⇒ 到额度就**写不进**（浏览器拦截），与后端二次校验口径一致，不静默裁剪。
 */
function textFieldMax(field) {
  const others = OTHER_FIELDS.filter((f) => f !== field)
    .reduce((n, f) => n + (profileForm.value[f]?.length || 0), 0)
  return Math.max(0, Math.min(profileLimits.value.textMax, profileLimits.value.textTotalMax - others))
}

/** 本框还剩多少字（同时反映单框与三框合计两条额度） */
function textFieldRemaining(field) {
  return Math.max(0, textFieldMax(field) - (profileForm.value[field]?.length || 0))
}

async function saveProfile() {
  if (profileSaving.value) return
  profileError.value = ''
  profileSaving.value = true
  try {
    await saveHealthProfile(profileForm.value)
    profileOpen.value = false
  } catch (e) {
    // 后端二次校验拒绝（超条数 / 超字数）会给可展示的 message
    profileError.value = e.message || '保存失败，请稍后重试'
  } finally {
    profileSaving.value = false
  }
}

// ---------------------------------------------------------------- 展示

function hhmm(at) {
  return at ? String(at).slice(11, 16) : ''
}

function shortDate(date) {
  return String(date).slice(5)
}

let desktopMQ = null
const onDesktopChange = (e) => { isDesktop.value = e.matches }

onMounted(() => {
  if (typeof ResizeObserver !== 'undefined') marksRO = new ResizeObserver(syncMarksScroll)
  desktopMQ = window.matchMedia('(min-width: 768px)')
  desktopMQ.addEventListener?.('change', onDesktopChange)
  loadSessions()
})

onBeforeUnmount(() => {
  turnAbort?.abort()
  marksRO?.disconnect()
  desktopMQ?.removeEventListener?.('change', onDesktopChange)
  clearTimeout(copiedTimer)
})
</script>

<style scoped>
/* ============================================================
   对话页 —— 方向 04「陪诊伙伴」
   规范：.claude/rules/前端设计方案.md §3 / §4.1
   本页只写「本页私有部件」与「页面布局」。共享部件（气泡 / 推荐卡 / 步进条 /
   输入区 / 浮层 / 标签 / 按钮…）一律吃 patient.css 的定义，**同名类不在这里
   重复实现**——两份定义里页面那份会静默覆盖全局，改一处漏一处（2026-10-07 收掉）。
   ============================================================ */

.p-chat { height: 100vh; background: var(--bg); }

/* ---------- 两栏：会话目录 + 对话舞台 ---------- */
.p-chat__body { position: relative; display: flex; height: 100%; }
.p-chat__stage {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--stage);
}
.p-chat__main {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.p-chat__thread {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-bottom: 20px;
}

/* 助手消息的骨架（**消息流本身在共享件 §3.1 里**，这里只补本页要的头像列）：
   30px 圆头像 + 右侧「标签 + 正文 + 复制」。追问 / 资料 / 普通回复共用这一套，
   三者的区别只在标签文字与那根竖线——换皮不许把它们抹平。 */
.p-ai { display: flex; gap: 12px; }
.p-ai__ava {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  margin-top: 4px;
  border-radius: var(--r-full);
  background: var(--leaf-soft);
  color: var(--leaf-deep);
}
.p-ai__bd { flex: 1; min-width: 0; }

/* 滚动条：显式定宽 12px 并淡化。定宽是为了让问题导航的把手能**精确**停在它左边
   （宽度不定就只能靠猜）。**不能再写 `scrollbar-width`**——Chromium 一旦认了那个
   标准属性，下面这套伪元素就整个失效。 */
.p-chat__thread::-webkit-scrollbar,
.p-chat__main::-webkit-scrollbar { width: 12px; }
.p-chat__thread::-webkit-scrollbar-track,
.p-chat__main::-webkit-scrollbar-track { background: transparent; }
.p-chat__thread::-webkit-scrollbar-thumb,
.p-chat__main::-webkit-scrollbar-thumb {
  background: var(--scroll-thumb);
  border: 3px solid transparent; /* 12px 轨道里只画中间 6px */
  background-clip: content-box;
}
.p-chat__thread::-webkit-scrollbar-thumb:hover,
.p-chat__main::-webkit-scrollbar-thumb:hover {
  background: var(--scroll-thumb-hover);
  background-clip: content-box;
}

/* 空状态：接诊卡与输入区当一整块，在顶栏以下的空白里垂直居中。
   用上下 auto 外边距而不是 justify-content——空间不够时它退化成 0，不会把顶部裁掉 */
.p-chat__main.is-empty { overflow-y: auto; }
.p-chat__main.is-empty .p-chat__thread {
  flex: 0 1 auto;
  margin-top: auto;
  padding-bottom: 0;
}
/* 空状态下输入区不在纸的底边（下方还有居中留白），别在那儿画一条假纸边 */
.p-chat__main.is-empty .p-composer {
  border-top: none;
  background: none;
  padding-top: 20px;
  margin-bottom: auto;
}

/* ---------- 左：会话目录 ----------
   `--rail` 比舞台 `--stage` 暗一档：「我的就诊」与「对话」因此自然分成两块，
   两栏之间**一条线都不画**（画线会把一整块暖沙隔成两间病房） */
.p-chat__side { display: none; }
.p-chat__sidebox {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--rail);
}
/* 侧栏抬头：标识 ＋ 品牌名 ＋ 收起按钮（收起按钮靠 margin-left:auto 推到最右） */
.p-chat__hd { flex: none; display: flex; align-items: center; gap: 8px; padding: 16px 12px 12px; }
.p-chat__logo {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: var(--r-xs);
  background: var(--leaf);
  color: var(--on-accent);
}
.p-chat__brand {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 13.5px;
  font-weight: 600;
  letter-spacing: .04em;
  color: var(--ink);
}
.p-chat__brand small {
  display: block;
  margin-top: 4px;
  font-size: 10px;
  font-weight: 400;
  letter-spacing: .06em;
  color: var(--ink-3);
}
.p-chat__sidehd { padding: 12px 12px 0; border-bottom: 1px solid var(--line); }
.p-chat__count {
  margin-top: 4px;
  font-size: 19px;
  font-weight: 600;
  letter-spacing: .02em;
  color: var(--ink);
}
.p-chat__tabs { display: flex; gap: 8px; margin-top: 12px; padding-bottom: 12px; }
.p-chat__tabs button {
  flex: 1;
  min-height: 44px; /* 触控目标 ≥ 44px */
  border: none;
  border-radius: var(--r-full);
  background: none;
  cursor: pointer;
  font-family: var(--sans);
  font-size: 11.5px;
  letter-spacing: .1em;
  color: var(--ink-2);
  transition: background .18s ease, color .18s ease, box-shadow .18s ease;
}
.p-chat__tabs button:hover { color: var(--leaf-deep); }
.p-chat__tabs button.on {
  background: var(--surface);
  color: var(--leaf-deep);
  font-weight: 600;
  box-shadow: var(--sh-raised);
}
.p-chat__list { flex: 1; min-height: 0; overflow-y: auto; padding: 0 8px; }
.p-chat__nores {
  padding: 48px 12px;
  font-size: 11.5px;
  letter-spacing: .06em;
  color: var(--ink-3);
  text-align: center;
}
.p-chat__new {
  flex: none;
  margin: 4px 12px 12px;
  min-height: 44px; /* 触控目标 ≥ 44px */
  border: 0;
  border-radius: var(--r-sm);
  background: var(--leaf);
  color: var(--on-accent);
  cursor: pointer;
  font-family: var(--sans);
  font-size: 12.5px;
  font-weight: 600;
  letter-spacing: .1em;
  box-shadow: var(--sh-primary);
  transition: background .18s ease;
}
.p-chat__new:hover { background: var(--leaf-deep); }

/* 底部用户区：一张压在侧栏底上的白卡，与选中条目同一层次 */
.p-chat__user {
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 8px 12px;
  padding: 8px 12px;
  border-radius: var(--r-sm);
  background: var(--surface);
}
/* 昵称首字：与「陪您走完流程的人」这层语气一致，比一行纯文字更有归属感 */
.p-chat__uava {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: var(--r-full);
  background: var(--apricot-soft);
  color: var(--apricot-deep);
  font-size: 12.5px;
  font-weight: 600;
}
.p-chat__unick,
.p-chat__uprof,
.p-chat__uout {
  display: inline-flex;
  align-items: center;
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 0;
  border: none;
  background: none;
  cursor: pointer;
  font-family: var(--sans);
  font-size: 11.5px;
  letter-spacing: .1em;
  transition: color .18s ease;
}
.p-chat__unick {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  color: var(--ink);
  text-decoration: underline dashed var(--ink-3);
  text-underline-offset: 3px;
}
.p-chat__unick:hover { color: var(--leaf-deep); }
.p-chat__uprof {
  flex: none;
  color: var(--ink-2);
  text-decoration: underline dashed var(--ink-3);
  text-underline-offset: 3px;
}
.p-chat__uprof:hover { color: var(--leaf-deep); }
.p-chat__uout { flex: none; color: var(--ink-3); }
.p-chat__uout:hover { color: var(--alert); }

/* 目录条目：天头 + 条目 + 折叠条。侧栏底是 `--rail`，所以选中行用**实白卡片**
   ——「压上去」的层次靠这层明度关系出来，不是靠加边框 */
.p-day {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 12px 8px 4px;
  font-family: var(--sans);
  font-size: 10px;
  letter-spacing: .14em;
  color: var(--ink-3);
}
.p-day .n { letter-spacing: .06em; }
.p-i {
  width: 100%;
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 12px;
  border: none;
  border-radius: var(--r-sm);
  background: none;
  cursor: pointer;
  text-align: left;
  transition: background .18s ease, box-shadow .18s ease;
}
.p-i:hover { background: var(--hover-rail); }
.p-i.on { background: var(--surface); box-shadow: var(--sh-raised); }
.p-i__x {
  flex: 1;
  min-width: 0;
  font-size: 12.5px;
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.p-i.dim .p-i__x { color: var(--ink-2); }
.p-i.on .p-i__x { color: var(--leaf-deep); font-weight: 600; }
.p-i__q,
.p-i__d {
  flex: none;
  font-family: var(--sans);
  font-size: 10px;
  color: var(--ink-3);
}
.p-i__q { letter-spacing: .06em; }
.p-fold {
  width: 100%;
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 12px;
  border: none;
  border-radius: var(--r-sm);
  background: none;
  cursor: pointer;
  font-family: var(--sans);
  font-size: 10.5px;
  letter-spacing: .1em;
  color: var(--leaf-deep);
}
.p-fold:hover { text-decoration: underline; }

/* ---------- 归档：入口 hover 才浮现（与对话区复制按钮同一套语言），收纳区折在列表末尾 ---------- */
.p-irow { position: relative; }
.p-irow__act {
  position: absolute;
  right: 12px;
  top: 50%;
  transform: translateY(-50%);
  padding: 12px 4px;
  border: none;
  background: none;
  cursor: pointer;
  font-family: var(--sans);
  font-size: 10px;
  letter-spacing: .1em;
  color: var(--leaf-deep);
  opacity: 0;
  transition: opacity .18s ease;
}
.p-irow:hover .p-irow__act,
.p-irow__act:focus-visible { opacity: 1; }
/* hover 时把时间淡掉：归档动作就压在那个位置，两条文字会叠 */
.p-i__d { transition: opacity .18s ease; }
.p-irow:hover .p-i__d { opacity: 0; }
/* 触屏没有 hover：常显动作，并让出时间的位置 */
@media (hover: none) {
  .p-irow__act { opacity: 1; }
  .p-irow .p-i__d { opacity: 0; }
}
.p-irow.is-arch .p-i__x { color: var(--ink-2); }

/* ---------- 回放时的只读条（替代输入区） ---------- */
.p-chat__lock {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  background: var(--stage);
  border-top: 1px solid var(--line);
}
.p-chat__locktxt {
  font-family: var(--sans);
  font-size: 10.5px;
  letter-spacing: .14em;
  color: var(--ink-3);
}
.p-chat__lockbtn {
  flex: none;
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 0 16px;
  border: 0;
  border-radius: var(--r-sm);
  background: var(--leaf);
  color: var(--on-accent);
  cursor: pointer;
  font-family: var(--sans);
  font-size: 12.5px;
  letter-spacing: .1em;
  transition: background .18s ease;
}
.p-chat__lockbtn:hover { background: var(--leaf-deep); }
/* 回放里已挂号的结论卡，卡底补一行就诊信息 */
.p-chat__visit {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
  font-family: var(--sans);
  font-size: 11.5px;
  letter-spacing: .06em;
  color: var(--ink-2);
}

/* 书签跳到的那一问：左侧一条主色短竖线 */
.p-q { position: relative; display: flex; justify-content: flex-end; }
.p-q.is-active::before {
  content: '';
  position: absolute;
  left: -8px;
  top: 2px;
  bottom: 2px;
  width: 2px;
  background: var(--leaf);
}

/* ---------- 右缘：问题导航（悬浮面板） ----------
   absolute 而不是列：面板不占布局位，进出回放时正文那一列一动不动。
   收起时右缘只留一根细把手，鼠标挨到就展开 */
.p-chat__marks {
  position: absolute;
  /* 让开最右那条 12px 滚动条：把手停在它左边，两条竖线各归各位、互不打架 */
  right: 12px;
  top: 0;
  bottom: 0;
  width: 22px;
  z-index: 6;
  display: flex;
  align-items: center;
  justify-content: center;
}
/* 把手：收起态唯一看得见的东西。热区是整条右缘（够大够好按），视觉只有中间那 2px */
.p-chat__marksgrip {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: none;
  padding: 0;
  cursor: pointer;
}
.p-chat__marksgrip::before {
  content: '';
  width: 2px;
  height: 46px;
  background: var(--leaf-line);
  transition: background .18s ease, height .18s ease;
}
.p-chat__marks:hover .p-chat__marksgrip::before { background: var(--leaf); height: 62px; }
.p-chat__marks.is-open .p-chat__marksgrip::before { background: var(--leaf); }

.p-chat__markspanel {
  position: absolute;
  right: 100%; /* 贴在把手左侧 */
  top: 50%;
  width: 220px;
  max-height: min(56vh, 318px);
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  /* 这一块是**浮在纸上**的——没有阴影就和正文糊在一起 */
  box-shadow: var(--sh-overlay);
  /* 收起态：往右挪一点 + 透明，不接收鼠标 */
  transform: translate(12px, -50%);
  opacity: 0;
  pointer-events: none;
  transition: opacity .18s ease, transform .18s ease;
}
.p-chat__marks.is-open .p-chat__markspanel {
  opacity: 1;
  pointer-events: auto;
  transform: translate(0, -50%);
}
.p-chat__markshd {
  flex: none;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 12px;
  border-bottom: 1px solid var(--line);
}
.p-chat__marksno {
  font-family: var(--sans);
  font-size: 10px;
  letter-spacing: .06em;
  color: var(--ink-3);
}
/* 放不下就在面板里自己滚；滚动条不画（220px 的一条卡片，画上滚动条更乱） */
.p-chat__markset {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: none;
  padding: 4px 0;
}
.p-chat__markset::-webkit-scrollbar { display: none; }
/* 溢出时上下渐隐——被截掉的那条不该看起来像正常的一条。
   渐隐用的是**遮罩模板色**（任意不透明色都行），借 `--surface` 当"不透明"，
   免得这里再散写一个 `#000` */
.p-chat__markset.is-scroll {
  -webkit-mask-image: linear-gradient(to bottom, transparent 0, var(--surface) 10px, var(--surface) calc(100% - 10px), transparent 100%);
  mask-image: linear-gradient(to bottom, transparent 0, var(--surface) 10px, var(--surface) calc(100% - 10px), transparent 100%);
}
/* 一行 = 一个问题的正文首句 + 右缘一根横杠（当前那根长而粗） */
.p-mark {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: none;
  background: none;
  cursor: pointer;
  text-align: left;
  transition: background .18s ease;
}
.p-mark:hover { background: var(--hover-soft); }
.p-mark__x {
  flex: 1;
  min-width: 0;
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--ink-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.p-mark__bar {
  flex: none;
  width: 13px;
  height: 2px;
  background: var(--ink-3);
  transition: width .18s ease, height .18s ease, background .18s ease;
}
.p-mark.on .p-mark__x { color: var(--leaf-deep); }
.p-mark.on .p-mark__bar {
  width: 20px;
  height: 4px;
  background: var(--leaf);
}

/* ---------- 空状态：陪诊卡 ----------
   问候行 + 「说清这三件事」+ 三张部件卡 + 示例句 + 常见主诉。
   三个部件卡**不是按钮**——别让人以为要点（人体图功能会把「部位」那一格变成真入口） */
.p-hello { display: flex; flex-direction: column; max-width: 640px; }
.p-hello__hd { display: flex; align-items: center; gap: 12px; }
.p-hello__ava {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: var(--r-sm);
  background: var(--leaf);
  color: var(--on-accent);
}
.p-hello__t {
  font-size: 26px; /* 巨型：一屏最大的那句话 */
  font-weight: 600;
  letter-spacing: .01em;
  line-height: 1.4;
}
.p-hello__lead { margin-top: 4px; font-size: 13.5px; line-height: 1.85; color: var(--ink-2); }
.p-hello__label {
  margin: 28px 0 12px;
  font-family: var(--sans);
  font-size: 10px;
  letter-spacing: .18em;
  color: var(--ink-2);
}
.p-hello__three { display: flex; gap: 12px; }
.p-hello__bub {
  flex: 1;
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--surface);
}
.p-hello__bub b {
  display: block;
  font-family: var(--sans);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: .14em;
  color: var(--leaf-deep);
}
.p-hello__bub span { display: block; margin-top: 8px; font-size: 12.5px; line-height: 1.7; color: var(--ink-2); }
.p-hello__eg {
  margin-top: 16px;
  padding: 12px 16px;
  border-left: 4px solid var(--leaf-soft);
  border-radius: var(--r-sm);
  background: var(--surface);
  font-size: 12.5px;
  line-height: 1.85;
  color: var(--ink-2);
}
.p-hello__chips { display: flex; flex-wrap: wrap; gap: 8px; }
.p-hello__chip {
  display: inline-flex;
  align-items: center;
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 0 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-full);
  background: var(--surface);
  font-family: var(--sans);
  font-size: 12.5px;
  color: var(--ink-2);
  cursor: pointer;
  transition: border-color .18s ease, color .18s ease, background .18s ease;
}
.p-hello__chip:hover {
  border-color: var(--leaf);
  background: var(--leaf-soft);
  color: var(--leaf-deep);
}

/* ---------- 一键复制（学 DS：桌面 hover 才浮现，触屏常显） ---------- */
.p-q__col {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  max-width: 64%;
}
.p-q__col .p-user { max-width: 100%; }
.p-act__btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
  padding: 4px 0;
  border: none;
  background: none;
  cursor: pointer;
  font-family: var(--sans);
  font-size: 10.5px;
  letter-spacing: .1em;
  color: var(--ink-3);
  opacity: 0;
  transition: opacity .18s ease, color .18s ease;
}
.p-q:hover .p-act__btn,
.p-ai:hover .p-act__btn,
.p-act__btn.is-copied,
.p-act__btn:focus-visible { opacity: 1; }
.p-act__btn:hover { color: var(--leaf-deep); }
.p-act__btn.is-copied { color: var(--leaf-deep); }
/* 结论卡的复制常显——卡是"交代"，hover 才出现会让人找不到 */
.p-card__copy { margin-top: 12px; opacity: 1; }
/* 卡里最后那个主操作（陪伴带之后），与陪伴带保持一个档位的间距 */
.p-card__go { margin-top: 16px; }
/* 触屏没有 hover：复制按钮常显 */
@media (hover: none) {
  .p-act__btn { opacity: 1; }
}

/* ---------- 侧栏折叠图标与精简轨（桌面） ---------- */
.p-chat__iconbtn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: none;
  border-radius: var(--r-xs);
  background: none;
  padding: 0;
  cursor: pointer;
  color: var(--ink-2);
  transition: color .18s ease, background .18s ease;
}
.p-chat__iconbtn:hover { color: var(--leaf-deep); background: var(--surface); }
/* 收起按钮在侧栏抬头里（桌面）；精简轨：收起态才出现，与展开态的 sidebox 互换 */
.p-chat__foldbtn { display: none; }
.p-chat__rail { display: none; }
@media (min-width: 768px) {
  .p-chat__foldbtn { display: inline-flex; margin-left: auto; }
  /* 收起后侧栏只剩横向一条：标识（点它展开）＋ 展开图标 ＋ 新建。
     宽度写**定值**而不是 fit-content——`fit-content` 不参与 width 插值，收起会「啪」地
     跳过去，0.2s 的过渡等于白写（实测采样只有 [248, 127] 两帧）。
     min-width 兜底：图标有增减时轨不会挤坏。当前 123 = 24(内边距) + 26(标识) + 4 + 1(分隔) + 4 + 30 + 4 + 30。 */
  .p-chat__side.is-collapsed { width: 123px; min-width: fit-content; }
  .p-chat__side.is-collapsed .p-chat__sidebox { display: none; }
  .p-chat__side.is-collapsed .p-chat__rail {
    display: flex;
    flex-direction: row;
    align-items: center;
    gap: 4px;
    padding: 12px;
    color: var(--leaf-deep);
    /* 贴在顶部，不要撑满高度居中 */
    height: auto;
  }
  /* 收起态的标识：它本身就是展开入口 */
  .p-chat__railbrand {
    flex: none;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 26px;
    height: 26px;
    border: none;
    background: none;
    padding: 0;
    cursor: pointer;
    color: var(--leaf-deep);
    transition: opacity .18s ease;
  }
  .p-chat__railbrand:hover { opacity: .7; }
  .p-chat__railsep { flex: none; width: 1px; height: 16px; background: var(--line-2); }
}

/* 流式等待：首字到达前的轻提示，随 delta 填充自动消失 */
.p-ai__wait {
  font-family: var(--sans);
  font-size: 11.5px;
  letter-spacing: .06em;
  color: var(--ink-3);
}

/* ---------- 桌面 ≥768px：DS 式布局——侧栏贴窗口最左，对话内容在中央收 1000px ----------
   两栏靠底色差分开（--rail vs --stage），中间一条线都不画 */
@media (min-width: 768px) {
  .p-chat__body { max-width: none; margin: 0; border: none; }
  .p-chat__side {
    display: block;
    flex: none;
    /* 收起/展开平滑过渡；宽度 0 时裁掉侧栏内容 */
    overflow: hidden;
    transition: width .2s ease;
  }
  /* 整条顶栏在桌面端移除：标题、用户区都归左侧栏，对话区顶上不再有一条横线 */
  .p-chat .p-chat__topbar { display: none; }
  /* 滚动容器是**整幅宽**的：滚动条因此落在窗口最右缘，而不是缩在"对话框"里。
     内容改由内边距收成 1000px 居中——内边距不会带着滚动条一起走 */
  .p-chat__main { width: 100%; max-width: none; margin: 0; }
  /* .p-chat__main 的**每一个**直接子元素都要在这里列全——容器全宽之后，
     漏掉一个（比如只读条）它就会自己铺满整幅宽，跟旁边的区块错位 */
  .p-chat .p-thread,
  .p-chat .p-steps,
  .p-chat .p-composer,
  .p-chat .p-chat__lock {
    width: 100%;
    max-width: none;
    margin: 0;
    border-left: none;
    border-right: none;
    /* 宽屏收到 1000px 居中，窄屏退化成 32px 内边距 */
    padding-left: max(32px, calc((100% - 1000px) / 2));
    padding-right: max(32px, calc((100% - 1000px) / 2));
  }
  .p-chat__menubtn { display: none; }
}

/* ---------- 手机 <768px：会话目录是覆盖层，对话区全宽 ---------- */
/* 这一处是浮层语言的**唯一例外**：它不是"浮层内容"，而是同一块侧栏在窄屏下的形态
   ——底色仍是 `--rail`、内容一字未变，只是套了一层 `--mask` 遮罩。
   遮罩与阴影两个令牌照旧从共享件取。 */
@media (max-width: 767px) {
  /* 触屏没有 hover，右缘那条得够宽才点得中；面板也收窄一点，别盖掉半屏正文。
     手机滚动条是悬浮式不占位，把手可以更贴边 */
  .p-chat__marks { width: 34px; right: 4px; }
  .p-chat__markspanel { width: 186px; }
  .p-chat__side {
    display: block;
    position: absolute;
    inset: 0;
    z-index: 20;
    background: var(--mask);
    opacity: 0;
    pointer-events: none;
    transition: opacity .18s ease;
  }
  .p-chat__side.is-open { opacity: 1; pointer-events: auto; }
  .p-chat__sidebox {
    width: 82%;
    max-width: 320px;
    border-radius: 0 var(--r-lg) var(--r-lg) 0;
    box-shadow: var(--sh-overlay);
  }
  .p-chat__menubtn {
    flex: none;
    margin-right: 8px;
    min-height: 44px; /* 触控目标 ≥ 44px */
    border: 1px solid var(--line);
    border-radius: var(--r-xs);
    background: none;
    padding: 0 12px;
    cursor: pointer;
    font-family: var(--sans);
    font-size: 10.5px;
    letter-spacing: .14em;
    color: var(--ink-2);
    transition: border-color .18s ease, color .18s ease;
  }
  .p-chat__menubtn:hover { border-color: var(--leaf); color: var(--leaf-deep); }
  .p-chat .p-topbar__title { flex: 1; }
}

/* ---------- 健康档案：表单内部 ----------
   浮层**外壳**（遮罩 / 容器 / 抬头 / 关闭 / 进出节奏）走共享件 `.p-overlay*` 与
   `.p-fade-*`，本页只留表单——见设计文档 §3.8。
   已知粗糙：性别 / 年龄段用的是**原生 `<select>`**，外观由浏览器决定；换成自绘下拉
   属交互改动，不在这张票里（设计文档 §3.9 的"不用原生 select"是管理端那一侧的规矩）。 */
.p-prof__loading {
  padding: 48px 16px;
  font-family: var(--sans);
  font-size: 11.5px;
  letter-spacing: .06em;
  color: var(--ink-3);
}
.p-prof__row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--line);
}
.p-prof__label {
  flex: none;
  width: 64px;
  font-family: var(--sans);
  font-size: 11.5px;
  letter-spacing: .1em;
  color: var(--ink-2);
}
.p-prof__select {
  flex: 1;
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--surface);
  font-family: var(--sans);
  font-size: 13.5px;
  color: var(--ink);
}
.p-prof__select:focus { outline: none; border-color: var(--leaf); }
.p-prof__group {
  padding: 16px 0;
  border-bottom: 1px solid var(--line);
}
.p-prof__group:last-of-type { border-bottom: none; }
.p-prof__ghead {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}
.p-prof__glabel {
  font-family: var(--sans);
  font-size: 11.5px;
  letter-spacing: .1em;
  color: var(--ink);
}
.p-prof__quota {
  font-family: var(--sans);
  font-size: 10px;
  color: var(--ink-3);
}
.p-prof__chips { display: flex; flex-wrap: wrap; gap: 8px; }
.p-prof__chip {
  display: inline-flex;
  align-items: center;
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 0 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-full);
  background: var(--surface);
  font-family: var(--sans);
  font-size: 12.5px;
  color: var(--ink);
  cursor: pointer;
  transition: border-color .18s ease, background .18s ease, color .18s ease;
}
.p-prof__chip:hover { border-color: var(--leaf); color: var(--leaf-deep); }
/* 选中态与科室列表同一口径：浅底 + 主色描边 + 主色深字（不是实心主色底） */
.p-prof__chip.is-on {
  background: var(--leaf-soft);
  border-color: var(--leaf);
  color: var(--leaf-deep);
  font-weight: 600;
}
.p-prof__none {
  font-family: var(--sans);
  font-size: 11.5px;
  color: var(--ink-3);
}
.p-prof__other { display: flex; align-items: center; gap: 8px; margin-top: 12px; }
.p-prof__input {
  flex: 1;
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--surface);
  font-family: var(--sans);
  font-size: 13.5px;
  color: var(--ink);
}
.p-prof__input:focus { outline: none; border-color: var(--leaf); }
.p-prof__left {
  flex: none;
  font-family: var(--sans);
  font-size: 10px;
  color: var(--ink-3);
}
.p-prof__err {
  margin: 12px 0 4px;
  padding: 12px 16px;
  border-left: 3px solid var(--alert);
  border-radius: var(--r-sm);
  background: var(--alert-soft);
  color: var(--alert);
  font-size: 11.5px;
  line-height: 1.85;
}
</style>
