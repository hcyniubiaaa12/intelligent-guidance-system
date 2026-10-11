let installPromise = null

/** 管理端首次进入才安装，患者端不承担组件库与全量样式的首屏开销。 */
export async function ensureElement(app) {
  if (!installPromise) {
    installPromise = (async () => {
      const [element, locale] = await Promise.all([
        import('element-plus'),
        import('element-plus/es/locale/lang/zh-cn'),
        import('element-plus/dist/index.css')
      ])
      // 覆盖样式后加载，避免网络完成顺序让 Element 默认主题反盖管理端主题。
      await import('../styles/element-override.css')
      app.use(element.default, { locale: locale.default })
    })().catch(error => {
      installPromise = null
      throw error
    })
  }
  await installPromise
}
