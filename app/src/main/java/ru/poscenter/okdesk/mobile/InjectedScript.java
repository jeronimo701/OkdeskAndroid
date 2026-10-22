package ru.poscenter.okdesk.mobile;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class InjectedScript {
    private InjectedScript() {}

    static String build(Context context, JSONObject values, String host) throws Exception {
        String userscript = readAsset(context, "okdesk.user.js", 2_000_000);
        String mobileTheme = readAsset(context, "mobile-theme.css", 100_000);
        return "(function () {\n"
                + "if (window.top !== window) return;\n"
                + "const values = JSON.parse(" + JSONObject.quote(values.toString()) + ");\n"
                + "const portalHost = " + JSONObject.quote(host) + ";\n"
                + "const appTheme = values['okdesk_app_theme'] === 'light' ? 'light' : 'dark';\n"
                + "values['okdesk_script_theme_' + portalHost] = appTheme;\n"
                + "document.documentElement.dataset.okdeskAppTheme=appTheme;document.documentElement.dataset.okdeskScriptTheme=appTheme;\n"
                + "if (!values['okdesk_mobile_setup_complete_' + portalHost]) return;\n"
                + "const mobileKey = 'okdesk_mobile_view_' + portalHost;\n"
                + "const apiEnabled = Boolean(values['okdesk_api_token_' + portalHost]);\n"
                + "const pendingMode = sessionStorage.getItem('okdesk_app_pending_mobile_mode');\n"
                + "if (pendingMode) { values[mobileKey] = pendingMode; sessionStorage.removeItem('okdesk_app_pending_mobile_mode'); }\n"
                + "const requests = new Map(); let nextRequest = 0;\n"
                + "OkdeskNative.addEventListener('message', event => {\n"
                + "  let reply; try { reply = JSON.parse(event.data); } catch (_) { return; }\n"
                + "  const options = requests.get(reply.id); if (!options) return;\n"
                + "  requests.delete(reply.id);\n"
                + "  if (reply.error) options.onerror?.({status: reply.status || 0, responseText: reply.error});\n"
                + "  else options.onload?.({status: reply.status, responseText: reply.text});\n"
                + "});\n"
                + "function GM_getValue(key, fallback) { return Object.prototype.hasOwnProperty.call(values, key) ? values[key] : fallback; }\n"
                + "function GM_setValue(key, value) { values[key] = value; if (key === mobileKey) sessionStorage.setItem('okdesk_app_pending_mobile_mode', value); OkdeskNative.postMessage(JSON.stringify({type:'set', key, value})); }\n"
                + "function GM_registerMenuCommand() {}\n"
                + "function GM_setClipboard(text) { OkdeskNative.postMessage(JSON.stringify({type:'clipboard', text:String(text)})); }\n"
                + "function GM_xmlhttpRequest(options) {\n"
                + "  const id = ++nextRequest; requests.set(id, options);\n"
                + "  OkdeskNative.postMessage(JSON.stringify({type:'request', id, method:options.method || 'GET', url:options.url}));\n"
                + "  return {abort() { requests.delete(id); }};\n"
                + "}\n"
                + "const unsafeWindow = window;\n"
                + "function launch() {\n"
                + userscript + "\n"
                + "const mobileTheme = document.createElement('style');\n"
                + "mobileTheme.textContent = " + JSONObject.quote(mobileTheme) + ";\n"
                + "document.head.appendChild(mobileTheme);\n"
                + "if (!apiEnabled) { const apiStyle=document.createElement('style'); apiStyle.textContent='#okdesk-quick-replies-panel{display:none!important}'; document.head.appendChild(apiStyle); }\n"
                + "const addAppMenu = () => { const header=document.querySelector('#okdesk-mobile-app .m-top'); if(!header||header.querySelector('.okdesk-app-menu'))return; const button=document.createElement('button'); button.type='button'; button.className='m-icon okdesk-app-menu'; button.textContent='☰'; button.title='Разделы приложения'; button.setAttribute('aria-label','Разделы приложения'); button.addEventListener('click',()=>OkdeskNative.postMessage(JSON.stringify({type:'navigation'}))); header.prepend(button); };\n"
                + "addAppMenu(); new MutationObserver(addAppMenu).observe(document.documentElement,{childList:true,subtree:true});\n"
                + "}\n"
                + "if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', launch, {once:true});\n"
                + "else launch();\n"
                + "})();";
    }

    private static String readAsset(Context context, String name, int maxSize) throws Exception {
        try (InputStream input = context.getAssets().open(name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > maxSize) throw new IllegalStateException("Asset is too large: " + name);
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
