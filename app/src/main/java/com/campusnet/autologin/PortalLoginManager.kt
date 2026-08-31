package com.campusnet.autologin

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * 用无头 WebView 打开认证页，自动填入账号密码并触发页面自身的登录按钮，
 * 复用认证页自带 JS（page_init.js 的提交与"外网拨号"轮询）。
 * 若页面 JS 提交无效，超时后兜底用页面内 JS 直接 POST /webauth.do。
 */
object PortalLoginManager {

    data class LoginOutcome(val success: Boolean, val message: String)

    private const val PAGE_LOAD_TIMEOUT_MS = 20_000L
    private const val FORM_WAIT_MS = 12_000L
    private const val LOGIN_WAIT_MS = 45_000L
    private const val EVALJS_TIMEOUT_MS = 10_000L
    private const val LOGIN_TOTAL_TIMEOUT_MS = 150_000L

    suspend fun login(
        context: Context,
        portalUrl: String,
        userId: String,
        passwd: String
    ): LoginOutcome {
        // 整体兜底超时：页面加载 20s + 表单等待 12s + 轮询 45s + 兜底提交 + 余量。
        // 防止 WebView 异常导致协程永久挂起、长期持有检测互斥锁。
        return withTimeoutOrNull(LOGIN_TOTAL_TIMEOUT_MS) {
            loginInternal(context, portalUrl, userId, passwd)
        } ?: LoginOutcome(false, "登录流程超时，已中止")
    }

    private suspend fun loginInternal(
        context: Context,
        portalUrl: String,
        userId: String,
        passwd: String
    ): LoginOutcome = withContext(Dispatchers.Main) {
        val app = context.applicationContext
        var webView: WebView? = null
        try {
            val wv = createWebView(app)
            webView = wv

            val pageLoaded = CompletableDeferred<Unit>()
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (!pageLoaded.isCompleted) pageLoaded.complete(Unit)
                }
            }

