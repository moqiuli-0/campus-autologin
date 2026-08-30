# 目前未启用混淆（build.gradle.kts 中 isMinifyEnabled = false，稳定性优先）。
# 若以后开启 R8：
#   1. 先在此补充必要的 keep 规则并完成真机回归验证；
#   2. WebView 加载的页面 JS 通过 DOM 访问，不涉及 JNI 反射，理论上无需额外 keep；
#   3. 开启后安装包体积可显著缩小。
