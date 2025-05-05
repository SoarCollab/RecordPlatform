export function useLoadingCheck() {
  // 确保在浏览器环境执行
  if (typeof document === "undefined") return;

  // 直接通过 ID 获取元素（更高效）
  const loading = document.getElementById("loading-app");
  if (!loading) return;

  // 使用现代 .remove() 方法
  setTimeout(() => {
    // 再次检查元素是否仍在 DOM 中
    if (document.body.contains(loading)) {
      loading.remove();
    }
  }, 100);
}

export function useScrollToTop() {
  const app = document.getElementById('app')
  if (app) {
    setTimeout(() => {
      app.scrollTo({
        top: 0,
      })
    }, 300)
  }
}