            val startUrl = portalUrl.ifBlank { SettingsStore.DEFAULT_PORTAL }
            AppLog.info("WebView 开始加载认证页：$startUrl")
            wv.loadUrl(startUrl)
            if (withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) { pageLoaded.await() } == null) {
                AppLog.info("认证页加载超时")
                return@withContext LoginOutcome(false, "认证页加载超时")
            }

            // 等待登录表单渲染出来（外链 JS 加载完成前元素不存在）
            var filled = ""
            var hasForm = false
            var waited = 0L
            while (waited <= FORM_WAIT_MS) {
                filled = evalJs(wv, fillJs(userId, passwd))
                AppLog.verbose("填充表单尝试：$filled（已等 ${waited}ms）")
                if (filled == "OK") { hasForm = true; break }
                delay(1000)
                waited += 1000
            }
            if (!hasForm) {
                AppLog.info("未找到登录表单，最后一次填充结果=$filled")
                // 可能已经登录过，页面被重定向到成功页：直接看联网状态
                if (ConnectivityChecker.check() is ConnectivityChecker.Result.Online) {
                    AppLog.info("页面无表单但网络已通，视为已登录")
                    return@withContext LoginOutcome(true, "认证页未显示登录表单，但网络已可访问")
                }
                return@withContext LoginOutcome(false, "认证页上未找到登录表单（页面结构变化？）")
            }

            val clicked = evalJs(wv, clickJs())
            AppLog.info("点击登录按钮结果=$clicked")
            if (clicked != "OK") {
                return@withContext LoginOutcome(false, "未找到登录按钮（$clicked）")
            }

            // 轮询联网结果；同时读取页面错误信息
            val startAt = System.currentTimeMillis()
            val deadline = startAt + LOGIN_WAIT_MS
            var fallbackSubmitted = false
            var pageError = ""
            while (System.currentTimeMillis() < deadline) {
                delay(3000)
                if (ConnectivityChecker.check() is ConnectivityChecker.Result.Online) {
                    AppLog.info("登录后联网检测通过")
                    return@withContext LoginOutcome(true, "自动登录成功，已联网")
                }
                pageError = readPageError(wv)
                AppLog.verbose("等待联网中，页面信息：$pageError")
                val dialing = pageError.contains("拨号") || pageError.contains("稍候") || pageError.isBlank()
                if (!dialing && pageError.isNotBlank()) break

                val elapsed = System.currentTimeMillis() - startAt
                if (!fallbackSubmitted && elapsed > 10_000) {
                    // 页面自身提交似乎未生效：在页面上下文里直接 POST webauth.do
                    fallbackSubmitted = true
                    val fb = evalJs(wv, fallbackSubmitJs(userId, passwd))
                    AppLog.info("兜底直提 webauth.do 结果：$fb")
                }
            }

            if (pageError.isNotBlank()) {
                LoginOutcome(false, "登录失败：$pageError")
            } else {
                // 最后再确认一次，避免刚好在轮询间隙恢复
                if (ConnectivityChecker.check() is ConnectivityChecker.Result.Online) {
                    LoginOutcome(true, "自动登录成功，已联网")
                } else {
                    LoginOutcome(false, "已提交登录但仍无法联网（请检查账号密码）")
                }
            }
        } catch (e: Exception) {
            AppLog.info("登录过程异常：${e.message}")
            LoginOutcome(false, "登录过程异常：${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { webView?.stopLoading() } catch (_: Exception) {}
            try { webView?.loadUrl("about:blank") } catch (_: Exception) {}
            try { webView?.destroy() } catch (_: Exception) {}
            try { CookieManager.getInstance().flush() } catch (_: Exception) {}
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        // 双设备技巧：校园网按"手机版/电脑版"页面各记一台设备。开启电脑模式后，
        // 用桌面 Chrome 的 UA 打开认证页，即可占用"电脑版"名额，让两台安卓设备同时在线。
        if (SettingsStore.load(context).desktopUA) {
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    }

    private suspend fun evalJs(wv: WebView, script: String): String =
        withTimeoutOrNull(EVALJS_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                wv.evaluateJavascript(script) { value ->
                    if (cont.isActive) cont.resume(value ?: "")
                }
            }
        } ?: ""

    private fun jsString(raw: String): String = try {
        (JSONTokener(raw).nextValue() as? String) ?: raw
    } catch (e: Exception) {
        raw
    }

    private fun jsLiteral(s: String): String = JSONObject.quote(s)

    private fun fillJs(userId: String, passwd: String): String {
        val u = jsLiteral(userId)
        val p = jsLiteral(passwd)
        return """
        (function(){
          try{
            var u=document.getElementById('userId');
            var p=document.getElementById('passwd');
            if(!u||!p) return 'NO_FORM';
            u.value=$u; p.value=$p;
            try{u.dispatchEvent(new Event('input',{bubbles:true}));p.dispatchEvent(new Event('input',{bubbles:true}));}catch(e2){}
            return 'OK';
          }catch(e){return 'ERR:'+e.message;}
        })()
        """.trimIndent()
    }

    private fun clickJs(): String = """
        (function(){
          try{
            var b=document.getElementById('submitForm');
            if(!b) return 'NO_BTN';
            b.click();
            return 'OK';
          }catch(e){return 'ERR:'+e.message;}
        })()
    """.trimIndent()

    /** 读取认证页错误信息：优先隐藏域 #errMessage，其次 layui 弹层文本。 */
    private suspend fun readPageError(wv: WebView): String {
        val raw = evalJs(
            wv, """
            (function(){
              try{
                var a=document.getElementById('act');
                var e=document.getElementById('errMessage');
                var m='';
                var els=document.querySelectorAll('.layui-layer-content');
                for(var i=0;i<els.length;i++){var t=(els[i].textContent||'').trim(); if(t) m=(m?m+' ':'')+t;}
                return JSON.stringify({act:(a?a.value:''),err:((e?e.value:'')||m)});
              }catch(ex){return JSON.stringify({act:'',err:''});}
            })()
        """.trimIndent()
        )
        return try {
            val obj = JSONObject(jsString(raw))
            val err = obj.optString("err").trim()
            // 常见噪声文案过滤
            when {
                err.isBlank() -> ""
                err.contains("拨号") -> "外网拨号中"  // 属于正常等待
                else -> err
            }
        } catch (e: Exception) {
            ""
        }
    }

    /** 兜底：在页面上下文组装 goLoginForm 全部字段，直接 POST /webauth.do。 */
    private fun fallbackSubmitJs(userId: String, passwd: String): String {
        val u = jsLiteral(userId)
        val p = jsLiteral(passwd)
        return """
        (function(){
          try{
            var f=document.getElementById('goLoginForm');
            if(!f) return 'NO_FORM';
            var up=document.getElementById('urlParameter');
            var action='/webauth.do';
            if(up && up.value) action=action+'?'+up.value;
            var parts=[];
            function addKV(k,v){parts.push(encodeURIComponent(k)+'='+encodeURIComponent(v==null?'':v));}
            var els=f.elements;
            for(var i=0;i<els.length;i++){
              var el=els[i];
              if(el && el.name && el.type!=='checkbox' && !el.disabled) addKV(el.name, el.value);
            }
            addKV('userId',$u); addKV('passwd',$p); addKV('remInfo','on');
            var x=new XMLHttpRequest();
            x.open('POST',action,false);
            x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');
            x.send(parts.join('&'));
            return 'POSTED:'+x.status;
          }catch(e){return 'ERR:'+e.message;}
        })()
        """.trimIndent()
    }
}
