package ru.poscenter.okdesk.mobile;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class OptposInjectedScript {
    private OptposInjectedScript() {}

    static String build(Context context, JSONObject values) throws Exception {
        String userscript = readAsset(context, "optposcenter.user.js", 1_000_000);
        return "(function(){if(window.top!==window)return;const start=()=>{if(!document.head){setTimeout(start,10);return}\n"
                + "const values=JSON.parse(" + JSONObject.quote(values.toString()) + ");const appTheme=values['okdesk_app_theme']==='light'?'light':'dark';document.documentElement.dataset.okdeskAppTheme=appTheme;\n"
                + "const loginRoute=location.pathname.replace(/\\/+$/,'')==='/user/login'||new URLSearchParams(location.search).get('q')?.includes('user/login');const catalogRoute=location.pathname.startsWith('/catalog');let catalogRequested=false;try{catalogRequested=sessionStorage.getItem('okdeskOptposCatalogRequested')==='1';if(catalogRoute){sessionStorage.removeItem('okdeskOptposCatalogRequested');catalogRequested=false}else if(loginRoute){sessionStorage.setItem('okdeskOptposCatalogRequested','1');catalogRequested=true}else if(catalogRequested){sessionStorage.removeItem('okdeskOptposCatalogRequested');location.replace('/catalog/');return}}catch(_){}\n"
                + "const viewport=document.querySelector('meta[name=viewport]')||document.head.appendChild(Object.assign(document.createElement('meta'),{name:'viewport'}));viewport.content='width=device-width,initial-scale=1,viewport-fit=cover';\n"
                + "const mobileStyle=document.createElement('style');mobileStyle.textContent=`html,body{min-height:100%;margin:0!important;background:#f2f6f5!important}body{font-family:Roboto,Arial,sans-serif!important}#opc-toolbar{position:fixed!important;inset:0!important;z-index:2147483000!important;width:100%!important;height:100dvh!important;max-width:none!important;overflow:auto!important;overscroll-behavior:contain!important;margin:0!important;padding:14px!important;border:0!important;border-radius:0!important;background:#f2f6f5!important;color:#203033!important;box-sizing:border-box!important;box-shadow:none!important}#opc-toolbar>strong{display:none!important}#opc-toolbar>input:not([data-role]),#opc-toolbar>select,#opc-toolbar>button{display:none!important}#opc-toolbar>input[placeholder]{display:block!important;width:100%!important;height:48px!important;margin:0 0 10px!important;padding:0 14px!important;border:1px solid #c5d5d2!important;border-radius:10px!important;background:#fff!important;color:#203033!important;font-size:16px!important;box-sizing:border-box!important}#opc-toolbar [data-role=status]{margin:0 0 8px!important;color:#60716d!important;font-size:12px!important}#opc-toolbar [data-role=results]{width:100%!important;max-height:none!important;overflow:visible!important;padding:0!important}#opc-toolbar [data-role=results] section{margin:10px 0!important;padding:10px!important;border:1px solid #d1ddda!important;border-radius:8px!important;background:#fff!important}#opc-toolbar [data-role=results] section>button{padding:8px!important;color:#203033!important;font-size:14px!important}#opc-toolbar [data-role=results] section>div>div{margin:8px 0!important;padding:12px!important;border:1px solid #e0e8e5!important;border-radius:8px!important;background:#fff!important;color:#203033!important}#opc-toolbar [data-role=results] section>div>div button{min-height:40px!important}#opc-toolbar [data-role=results] img{max-width:100%!important;height:auto!important}html[data-okdesk-app-theme=dark]{color-scheme:dark;--opc-bg:#10191c;--opc-surface:#1b282c;--opc-raised:#24353a;--opc-text:#edf5f3;--opc-muted:#a6bbb8;--opc-border:#3a5051}html[data-okdesk-app-theme=dark],html[data-okdesk-app-theme=dark] body{background:var(--opc-bg)!important;color:var(--opc-text)!important}html[data-okdesk-app-theme=dark] #opc-toolbar{background:var(--opc-bg)!important;color:var(--opc-text)!important}html[data-okdesk-app-theme=dark] #opc-toolbar :is(input,select,button,[data-role=results] section>div>div){background:var(--opc-surface)!important;color:var(--opc-text)!important;border-color:var(--opc-border)!important}html[data-okdesk-app-theme=dark] #opc-toolbar [data-role=status]{color:var(--opc-muted)!important}html[data-okdesk-app-theme=dark] #opc-toolbar [data-role=results] section{background:var(--opc-surface)!important;border-color:var(--opc-border)!important}html[data-okdesk-app-theme=dark] #opc-toolbar [data-role=results] section>button{color:var(--opc-text)!important;border-color:var(--opc-border)!important}`;document.head.appendChild(mobileStyle);\n"
                + "const controlsStyle=document.createElement('style');controlsStyle.textContent='#opc-toolbar>strong{display:block!important;margin:2px 0 8px!important;font-size:16px!important}#opc-toolbar>select,#opc-toolbar>button{display:inline-flex!important;align-items:center!important;justify-content:center;box-sizing:border-box!important}#opc-toolbar>select,#opc-toolbar>[data-role=type-filter]{display:block!important;width:100%!important;max-width:none!important;height:44px!important;margin:0 0 8px!important;padding:0 12px!important;border-radius:8px!important;font-size:15px!important}#opc-toolbar>button{width:calc(50% - 6px)!important;min-height:48px!important;margin:2px!important;padding:6px!important;font-size:13px!important;line-height:1.2!important;white-space:normal!important}html[data-okdesk-app-theme=dark] .opc-modal :is(input,select,textarea){color-scheme:dark!important;background:#24353a!important;color:#edf5f3!important;border-color:#3a5051!important}html[data-okdesk-app-theme=dark] .opc-modal [data-detail-box]{background:#24353a!important;color:#edf5f3!important;border-color:#3a5051!important}';document.head.appendChild(controlsStyle);\n"
                + "const requests=new Map();let nextRequest=0;const commands=new Map();\n"
                + "OkdeskNative.addEventListener('message',event=>{let reply;try{reply=JSON.parse(event.data)}catch(_){return}const options=requests.get(reply.id);if(!options)return;requests.delete(reply.id);clearTimeout(options.timer);if(reply.error)options.onerror?.({status:reply.status||0,responseText:reply.error});else options.onload?.({status:reply.status,responseText:reply.text})});\n"
                + "const largeValues=new Map();function GM_getValue(key,fallback){if(largeValues.has(key))return largeValues.get(key);try{const raw=localStorage.getItem('okdesk_app_value:'+key);if(raw!==null){const value=JSON.parse(raw);largeValues.set(key,value);return value}}catch(error){console.warn('Optposcenter local storage read failed',error)}return Object.prototype.hasOwnProperty.call(values,key)?values[key]:fallback}\n"
                + "function GM_setValue(key,value){values[key]=value;const raw=JSON.stringify(value);try{if(raw.length>250000){localStorage.setItem('okdesk_app_value:'+key,raw);largeValues.set(key,value);OkdeskNative.postMessage(JSON.stringify({type:'delete',key}));return}localStorage.removeItem('okdesk_app_value:'+key);largeValues.delete(key)}catch(error){throw new Error('локальное хранилище каталога заполнено: '+error.message)}OkdeskNative.postMessage(JSON.stringify({type:'set',key,value}))}\n"
                + "function GM_registerMenuCommand(label,callback){if(typeof callback==='function')commands.set(label,callback)}\n"
                + "window.__okdeskInvokeOptposCommand=label=>{const callback=commands.get(label);if(callback)callback()}\n"
                + "function GM_xmlhttpRequest(options){const id=++nextRequest;const item={...options};requests.set(id,item);if(options.timeout)item.timer=setTimeout(()=>{requests.delete(id);options.ontimeout?.({})},options.timeout);OkdeskNative.postMessage(JSON.stringify({type:'request',id,method:options.method||'GET',url:options.url}));return{abort(){clearTimeout(item.timer);requests.delete(id)}}}\n"
                + "let catalogDependencyLoading=false,catalogDependencyReady=Boolean(window.XLSX);\n"
                + "function isCatalogContext(){return location.pathname.startsWith('/catalog')}\n"
                + "function loadCatalogDependency(){if(catalogDependencyReady||catalogDependencyLoading)return;catalogDependencyLoading=true;const dependency=document.createElement('script');dependency.src='https://cdn.jsdelivr.net/npm/xlsx@0.18.5/dist/xlsx.full.min.js';dependency.onload=()=>{catalogDependencyLoading=false;catalogDependencyReady=true};dependency.onerror=()=>{catalogDependencyLoading=false;console.warn('Optposcenter XLSX dependency unavailable; search remains available')};document.head.appendChild(dependency)}\n"
                + "function runCatalogUserscript(){runUserscript();loadCatalogDependency()}\n"
                + "function launch(){if(isCatalogContext())runCatalogUserscript();else runUserscript()}\n"
                + "function runUserscript(){try{eval(" + JSONObject.quote(userscript) + ")}catch(error){console.error('Optposcenter add-on failed',error)}}\n"
                + "function ensureCatalogSearch(){if(!isCatalogContext()||document.getElementById('opc-toolbar')||!document.querySelector('main h1,h1'))return;runCatalogUserscript()}\n"
                + "function installSearchWatcher(){new MutationObserver(ensureCatalogSearch).observe(document.documentElement,{childList:true,subtree:true});window.addEventListener('pageshow',ensureCatalogSearch);window.addEventListener('popstate',()=>setTimeout(ensureCatalogSearch,0))}\n"
                + "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',()=>{launch();installSearchWatcher()},{once:true});else{setTimeout(()=>{launch();installSearchWatcher()},0)}\n"
                + "};start()})();";
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
