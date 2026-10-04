import {Innertube,Platform,Log} from 'youtubei.js/web';
Log.setLevel(Log.Level.NONE);
// No browser/network/storage authority. Native reads the queue, validates every request,
// and supplies bounded anonymous responses. There is no addJavascriptInterface/native object.
for (const name of ['indexedDB','caches','WebSocket','XMLHttpRequest','importScripts']) {
  try { Object.defineProperty(globalThis,name,{value:undefined,configurable:false}); } catch {}
}
let pending=new Map(), next=0, count=0;
const MAX_REQUESTS=20;
async function metadataFetch(input,init) {
 const request=new Request(input,init);
 const url=new URL(request.url);
 if(url.protocol!=='https:' || url.hostname!=='www.youtube.com' || url.username || url.password ||
   !/^\/(?:sw\.js_data|iframe_api|youtubei\/v1\/(?:config|player)|s\/player\/[a-zA-Z0-9_-]+\/[^?#]*base\.js)$/.test(url.pathname) || ++count>MAX_REQUESTS)
   throw Error('metadata_scope');
 if(!['GET','POST'].includes(request.method))throw Error('metadata_method');
 const body=request.method==='POST'?await request.text():'';
 if(body.length>65536)throw Error('metadata_body_budget');
 const headers={};request.headers.forEach((v,k)=>{headers[k]=v;});
 const id=++next;
 return new Promise((resolve,reject)=>{pending.set(id,{resolve,reject});postMessage({request:{id,url:request.url,method:request.method,headers,body}});});
}
globalThis.fetch=metadataFetch;
Platform.shim.fetch=metadataFetch;
// Public player-derived evaluation remains inside this worker, without native/DOM/storage
// access. Native timeout destroys the runtime; output is validated before it becomes a plan.
Platform.shim.eval=(data,env)=>{
 const n=env.n,sig=env.sig,sp=env.sp;
 if(typeof data.output!=='string'||data.output.length>4*1024*1024)throw Error('player_budget');
 return eval(data.output);
};
onmessage=async event=>{
 const data=event.data;
 if(data.response){
  const item=pending.get(data.response.id);if(!item)return;pending.delete(data.response.id);
  if(data.response.error){item.reject(Error('metadata_failure'));return;}
  const raw=atob(data.response.body);const bytes=Uint8Array.from(raw,c=>c.charCodeAt(0));
  const response=new Response(bytes,{status:data.response.status,headers:data.response.headers});
  Object.defineProperty(response,'url',{value:data.response.url});item.resolve(response);return;
 }
 if(data.videoId){
  try {
   if(!/^[A-Za-z0-9_-]{11}$/.test(data.videoId))throw Error('identity');
   const yt=await Innertube.create({fetch:metadataFetch});
   const info=await yt.getBasicInfo(data.videoId,{client:'IOS'});
   if(info.playability_status?.status!=='OK'||info.basic_info.is_live||info.basic_info.is_upcoming)throw Error('access_or_live');
   const all=info.streaming_data?.adaptive_formats||[];
   const videos=all.filter(f=>f.has_video&&!f.has_audio&&f.mime_type?.includes('avc1')&&f.height>0&&f.height<=1080&&!f.drm_families?.length&&!f.is_type_otf).slice(0,12);
   const audios=all.filter(f=>f.has_audio&&!f.has_video&&f.mime_type?.includes('mp4a.40.2')&&!f.drm_families?.length&&!f.is_type_otf&&f.is_original).slice(0,4);
   if(!videos.length||!audios.length)throw Error('unsupported_tracks');
   const serialize=async f=>({id:String(f.itag),url:await f.decipher(yt.session.player),mime:f.mime_type,width:f.width||0,height:f.height||0,length:f.content_length||0,durationMs:f.approx_duration_ms||0});
   const output={videoId:data.videoId,title:(info.basic_info.title||'YouTube video').slice(0,180),videos:await Promise.all(videos.map(serialize)),audios:await Promise.all(audios.map(serialize))};
   if(JSON.stringify(output).length>131072)throw Error('format_budget');
   postMessage({result:output});
  }catch{postMessage({failure:'YouTube 当前解析或访问条件不受支持；未创建下载任务'});}
 }
};
